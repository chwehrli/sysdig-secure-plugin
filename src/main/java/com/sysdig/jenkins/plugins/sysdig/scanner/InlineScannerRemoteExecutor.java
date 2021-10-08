/*
Copyright (C) 2016-2020 Sysdig

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.sysdig.jenkins.plugins.sysdig.scanner;

import com.google.common.base.Strings;
import com.sysdig.jenkins.plugins.sysdig.BuildConfig;
import com.sysdig.jenkins.plugins.sysdig.SysdigBuilder;
import com.sysdig.jenkins.plugins.sysdig.containerrunner.Container;
import com.sysdig.jenkins.plugins.sysdig.containerrunner.ContainerRunner;
import com.sysdig.jenkins.plugins.sysdig.containerrunner.ContainerRunnerFactory;
import com.sysdig.jenkins.plugins.sysdig.containerrunner.DockerClientContainerFactory;
import com.sysdig.jenkins.plugins.sysdig.log.SysdigLogger;
import hudson.EnvVars;
import hudson.remoting.Callable;
import org.jenkinsci.remoting.RoleChecker;

import java.io.*;
import java.util.*;

public class InlineScannerRemoteExecutor implements Callable<String, Exception>, Serializable {

  private static final String DUMMY_ENTRYPOINT = "cat";
  private static final String[] MKDIR_COMMAND = new String[]{"mkdir", "-p", "/tmp/sysdig-inline-scan/logs"};
  private static final String[] TOUCH_COMMAND = new String[]{"touch", "/tmp/sysdig-inline-scan/logs/info.log"};
  private static final String[] TAIL_COMMAND = new String[]{"tail", "--sleep-interval=0.2", "-f", "/tmp/sysdig-inline-scan/logs/info.log"};
  private static final String SCAN_COMMAND = "/sysdig-inline-scan.sh";
  private static final String[] SCAN_ARGS = new String[] {
    "--storage-type=docker-daemon",
    "--format=JSON"};
  private static final String VERBOSE_ARG = "--verbose";
  private static final String SKIP_TLS_ARG = "--sysdig-skip-tls";
  private static final String SYSDIG_URL_ARG = "--sysdig-url=%s";
  private static final String ON_PREM_ARG = "--on-prem";
  private static final String DOCKERFILE_ARG = "--dockerfile=/tmp/";
  private static final String DOCKERFILE_MOUNTPOINT = "/tmp/";

  private static final int STOP_SECONDS = 1;
  private static final int MAX_WAIT_MS = 60000;

  // Use a default container runner factory, but allow overriding for mocks in tests
  private static ContainerRunnerFactory containerRunnerFactory = new DockerClientContainerFactory();

  public static void setContainerRunnerFactory(ContainerRunnerFactory containerRunnerFactory) {
    InlineScannerRemoteExecutor.containerRunnerFactory = containerRunnerFactory;
  }

  private final String imageName;
  private final String dockerFile;
  private final BuildConfig config;
  private final SysdigLogger logger;
  private final EnvVars envVars;
  private final StringBuilder jsonOutput = new StringBuilder();
  private final StringBuilder errorOutput = new StringBuilder();


  public InlineScannerRemoteExecutor(String imageName, String dockerFile, BuildConfig config, SysdigLogger logger, EnvVars envVars) {
    this.imageName = imageName;
    this.dockerFile = dockerFile;
    this.config = config;
    this.logger = logger;
    this.envVars = envVars;
  }

  @Override
  public void checkRoles(RoleChecker checker) throws SecurityException { }
  @Override

  public String call() throws InterruptedException {
    ContainerRunner containerRunner = containerRunnerFactory.getContainerRunner(logger, envVars);

    List<String> args = new ArrayList<>();
    args.add(SCAN_COMMAND);
    args.addAll(Arrays.asList(SCAN_ARGS));
    if (config.getDebug()) {
      args.add(VERBOSE_ARG);
    }
    if (!config.getEngineverify()) {
      args.add(SKIP_TLS_ARG);
    }
    args.add(imageName);
    if (!config.getEngineurl().equals(SysdigBuilder.DescriptorImpl.DEFAULT_ENGINE_URL)) {
      args.add(String.format(SYSDIG_URL_ARG, config.getEngineurl()));
      args.add(ON_PREM_ARG);
    }

    List<String> containerEnvVars = new ArrayList<>();
    containerEnvVars.add("SYSDIG_API_TOKEN=" + this.config.getSysdigToken());
    containerEnvVars.add("SYSDIG_ADDED_BY=cicd-inline-scan");
    addProxyVars(envVars, containerEnvVars, logger);

    List<String> bindMounts = new ArrayList<>();
    bindMounts.add("/var/run/docker.sock:/var/run/docker.sock");

    logger.logDebug("System environment: " + System.getenv().toString());
    logger.logDebug("Final environment: " + envVars);
    logger.logDebug("Creating container with environment: " + containerEnvVars);
    logger.logDebug("Bind mounts: " + bindMounts);

    Container inlineScanContainer = containerRunner.createContainer(envVars.get("SYSDIG_OVERRIDE_INLINE_SCAN_IMAGE", config.getInlineScanImage()), Collections.singletonList(DUMMY_ENTRYPOINT), null, containerEnvVars, config.getRunAsUser(), bindMounts);

    if (!Strings.isNullOrEmpty(dockerFile)) {
      File f = new File(dockerFile);
      logger.logDebug("Copying Dockerfile from " + f.getAbsolutePath() + " to " + DOCKERFILE_MOUNTPOINT + f.getName() + " inside container");
      inlineScanContainer.copy(dockerFile, DOCKERFILE_MOUNTPOINT);
      args.add(DOCKERFILE_ARG + f.getName());
    }

    if (!Strings.isNullOrEmpty(config.getInlineScanExtraParams())) {
      args.addAll(Arrays.asList(config.getInlineScanExtraParams().split(" ")));
    }

    long exitCode = -1;
    Object tailFinished = new Object();

    try {
      //TODO: Get exit code in run and exec?
      inlineScanContainer.runAsync(frame -> this.sendToLog(frame), frame -> this.sendToLog(frame));

      inlineScanContainer.exec(Arrays.asList(MKDIR_COMMAND), null, frame -> this.sendToLog(frame), frame -> this.sendToLog(frame));
      inlineScanContainer.exec(Arrays.asList(TOUCH_COMMAND), null,  frame -> this.sendToLog(frame), frame -> this.sendToLog(frame));
      inlineScanContainer.execAsync(Arrays.asList(TAIL_COMMAND), null, frame -> this.sendToLog(frame), frame -> this.sendToLog( frame), code -> { synchronized (tailFinished) { logger.logDebug("Container tail completed");  tailFinished.notify(); } });

      logger.logDebug("Executing command in container: " + args);
      exitCode = inlineScanContainer.exec(args, null, frame -> this.sendToJSONOutput(frame), frame -> this.sendToErrorOutput(frame));
    } finally {
      logger.logDebug("Stopping container");
      inlineScanContainer.stop(STOP_SECONDS);
      logger.logDebug("Container stopped");
      synchronized (tailFinished) {
        tailFinished.wait(MAX_WAIT_MS);
      }

      logger.logDebug("Tail finished");

      if (config.getDebug()) {
        logger.logDebug("Inline-scanner verbose execution stdout output:\n" + jsonOutput);
        logger.logDebug("Inline-scanner verbose execution stderr output:\n" + errorOutput);
      }
    }

    // For exit code 2 (wrong params) or other unexpected exit codes, just show the output
    if (exitCode != 0 && exitCode != 1 && exitCode != 3) {
      logger.logError(String.format("Error executing the inline scanner. Exit code %d", exitCode));
      if (!config.getDebug()) {
        logger.logError("Standard Output:\n" + jsonOutput);
        logger.logError("ErrorOutput:\n" + errorOutput);
      }
      throw new InterruptedException(String.format("Error executing the inline scanner. Exit code %d", exitCode));
    }

    return jsonOutput.toString();
  }

  private void addProxyVars(EnvVars currentEnv, List<String> envVars, SysdigLogger logger) {
    String http_proxy = currentEnv.get("http_proxy");

    if (Strings.isNullOrEmpty(http_proxy)) {
      http_proxy = currentEnv.get("HTTP_PROXY");
      if (!Strings.isNullOrEmpty(http_proxy)) {
        logger.logDebug("HTTP proxy setting from env var HTTP_PROXY (http_proxy empty): " + http_proxy);
      }
    } else {
      logger.logDebug("HTTP proxy setting from env var http_proxy: " + http_proxy);
    }

    if (!Strings.isNullOrEmpty(http_proxy)) {
      envVars.add("http_proxy=" + http_proxy);
    }

    String https_proxy = currentEnv.get("https_proxy");

    if (Strings.isNullOrEmpty(https_proxy)) {
      https_proxy = currentEnv.get("HTTPS_PROXY");
      if (!Strings.isNullOrEmpty(https_proxy)) {
        logger.logDebug("HTTPS proxy setting from env var HTTPS_PROXY (https_proxy empty): " + https_proxy);
      }
    } else {
      logger.logDebug("HTTPS proxy setting from env var https_proxy: " + https_proxy);
    }

    if (Strings.isNullOrEmpty(https_proxy)) {
      https_proxy = http_proxy;
      if (!Strings.isNullOrEmpty(https_proxy)) {
        logger.logDebug("HTTPS proxy setting from env var http_proxy (https_proxy and HTTPS_PROXY empty): " + https_proxy);
      }
    }

    if (!Strings.isNullOrEmpty(https_proxy)) {
      envVars.add("https_proxy=" + https_proxy);
    }

    String no_proxy = currentEnv.get("no_proxy");

    if (Strings.isNullOrEmpty(no_proxy)) {
      no_proxy = currentEnv.get("NO_PROXY");
      if (!Strings.isNullOrEmpty(no_proxy)) {
        logger.logDebug("NO proxy setting from env var NO_PROXY (no_proxy empty): " + no_proxy);
      }
    } else {
      logger.logDebug("NO proxy setting from env var no_proxy: " + no_proxy);
    }

    if (!Strings.isNullOrEmpty(no_proxy)) {
      envVars.add("no_proxy=" + no_proxy);
    }
  }

  private void sendToJSONOutput(String frame) {
    for (String line: frame.split("[\n\r]")) {
      // Workaround for older versions of inline-scan which can include some verbose output from "set -x", starting with "+ " in the stdout
      if (!line.startsWith("+ ")) {
        jsonOutput.append(line);
      } else {
        logger.logError("Filtered out spurious JSON output line: " + line);
        errorOutput.append(line + "\n");
      }
    }
  }

  private void sendToLog(String frame) {
    for (String line: frame.split("[\n\r]")) {
      logger.logInfo(line);
    }
  }

  private void sendToErrorOutput(String frame) {
    for (String line: frame.split("[\n\r]")) {
      errorOutput.append(line + "\n");
    }
  }
}
