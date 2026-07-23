// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;
import static com.swirlds.platform.builder.ConsensusNoOpModules.createNoOpEventCreatorModule;
import static com.swirlds.platform.builder.ConsensusNoOpModules.createNoOpEventIntakeModule;
import static com.swirlds.platform.builder.ConsensusNoOpModules.createNoOpGossipModule;
import static com.swirlds.platform.builder.ConsensusNoOpModules.createNoOpHashgraphModule;
import static com.swirlds.platform.builder.ConsensusNoOpModules.createNoOpIssDetectionModule;
import static com.swirlds.platform.builder.ConsensusNoOpModules.createNoOpPcesModule;
import static com.swirlds.platform.builder.ConsensusNoOpModules.createNoOpStateManagementModule;
import static com.swirlds.platform.builder.ConsensusNoOpModules.createNoOpStatusMonitorModule;
import static com.swirlds.platform.builder.ConsensusNoOpModules.createNoOpTransactionHandlingModule;
import static com.swirlds.platform.state.NoOpConsensusStateEventHandler.NO_OP_CONSENSUS_STATE_EVENT_HANDLER;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.base.time.Time;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.monitor.StatusMonitorModule;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.hiero.base.utility.test.fixtures.file.TestFileSystemManager;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.event.stream.config.EventConfig_;
import org.hiero.consensus.event.stream.config.EventStreamWiringConfig;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.iss.detection.IssDetectionModule;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.StateModule;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link ConsensusLayerWiring}
 */
class ConsensusLayerWiringTests {

    @TempDir
    static Path tmpDir;

    static Stream<Configuration> configurations() {
        return Stream.of(
                new TestConfigBuilder()
                        .withValue(EventConfig_.EVENTS_LOG_DIR, tmpDir.resolve("eventStream"))
                        .withValue("platformWiring.inlinePces", "false")
                        .getOrCreateConfig(),
                new TestConfigBuilder()
                        .withValue(EventConfig_.EVENTS_LOG_DIR, tmpDir.resolve("eventStream"))
                        .withValue("platformWiring.inlinePces", "true")
                        .getOrCreateConfig());
    }

    @ParameterizedTest
    @MethodSource("configurations")
    @DisplayName("Assert that all input wires are bound to something")
    void testBindings(final Configuration configuration) {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();
        final TestFileSystemManager fileSystemManager = new TestFileSystemManager(tmpDir);

        final ConsensusLayerInputs inputs = new ConsensusLayerInputs(
                configuration,
                new NoOpMetrics(),
                Time.getCurrent(),
                null,
                null,
                NodeId.FIRST_NODE_ID,
                null,
                fileSystemManager,
                mock(ExecutionLayer.class),
                NO_OP_CONSENSUS_STATE_EVENT_HANDLER,
                null,
                null,
                SemanticVersion.DEFAULT,
                "testApp",
                "123",
                "cesStream",
                0,
                _ -> {},
                model,
                null,
                Map.of());

        final EventStreamWiringConfig eventStreamConfig = configuration.getConfigData(EventStreamWiringConfig.class);
        final ComponentWiring<ConsensusEventStream, Void> eventStreamWiring =
                new ComponentWiring<>(model, ConsensusEventStream.class, eventStreamConfig.consensusEventStream());
        final RunningEventHashOverrideWiring runningEventHashOverrideWiring =
                RunningEventHashOverrideWiring.create(model);
        final ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring =
                new ComponentWiring<>(model, EventWindowManager.class, DIRECT_THREADSAFE_CONFIGURATION);
        final ComponentWiring<AppNotifier, Void> notifierWiring =
                new ComponentWiring<>(model, AppNotifier.class, DIRECT_THREADSAFE_CONFIGURATION);

        final EventCreatorModule eventCreatorModule = createNoOpEventCreatorModule(model, configuration);
        final EventIntakeModule eventIntakeModule = createNoOpEventIntakeModule(model, configuration);
        final PcesModule pcesModule = createNoOpPcesModule(model, configuration);
        final HashgraphModule hashgraphModule = createNoOpHashgraphModule(model, configuration);
        final GossipModule gossipModule = createNoOpGossipModule(model, configuration, fileSystemManager);
        final IssDetectionModule issDetectionModule =
                createNoOpIssDetectionModule(model, configuration, fileSystemManager);
        final TransactionHandlingModule transactionHandlingModule =
                createNoOpTransactionHandlingModule(model, configuration, fileSystemManager);
        final StateModule stateModule = createNoOpStateManagementModule(model, configuration, fileSystemManager);
        final StatusMonitorModule statusMonitorModule = createNoOpStatusMonitorModule(model, configuration);

        final ConsensusLayerBuildingBlocks buildingBlocks = new ConsensusLayerBuildingBlocks(
                model,
                configuration,
                eventCreatorModule,
                eventIntakeModule,
                pcesModule,
                hashgraphModule,
                gossipModule,
                issDetectionModule,
                transactionHandlingModule,
                stateModule,
                eventStreamWiring,
                runningEventHashOverrideWiring,
                eventWindowManagerWiring,
                notifierWiring,
                statusMonitorModule,
                NotificationEngine.buildEngine(getStaticThreadManager()),
                null,
                null,
                null,
                null,
                null);
        ConsensusLayerWiring.wire(inputs, buildingBlocks);

        eventWindowManagerWiring.bind(mock(EventWindowManager.class));
        eventStreamWiring.bind(mock(ConsensusEventStream.class));
        notifierWiring.bind(mock(AppNotifier.class));

        model.start();
        assertFalse(model.checkForUnboundInputWires());
        model.stop();
    }
}
