/*
 * Copyright 2024 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.gradle;

import com.google.cloud.tools.jib.Command;
import com.google.cloud.tools.jib.registry.LocalRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.TaskOutcome;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Integration tests for Gradle plugin with containerizingMode configuration.
 *
 * <p>Tests the Gradle plugin's ability to configure entrypointMode via:
 * <ul>
 *   <li>Direct build.gradle configuration</li>
 *   <li>System property override (-Djib.container.entrypointMode=cmd)</li>
 * </ul>
 */
public class ContainerizingModeGradleIntegrationTest {

  @ClassRule public static final LocalRegistry localRegistry = new LocalRegistry(5001);

  @ClassRule
  public static final TestProject testProject = new TestProject("containerizing-mode");

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final String BASE_IMAGE_NAME = "localhost:5001/test-entrypoint-base";
  private static final String TEST_IMAGE_BUILD_CONFIG = "localhost:5001/gradle-containerizing-mode-test";
  private static final String TEST_IMAGE_SYSTEM_PROP = "localhost:5001/gradle-system-property-test";

  /**
   * Sets up the test environment by building a base image with an entrypoint.
   */
  @BeforeClass
  public static void setUpClass() throws IOException, InterruptedException {
    // Build the base image with an entrypoint script
    Path dockerfilePath = Paths.get("../jib-core/src/integration-test/resources/entrypoint-test");
    new Command(
            "docker",
            "build",
            "--platform",
            "linux/amd64",
            "-t",
            BASE_IMAGE_NAME,
            dockerfilePath.toAbsolutePath().toString())
        .run();

    // Push to local registry
    new Command("docker", "push", BASE_IMAGE_NAME).run();
  }

  /**
   * Cleans up test images after all tests complete.
   */
  @AfterClass
  public static void tearDownClass() throws IOException, InterruptedException {
    // Clean up test images
    try {
      new Command("docker", "rmi", BASE_IMAGE_NAME).run();
    } catch (Exception e) {
      // Ignore cleanup errors
    }
    try {
      new Command("docker", "rmi", TEST_IMAGE_BUILD_CONFIG).run();
    } catch (Exception e) {
      // Ignore cleanup errors
    }
    try {
      new Command("docker", "rmi", TEST_IMAGE_SYSTEM_PROP).run();
    } catch (Exception e) {
      // Ignore cleanup errors
    }
  }

  /**
   * Gets the ENTRYPOINT field from a Docker image.
   */
  private static String getEntrypoint(String imageReference)
      throws IOException, InterruptedException {
    return new Command("docker", "inspect", "-f", "{{.Config.Entrypoint}}", imageReference)
        .run()
        .trim();
  }

  /**
   * Gets the CMD field from a Docker image.
   */
  private static String getCmd(String imageReference) throws IOException, InterruptedException {
    return new Command("docker", "inspect", "-f", "{{.Config.Cmd}}", imageReference)
        .run()
        .trim();
  }

  /**
   * Tests Gradle plugin with entrypointMode configured in build.gradle.
   * Verifies that container { entrypointMode = 'cmd' } in build.gradle
   * correctly preserves the base image's entrypoint.
   */
  @Test
  public void testGradlePlugin_buildGradleConfiguration() throws IOException, InterruptedException {

    // Run Gradle build
    BuildResult buildResult =
        JibRunHelper.buildToDockerDaemon(
            testProject, "localhost:5001/gradle-containerizing-mode-test", "build.gradle");

    // Verify build succeeded
    BuildTask jibTask = buildResult.task(":jib");
    Assert.assertNotNull(jibTask);
    Assert.assertEquals(TaskOutcome.SUCCESS, jibTask.getOutcome());

    // Pull the built image
    localRegistry.pull(TEST_IMAGE_BUILD_CONFIG);

    // Verify ENTRYPOINT contains the base image's entrypoint (preserved)
    String entrypoint = getEntrypoint(TEST_IMAGE_BUILD_CONFIG);
    MatcherAssert.assertThat(
        "ENTRYPOINT should preserve base image entrypoint",
        entrypoint,
        CoreMatchers.containsString("/entrypoint.sh"));

    // Verify CMD contains the Java command (not in ENTRYPOINT)
    String cmd = getCmd(TEST_IMAGE_BUILD_CONFIG);
    MatcherAssert.assertThat(
        "CMD should contain java command",
        cmd,
        CoreMatchers.containsString("java"));
    MatcherAssert.assertThat(
        "CMD should contain main class",
        cmd,
        CoreMatchers.containsString("com.test.HelloWorld"));
    MatcherAssert.assertThat(
        "CMD should contain arg1",
        cmd,
        CoreMatchers.containsString("arg1"));
    MatcherAssert.assertThat(
        "CMD should contain arg2",
        cmd,
        CoreMatchers.containsString("arg2"));
  }

  /**
   * Tests Gradle plugin with entrypointMode set via system property.
   * Verifies that -Djib.container.entrypointMode=cmd correctly overrides
   * the default behavior even when not specified in build.gradle.
   */
  @Test
  public void testGradlePlugin_systemPropertyOverride() throws IOException, InterruptedException {

    // Run Gradle build with system property
    BuildResult buildResult =
        testProject
            .build(
                temporaryFolder.getRoot().toPath(),
                "clean",
                "jib",
                "-Djib.useOnlyProjectCache=true",
                "-Djib.console=plain",
                "-Djib.container.entrypointMode=cmd",
                "-b=build-system-property.gradle")
            .getBuildResult();

    // Verify build succeeded
    BuildTask jibTask = buildResult.task(":jib");
    Assert.assertNotNull(jibTask);
    Assert.assertEquals(TaskOutcome.SUCCESS, jibTask.getOutcome());

    // Pull the built image
    localRegistry.pull(TEST_IMAGE_SYSTEM_PROP);

    // Verify ENTRYPOINT contains the base image's entrypoint (preserved via system property)
    String entrypoint = getEntrypoint(TEST_IMAGE_SYSTEM_PROP);
    MatcherAssert.assertThat(
        "ENTRYPOINT should preserve base image entrypoint when system property is set",
        entrypoint,
        CoreMatchers.containsString("/entrypoint.sh"));

    // Verify CMD contains the Java command
    String cmd = getCmd(TEST_IMAGE_SYSTEM_PROP);
    MatcherAssert.assertThat(
        "CMD should contain java command",
        cmd,
        CoreMatchers.containsString("java"));
    MatcherAssert.assertThat(
        "CMD should contain main class",
        cmd,
        CoreMatchers.containsString("com.test.HelloWorld"));
  }

  /**
   * Tests Gradle plugin with invalid entrypointMode value.
   * Verifies that invalid values throw appropriate exception with helpful message.
   */
  @Test
  public void testGradlePlugin_invalidEntrypointMode() throws IOException, InterruptedException {

    // Run Gradle build with invalid system property - should fail
    BuildResult buildResult =
        testProject
            .build(
                temporaryFolder.getRoot().toPath(),
                "clean",
                "jib",
                "-Djib.useOnlyProjectCache=true",
                "-Djib.console=plain",
                "-Djib.container.entrypointMode=invalid-mode",
                "-b=build.gradle")
            .getBuildResult();

    // Verify build failed
    BuildTask jibTask = buildResult.task(":jib");
    Assert.assertNotNull(jibTask);
    Assert.assertEquals(TaskOutcome.FAILED, jibTask.getOutcome());

    // Verify error message is helpful
    String output = buildResult.getOutput();
    MatcherAssert.assertThat(
        "Error message should mention invalid mode",
        output,
        CoreMatchers.containsString("Invalid entrypoint mode"));
    MatcherAssert.assertThat(
        "Error message should mention valid modes",
        output,
        CoreMatchers.containsString("'entrypoint' or 'cmd'"));
  }
}
