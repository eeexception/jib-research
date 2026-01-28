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

package com.google.cloud.tools.jib.plugins.common;

/** Thrown when an invalid entrypoint mode value is specified. */
public class InvalidEntrypointModeException extends RuntimeException {

  private final String invalidMode;
  private final String validModes;

  /**
   * Creates a new exception.
   *
   * @param invalidMode the invalid mode value provided by the user
   * @param validModes description of valid mode values
   */
  public InvalidEntrypointModeException(String invalidMode, String validModes) {
    super("Invalid entrypoint mode: '" + invalidMode + "'. Valid modes are: " + validModes);
    this.invalidMode = invalidMode;
    this.validModes = validModes;
  }

  public String getInvalidMode() {
    return invalidMode;
  }

  public String getValidModes() {
    return validModes;
  }
}
