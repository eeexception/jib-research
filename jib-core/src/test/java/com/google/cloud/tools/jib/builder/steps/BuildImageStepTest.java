/*
 * Copyright 2018 Google LLC.
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

package com.google.cloud.tools.jib.builder.steps;

import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.api.buildplan.Port;
import com.google.cloud.tools.jib.blob.BlobDescriptor;
import com.google.cloud.tools.jib.builder.ProgressEventDispatcher;
import com.google.cloud.tools.jib.cache.CachedLayer;
import com.google.cloud.tools.jib.configuration.BuildContext;
import com.google.cloud.tools.jib.configuration.ContainerConfiguration;
import com.google.cloud.tools.jib.configuration.DockerHealthCheck;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.image.Image;
import com.google.cloud.tools.jib.image.json.HistoryEntry;
import com.google.cloud.tools.jib.image.json.V22ManifestTemplate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.security.DigestException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

/** Tests for {@link BuildImageStep}. */
@RunWith(MockitoJUnitRunner.class)
public class BuildImageStepTest {

  @Mock private ProgressEventDispatcher.Factory mockProgressEventDispatcherFactory;
  @Mock private BuildContext mockBuildContext;
  @Mock private ContainerConfiguration mockContainerConfiguration;
  @Mock private CachedLayer mockCachedLayer;

  private Image baseImage;
  private List<PreparedLayer> baseImageLayers;
  private List<PreparedLayer> applicationLayers;

  private DescriptorDigest testDescriptorDigest;
  private HistoryEntry nonEmptyLayerHistory;
  private HistoryEntry emptyLayerHistory;

  @Before
  public void setUp() throws DigestException {
    testDescriptorDigest =
        DescriptorDigest.fromHash(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

    Mockito.when(mockBuildContext.getEventHandlers()).thenReturn(EventHandlers.NONE);
    Mockito.when(mockBuildContext.getContainerConfiguration())
        .thenReturn(mockContainerConfiguration);
    Mockito.when(mockBuildContext.getToolName()).thenReturn("jib");
    Mockito.when(mockContainerConfiguration.getCreationTime()).thenReturn(Instant.EPOCH);
    Mockito.when(mockContainerConfiguration.getEnvironmentMap()).thenReturn(ImmutableMap.of());
    Mockito.when(mockContainerConfiguration.getProgramArguments()).thenReturn(ImmutableList.of());
    Mockito.when(mockContainerConfiguration.getExposedPorts()).thenReturn(ImmutableSet.of());
    Mockito.when(mockContainerConfiguration.getEntrypoint()).thenReturn(ImmutableList.of());
    Mockito.when(mockContainerConfiguration.getUser()).thenReturn("root");
    Mockito.when(mockCachedLayer.getBlobDescriptor())
        .thenReturn(new BlobDescriptor(0, testDescriptorDigest));

    nonEmptyLayerHistory =
        HistoryEntry.builder()
            .setCreationTimestamp(Instant.EPOCH)
            .setAuthor("JibBase")
            .setCreatedBy("jib-test")
            .build();
    emptyLayerHistory =
        HistoryEntry.builder()
            .setCreationTimestamp(Instant.EPOCH)
            .setAuthor("JibBase")
            .setCreatedBy("jib-test")
            .setEmptyLayer(true)
            .build();

    baseImage =
        Image.builder(V22ManifestTemplate.class)
            .setArchitecture("wasm")
            .setOs("js")
            .addEnvironment(ImmutableMap.of("BASE_ENV", "BASE_ENV_VALUE", "BASE_ENV_2", "DEFAULT"))
            .addLabel("base.label", "base.label.value")
            .addLabel("base.label.2", "default")
            .setUser("base:user")
            .setWorkingDirectory("/base/working/directory")
            .setEntrypoint(ImmutableList.of("baseImageEntrypoint"))
            .setProgramArguments(ImmutableList.of("catalina.sh", "run"))
            .setHealthCheck(
                DockerHealthCheck.fromCommand(ImmutableList.of("CMD-SHELL", "echo hi"))
                    .setInterval(Duration.ofSeconds(3))
                    .setTimeout(Duration.ofSeconds(2))
                    .setStartPeriod(Duration.ofSeconds(1))
                    .setRetries(20)
                    .build())
            .addExposedPorts(ImmutableSet.of(Port.tcp(1000), Port.udp(2000)))
            .addVolumes(
                ImmutableSet.of(
                    AbsoluteUnixPath.get("/base/path1"), AbsoluteUnixPath.get("/base/path2")))
            .addHistory(nonEmptyLayerHistory)
            .addHistory(emptyLayerHistory)
            .addHistory(emptyLayerHistory)
            .build();
    baseImageLayers =
        Arrays.asList(
            new PreparedLayer.Builder(mockCachedLayer).build(),
            new PreparedLayer.Builder(mockCachedLayer).build(),
            new PreparedLayer.Builder(mockCachedLayer).build());
    applicationLayers =
        Arrays.asList(
            new PreparedLayer.Builder(mockCachedLayer).setName("dependencies").build(),
            new PreparedLayer.Builder(mockCachedLayer).setName("resources").build(),
            new PreparedLayer.Builder(mockCachedLayer).setName("classes").build(),
            new PreparedLayer.Builder(mockCachedLayer).setName("extra files").build());
  }

  @Test
  public void test_basicCase() {
    Image image =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImage,
                baseImageLayers,
                applicationLayers)
            .call();
    Assert.assertEquals("root", image.getUser());
    Assert.assertEquals(
        testDescriptorDigest, image.getLayers().get(0).getBlobDescriptor().getDigest());
  }

  @Test
  public void test_propagateBaseImageConfiguration() {
    Mockito.when(mockContainerConfiguration.getEnvironmentMap())
        .thenReturn(ImmutableMap.of("MY_ENV", "MY_ENV_VALUE", "BASE_ENV_2", "NEW_VALUE"));
    Mockito.when(mockContainerConfiguration.getLabels())
        .thenReturn(ImmutableMap.of("my.label", "my.label.value", "base.label.2", "new.value"));
    Mockito.when(mockContainerConfiguration.getExposedPorts())
        .thenReturn(ImmutableSet.of(Port.tcp(3000), Port.udp(4000)));
    Mockito.when(mockContainerConfiguration.getVolumes())
        .thenReturn(
            ImmutableSet.of(
                AbsoluteUnixPath.get("/new/path1"), AbsoluteUnixPath.get("/new/path2")));
    Image image =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImage,
                baseImageLayers,
                applicationLayers)
            .call();
    Assert.assertEquals("wasm", image.getArchitecture());
    Assert.assertEquals("js", image.getOs());
    Assert.assertEquals(
        ImmutableMap.of(
            "BASE_ENV", "BASE_ENV_VALUE", "MY_ENV", "MY_ENV_VALUE", "BASE_ENV_2", "NEW_VALUE"),
        image.getEnvironment());
    Assert.assertEquals(
        ImmutableMap.of(
            "base.label",
            "base.label.value",
            "my.label",
            "my.label.value",
            "base.label.2",
            "new.value"),
        image.getLabels());
    Assert.assertNotNull(image.getHealthCheck());
    Assert.assertEquals(
        ImmutableList.of("CMD-SHELL", "echo hi"), image.getHealthCheck().getCommand());
    Assert.assertTrue(image.getHealthCheck().getInterval().isPresent());
    Assert.assertEquals(Duration.ofSeconds(3), image.getHealthCheck().getInterval().get());
    Assert.assertTrue(image.getHealthCheck().getTimeout().isPresent());
    Assert.assertEquals(Duration.ofSeconds(2), image.getHealthCheck().getTimeout().get());
    Assert.assertTrue(image.getHealthCheck().getStartPeriod().isPresent());
    Assert.assertEquals(Duration.ofSeconds(1), image.getHealthCheck().getStartPeriod().get());
    Assert.assertTrue(image.getHealthCheck().getRetries().isPresent());
    Assert.assertEquals(20, (int) image.getHealthCheck().getRetries().get());
    Assert.assertEquals(
        ImmutableSet.of(Port.tcp(1000), Port.udp(2000), Port.tcp(3000), Port.udp(4000)),
        image.getExposedPorts());
    Assert.assertEquals(
        ImmutableSet.of(
            AbsoluteUnixPath.get("/base/path1"),
            AbsoluteUnixPath.get("/base/path2"),
            AbsoluteUnixPath.get("/new/path1"),
            AbsoluteUnixPath.get("/new/path2")),
        image.getVolumes());
    Assert.assertEquals("/base/working/directory", image.getWorkingDirectory());
    Assert.assertEquals("root", image.getUser());

    Assert.assertEquals(image.getHistory().get(0), nonEmptyLayerHistory);
    Assert.assertEquals(image.getHistory().get(1), emptyLayerHistory);
    Assert.assertEquals(image.getHistory().get(2), emptyLayerHistory);
    Assert.assertEquals(ImmutableList.of(), image.getEntrypoint());
    Assert.assertEquals(ImmutableList.of(), image.getProgramArguments());
  }

  @Test
  public void testOverrideWorkingDirectory() {
    Mockito.when(mockContainerConfiguration.getWorkingDirectory())
        .thenReturn(AbsoluteUnixPath.get("/my/directory"));

    Image image =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImage,
                baseImageLayers,
                applicationLayers)
            .call();

    Assert.assertEquals("/my/directory", image.getWorkingDirectory());
  }

  @Test
  public void test_inheritedUser() {
    Mockito.when(mockContainerConfiguration.getUser()).thenReturn(null);

    Image image =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImage,
                baseImageLayers,
                applicationLayers)
            .call();

    Assert.assertEquals("base:user", image.getUser());
  }

  @Test
  public void test_inheritedEntrypoint() {
    Mockito.when(mockContainerConfiguration.getEntrypoint()).thenReturn(null);
    Mockito.when(mockContainerConfiguration.getProgramArguments())
        .thenReturn(ImmutableList.of("test"));

    Image image =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImage,
                baseImageLayers,
                applicationLayers)
            .call();

    Assert.assertEquals(ImmutableList.of("baseImageEntrypoint"), image.getEntrypoint());
    Assert.assertEquals(ImmutableList.of("test"), image.getProgramArguments());
  }

  @Test
  public void test_inheritedEntrypointAndProgramArguments() {
    Mockito.when(mockContainerConfiguration.getEntrypoint()).thenReturn(null);
    Mockito.when(mockContainerConfiguration.getProgramArguments()).thenReturn(null);

    Image image =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImage,
                baseImageLayers,
                applicationLayers)
            .call();

    Assert.assertEquals(ImmutableList.of("baseImageEntrypoint"), image.getEntrypoint());
    Assert.assertEquals(ImmutableList.of("catalina.sh", "run"), image.getProgramArguments());
  }

  @Test
  public void test_notInheritedProgramArguments() {
    Mockito.when(mockContainerConfiguration.getEntrypoint())
        .thenReturn(ImmutableList.of("myEntrypoint"));
    Mockito.when(mockContainerConfiguration.getProgramArguments()).thenReturn(null);

    Image image =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImage,
                baseImageLayers,
                applicationLayers)
            .call();

    Assert.assertEquals(ImmutableList.of("myEntrypoint"), image.getEntrypoint());
    Assert.assertNull(image.getProgramArguments());
  }

  @Test
  public void test_generateHistoryObjects() {
    Image image =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImage,
                baseImageLayers,
                applicationLayers)
            .call();

    // Make sure history is as expected
    HistoryEntry expectedAddedBaseLayerHistory =
        HistoryEntry.builder()
            .setCreationTimestamp(Instant.EPOCH)
            .setComment("auto-generated by Jib")
            .build();

    HistoryEntry expectedApplicationLayerHistoryDependencies =
        HistoryEntry.builder()
            .setCreationTimestamp(Instant.EPOCH)
            .setAuthor("Jib")
            .setCreatedBy("jib:null")
            .setComment("dependencies")
            .build();

    HistoryEntry expectedApplicationLayerHistoryResources =
        HistoryEntry.builder()
            .setCreationTimestamp(Instant.EPOCH)
            .setAuthor("Jib")
            .setCreatedBy("jib:null")
            .setComment("resources")
            .build();

    HistoryEntry expectedApplicationLayerHistoryClasses =
        HistoryEntry.builder()
            .setCreationTimestamp(Instant.EPOCH)
            .setAuthor("Jib")
            .setCreatedBy("jib:null")
            .setComment("classes")
            .build();

    HistoryEntry expectedApplicationLayerHistoryExtrafiles =
        HistoryEntry.builder()
            .setCreationTimestamp(Instant.EPOCH)
            .setAuthor("Jib")
            .setCreatedBy("jib:null")
            .setComment("extra files")
            .build();

    // Base layers (1 non-empty propagated, 2 empty propagated, 2 non-empty generated)
    Assert.assertEquals(nonEmptyLayerHistory, image.getHistory().get(0));
    Assert.assertEquals(emptyLayerHistory, image.getHistory().get(1));
    Assert.assertEquals(emptyLayerHistory, image.getHistory().get(2));
    Assert.assertEquals(expectedAddedBaseLayerHistory, image.getHistory().get(3));
    Assert.assertEquals(expectedAddedBaseLayerHistory, image.getHistory().get(4));

    // Application layers (4 generated)
    Assert.assertEquals(expectedApplicationLayerHistoryDependencies, image.getHistory().get(5));
    Assert.assertEquals(expectedApplicationLayerHistoryResources, image.getHistory().get(6));
    Assert.assertEquals(expectedApplicationLayerHistoryClasses, image.getHistory().get(7));
    Assert.assertEquals(expectedApplicationLayerHistoryExtrafiles, image.getHistory().get(8));

    // Should be exactly 9 total
    Assert.assertEquals(9, image.getHistory().size());
  }

  @Test
  public void testTruncateLongClasspath_shortClasspath() {
    ImmutableList<String> entrypoint =
        ImmutableList.of(
            "java", "-Dmy-property=value", "-cp", "/app/classes:/app/libs/*", "com.example.Main");

    Assert.assertEquals(
        "[java, -Dmy-property=value, -cp, /app/classes:/app/libs/*, com.example.Main]",
        BuildImageStep.truncateLongClasspath(entrypoint));
  }

  @Test
  public void testTruncateLongClasspath_longClasspath() {
    String classpath =
        "/app/resources:/app/classes:/app/libs/spring-boot-starter-web-2.0.3.RELEASE.jar:/app/libs/"
            + "shared-library-0.1.0.jar:/app/libs/spring-boot-starter-json-2.0.3.RELEASE.jar:/app/"
            + "libs/spring-boot-starter-2.0.3.RELEASE.jar:/app/libs/spring-boot-starter-tomcat-2.0."
            + "3.RELEASE.jar";
    ImmutableList<String> entrypoint =
        ImmutableList.of("java", "-Dmy-property=value", "-cp", classpath, "com.example.Main");

    Assert.assertEquals(
        "[java, -Dmy-property=value, -cp, /app/resources:/app/classes:/app/libs/spring-boot-starter"
            + "-web-2.0.3.RELEASE.jar:/app/libs/shared-library-0.1.0.jar:/app/libs/spring-boot-"
            + "starter-json-2.0.3.RELEASE.jar:/app/libs/spring-boot-starter-2.<... classpath "
            + "truncated ...>, com.example.Main]",
        BuildImageStep.truncateLongClasspath(entrypoint));
  }

  // ============================================================================
  // RED TESTS: These tests demonstrate the problematic behavior that needs fixing.
  // These tests are LOCKED and cannot be modified during implementation.
  // ============================================================================

  /**
   * RED TEST 1 (LOCKED): CMD mode should preserve base image entrypoint.
   *
   * <p>Problem: When a base image has an entrypoint script (e.g., /__cacert_entrypoint.sh), Jib
   * currently overwrites it with the Java launch command. This test demonstrates that with CMD
   * mode enabled, the base image entrypoint should be preserved.
   *
   * <p>Expected to FAIL until ContainerizingMode.CMD is implemented.
   */
  @Test
  public void testCmdMode_preservesBaseImageEntrypoint() {
    // Base image has a cacert entrypoint script
    Image baseImageWithEntrypoint =
        Image.builder(V22ManifestTemplate.class)
            .setEntrypoint(ImmutableList.of("/__cacert_entrypoint.sh"))
            .build();

    // User configures Java command with CMD mode
    Mockito.when(mockContainerConfiguration.getContainerizingMode())
        .thenReturn(com.google.cloud.tools.jib.configuration.ContainerizingMode.CMD);
    Mockito.when(mockContainerConfiguration.getEntrypoint())
        .thenReturn(ImmutableList.of("java", "-cp", "/app/classes:/app/libs/*", "com.example.Main"));
    Mockito.when(mockContainerConfiguration.getProgramArguments())
        .thenReturn(ImmutableList.of("--port", "8080"));

    Image result =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImageWithEntrypoint,
                baseImageLayers,
                applicationLayers)
            .call();

    // Verify: Base image entrypoint is preserved (not overwritten)
    Assert.assertEquals(
        ImmutableList.of("/__cacert_entrypoint.sh"),
        result.getEntrypoint());
  }

  /**
   * RED TEST 2 (LOCKED): CMD mode should combine Java command and args into program arguments.
   *
   * <p>Problem: In CMD mode, the Java launch command (configured as "entrypoint") should be placed
   * in the Docker CMD field (programArguments), combined with any additional arguments.
   *
   * <p>Expected to FAIL until ContainerizingMode.CMD is implemented.
   */
  @Test
  public void testCmdMode_combinesJavaCommandAndArgsIntoProgramArguments() {
    Image baseImageWithEntrypoint =
        Image.builder(V22ManifestTemplate.class)
            .setEntrypoint(ImmutableList.of("/__cacert_entrypoint.sh"))
            .build();

    Mockito.when(mockContainerConfiguration.getContainerizingMode())
        .thenReturn(com.google.cloud.tools.jib.configuration.ContainerizingMode.CMD);
    Mockito.when(mockContainerConfiguration.getEntrypoint())
        .thenReturn(ImmutableList.of("java", "-cp", "/app/classes:/app/libs/*", "com.example.Main"));
    Mockito.when(mockContainerConfiguration.getProgramArguments())
        .thenReturn(ImmutableList.of("--port", "8080"));

    Image result =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImageWithEntrypoint,
                baseImageLayers,
                applicationLayers)
            .call();

    // Verify: Java command + args are combined into program arguments (Docker CMD)
    Assert.assertEquals(
        ImmutableList.of("java", "-cp", "/app/classes:/app/libs/*", "com.example.Main", "--port", "8080"),
        result.getProgramArguments());
  }

  /**
   * RED TEST 3 (LOCKED): CMD mode with null base entrypoint should work correctly.
   *
   * <p>Edge case: When base image has no entrypoint and CMD mode is enabled, the entrypoint should
   * remain null and Java command should go to CMD.
   *
   * <p>Expected to FAIL until ContainerizingMode.CMD is implemented.
   */
  @Test
  public void testCmdMode_nullBaseEntrypoint_putsCmdOnly() {
    // Base image with no entrypoint
    Image baseImageWithoutEntrypoint =
        Image.builder(V22ManifestTemplate.class)
            .setEntrypoint(null)
            .build();

    Mockito.when(mockContainerConfiguration.getContainerizingMode())
        .thenReturn(com.google.cloud.tools.jib.configuration.ContainerizingMode.CMD);
    Mockito.when(mockContainerConfiguration.getEntrypoint())
        .thenReturn(ImmutableList.of("java", "-jar", "app.jar"));
    Mockito.when(mockContainerConfiguration.getProgramArguments()).thenReturn(null);

    Image result =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImageWithoutEntrypoint,
                baseImageLayers,
                applicationLayers)
            .call();

    // Verify: Entrypoint remains null (nothing to inherit)
    Assert.assertNull(result.getEntrypoint());

    // Verify: Java command goes to program arguments (Docker CMD)
    Assert.assertEquals(
        ImmutableList.of("java", "-jar", "app.jar"),
        result.getProgramArguments());
  }

  /**
   * RED TEST 4 (LOCKED): ENTRYPOINT mode should maintain current behavior (backward compatibility).
   *
   * <p>This test ensures that the default ENTRYPOINT mode maintains the current behavior where the
   * Java launch command goes to Docker ENTRYPOINT and overwrites the base image entrypoint.
   *
   * <p>Expected to FAIL until ContainerizingMode.ENTRYPOINT is implemented with default behavior.
   */
  @Test
  public void testEntrypointMode_maintainsCurrentBehavior() {
    Image baseImageWithEntrypoint =
        Image.builder(V22ManifestTemplate.class)
            .setEntrypoint(ImmutableList.of("/__cacert_entrypoint.sh"))
            .build();

    // ENTRYPOINT mode (default) - should maintain current behavior
    Mockito.when(mockContainerConfiguration.getContainerizingMode())
        .thenReturn(com.google.cloud.tools.jib.configuration.ContainerizingMode.ENTRYPOINT);
    Mockito.when(mockContainerConfiguration.getEntrypoint())
        .thenReturn(ImmutableList.of("java", "-jar", "app.jar"));
    Mockito.when(mockContainerConfiguration.getProgramArguments())
        .thenReturn(ImmutableList.of("arg1", "arg2"));

    Image result =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImageWithEntrypoint,
                baseImageLayers,
                applicationLayers)
            .call();

    // Verify: Java command goes to entrypoint (overwrites base image)
    Assert.assertEquals(
        ImmutableList.of("java", "-jar", "app.jar"),
        result.getEntrypoint());

    // Verify: Program arguments remain separate
    Assert.assertEquals(
        ImmutableList.of("arg1", "arg2"),
        result.getProgramArguments());
  }

  /**
   * RED TEST 5 (LOCKED): CMD mode with only Java command (no extra args).
   *
   * <p>Test case: User provides only the Java command without additional program arguments. The
   * Java command should still go to CMD in CMD mode.
   *
   * <p>Expected to FAIL until ContainerizingMode.CMD is implemented.
   */
  @Test
  public void testCmdMode_onlyJavaCommand_noProgramArguments() {
    Image baseImageWithEntrypoint =
        Image.builder(V22ManifestTemplate.class)
            .setEntrypoint(ImmutableList.of("/tini", "--"))
            .build();

    Mockito.when(mockContainerConfiguration.getContainerizingMode())
        .thenReturn(com.google.cloud.tools.jib.configuration.ContainerizingMode.CMD);
    Mockito.when(mockContainerConfiguration.getEntrypoint())
        .thenReturn(ImmutableList.of("java", "-jar", "app.jar"));
    Mockito.when(mockContainerConfiguration.getProgramArguments()).thenReturn(null);

    Image result =
        new BuildImageStep(
                mockBuildContext,
                mockProgressEventDispatcherFactory,
                baseImageWithEntrypoint,
                baseImageLayers,
                applicationLayers)
            .call();

    // Verify: Base entrypoint preserved
    Assert.assertEquals(
        ImmutableList.of("/tini", "--"),
        result.getEntrypoint());

    // Verify: Java command in program arguments
    Assert.assertEquals(
        ImmutableList.of("java", "-jar", "app.jar"),
        result.getProgramArguments());
  }
}
