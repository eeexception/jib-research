# CHANGES — INHERIT Entrypoint → CMD Routing

## Overview

When a base image defines an `ENTRYPOINT` (e.g. `/__cacert_entrypoint.sh` for certificate
setup that calls `exec "$@"`), Jib previously overwrote it with the auto-generated Java
launch command. Setting `entrypoint = INHERIT` was intended to preserve the base
`ENTRYPOINT`, but the Java command was silently discarded — it never appeared in `CMD`,
so the base entrypoint script had nothing to forward to.

This change fixes the gap: when `INHERIT` is active, the computed Java (or Jetty) command
is routed to Docker `CMD` instead of `ENTRYPOINT`, letting the base entrypoint script
`exec` into the application.

---

## Files Changed

### Production Source

| File | Lines | Role |
|---|---|---|
| `jib-plugins-common/.../PluginConfigurationProcessor.java` | 92–93, 436–442, 596–730 | Core routing logic and refactoring |

### Test Sources

| File | Lines | Role |
|---|---|---|
| `jib-plugins-common/.../PluginConfigurationProcessorTest.java` | 230–348 | 4 reproduction tests exercising the shared processor |
| `jib-core/.../BuildImageStepTest.java` | 318–342 | 1 contract test proving core layer supports null entrypoint + explicit CMD |
| `jib-maven-plugin/.../MavenRawConfigurationTest.java` | 160–175 | 1 adapter pass-through test |
| `jib-gradle-plugin/.../GradleRawConfigurationTest.java` | 161–176 | 1 adapter pass-through test |

### Documentation

| File | Change |
|---|---|
| `jib-maven-plugin/CHANGELOG.md` | Unreleased entry under `### Added` |
| `jib-gradle-plugin/CHANGELOG.md` | Unreleased entry under `### Added` |

---

## Implementation Detail

### Bug Root Cause

`computeEntrypoint()` evaluated the `INHERIT` keyword and returned `null` (line 703 in the
original) *before* the Java command list was ever constructed (lines 708–713 in the
original). The command was never built, so there was nothing to route to CMD.

### Fix — `computeEntrypoint()` in `PluginConfigurationProcessor.java`

#### 1. `isInherit` flag (lines 605–608)

```java
boolean isInherit =
    entrypointDefined
        && rawEntrypoint.get().size() == 1
        && "INHERIT".equals(rawEntrypoint.get().get(0));
```

Detects the single-element `["INHERIT"]` list. Used as a gate throughout the method to
distinguish "inherit base ENTRYPOINT and route command to CMD" from "user supplied an
explicit entrypoint list".

#### 2. Warning gate (lines 611–613)

```java
if (entrypointDefined
    && !isInherit
    && hasJavaLaunchConfiguration(rawConfiguration, rawExtraClasspath)) {
```

Suppresses the "mainClass/jvmFlags/… are ignored when entrypoint is specified" info log
when `INHERIT` is active, because those values are still consumed to build the CMD.

#### 3. WAR block INHERIT path (lines 635–638)

```java
if (isInherit) {
  jibContainerBuilder.setProgramArguments(jettyCommand);
  return null;
}
```

Constructs the Jetty command (or null for custom-base-image WAR projects), routes it to
CMD via `setProgramArguments`, then returns null so the base ENTRYPOINT is inherited.

#### 4. `MainClassInferenceException` gate (line 693)

```java
if (entrypointDefined && !isInherit) {
```

Previously swallowed the exception for *any* explicit entrypoint (including INHERIT).
Now it only swallows for genuinely user-supplied entrypoints. When INHERIT is active the
mainClass is required for CMD construction, so the exception is correctly rethrown.

#### 5. Non-WAR block INHERIT path (lines 707–716)

```java
ImmutableList.Builder<String> javaCommandBuilder =
    ImmutableList.<String>builder().add("java");
javaCommandBuilder.addAll(rawConfiguration.getJvmFlags());
ImmutableList<String> javaCommand =
    javaCommandBuilder.add("-cp").add(classpathString).add(mainClass).build();

if (isInherit) {
  jibContainerBuilder.setProgramArguments(javaCommand);
  return null;
}
```

Builds the full Java command *before* checking `isInherit`. When INHERIT is active,
routes the command to CMD and returns null for entrypoint.

#### 6. Calling-code conditional override (lines 440–442)

```java
rawConfiguration.getProgramArguments().ifPresent(jibContainerBuilder::setProgramArguments);
```

Replaced the unconditional `.setProgramArguments(…orElse(null))` which would overwrite
CMD back to null after `computeEntrypoint` set it. Now only applies when the user
explicitly configured `programArguments`.

---

## Refactoring

### `DEFAULT_JETTY_COMMAND` constant (lines 92–93)

```java
private static final ImmutableList<String> DEFAULT_JETTY_COMMAND =
    ImmutableList.of("java", "-jar", "/usr/local/jetty/start.jar", "--module=ee10-deploy");
```

Extracted the inline `Arrays.asList(…)` into a named `ImmutableList` constant. Matches
the project's established pattern for command-list constants (`GENERATED_LAYERS`,
`CONST_LAYERS`). Eliminates the repeated inline literal and provides compile-time
immutability.

### `hasJavaLaunchConfiguration` helper (lines 724–730)

```java
private static boolean hasJavaLaunchConfiguration(
    RawConfiguration rawConfiguration, List<String> rawExtraClasspath) {
  return rawConfiguration.getMainClass().isPresent()
      || !rawConfiguration.getJvmFlags().isEmpty()
      || !rawExtraClasspath.isEmpty()
      || rawConfiguration.getExpandClasspathDependencies();
}
```

The four-condition predicate "did the user configure mainClass, jvmFlags, extraClasspath,
or expandClasspathDependencies?" was duplicated verbatim in two places (the
entrypoint-specified info warning and the WAR-project warning). Extracted to a single
private helper, applied at both call sites (lines 613, 625).

### Immutable `javaCommand` via `ImmutableList.Builder` (lines 707–711)

Replaced mutable `ArrayList` + sequential `.add()` calls with `ImmutableList.Builder`.
The constructed command is handed off via `setProgramArguments` or returned — it should
be immutable at that boundary, consistent with `DEFAULT_JETTY_COMMAND`.

---

## New Tests

### Reproduction Tests — `PluginConfigurationProcessorTest.java`

These four tests exercise the shared processor layer directly. They were written *before*
the fix (TDD red phase), locked, and verified green after the fix.

| # | Method | Line | Purpose |
|---|---|---|---|
| 1 | `testReproduction_inheritEntrypoint_javaCommandLostFromCmd` | 230 | Baseline: `INHERIT` with default mainClass. Asserts `entrypoint` is null and `cmd` contains the full Java launch command. |
| 2 | `testReproduction_inheritEntrypoint_warProject_jettyCommandLostFromCmd` | 260 | WAR variant: `INHERIT` on a WAR project. Asserts `cmd` contains the default Jetty command, not the Java command. |
| 3 | `testReproduction_inheritEntrypoint_customMainClass_javaCommandLostFromCmd` | 289 | Custom `mainClass`: `INHERIT` with `mainClass = com.example.MyApp`. Asserts `cmd` carries the user-specified main class. |
| 4 | `testReproduction_inheritEntrypoint_jvmFlags_javaCommandLostFromCmd` | 318 | JVM flags: `INHERIT` with `-Xmx512m -Dapp.env=prod`. Asserts `cmd` includes the flags in correct position before `-cp`. |

All four assert the same contract:
- `buildPlan.getEntrypoint()` is `null` (base image ENTRYPOINT will be inherited).
- `buildPlan.getCmd()` contains the fully-constructed launch command (base image CMD is
  *not* inherited — the explicit CMD overrides it).

### Contract Test — `BuildImageStepTest.java`

| Method | Line | Purpose |
|---|---|---|
| `test_nullEntrypoint_cmdSet_baseEntrypointInherited` | 318 | Proves the core image-assembly layer (`BuildImageStep`) already supports null entrypoint + explicit programArguments. Base ENTRYPOINT `["baseImageEntrypoint"]` is inherited; base CMD `["catalina.sh", "run"]` is replaced by the explicit Java command. This contract must hold for the plugin-layer fix to work. |

### Plugin Adapter Pass-Through Tests

| Method | File | Line | Purpose |
|---|---|---|---|
| `testInheritEntrypointPassesThroughMavenAdapter` | `MavenRawConfigurationTest.java` | 160 | Constructs a real `MavenRawConfiguration` from a mocked `JibPluginConfiguration` that returns `entrypoint = ["INHERIT"]` and `args = null`. Asserts `getEntrypoint()` returns the keyword verbatim and `getProgramArguments()` returns `Optional.empty()` — confirming the Maven adapter does not intercept or transform the value, and does not interfere with the processor's CMD routing. |
| `testInheritEntrypointPassesThroughGradleAdapter` | `GradleRawConfigurationTest.java` | 161 | Same contract as above, exercised through the Gradle adapter path (`GradleRawConfiguration` ← mocked `JibExtension` ← mocked `ContainerParameters`). |

---

## Test Execution Summary

All tests pass with zero regressions across the four modules:

```
:jib-core:test                   BUILD SUCCESSFUL
:jib-plugins-common:test         BUILD SUCCESSFUL
:jib-maven-plugin:test           BUILD SUCCESSFUL
:jib-gradle-plugin:test          BUILD SUCCESSFUL
```
