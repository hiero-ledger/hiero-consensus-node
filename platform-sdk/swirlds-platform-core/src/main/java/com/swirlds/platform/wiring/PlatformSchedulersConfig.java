// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Contains configuration values for the platform schedulers.
 *
 * @param internalEventValidator               configuration for the internal event validator scheduler
 * @param eventDeduplicator                    configuration for the event deduplicator scheduler
 * @param eventSignatureValidator              configuration for the event signature validator scheduler
 * @param orphanBuffer                         configuration for the orphan buffer scheduler
 * @param consensusEngine                      configuration for the consensus engine scheduler
 * @param eventCreationManager                 configuration for the event creation manager scheduler
 * @param stateSigner                          configuration for the state signer scheduler
 * @param pcesSequencer                        configuration for the preconsensus event sequencer scheduler
 * @param applicationTransactionPrehandler     configuration for the application transaction prehandler scheduler
 * @param stateSignatureCollector              configuration for the state signature collector scheduler
 * @param transactionHandler                   configuration for the transaction handler scheduler
 * @param issDetector                          configuration for the ISS detector scheduler
 * @param issHandler                           configuration for the ISS handler scheduler
 * @param hashLogger                           configuration for the hash logger scheduler
 * @param latestCompleteStateNotifier          configuration for the latest complete state notifier scheduler
 * @param stateHasher                          configuration for the state hasher scheduler
 * @param stateGarbageCollector                configuration for the state garbage collector scheduler
 * @param stateGarbageCollectorHeartbeatPeriod the frequency that heartbeats should be sent to the state garbage
 *                                             collector
 * @param platformPublisher                    configuration for the platform publisher scheduler
 * @param consensusEventStream                 configuration for the consensus event stream scheduler
 * @param roundDurabilityBuffer                configuration for the round durability buffer scheduler
 * @param signedStateSentinel                  configuration for the signed state sentinel scheduler
 * @param signedStateSentinelHeartbeatPeriod   the frequency that heartbeats should be sent to the signed state
 *                                             sentinel
 * @param platformMonitor                      configuration for the platform monitor scheduler
 * @param transactionPool                      configuration for the transaction pool scheduler
 * @param gossip                               configuration for the gossip scheduler
 * @param eventHasher                          configuration for the event hasher scheduler
 * @param branchDetector                       configuration for the branch detector scheduler
 * @param branchReporter                       configuration for the branch reporter scheduler
 */
@ConfigData("platformSchedulers")
public record PlatformSchedulersConfig(
        @ConfigProperty(defaultValue = "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration internalEventValidator,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(5000) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration eventDeduplicator,
        @ConfigProperty(defaultValue = "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration eventSignatureValidator,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration orphanBuffer,
        @ConfigProperty(
                        defaultValue =
                                "SEQUENTIAL_THREAD CAPACITY(500) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC")
                TaskSchedulerConfiguration consensusEngine,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration eventCreationManager,
        @ConfigProperty(defaultValue = "SEQUENTIAL_THREAD CAPACITY(20) UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration stateSnapshotManager,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(10) UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration stateSigner,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC")
                TaskSchedulerConfiguration pcesInlineWriter,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC")
                TaskSchedulerConfiguration futureEventBuffer,
        @ConfigProperty(defaultValue = "DIRECT") TaskSchedulerConfiguration pcesSequencer,
        @ConfigProperty(defaultValue = "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration applicationTransactionPrehandler,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration stateSignatureCollector,
        @ConfigProperty(
                        defaultValue =
                                "SEQUENTIAL_THREAD CAPACITY(100000) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC")
                TaskSchedulerConfiguration transactionHandler,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration issDetector,
        @ConfigProperty(defaultValue = "DIRECT") TaskSchedulerConfiguration issHandler,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(100) UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration hashLogger,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(5) UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration latestCompleteStateNotifier,
        @ConfigProperty(
                        defaultValue =
                                "SEQUENTIAL_THREAD CAPACITY(100000) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC")
                TaskSchedulerConfiguration stateHasher,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(60) UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration stateGarbageCollector,
        @ConfigProperty(defaultValue = "200ms") Duration stateGarbageCollectorHeartbeatPeriod,
        @ConfigProperty(defaultValue = "SEQUENTIAL UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration signedStateSentinel,
        @ConfigProperty(defaultValue = "10s") Duration signedStateSentinelHeartbeatPeriod,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration platformPublisher,
        @ConfigProperty(defaultValue = "DIRECT_THREADSAFE") TaskSchedulerConfiguration consensusEventStream,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(5) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration roundDurabilityBuffer,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration platformMonitor,
        @ConfigProperty(defaultValue = "DIRECT_THREADSAFE") TaskSchedulerConfiguration transactionPool,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration gossip,
        @ConfigProperty(defaultValue = "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration eventHasher,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration branchDetector,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration branchReporter) {}
