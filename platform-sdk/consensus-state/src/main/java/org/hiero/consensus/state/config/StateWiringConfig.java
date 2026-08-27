// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration;

/**
 * Contains configuration values for the state management wiring.
 *
 * @param stateHasher configuration for the state hasher scheduler
 * @param hashLogger configuration for the hash logger scheduler
 * @param stateSigner configuration for the state signer scheduler
 * @param stateSignatureCollector configuration for the state signature collector scheduler
 * @param stateSnapshotManager configuration for the state snapshot manager scheduler
 * @param stateGarbageCollector configuration for the state garbage collector scheduler
 * @param stateGarbageCollectorHeartbeatPeriod the frequency that heartbeats should be sent to the state garbage
 * @param signedStateSentinel configuration for the signed state sentinel scheduler
 * @param signedStateSentinelHeartbeatPeriod the frequency of signed state sentinels heartbeats
 */
@ConfigData("state.wiring")
public record StateWiringConfig(
        @ConfigProperty(
                defaultValue =
                        "SEQUENTIAL_THREAD CAPACITY(100000) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC")
        TaskSchedulerConfiguration stateHasher,

        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(100) UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration hashLogger,

        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(10) UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration stateSigner,

        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration stateSignatureCollector,

        @ConfigProperty(defaultValue = "SEQUENTIAL_THREAD CAPACITY(20) UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration stateSnapshotManager,

        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(60) UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration stateGarbageCollector,

        @ConfigProperty(defaultValue = "200ms") Duration stateGarbageCollectorHeartbeatPeriod,

        @ConfigProperty(defaultValue = "SEQUENTIAL UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration signedStateSentinel,

        @ConfigProperty(defaultValue = "10s") Duration signedStateSentinelHeartbeatPeriod) {}
