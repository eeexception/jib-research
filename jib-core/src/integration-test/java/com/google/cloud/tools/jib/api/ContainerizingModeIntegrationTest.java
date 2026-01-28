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

package com.google.cloud.tools.jib.api;

import com.google.cloud.tools.jib.Command;
import com.google.cloud.tools.jib.configuration.ContainerizingMode;
import com.google.cloud.tools.jib.registry.LocalRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Integration tests for ContainerizingMode feature (ENTRYPOINT vs CMD mode).
 *
 * <p>Tests that Jib can preserve base image entrypoints when using CMD mode, allowing base images
 * with wrapper scripts (like /__cacert_entrypoint.sh) to work correctly.
 */
public class ContainerizingModeIntegrationTest {

  @ClassRule public static final LocalRegistry localRegistry = new LocalRegistry(5001);

  private static final String BASE_IMAGE_NAME = "localhost:5001/test-entrypoint-base";
  private static final String TEST_IMAGE_ENTRYPOINT_MODE = "localhost:5001/test-entrypoint-mode";
  private static final String TEST_IMAGE_CMD_MODE = "localhost:5001/test-cmd-mode";

  /**
   * Sets up the test environment by building a base image with an entrypoint.
   */
  @BeforeClass
  public static void setUpClass() throws IOException, InterruptedException {
    // Build the base image with an entrypoint script (single platform)
    Path dockerfilePath = Paths.get("src/integration-test/resources/entrypoint-test");
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
      new Command("docker", "rmi", TEST_IMAGE_ENTRYPOINT_MODE).run();
    } catch (Exception e) {
      // Ignore cleanup errors
    }
    try {
      new Command("docker", "rmi", TEST_IMAGE_CMD_MODE).run();
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
   * Verifies the base image has the expected entrypoint.
   */
  @Test
  public void testBaseImage_hasEntrypoint() throws IOException, InterruptedException {
    String entrypoint = getEntrypoint(BASE_IMAGE_NAME);
    String cmd = getCmd(BASE_IMAGE_NAME);

    // Base image should have entrypoint set to the wrapper script
    MatcherAssert.assertThat(entrypoint, CoreMatchers.containsString("/entrypoint.sh"));

    // Base image should have null/empty CMD
    Assert.assertTrue("Base CMD should be null or empty", cmd.equals("[]") || cmd.equals("<no value>"));
  }

  /**
   * Tests ENTRYPOINT mode (default behavior) - Java command goes to ENTRYPOINT field.
   * This should replace the base image's entrypoint.
   */
  @Test
  public void testEntrypointMode_replacesBaseImageEntrypoint()
      throws InvalidImageReferenceException, InterruptedException, CacheDirectoryCreationException,
          IOException, RegistryException, ExecutionException {

    // Build image in ENTRYPOINT mode (default)
    Path tempDir = Files.createTempDirectory("jib-test-entrypoint-mode");
    tempDir.toFile().deleteOnExit();

    Jib.from(BASE_IMAGE_NAME)
        .setEntrypoint("java", "-jar", "app.jar")
        .setProgramArguments("arg1", "arg2")
        .containerize(
            Containerizer.to(RegistryImage.named(TEST_IMAGE_ENTRYPOINT_MODE))
                .setAllowInsecureRegistries(true)
                .setApplicationLayersCache(tempDir));

    // Pull the built image
    localRegistry.pull(TEST_IMAGE_ENTRYPOINT_MODE);

    // Verify ENTRYPOINT contains the Java command
    String entrypoint = getEntrypoint(TEST_IMAGE_ENTRYPOINT_MODE);
    MatcherAssert.assertThat(
        "ENTRYPOINT should contain java command",
        entrypoint,
        CoreMatchers.containsString("java"));
    MatcherAssert.assertThat(
        "ENTRYPOINT should contain app.jar",
        entrypoint,
        CoreMatchers.containsString("app.jar"));

    // Verify CMD contains the program arguments
    String cmd = getCmd(TEST_IMAGE_ENTRYPOINT_MODE);
    MatcherAssert.assertThat(
        "CMD should contain arg1",
        cmd,
        CoreMatchers.containsString("arg1"));
    MatcherAssert.assertThat(
        "CMD should contain arg2",
        cmd,
        CoreMatchers.containsString("arg2"));

    // Base entrypoint should NOT be present (replaced)
    MatcherAssert.assertThat(
        "Base entrypoint should be replaced",
        entrypoint,
        CoreMatchers.not(CoreMatchers.containsString("/entrypoint.sh")));
  }

  /**
   * Tests CMD mode - Java command goes to CMD field, base entrypoint is preserved.
   * This allows base image wrapper scripts to execute before the Java application.
   */
  @Test
  public void testCmdMode_preservesBaseImageEntrypoint()
      throws InvalidImageReferenceException, InterruptedException, CacheDirectoryCreationException,
          IOException, RegistryException, ExecutionException {

    // Build image in CMD mode
    Path tempDir = Files.createTempDirectory("jib-test-cmd-mode");
    tempDir.toFile().deleteOnExit();

    Jib.from(BASE_IMAGE_NAME)
        .setContainerizingMode(ContainerizingMode.CMD)
        .setEntrypoint("java", "-jar", "app.jar")
        .setProgramArguments("arg1", "arg2")
        .containerize(
            Containerizer.to(RegistryImage.named(TEST_IMAGE_CMD_MODE))
                .setAllowInsecureRegistries(true)
                .setApplicationLayersCache(tempDir));

    // Pull the built image
    localRegistry.pull(TEST_IMAGE_CMD_MODE);

    // Verify ENTRYPOINT contains the base image's entrypoint (preserved)
    String entrypoint = getEntrypoint(TEST_IMAGE_CMD_MODE);
    MatcherAssert.assertThat(
        "ENTRYPOINT should preserve base image entrypoint",
        entrypoint,
        CoreMatchers.containsString("/entrypoint.sh"));

    // Verify CMD contains the Java command AND arguments (combined)
    String cmd = getCmd(TEST_IMAGE_CMD_MODE);
    MatcherAssert.assertThat(
        "CMD should contain java command",
        cmd,
        CoreMatchers.containsString("java"));
    MatcherAssert.assertThat(
        "CMD should contain app.jar",
        cmd,
        CoreMatchers.containsString("app.jar"));
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
   * Tests that CMD mode works correctly when base image has no entrypoint.
   * Java command should go to CMD, ENTRYPOINT should remain null.
   */
  @Test
  public void testCmdMode_withNoBaseEntrypoint()
      throws InvalidImageReferenceException, InterruptedException, CacheDirectoryCreationException,
          IOException, RegistryException, ExecutionException {

    // Use busybox which has no entrypoint
    localRegistry.pullAndPushToLocal("busybox", "busybox");
    String testImage = "localhost:5001/test-cmd-mode-no-base-entrypoint";

    Path tempDir = Files.createTempDirectory("jib-test-cmd-mode-no-base");
    tempDir.toFile().deleteOnExit();

    Jib.from("localhost:5001/busybox")
        .setContainerizingMode(ContainerizingMode.CMD)
        .setEntrypoint("java", "-jar", "app.jar")
        .setProgramArguments("arg1")
        .containerize(
            Containerizer.to(RegistryImage.named(testImage))
                .setAllowInsecureRegistries(true)
                .setApplicationLayersCache(tempDir));

    // Pull the built image
    localRegistry.pull(testImage);

    // Verify ENTRYPOINT is null/empty (no base entrypoint to inherit)
    String entrypoint = getEntrypoint(testImage);
    Assert.assertTrue(
        "ENTRYPOINT should be null/empty when base has no entrypoint",
        entrypoint.equals("[]") || entrypoint.equals("<no value>"));

    // Verify CMD contains the Java command
    String cmd = getCmd(testImage);
    MatcherAssert.assertThat(
        "CMD should contain java command",
        cmd,
        CoreMatchers.containsString("java"));
    MatcherAssert.assertThat(
        "CMD should contain app.jar",
        cmd,
        CoreMatchers.containsString("app.jar"));

    // Cleanup
    try {
      new Command("docker", "rmi", testImage).run();
    } catch (Exception e) {
      // Ignore
    }
  }
}
