// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.platform.builder.ApplicationCallbacks;
import com.swirlds.platform.builder.PlatformBuildingBlocks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.components.appcomm.LatestCompleteStateNotifier;
import com.swirlds.platform.components.consensus.ConsensusEngine;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.event.deduplication.EventDeduplicator;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.event.preconsensus.InlinePcesWriter;
import com.swirlds.platform.event.preconsensus.PcesReplayer;
import com.swirlds.platform.event.resubmitter.TransactionResubmitter;
import com.swirlds.platform.event.stream.ConsensusEventStream;
import com.swirlds.platform.event.validation.EventSignatureValidator;
import com.swirlds.platform.event.validation.InternalEventValidator;
import com.swirlds.platform.eventhandling.DefaultTransactionHandler;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.publisher.PlatformPublisher;
import com.swirlds.platform.state.hasher.StateHasher;
import com.swirlds.platform.state.hashlogger.HashLogger;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.iss.IssHandler;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.state.signed.StateGarbageCollector;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.signer.StateSigner;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.status.StatusStateMachine;
import java.util.stream.Stream;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.FutureEventBuffer;
import org.hiero.consensus.event.creator.impl.EventCreationManager;
import org.hiero.consensus.event.creator.impl.pool.TransactionPool;
import org.hiero.consensus.event.creator.impl.signing.SelfEventSigner;
import org.hiero.consensus.event.creator.impl.stale.DefaultStaleEventDetector;
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
        final ApplicationCallbacks applicationCallbacks = new ApplicationCallbacks(x -> {}, x -> {}, x -> {}, x -> {
            return null;
        });

        final WiringModel model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final PlatformWiring wiring = new PlatformWiring(platformContext, model, applicationCallbacks, true);

        final PlatformComponentBuilder componentBuilder =
                new PlatformComponentBuilder(mock(PlatformBuildingBlocks.class));

        componentBuilder
                .withEventHasher(mock(EventHasher.class))
                .withInternalEventValidator(mock(InternalEventValidator.class))
                .withEventDeduplicator(mock(EventDeduplicator.class))
                .withEventSignatureValidator(mock(EventSignatureValidator.class))
                .withStateGarbageCollector(mock(StateGarbageCollector.class))
                .withSelfEventSigner(mock(SelfEventSigner.class))
                .withOrphanBuffer(mock(OrphanBuffer.class))
                .withEventCreationManager(mock(EventCreationManager.class))
                .withConsensusEngine(mock(ConsensusEngine.class))
                .withConsensusEventStream(mock(ConsensusEventStream.class))
                .withStatusStateMachine(mock(StatusStateMachine.class))
                .withTransactionPrehandler(mock(TransactionPrehandler.class))
                .withInlinePcesWriter(mock(InlinePcesWriter.class))
                .withSignedStateSentinel(mock(SignedStateSentinel.class))
                .withIssDetector(mock(IssDetector.class))
                .withIssHandler(mock(IssHandler.class))
                .withStateHasher(mock(StateHasher.class))
                .withStaleEventDetector(mock(DefaultStaleEventDetector.class))
                .withTransactionResubmitter(mock(TransactionResubmitter.class))
                .withTransactionPool(mock(TransactionPool.class))
                .withStateSnapshotManager(mock(StateSnapshotManager.class))
                .withHashLogger(mock(HashLogger.class))
                .withBranchDetector(mock(BranchDetector.class))
                .withBranchReporter(mock(BranchReporter.class))
                .withStateSigner(mock(StateSigner.class))
                .withTransactionHandler(mock(DefaultTransactionHandler.class))
                .withLatestCompleteStateNotifier(mock(LatestCompleteStateNotifier.class))
                .withFutureEventBuffer(mock(FutureEventBuffer.class));

        // Gossip is a special case, it's not like other components.
        // Currently we just have a facade between gossip and the wiring framework.
        // In the future when gossip is refactored to operate within the wiring
        // framework like other components, such things will not be needed.
        componentBuilder.withGossip(
                (wiringModel,
                        eventInput,
                        eventWindowInput,
                        eventOutput,
                        startInput,
                        stopInput,
                        clearInput,
                        systemHealthInput,
                        platformStatusInput) -> {
                    eventInput.bindConsumer(event -> {});
                    eventWindowInput.bindConsumer(eventWindow -> {});
                    startInput.bindConsumer(noInput -> {});
                    stopInput.bindConsumer(noInput -> {});
                    clearInput.bindConsumer(noInput -> {});
                    systemHealthInput.bindConsumer(duration -> {});
                    platformStatusInput.bindConsumer(platformStatus -> {});
                });

        wiring.bind(
                componentBuilder,
                mock(PcesReplayer.class),
                mock(StateSignatureCollector.class),
                mock(EventWindowManager.class),
                mock(InlinePcesWriter.class),
                mock(SignedStateNexus.class),
                mock(LatestCompleteStateNexus.class),
                mock(SavedStateController.class),
                mock(AppNotifier.class),
                mock(PlatformPublisher.class));

        wiring.start();
        assertFalse(wiring.getModel().checkForUnboundInputWires());
        wiring.stop();
    }
}
