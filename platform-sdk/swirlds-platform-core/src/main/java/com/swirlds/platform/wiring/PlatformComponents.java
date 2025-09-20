// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.NO_OP_CONFIGURATION;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.builder.ApplicationCallbacks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.components.appcomm.CompleteStateNotificationWithCleanup;
import com.swirlds.platform.components.appcomm.LatestCompleteStateNotifier;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.event.deduplication.EventDeduplicator;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.event.preconsensus.InlinePcesWriter;
import com.swirlds.platform.event.preconsensus.PcesReplayer;
import com.swirlds.platform.event.stream.ConsensusEventStream;
import com.swirlds.platform.event.validation.EventSignatureValidator;
import com.swirlds.platform.event.validation.InternalEventValidator;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import com.swirlds.platform.eventhandling.TransactionHandler;
import com.swirlds.platform.eventhandling.TransactionHandlerResult;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.publisher.PlatformPublisher;
import com.swirlds.platform.state.hasher.StateHasher;
import com.swirlds.platform.state.hashlogger.HashLogger;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.iss.IssHandler;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.state.signed.StateGarbageCollector;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.signer.StateSigner;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.status.StatusStateMachine;
import com.swirlds.platform.wiring.components.ConsensusWiring;
import com.swirlds.platform.wiring.components.GossipWiring;
import com.swirlds.platform.wiring.components.PcesReplayerWiring;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Encapsulates wiring for {@link SwirldsPlatform}.
 */
public record PlatformComponents(
        WiringModel model,
        ComponentWiring<StateSigner, StateSignatureTransaction> stateSignerWiring,
        ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring,
        ComponentWiring<IssHandler, Void> issHandlerWiring,
        ComponentWiring<LatestCompleteStateNotifier, CompleteStateNotificationWithCleanup>
                latestCompleteStateNotifierWiring,
        ComponentWiring<SignedStateNexus, Void> latestImmutableStateNexusWiring,
        ComponentWiring<LatestCompleteStateNexus, Void> latestCompleteStateNexusWiring,
        ComponentWiring<SavedStateController, StateWithHashComplexity> savedStateControllerWiring,
        ComponentWiring<StateGarbageCollector, Void> stateGarbageCollectorWiring,
        ComponentWiring<SignedStateSentinel, Void> signedStateSentinelWiring,
        PcesReplayerWiring pcesReplayerWiring,
        GossipWiring gossipWiring,
        ComponentWiring<StateSnapshotManager, StateSavingResult> stateSnapshotManagerWiring,
        ComponentWiring<EventHasher, PlatformEvent> eventHasherWiring,
        ComponentWiring<InternalEventValidator, PlatformEvent> internalEventValidatorWiring,
        ComponentWiring<EventDeduplicator, PlatformEvent> eventDeduplicatorWiring,
        ComponentWiring<EventSignatureValidator, PlatformEvent> eventSignatureValidatorWiring,
        ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring,
        ConsensusWiring consensusEngineWiring,
        ComponentWiring<EventCreatorModule, PlatformEvent> eventCreationManagerWiring,
        ComponentWiring<InlinePcesWriter, PlatformEvent> pcesInlineWriterWiring,
        ComponentWiring<TransactionPrehandler, Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
                applicationTransactionPrehandlerWiring,
        ComponentWiring<StateSignatureCollector, List<ReservedSignedState>> stateSignatureCollectorWiring,
        ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring,
        ComponentWiring<TransactionHandler, TransactionHandlerResult> transactionHandlerWiring,
        RunningEventHashOverrideWiring runningEventHashOverrideWiring,
        ComponentWiring<IssDetector, List<IssNotification>> issDetectorWiring,
        ComponentWiring<HashLogger, Void> hashLoggerWiring,
        ComponentWiring<StateHasher, ReservedSignedState> stateHasherWiring,
        ComponentWiring<AppNotifier, Void> notifierWiring,
        ComponentWiring<PlatformPublisher, Void> platformPublisherWiring,
        ComponentWiring<StatusStateMachine, PlatformStatus> statusStateMachineWiring,
        ComponentWiring<BranchDetector, PlatformEvent> branchDetectorWiring,
        ComponentWiring<BranchReporter, Void> branchReporterWiring) {

    /**
     * Bind components to the wiring.
     *
     * @param builder                   builds platform components that need to be bound to wires
     * @param pcesReplayer              the PCES replayer to bind
     * @param stateSignatureCollector   the signed state manager to bind
     * @param eventWindowManager        the event window manager to bind
     * @param latestImmutableStateNexus the latest immutable state nexus to bind
     * @param latestCompleteStateNexus  the latest complete state nexus to bind
     * @param savedStateController      the saved state controller to bind
     * @param notifier                  the notifier to bind
     * @param platformPublisher         the platform publisher to bind
     */
    public void bind(
            @NonNull final PlatformComponentBuilder builder,
            @NonNull final PcesReplayer pcesReplayer,
            @NonNull final StateSignatureCollector stateSignatureCollector,
            @NonNull final EventWindowManager eventWindowManager,
            @Nullable final InlinePcesWriter inlinePcesWriter,
            @NonNull final SignedStateNexus latestImmutableStateNexus,
            @NonNull final LatestCompleteStateNexus latestCompleteStateNexus,
            @NonNull final SavedStateController savedStateController,
            @NonNull final AppNotifier notifier,
            @NonNull final PlatformPublisher platformPublisher) {

        eventHasherWiring.bind(builder::buildEventHasher);
        internalEventValidatorWiring.bind(builder::buildInternalEventValidator);
        eventDeduplicatorWiring.bind(builder::buildEventDeduplicator);
        eventSignatureValidatorWiring.bind(builder::buildEventSignatureValidator);
        orphanBufferWiring.bind(builder::buildOrphanBuffer);
        consensusEngineWiring.bind(builder::buildConsensusEngine);
        stateSnapshotManagerWiring.bind(builder::buildStateSnapshotManager);
        stateSignerWiring.bind(builder::buildStateSigner);
        pcesReplayerWiring.bind(pcesReplayer);
        if (inlinePcesWriter != null) {
            pcesInlineWriterWiring.bind(inlinePcesWriter);
        } else {
            pcesInlineWriterWiring.bind(builder::buildInlinePcesWriter);
        }
        eventCreationManagerWiring.bind(builder::buildEventCreator);
        stateSignatureCollectorWiring.bind(stateSignatureCollector);
        eventWindowManagerWiring.bind(eventWindowManager);
        applicationTransactionPrehandlerWiring.bind(builder::buildTransactionPrehandler);
        transactionHandlerWiring.bind(builder::buildTransactionHandler);
        consensusEventStreamWiring.bind(builder::buildConsensusEventStream);
        issDetectorWiring.bind(builder::buildIssDetector);
        issHandlerWiring.bind(builder::buildIssHandler);
        hashLoggerWiring.bind(builder::buildHashLogger);
        latestCompleteStateNotifierWiring.bind(builder::buildLatestCompleteStateNotifier);
        latestImmutableStateNexusWiring.bind(latestImmutableStateNexus);
        latestCompleteStateNexusWiring.bind(latestCompleteStateNexus);
        savedStateControllerWiring.bind(savedStateController);
        stateHasherWiring.bind(builder::buildStateHasher);
        notifierWiring.bind(notifier);
        platformPublisherWiring.bind(platformPublisher);
        stateGarbageCollectorWiring.bind(builder::buildStateGarbageCollector);
        statusStateMachineWiring.bind(builder::buildStatusStateMachine);
        signedStateSentinelWiring.bind(builder::buildSignedStateSentinel);
        gossipWiring.bind(builder.buildGossip());
        branchDetectorWiring.bind(builder::buildBranchDetector);
        branchReporterWiring.bind(builder::buildBranchReporter);
    }

    /**
     * Creates a new instance of PlatformComponents.
     *
     * @param platformContext      the platform context
     * @param model                the wiring model
     * @param applicationCallbacks the application callbacks (some wires are only created if the application wants a
     *                             callback for something)
     */
    public static PlatformComponents create(
            @NonNull final PlatformContext platformContext,
            @NonNull final WiringModel model,
            @NonNull final ApplicationCallbacks applicationCallbacks) {

        Objects.requireNonNull(platformContext);
        Objects.requireNonNull(model);
        Objects.requireNonNull(applicationCallbacks);

        final PlatformSchedulersConfig config =
                platformContext.getConfiguration().getConfigData(PlatformSchedulersConfig.class);

        final ComponentWiring<EventHasher, PlatformEvent> eventHasherWiring =
                new ComponentWiring<>(model, EventHasher.class, config.eventHasher());

        final ComponentWiring<InternalEventValidator, PlatformEvent> internalEventValidatorWiring =
                new ComponentWiring<>(model, InternalEventValidator.class, config.internalEventValidator());
        final ComponentWiring<EventDeduplicator, PlatformEvent> eventDeduplicatorWiring =
                new ComponentWiring<>(model, EventDeduplicator.class, config.eventDeduplicator());
        final ComponentWiring<EventSignatureValidator, PlatformEvent> eventSignatureValidatorWiring =
                new ComponentWiring<>(model, EventSignatureValidator.class, config.eventSignatureValidator());
        final ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring =
                new ComponentWiring<>(model, OrphanBuffer.class, config.orphanBuffer());
        final ConsensusWiring consensusEngineWiring = ConsensusWiring.create(model, config.consensusEngine());

        final ComponentWiring<EventCreatorModule, PlatformEvent> eventCreationManagerWiring =
                new ComponentWiring<>(model, EventCreatorModule.class, config.eventCreationManager());

        final ComponentWiring<TransactionPrehandler, Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
                applicationTransactionPrehandlerWiring = new ComponentWiring<>(
                        model, TransactionPrehandler.class, config.applicationTransactionPrehandler());
        final ComponentWiring<StateSignatureCollector, List<ReservedSignedState>> stateSignatureCollectorWiring =
                new ComponentWiring<>(model, StateSignatureCollector.class, config.stateSignatureCollector());
        final ComponentWiring<StateSnapshotManager, StateSavingResult> stateSnapshotManagerWiring =
                new ComponentWiring<>(model, StateSnapshotManager.class, config.stateSnapshotManager());
        final ComponentWiring<StateSigner, StateSignatureTransaction> stateSignerWiring =
                new ComponentWiring<>(model, StateSigner.class, config.stateSigner());
        final ComponentWiring<TransactionHandler, TransactionHandlerResult> transactionHandlerWiring =
                new ComponentWiring<>(
                        model,
                        TransactionHandler.class,
                        config.transactionHandler(),
                        data -> data instanceof final ConsensusRound consensusRound
                                ? Math.max(consensusRound.getNumAppTransactions(), 1)
                                : 1);
        final ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring =
                new ComponentWiring<>(model, ConsensusEventStream.class, config.consensusEventStream());
        final RunningEventHashOverrideWiring runningEventHashOverrideWiring =
                RunningEventHashOverrideWiring.create(model);

        final ComponentWiring<StateHasher, ReservedSignedState> stateHasherWiring = new ComponentWiring<>(
                model,
                StateHasher.class,
                config.stateHasher(),
                data -> data instanceof final StateWithHashComplexity swhc ? swhc.hashComplexity() : 1);

        final GossipWiring gossipWiring = new GossipWiring(platformContext, model);

        final PcesReplayerWiring pcesReplayerWiring = PcesReplayerWiring.create(model);

        final ComponentWiring<InlinePcesWriter, PlatformEvent> pcesInlineWriterWiring =
                new ComponentWiring<>(model, InlinePcesWriter.class, config.pcesInlineWriter());

        final ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring =
                new ComponentWiring<>(model, EventWindowManager.class, DIRECT_THREADSAFE_CONFIGURATION);

        final ComponentWiring<IssDetector, List<IssNotification>> issDetectorWiring =
                new ComponentWiring<>(model, IssDetector.class, config.issDetector());
        final ComponentWiring<IssHandler, Void> issHandlerWiring =
                new ComponentWiring<>(model, IssHandler.class, config.issHandler());
        final ComponentWiring<HashLogger, Void> hashLoggerWiring =
                new ComponentWiring<>(model, HashLogger.class, config.hashLogger());

        final ComponentWiring<LatestCompleteStateNotifier, CompleteStateNotificationWithCleanup>
                latestCompleteStateNotifierWiring = new ComponentWiring<>(
                        model, LatestCompleteStateNotifier.class, config.latestCompleteStateNotifier());

        final ComponentWiring<SignedStateNexus, Void> latestImmutableStateNexusWiring =
                new ComponentWiring<>(model, SignedStateNexus.class, DIRECT_THREADSAFE_CONFIGURATION);
        final ComponentWiring<LatestCompleteStateNexus, Void> latestCompleteStateNexusWiring =
                new ComponentWiring<>(model, LatestCompleteStateNexus.class, DIRECT_THREADSAFE_CONFIGURATION);
        final ComponentWiring<SavedStateController, StateWithHashComplexity> savedStateControllerWiring =
                new ComponentWiring<>(model, SavedStateController.class, DIRECT_THREADSAFE_CONFIGURATION);

        final ComponentWiring<AppNotifier, Void> notifierWiring =
                new ComponentWiring<>(model, AppNotifier.class, DIRECT_THREADSAFE_CONFIGURATION);

        final boolean publishPreconsensusEvents = applicationCallbacks.preconsensusEventConsumer() != null;
        final boolean publishSnapshotOverrides = applicationCallbacks.snapshotOverrideConsumer() != null;
        final boolean publishStaleEvents = applicationCallbacks.staleEventConsumer() != null;

        final TaskSchedulerConfiguration publisherConfiguration =
                (publishPreconsensusEvents || publishSnapshotOverrides || publishStaleEvents)
                        ? config.platformPublisher()
                        : NO_OP_CONFIGURATION;
        final ComponentWiring<PlatformPublisher, Void> platformPublisherWiring =
                new ComponentWiring<>(model, PlatformPublisher.class, publisherConfiguration);

        final ComponentWiring<StateGarbageCollector, Void> stateGarbageCollectorWiring =
                new ComponentWiring<>(model, StateGarbageCollector.class, config.stateGarbageCollector());
        final ComponentWiring<SignedStateSentinel, Void> signedStateSentinelWiring =
                new ComponentWiring<>(model, SignedStateSentinel.class, config.signedStateSentinel());
        final ComponentWiring<StatusStateMachine, PlatformStatus> statusStateMachineWiring =
                new ComponentWiring<>(model, StatusStateMachine.class, config.statusStateMachine());

        final ComponentWiring<BranchDetector, PlatformEvent> branchDetectorWiring =
                new ComponentWiring<>(model, BranchDetector.class, config.branchDetector());
        final ComponentWiring<BranchReporter, Void> branchReporterWiring =
                new ComponentWiring<>(model, BranchReporter.class, config.branchReporter());

        return new PlatformComponents(
                model,
                stateSignerWiring,
                consensusEventStreamWiring,
                issHandlerWiring,
                latestCompleteStateNotifierWiring,
                latestImmutableStateNexusWiring,
                latestCompleteStateNexusWiring,
                savedStateControllerWiring,
                stateGarbageCollectorWiring,
                signedStateSentinelWiring,
                pcesReplayerWiring,
                gossipWiring,
                stateSnapshotManagerWiring,
                eventHasherWiring,
                internalEventValidatorWiring,
                eventDeduplicatorWiring,
                eventSignatureValidatorWiring,
                orphanBufferWiring,
                consensusEngineWiring,
                eventCreationManagerWiring,
                pcesInlineWriterWiring,
                applicationTransactionPrehandlerWiring,
                stateSignatureCollectorWiring,
                eventWindowManagerWiring,
                transactionHandlerWiring,
                runningEventHashOverrideWiring,
                issDetectorWiring,
                hashLoggerWiring,
                stateHasherWiring,
                notifierWiring,
                platformPublisherWiring,
                statusStateMachineWiring,
                branchDetectorWiring,
                branchReporterWiring);
    }
}
