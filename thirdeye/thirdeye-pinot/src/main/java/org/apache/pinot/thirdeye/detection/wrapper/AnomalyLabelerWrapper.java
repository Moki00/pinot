/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pinot.thirdeye.detection.wrapper;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.collections4.MapUtils;
import org.apache.pinot.thirdeye.anomaly.AnomalySeverity;
import org.apache.pinot.thirdeye.datalayer.dto.DetectionConfigDTO;
import org.apache.pinot.thirdeye.datalayer.dto.EvaluationDTO;
import org.apache.pinot.thirdeye.datalayer.dto.MergedAnomalyResultDTO;
import org.apache.pinot.thirdeye.detection.ConfigUtils;
import org.apache.pinot.thirdeye.detection.DataProvider;
import org.apache.pinot.thirdeye.detection.DetectionPipeline;
import org.apache.pinot.thirdeye.detection.DetectionPipelineResult;
import org.apache.pinot.thirdeye.detection.DetectionUtils;
import org.apache.pinot.thirdeye.detection.PredictionResult;
import org.apache.pinot.thirdeye.detection.spi.components.Labeler;

/**
 * This anomaly labeler wrapper runs the anomaly labeler component to label anomalies generated by detector based on the labeler implementation.
 */
public class AnomalyLabelerWrapper extends DetectionPipeline {
  private static final String PROP_NESTED = "nested";
  private static final String PROP_LABELER = "labeler";
  private static final String PROP_METRIC_URN = "metricUrn";

  private final List<Map<String, Object>> nestedProperties;
  private String labelerName;
  private Labeler labeler;
  private String metricUrn;

  public AnomalyLabelerWrapper(DataProvider provider, DetectionConfigDTO config, long startTime, long endTime) {
    super(provider, config, startTime, endTime);
    Map<String, Object> properties = config.getProperties();
    this.nestedProperties = ConfigUtils.getList(properties.get(PROP_NESTED));

    Preconditions.checkArgument(this.config.getProperties().containsKey(PROP_LABELER));
    this.labelerName = DetectionUtils.getComponentKey(MapUtils.getString(config.getProperties(), PROP_LABELER));
    Preconditions.checkArgument(this.config.getComponents().containsKey(this.labelerName));
    this.labeler = (Labeler) this.config.getComponents().get(this.labelerName);
    this.metricUrn = MapUtils.getString(properties, PROP_METRIC_URN);

  }

  @Override
  public DetectionPipelineResult run() throws Exception {
    List<MergedAnomalyResultDTO> anomalies = new ArrayList<>();
    Map<String, Object> diagnostics = new HashMap<>();
    List<PredictionResult> predictionResults = new ArrayList<>();
    List<EvaluationDTO> evaluations = new ArrayList<>();

    Set<Long> lastTimeStamps = new HashSet<>();
    for (Map<String, Object> properties : this.nestedProperties) {
      if (this.metricUrn != null){
        properties.put(PROP_METRIC_URN, this.metricUrn);
      }
      DetectionPipelineResult intermediate = this.runNested(properties, this.startTime, this.endTime);
      lastTimeStamps.add(intermediate.getLastTimestamp());
      predictionResults.addAll(intermediate.getPredictions());
      evaluations.addAll(intermediate.getEvaluations());
      diagnostics.putAll(intermediate.getDiagnostics());
      anomalies.addAll(intermediate.getAnomalies());
    }
    Map<MergedAnomalyResultDTO, AnomalySeverity> res = this.labeler.label(anomalies);
    for (MergedAnomalyResultDTO anomaly : anomalies) {
      AnomalySeverity newSeverity = res.getOrDefault(anomaly, AnomalySeverity.DEFAULT);
      if (anomaly.getSeverityLabel() != newSeverity) {
        if (anomaly.getId() != null && anomaly.getSeverityLabel().compareTo(newSeverity) > 0) {
          // only set renotify if the anomaly exists and its severity gets higher
          anomaly.setRenotify(true);
        }
        anomaly.setSeverityLabel(newSeverity);
      }
    }
    return new DetectionPipelineResult(anomalies, DetectionUtils.consolidateNestedLastTimeStamps(lastTimeStamps),
        predictionResults, evaluations).setDiagnostics(diagnostics);
  }
}
