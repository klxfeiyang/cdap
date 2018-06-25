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

package co.cask.cdap.proto.metadata.lineage;

import co.cask.cdap.api.annotation.Beta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Represents the lineage summary associated with the field of a dataset.
 *
 * In incoming direction, it consists of the datasets and their fields
 * ({@link DatasetField}) that this field originates from.
 *
 * In outgoing direction, it consists of the datasets and their fields
 * ({@link DatasetField}) that were computed from this field.
 */
@Beta
public class FieldLineageSummary {
  private final List<DatasetField> incoming;
  private final List<DatasetField> outgoing;

  public FieldLineageSummary(@Nullable List<DatasetField> incoming,
                             @Nullable List<DatasetField> outgoing) {
    this.incoming = incoming == null ? null : Collections.unmodifiableList(new ArrayList<>(incoming));
    this.outgoing = outgoing == null ? null : Collections.unmodifiableList(new ArrayList<>(outgoing));
  }

  @Nullable
  public List<DatasetField> getIncoming() {
    return incoming;
  }

  @Nullable
  public List<DatasetField> getOutgoing() {
    return outgoing;
  }
}