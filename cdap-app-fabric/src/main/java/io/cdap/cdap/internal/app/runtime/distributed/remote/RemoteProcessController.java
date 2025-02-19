/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.internal.app.runtime.distributed.remote;

import io.cdap.cdap.runtime.spi.runtimejob.RuntimeJobStatus;

/**
 * Interface that exposes methods for controlling remote execution process.
 */
public interface RemoteProcessController {

  /**
   * Checks if the remote process for the program execution is still running or not.
   *
   * @return {@code true} if it is still running, {@code false} otherwise
   * @throws Exception if not able to determine if the process is still running or not
   */
  boolean isRunning() throws Exception;

  /**
   * Returns the runtime status {@link RuntimeJobStatus} of the remote process for the program execution.
   *
   * @throws Exception if not able to determine the status of the remote process
   */
  RuntimeJobStatus getStatus() throws Exception;

  /**
   * Graceful shutdown of the remote process
   * @throws Exception if not able to terminate the remote process
   */
  void terminate() throws Exception;

  /**
   * Kills the remote process depending on the ProgramStatus
   * @throws Exception if not able to kill the remote process
   * @param runtimeJobStatus
   */
  void kill(RuntimeJobStatus runtimeJobStatus) throws Exception;
}
