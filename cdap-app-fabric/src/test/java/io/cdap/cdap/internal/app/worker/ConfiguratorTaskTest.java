/*
 * Copyright © 2022 Cask Data, Inc.
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

package io.cdap.cdap.internal.app.worker;

import com.google.inject.Injector;
import io.cdap.cdap.common.conf.CConfiguration;
import io.cdap.cdap.internal.app.runtime.distributed.MockMasterEnvironment;
import io.cdap.cdap.master.environment.MasterEnvironments;
import io.cdap.cdap.master.spi.environment.MasterEnvironment;
import org.junit.Test;

public class ConfiguratorTaskTest {
  @Test
  public void testConfiguratorTaskInjector() throws Exception {
    MasterEnvironment masterEnvironment = new MockMasterEnvironment();
    masterEnvironment.initialize(null);
    MasterEnvironment tmpMasterEnv = MasterEnvironments.setMasterEnvironment(masterEnvironment);

    Injector injector = ConfiguratorTask.createInjector(CConfiguration.create());
    injector.getInstance(ConfiguratorTask.ConfiguratorTaskRunner.class);

    MasterEnvironments.setMasterEnvironment(tmpMasterEnv);
  }
}
