// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.platform.builder.ConsensusModuleBuilder.createNoOpEventCreatorModule;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createNoOpEventIntakeModule;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createNoOpGossipModule;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createNoOpHashgraphModule;
import static com.swirlds.platform.builder.ConsensusModuleBuilder.createNoOpPcesModule;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.builder.ApplicationCallbacks;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.event.preconsensus.PcesReplayer;
import com.swirlds.platform.event.stream.ConsensusEventStream;
import com.swirlds.platform.eventhandling.DefaultTransactionHandler;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.state.hasher.StateHasher;
import com.swirlds.platform.state.hashlogger.HashLogger;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.iss.IssHandler;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.signer.StateSigner;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.PlatformMonitor;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.stream.Stream;
import org.hiero.consensus.crypto.KeyGeneratingException;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.crypto.SigningSchema;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.state.signed.StateGarbageCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link PlatformWiring}
 */
class PlatformWiringTests {
    static Stream<PlatformContext> testContexts() {
        return Stream.of(
                TestPlatformContextBuilder.create()
                        .withConfiguration(ConfigurationBuilder.create()
                                .autoDiscoverExtensions()
                                .withValue("platformWiring.inlinePces", "false")
                                .build())
                        .build(),
                TestPlatformContextBuilder.create()
                        .withConfiguration(ConfigurationBuilder.create()
                                .autoDiscoverExtensions()
                                .withValue("platformWiring.inlinePces", "true")
                                .build())
                        .build());
    }

    @ParameterizedTest
    @MethodSource("testContexts")
    @DisplayName("Assert that all input wires are bound to something")
    void testBindings(final PlatformContext platformContext) {
        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final Configuration configuration = platformContext.getConfiguration();
        final EventCreatorModule eventCreatorModule = createNoOpEventCreatorModule(model, configuration);
        final EventIntakeModule eventIntakeModule = createNoOpEventIntakeModule(model, configuration);
        final PcesModule pcesModule = createNoOpPcesModule(model, configuration);
        final HashgraphModule hashgraphModule = createNoOpHashgraphModule(model, configuration);
        final GossipModule gossipModule = createNoOpGossipModule(model, configuration);

        final PlatformComponents platformComponents = PlatformComponents.create(
                platformContext,
                model,
                eventCreatorModule,
                eventIntakeModule,
                pcesModule,
                hashgraphModule,
                gossipModule);
        PlatformWiring.wire(
                platformContext, mock(ExecutionLayer.class), platformComponents, ApplicationCallbacks.EMPTY);

        final PlatformComponentBuilder componentBuilder =
                new PlatformComponentBuilder(createBuildingBlocks(platformContext));

        final PlatformCoordinator coordinator = new PlatformCoordinator(platformComponents, ApplicationCallbacks.EMPTY);
        componentBuilder
                .withStateGarbageCollector(mock(StateGarbageCollector.class))
                .withConsensusEventStream(mock(ConsensusEventStream.class))
                .withPlatformMonitor(mock(PlatformMonitor.class))
                .withTransactionPrehandler(mock(TransactionPrehandler.class))
                .withSignedStateSentinel(mock(SignedStateSentinel.class))
                .withIssDetector(mock(IssDetector.class))
                .withIssHandler(mock(IssHandler.class))
                .withStateHasher(mock(StateHasher.class))
                .withStateSnapshotManager(mock(StateSnapshotManager.class))
                .withHashLogger(mock(HashLogger.class))
                .withBranchDetector(mock(BranchDetector.class))
                .withBranchReporter(mock(BranchReporter.class))
                .withStateSigner(mock(StateSigner.class))
                .withTransactionHandler(mock(DefaultTransactionHandler.class));

        platformComponents.bind(
                componentBuilder,
                mock(PcesReplayer.class),
                mock(StateSignatureCollector.class),
                mock(EventWindowManager.class),
                mock(SignedStateNexus.class),
                mock(LatestCompleteStateNexus.class),
                mock(SavedStateController.class),
                mock(AppNotifier.class));

        coordinator.start();
        assertFalse(model.checkForUnboundInputWires());
        coordinator.stop();
    }

    private static PlatformBuildingBlocks createBuildingBlocks(final PlatformContext context) {
        final PlatformBuildingBlocks blocks = mock(PlatformBuildingBlocks.class);
        when(blocks.platformContext()).thenReturn(context);
        when(blocks.secureRandomSupplier()).thenReturn(() -> mock(SecureRandom.class));
        final RosterHistory rosterHistory = mock(RosterHistory.class);
        when(rosterHistory.getCurrentRoster()).thenReturn(Roster.DEFAULT);
        when(blocks.rosterHistory()).thenReturn(rosterHistory);
        final KeysAndCerts keysAndCerts;
        try {
            keysAndCerts = KeysAndCertsGenerator.generate(
                    NodeId.FIRST_NODE_ID, SigningSchema.RSA, new SecureRandom(), new SecureRandom());
        } catch (final NoSuchAlgorithmException | NoSuchProviderException | KeyGeneratingException e) {
            throw new RuntimeException(e);
        }
        when(blocks.keysAndCerts()).thenReturn(keysAndCerts);
        return blocks;
    }
}
