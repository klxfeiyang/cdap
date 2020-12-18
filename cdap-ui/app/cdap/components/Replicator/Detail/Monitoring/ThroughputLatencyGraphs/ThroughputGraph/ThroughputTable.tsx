/*
 * Copyright © 2020 Cask Data, Inc.
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

import React from 'react';
import withStyles, { WithStyles, StyleRules } from '@material-ui/core/styles/withStyles';
import { IThroughputLatencyData } from 'components/Replicator/Detail/Monitoring/ThroughputLatencyGraphs/parser';
import { convertBytesToHumanReadable } from 'services/helpers';
import { formatNumber } from 'components/Replicator/utilities';

const styles = (): StyleRules => {
  return {
    grid: {
      '&.grid-wrapper': {
        height: '100%',

        '& .grid.grid-container.grid-compact': {
          maxHeight: '345px',

          '& .grid-row': {
            gridTemplateColumns: '3fr 2fr 1fr 1fr 1fr 1fr',

            '& > div:not(:first-child)': {
              textAlign: 'right',
            },
          },
        },
      },
    },
  };
};

interface IThroughputTableProps extends WithStyles<typeof styles> {
  data: IThroughputLatencyData[];
}

const ThroughputTableView: React.FC<IThroughputTableProps> = ({ classes, data }) => {
  return (
    <div className={`grid-wrapper ${classes.grid}`}>
      <div className="grid grid-container grid-compact">
        <div className="grid-header">
          <div className="grid-row">
            <div>Date and time</div>
            <div>Data replicated</div>
            <div>Inserts</div>
            <div>Updates</div>
            <div>Deletes</div>
            <div>Errors</div>
          </div>
        </div>

        <div className="grid-body">
          {data.map((row) => {
            return (
              <div className="grid-row" key={row.time}>
                <div>{row.formattedTimeRange}</div>
                <div>{convertBytesToHumanReadable(row.dataReplicated, null, true)}</div>
                <div>{formatNumber(row.inserts)}</div>
                <div>{formatNumber(row.updates)}</div>
                <div>{formatNumber(row.deletes)}</div>
                <div>{formatNumber(row.errors)}</div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

const ThroughputTable = withStyles(styles)(ThroughputTableView);
export default ThroughputTable;
