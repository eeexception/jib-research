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

package com.google.cloud.tools.jib.configuration;

/**
 * Defines where the Java launch command should be placed in the Docker image configuration.
 *
 * <p>This enum controls how Jib containerizes the Java application by determining whether the Java
 * launch command is placed in the Docker ENTRYPOINT or CMD field.
 */
public enum ContainerizingMode {
  /**
   * Default mode (backward compatible).
   *
   * <p>The Java launch command is placed in the Docker ENTRYPOINT field. Additional arguments
   * configured via programArguments go to the Docker CMD field.
   *
   * <p>This is the traditional Jib behavior where the Java command is the main executable.
   *
   * <p>Example result:
   * <pre>
   * {
   *   "Entrypoint": ["java", "-cp", "/app/classes:/app/libs/*", "com.example.Main"],
   *   "Cmd": ["arg1", "arg2"]
   * }
   * </pre>
   */
  ENTRYPOINT,

  /**
   * Command mode.
   *
   * <p>The Java launch command is placed in the Docker CMD field. The base image ENTRYPOINT is
   * inherited and preserved, allowing base image wrapper scripts or init systems to execute before
   * the Java application.
   *
   * <p>This mode is useful when the base image contains an entrypoint script that performs setup
   * tasks (e.g., certificate configuration, environment initialization) before executing the actual
   * application command.
   *
   * <p>Example use case: Base image with {@code ENTRYPOINT ["/__cacert_entrypoint.sh"]}
   *
   * <p>Example result:
   * <pre>
   * {
   *   "Entrypoint": ["/__cacert_entrypoint.sh"],
   *   "Cmd": ["java", "-cp", "/app/classes:/app/libs/*", "com.example.Main", "arg1", "arg2"]
   * }
   * </pre>
   *
   * <p>Docker will execute: {@code /__cacert_entrypoint.sh java -cp ... com.example.Main arg1 arg2}
   */
  CMD
}
