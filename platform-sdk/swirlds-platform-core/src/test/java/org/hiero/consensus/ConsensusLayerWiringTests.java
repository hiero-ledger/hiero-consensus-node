// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

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
import static org.hiero.base.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.hiero.consensus.fakes.noop.FakeRosterFactory.fakeRosterHistory;
import static org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.base.time.Time;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import com.swirlds.state.NoOpStateLifecycleManager;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.hiero.base.crypto.KeyGeneratingException;
import org.hiero.base.utility.test.fixtures.file.TestFileSystemManager;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.event.stream.config.EventConfig_;
import org.hiero.consensus.event.stream.config.EventStreamWiringConfig;
import org.hiero.consensus.fakes.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.fakes.noop.NoOpRecycleBin;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.iss.detection.IssDetectionModule;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.StateModule;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.status.monitor.StatusMonitorModule;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;
import org.hiero.consensus.wiring.framework.component.ComponentWiring;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.transformers.WireTransformer;
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
    void testBindings(final Configuration configuration)
            throws KeyGeneratingException, NoSuchAlgorithmException, KeyStoreException, NoSuchProviderException {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();
        final TestFileSystemManager fileSystemManager = new TestFileSystemManager(tmpDir);

        final ConsensusLayerInputs inputs = new ConsensusLayerInputs(
                configuration,
                new NoOpMetrics(),
                Time.getCurrent(),
                fakeRosterHistory(),
                KeysAndCertsGenerator.generate(NodeId.FIRST_NODE_ID),
                NodeId.FIRST_NODE_ID,
                new NoOpRecycleBin(),
                fileSystemManager,
                mock(ExecutionLayer.class),
                NO_OP_CONSENSUS_STATE_EVENT_HANDLER,
                ReservedSignedState.createNullReservation(),
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
        final WireTransformer<EventWindow, EventWindow> initialEventWindowDispatcher =
                new WireTransformer<>(model, "InitialEventWindowDispatcher", "event window", UnaryOperator.identity());
        final ComponentWiring<AppNotifier, Void> notifierWiring =
                new ComponentWiring<>(model, AppNotifier.class, DIRECT_THREADSAFE_CONFIGURATION);

        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new NoOpStateLifecycleManager<>();

        final EventCreatorModule eventCreatorModule = createNoOpEventCreatorModule(model, configuration);
        final EventIntakeModule eventIntakeModule = createNoOpEventIntakeModule(model, configuration);
        final StatusMonitorModule statusMonitorModule = createNoOpStatusMonitorModule(model, configuration);
        final PcesModule pcesModule = createNoOpPcesModule(model, configuration, statusMonitorModule);
        final HashgraphModule hashgraphModule = createNoOpHashgraphModule(model, configuration);
        final GossipModule gossipModule =
                createNoOpGossipModule(model, configuration, metrics, time, stateLifecycleManager);
        final IssDetectionModule issDetectionModule =
                createNoOpIssDetectionModule(model, configuration, fileSystemManager);
        final TransactionHandlingModule transactionHandlingModule = createNoOpTransactionHandlingModule(
                model, configuration, metrics, time, stateLifecycleManager, statusMonitorModule);
        final StateModule stateModule = createNoOpStateManagementModule(
                model, configuration, fileSystemManager, metrics, time, stateLifecycleManager);

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
                initialEventWindowDispatcher,
                notifierWiring,
                statusMonitorModule,
                NotificationEngine.buildEngine(getStaticThreadManager()),
                null,
                null,
                null,
                null,
                null);
        ConsensusLayerWiring.wire(inputs, buildingBlocks);

        eventStreamWiring.bind(mock(ConsensusEventStream.class));
        notifierWiring.bind(mock(AppNotifier.class));

        model.start();
        assertFalse(model.checkForUnboundInputWires());
        model.stop();
    }
}
