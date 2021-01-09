/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.io.network.partition.consumer.IndexedInputGate;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.io.*;
import org.apache.flink.streaming.runtime.io.PushingAsyncDataInput.DataOutput;
import org.apache.flink.streaming.runtime.metrics.WatermarkGauge;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StatusWatermarkValve;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatusMaintainer;

import static org.apache.flink.util.Preconditions.checkNotNull;

@Internal
public class OneInputStreamTask<IN, OUT> extends StreamTask<OUT, OneInputStreamOperator<IN, OUT>> {

	private final WatermarkGauge inputWatermarkGauge = new WatermarkGauge();

	public OneInputStreamTask(Environment env) throws Exception {
		super(env);
	}

	@Override
	public void init() throws Exception {
		StreamConfig configuration = getConfiguration();
		int numberOfInputs = configuration.getNumberOfInputs();

		if (numberOfInputs > 0) {
			CheckpointedInputGate inputGate = createCheckpointedInputGate();
			DataOutput<IN> output = createDataOutput();
			StreamTaskInput<IN> input = createTaskInput(inputGate, output);
			inputProcessor = new StreamOneInputProcessor<>(
				input,
				output,
				operatorChain);
		}
	}

	private CheckpointedInputGate createCheckpointedInputGate() {
		IndexedInputGate[] inputGates = getEnvironment().getAllInputGates();

		return InputProcessorUtil.createCheckpointedInputGate(
			this,
			configuration,
			inputGates,
			getTaskNameWithSubtaskAndId());
	}

	private DataOutput<IN> createDataOutput() {
		return new StreamTaskNetworkOutput<>(
			headOperator,
			getStreamStatusMaintainer(),
			inputWatermarkGauge);
	}

	private StreamTaskInput<IN> createTaskInput(CheckpointedInputGate inputGate, DataOutput<IN> output) {
		int numberOfInputChannels = inputGate.getNumberOfInputChannels();
		StatusWatermarkValve statusWatermarkValve = new StatusWatermarkValve(numberOfInputChannels, output);

		TypeSerializer<IN> inSerializer = configuration.getTypeSerializerIn1(getUserCodeClassLoader());
		return new StreamTaskNetworkInput<>(
			inputGate,
			inSerializer,
			statusWatermarkValve,
			0);
	}

	/**
	 * The network data output implementation used for processing stream elements
	 * from {@link StreamTaskNetworkInput} in one input processor.
	 */
	private static class StreamTaskNetworkOutput<IN> extends AbstractDataOutput<IN> {

		private final OneInputStreamOperator<IN, ?> operator;

		private final WatermarkGauge watermarkGauge;

		private StreamTaskNetworkOutput(
				OneInputStreamOperator<IN, ?> operator,
				StreamStatusMaintainer streamStatusMaintainer,
				WatermarkGauge watermarkGauge) {
			super(streamStatusMaintainer);

			this.operator = checkNotNull(operator);
			this.watermarkGauge = checkNotNull(watermarkGauge);
		}

		@Override
		public void emitRecord(StreamRecord<IN> record) throws Exception {
			operator.setKeyContextElement1(record);
			operator.processElement(record);
		}

		@Override
		public void emitWatermark(Watermark watermark) throws Exception {
			watermarkGauge.setCurrentWatermark(watermark.getTimestamp());
			operator.processWatermark(watermark);
		}

		@Override
		public void emitLatencyMarker(LatencyMarker latencyMarker) throws Exception {
			operator.processLatencyMarker(latencyMarker);
		}
	}
}
