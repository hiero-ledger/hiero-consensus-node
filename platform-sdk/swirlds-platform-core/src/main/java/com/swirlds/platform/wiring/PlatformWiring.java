// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.DIRECT_THREADSAFE_CONFIGURATION;
import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration.NO_OP_CONFIGURATION;
import static com.swirlds.component.framework.wires.SolderType.INJECT;
import static com.swirlds.component.framework.wires.SolderType.OFFER;
import static org.hiero.consensus.model.event.StaleEventDetectorOutput.SELF_EVENT;
import static org.hiero.consensus.model.event.StaleEventDetectorOutput.STALE_SELF_EVENT;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.component.framework.transformers.RoutableData;
import com.swirlds.component.framework.transformers.WireFilter;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.platform.builder.ApplicationCallbacks;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.components.appcomm.CompleteStateNotificationWithCleanup;
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
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.state.signed.StateGarbageCollector;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.state.signer.StateSigner;
import com.swirlds.platform.state.snapshot.StateDumpRequest;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.system.state.notifications.StateHashedNotification;
import com.swirlds.platform.system.status.PlatformStatusConfig;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.StatusStateMachine;
import com.swirlds.platform.wiring.components.GossipWiring;
import com.swirlds.platform.wiring.components.PcesReplayerWiring;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.function.Function;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.creator.impl.EventCreationManager;
import org.hiero.consensus.event.creator.impl.config.EventCreationConfig;
import org.hiero.consensus.event.creator.impl.pool.TransactionPool;
import org.hiero.consensus.event.creator.impl.stale.StaleEventDetector;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.StaleEventDetectorOutput;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.state.StateSavingResult;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.TransactionWrapper;
import org.hiero.consensus.roster.RosterHistory;

/**
 * Encapsulates wiring for {@link com.swirlds.platform.SwirldsPlatform}.
 */
public class PlatformWiring {

    private final WiringModel model;

    private final PlatformContext platformContext;
    private final PlatformSchedulersConfig config;

    private final ComponentWiring<EventHasher, PlatformEvent> eventHasherWiring;
    private final ComponentWiring<InternalEventValidator, PlatformEvent> internalEventValidatorWiring;
    private final ComponentWiring<EventDeduplicator, PlatformEvent> eventDeduplicatorWiring;
    private final ComponentWiring<EventSignatureValidator, PlatformEvent> eventSignatureValidatorWiring;
    private final ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring;
    private final ComponentWiring<ConsensusEngine, List<ConsensusRound>> consensusEngineWiring;
    private final ComponentWiring<EventCreationManager, PlatformEvent> eventCreationManagerWiring;
    private final ComponentWiring<StateSnapshotManager, StateSavingResult> stateSnapshotManagerWiring;
    private final ComponentWiring<StateSigner, StateSignatureTransaction> stateSignerWiring;
    private final PcesReplayerWiring pcesReplayerWiring;
    private final ComponentWiring<InlinePcesWriter, PlatformEvent> pcesInlineWriterWiring;
    private final ComponentWiring<TransactionPrehandler, Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
            applicationTransactionPrehandlerWiring;
    private final ComponentWiring<StateSignatureCollector, List<ReservedSignedState>> stateSignatureCollectorWiring;
    private final GossipWiring gossipWiring;
    private final ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring;
    private final ComponentWiring<TransactionHandler, TransactionHandlerResult> transactionHandlerWiring;
    private final ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring;
    private final RunningEventHashOverrideWiring runningEventHashOverrideWiring;
    private final ComponentWiring<IssDetector, List<IssNotification>> issDetectorWiring;
    private final ComponentWiring<IssHandler, Void> issHandlerWiring;
    private final ComponentWiring<HashLogger, Void> hashLoggerWiring;
    private final ComponentWiring<LatestCompleteStateNotifier, CompleteStateNotificationWithCleanup>
            latestCompleteStateNotifierWiring;
    private final ComponentWiring<SignedStateNexus, Void> latestImmutableStateNexusWiring;
    private final ComponentWiring<LatestCompleteStateNexus, Void> latestCompleteStateNexusWiring;
    private final ComponentWiring<SavedStateController, StateWithHashComplexity> savedStateControllerWiring;
    private final ComponentWiring<StateHasher, ReservedSignedState> stateHasherWiring;
    private final PlatformCoordinator platformCoordinator;
    private final ComponentWiring<AppNotifier, Void> notifierWiring;
    private final ComponentWiring<StateGarbageCollector, Void> stateGarbageCollectorWiring;
    private final ComponentWiring<SignedStateSentinel, Void> signedStateSentinelWiring;
    private final ComponentWiring<PlatformPublisher, Void> platformPublisherWiring;
    private final boolean publishPreconsensusEvents;
    private final boolean publishSnapshotOverrides;
    private final boolean publishStaleEvents;
    private final ApplicationCallbacks applicationCallbacks;
    private final ComponentWiring<StaleEventDetector, List<RoutableData<StaleEventDetectorOutput>>>
            staleEventDetectorWiring;
    private final ComponentWiring<TransactionResubmitter, List<TransactionWrapper>> transactionResubmitterWiring;
    private final ComponentWiring<TransactionPool, Void> transactionPoolWiring;
    private final ComponentWiring<StatusStateMachine, PlatformStatus> statusStateMachineWiring;
    private final ComponentWiring<BranchDetector, PlatformEvent> branchDetectorWiring;
    private final ComponentWiring<BranchReporter, Void> branchReporterWiring;

    /**
     * Constructor.
     *
     * @param platformContext      the platform context
     * @param model                the wiring model
     * @param applicationCallbacks the application callbacks (some wires are only created if the application wants a
     *                             callback for something)
     */
    public PlatformWiring(
            @NonNull final PlatformContext platformContext,
            @NonNull final WiringModel model,
            @NonNull final ApplicationCallbacks applicationCallbacks,
            final boolean isGenesis) {

        this.platformContext = Objects.requireNonNull(platformContext);
        this.model = Objects.requireNonNull(model);

        config = platformContext.getConfiguration().getConfigData(PlatformSchedulersConfig.class);

        eventHasherWiring = new ComponentWiring<>(model, EventHasher.class, config.eventHasher());

        internalEventValidatorWiring =
                new ComponentWiring<>(model, InternalEventValidator.class, config.internalEventValidator());
        eventDeduplicatorWiring = new ComponentWiring<>(model, EventDeduplicator.class, config.eventDeduplicator());
        eventSignatureValidatorWiring =
                new ComponentWiring<>(model, EventSignatureValidator.class, config.eventSignatureValidator());
        orphanBufferWiring = new ComponentWiring<>(model, OrphanBuffer.class, config.orphanBuffer());
        consensusEngineWiring = new ComponentWiring<>(model, ConsensusEngine.class, config.consensusEngine());

        eventCreationManagerWiring =
                new ComponentWiring<>(model, EventCreationManager.class, config.eventCreationManager());

        applicationTransactionPrehandlerWiring =
                new ComponentWiring<>(model, TransactionPrehandler.class, config.applicationTransactionPrehandler());
        stateSignatureCollectorWiring =
                new ComponentWiring<>(model, StateSignatureCollector.class, config.stateSignatureCollector());
        stateSnapshotManagerWiring =
                new ComponentWiring<>(model, StateSnapshotManager.class, config.stateSnapshotManager());
        stateSignerWiring = new ComponentWiring<>(model, StateSigner.class, config.stateSigner());
        transactionHandlerWiring = new ComponentWiring<>(
                model,
                TransactionHandler.class,
                config.transactionHandler(),
                data -> data instanceof final ConsensusRound consensusRound
                        ? Math.max(consensusRound.getNumAppTransactions(), 1)
                        : 1);
        consensusEventStreamWiring =
                new ComponentWiring<>(model, ConsensusEventStream.class, config.consensusEventStream());
        runningEventHashOverrideWiring = RunningEventHashOverrideWiring.create(model);

        stateHasherWiring = new ComponentWiring<>(
                model,
                StateHasher.class,
                config.stateHasher(),
                data -> data instanceof final StateWithHashComplexity swhc ? swhc.hashComplexity() : 1);

        gossipWiring = new GossipWiring(platformContext, model);

        pcesReplayerWiring = PcesReplayerWiring.create(model);

        pcesInlineWriterWiring = new ComponentWiring<>(model, InlinePcesWriter.class, config.pcesInlineWriter());

        eventWindowManagerWiring =
                new ComponentWiring<>(model, EventWindowManager.class, DIRECT_THREADSAFE_CONFIGURATION);

        issDetectorWiring = new ComponentWiring<>(model, IssDetector.class, config.issDetector());
        issHandlerWiring = new ComponentWiring<>(model, IssHandler.class, config.issHandler());
        hashLoggerWiring = new ComponentWiring<>(model, HashLogger.class, config.hashLogger());

        latestCompleteStateNotifierWiring =
                new ComponentWiring<>(model, LatestCompleteStateNotifier.class, config.latestCompleteStateNotifier());

        latestImmutableStateNexusWiring =
                new ComponentWiring<>(model, SignedStateNexus.class, DIRECT_THREADSAFE_CONFIGURATION);
        latestCompleteStateNexusWiring =
                new ComponentWiring<>(model, LatestCompleteStateNexus.class, DIRECT_THREADSAFE_CONFIGURATION);
        savedStateControllerWiring =
                new ComponentWiring<>(model, SavedStateController.class, DIRECT_THREADSAFE_CONFIGURATION);

        notifierWiring = new ComponentWiring<>(model, AppNotifier.class, DIRECT_THREADSAFE_CONFIGURATION);

        this.publishPreconsensusEvents = applicationCallbacks.preconsensusEventConsumer() != null;
        this.publishSnapshotOverrides = applicationCallbacks.snapshotOverrideConsumer() != null;
        this.publishStaleEvents = applicationCallbacks.staleEventConsumer() != null;
        this.applicationCallbacks = applicationCallbacks;

        final TaskSchedulerConfiguration publisherConfiguration;
        if (publishPreconsensusEvents || publishSnapshotOverrides || publishStaleEvents) {
            publisherConfiguration = config.platformPublisher();
        } else {
            publisherConfiguration = NO_OP_CONFIGURATION;
        }
        platformPublisherWiring = new ComponentWiring<>(model, PlatformPublisher.class, publisherConfiguration);

        stateGarbageCollectorWiring =
                new ComponentWiring<>(model, StateGarbageCollector.class, config.stateGarbageCollector());
        signedStateSentinelWiring =
                new ComponentWiring<>(model, SignedStateSentinel.class, config.signedStateSentinel());
        statusStateMachineWiring = new ComponentWiring<>(model, StatusStateMachine.class, config.statusStateMachine());

        staleEventDetectorWiring = new ComponentWiring<>(model, StaleEventDetector.class, config.staleEventDetector());
        transactionResubmitterWiring =
                new ComponentWiring<>(model, TransactionResubmitter.class, config.transactionResubmitter());
        transactionPoolWiring = new ComponentWiring<>(model, TransactionPool.class, config.transactionPool());
        branchDetectorWiring = new ComponentWiring<>(model, BranchDetector.class, config.branchDetector());
        branchReporterWiring = new ComponentWiring<>(model, BranchReporter.class, config.branchReporter());

        platformCoordinator = new PlatformCoordinator(
                eventHasherWiring::flush,
                internalEventValidatorWiring,
                eventDeduplicatorWiring,
                eventSignatureValidatorWiring,
                orphanBufferWiring,
                gossipWiring,
                consensusEngineWiring,
                eventCreationManagerWiring,
                applicationTransactionPrehandlerWiring,
                stateSignatureCollectorWiring,
                transactionHandlerWiring,
                stateHasherWiring,
                staleEventDetectorWiring,
                transactionPoolWiring,
                statusStateMachineWiring,
                branchDetectorWiring,
                branchReporterWiring,
                pcesInlineWriterWiring);

        wire();
    }

    /**
     * Get the wiring model.
     *
     * @return the wiring model
     */
    @NonNull
    public WiringModel getModel() {
        return model;
    }

    /**
     * Solder the EventWindow output to all components that need it.
     */
    private void solderEventWindow() {
        final OutputWire<EventWindow> eventWindowOutputWire = eventWindowManagerWiring.getOutputWire();

        eventWindowOutputWire.solderTo(eventDeduplicatorWiring.getInputWire(EventDeduplicator::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(
                eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(gossipWiring.getEventWindowInput(), INJECT);
        eventWindowOutputWire.solderTo(
                pcesInlineWriterWiring.getInputWire(InlinePcesWriter::updateNonAncientEventBoundary), INJECT);
        eventWindowOutputWire.solderTo(
                eventCreationManagerWiring.getInputWire(EventCreationManager::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(
                latestCompleteStateNexusWiring.getInputWire(LatestCompleteStateNexus::updateEventWindow));
        eventWindowOutputWire.solderTo(
                transactionResubmitterWiring.getInputWire(TransactionResubmitter::updateEventWindow));
        eventWindowOutputWire.solderTo(branchDetectorWiring.getInputWire(BranchDetector::updateEventWindow), INJECT);
        eventWindowOutputWire.solderTo(branchReporterWiring.getInputWire(BranchReporter::updateEventWindow), INJECT);
    }

    /**
     * Solder notifications into the notifier.
     */
    private void solderNotifier() {
        latestCompleteStateNotifierWiring
                .getOutputWire()
                .solderTo(notifierWiring.getInputWire(AppNotifier::sendLatestCompleteStateNotification));
        stateSnapshotManagerWiring
                .getTransformedOutput(StateSnapshotManager::toNotification)
                .solderTo(notifierWiring.getInputWire(AppNotifier::sendStateWrittenToDiskNotification), INJECT);

        final OutputWire<IssNotification> issNotificationOutputWire = issDetectorWiring.getSplitOutput();
        issNotificationOutputWire.solderTo(notifierWiring.getInputWire(AppNotifier::sendIssNotification));
        statusStateMachineWiring
                .getOutputWire()
                .solderTo(notifierWiring.getInputWire(AppNotifier::sendPlatformStatusChangeNotification));
    }

    /**
     * Wire the components together.
     */
    private void wire() {
        final InputWire<PlatformEvent> hasherInputWire =
                eventHasherWiring.getInputWire(EventHasher::hashEvent, "unhashed event");
        gossipWiring.getEventOutput().solderTo(hasherInputWire);

        eventHasherWiring
                .getOutputWire()
                .solderTo(internalEventValidatorWiring.getInputWire(InternalEventValidator::validateEvent));

        internalEventValidatorWiring
                .getOutputWire()
                .solderTo(eventDeduplicatorWiring.getInputWire(EventDeduplicator::handleEvent));
        eventDeduplicatorWiring
                .getOutputWire()
                .solderTo(eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::validateSignature));
        eventSignatureValidatorWiring
                .getOutputWire()
                .solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::handleEvent));
        final OutputWire<PlatformEvent> splitOrphanBufferOutput = orphanBufferWiring.getSplitOutput();

        splitOrphanBufferOutput.solderTo(pcesInlineWriterWiring.getInputWire(InlinePcesWriter::writeEvent));

        // Make sure that an event is persisted before being sent to consensus. This avoids the situation where we
        // reach consensus with events that might be lost due to a crash
        pcesInlineWriterWiring.getOutputWire().solderTo(consensusEngineWiring.getInputWire(ConsensusEngine::addEvent));

        // Make sure events are persisted before being gossipped. This prevents accidental branching in the case
        // where an event is created, gossipped, and then the node crashes before the event is persisted.
        // After restart, a node will not be aware of this event, so it can create a branch
        pcesInlineWriterWiring.getOutputWire().solderTo(gossipWiring.getEventInput(), INJECT);

        // Avoid using events as parents before they are persisted
        pcesInlineWriterWiring
                .getOutputWire()
                .solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::registerEvent));

        model.getHealthMonitorWire()
                .solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::reportUnhealthyDuration));

        model.getHealthMonitorWire().solderTo(gossipWiring.getSystemHealthInput());

        model.getHealthMonitorWire()
                .solderTo(transactionPoolWiring.getInputWire(TransactionPool::reportUnhealthyDuration));

        splitOrphanBufferOutput.solderTo(branchDetectorWiring.getInputWire(BranchDetector::checkForBranches));
        branchDetectorWiring.getOutputWire().solderTo(branchReporterWiring.getInputWire(BranchReporter::reportBranch));

        final double eventCreationHeartbeatFrequency = platformContext
                .getConfiguration()
                .getConfigData(EventCreationConfig.class)
                .creationAttemptRate();
        model.buildHeartbeatWire(eventCreationHeartbeatFrequency)
                .solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::maybeCreateEvent), OFFER);
        model.buildHeartbeatWire(platformContext
                        .getConfiguration()
                        .getConfigData(PlatformStatusConfig.class)
                        .statusStateMachineHeartbeatPeriod())
                .solderTo(statusStateMachineWiring.getInputWire(StatusStateMachine::heartbeat), OFFER);

        eventCreationManagerWiring
                .getOutputWire()
                .solderTo(staleEventDetectorWiring.getInputWire(StaleEventDetector::addSelfEvent));

        final OutputWire<PlatformEvent> staleEventsFromStaleEventDetector =
                staleEventDetectorWiring.getSplitAndRoutedOutput(STALE_SELF_EVENT);
        final OutputWire<PlatformEvent> selfEventsFromStaleEventDetector =
                staleEventDetectorWiring.getSplitAndRoutedOutput(SELF_EVENT);

        selfEventsFromStaleEventDetector.solderTo(
                internalEventValidatorWiring.getInputWire(InternalEventValidator::validateEvent), INJECT);

        staleEventsFromStaleEventDetector.solderTo(
                transactionResubmitterWiring.getInputWire(TransactionResubmitter::resubmitStaleTransactions));

        final Function<StateSignatureTransaction, Bytes> systemTransactionEncoder =
                applicationCallbacks.systemTransactionEncoder();

        if (publishStaleEvents) {
            staleEventsFromStaleEventDetector.solderTo(
                    platformPublisherWiring.getInputWire(PlatformPublisher::publishStaleEvent));
        }

        splitOrphanBufferOutput.solderTo(applicationTransactionPrehandlerWiring.getInputWire(
                TransactionPrehandler::prehandleApplicationTransactions));

        applicationTransactionPrehandlerWiring
                .getOutputWire()
                .solderTo(stateSignatureCollectorWiring.getInputWire(
                        StateSignatureCollector::handlePreconsensusSignatures));

        // Split output of StateSignatureCollector into single ReservedSignedStates.
        final OutputWire<ReservedSignedState> splitReservedSignedStateWire = stateSignatureCollectorWiring
                .getOutputWire()
                .buildSplitter("reservedStateSplitter", "reserved state lists");
        // Add another reservation to the signed states since we are soldering to two different input wires
        final OutputWire<ReservedSignedState> allReservedSignedStatesWire =
                splitReservedSignedStateWire.buildAdvancedTransformer(new SignedStateReserver("allStatesReserver"));

        // Future work: this should be a full component in its own right or folded in with the state file manager.
        final WireFilter<ReservedSignedState> saveToDiskFilter =
                new WireFilter<>(model, "saveToDiskFilter", "states", state -> {
                    if (state.get().isStateToSave()) {
                        return true;
                    }
                    state.close();
                    return false;
                });

        allReservedSignedStatesWire.solderTo(saveToDiskFilter.getInputWire());

        saveToDiskFilter
                .getOutputWire()
                .solderTo(stateSnapshotManagerWiring.getInputWire(StateSnapshotManager::saveStateTask));

        // Filter to complete states only and add a 3rd reservation since completes states are used in two input wires.
        final OutputWire<ReservedSignedState> completeReservedSignedStatesWire = allReservedSignedStatesWire
                .buildFilter("completeStateFilter", "states", rs -> {
                    if (rs.get().isComplete()) {
                        return true;
                    } else {
                        // close the second reservation on states that are not passed on.
                        rs.close();
                        return false;
                    }
                })
                .buildAdvancedTransformer(new SignedStateReserver("completeStatesReserver"));
        completeReservedSignedStatesWire.solderTo(
                latestCompleteStateNexusWiring.getInputWire(LatestCompleteStateNexus::setStateIfNewer));

        solderEventWindow();

        pcesReplayerWiring.eventOutput().solderTo(hasherInputWire);

        final OutputWire<ConsensusRound> consensusRoundOutputWire = consensusEngineWiring.getSplitOutput();

        consensusRoundOutputWire.solderTo(staleEventDetectorWiring.getInputWire(StaleEventDetector::addConsensusRound));

        pcesReplayerWiring
                .doneStreamingPcesOutputWire()
                .solderTo(pcesInlineWriterWiring.getInputWire(InlinePcesWriter::beginStreamingNewEvents));
        // with inline PCES, the round bypasses the round durability buffer and goes directly to the round handler
        consensusRoundOutputWire.solderTo(
                transactionHandlerWiring.getInputWire(TransactionHandler::handleConsensusRound));

        consensusRoundOutputWire.solderTo(
                eventWindowManagerWiring.getInputWire(EventWindowManager::extractEventWindow));

        consensusEngineWiring
                .getSplitAndTransformedOutput(ConsensusEngine::getCesEvents)
                .solderTo(consensusEventStreamWiring.getInputWire(ConsensusEventStream::addEvents));

        // The TransactionHandler output is split into two types: system transactions, and state with complexity.
        final OutputWire<Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
                transactionHandlerSysTxnsOutputWire = transactionHandlerWiring
                        .getOutputWire()
                        .buildTransformer(
                                "getSystemTransactions",
                                "transaction handler result",
                                TransactionHandlerResult::systemTransactions);
        transactionHandlerSysTxnsOutputWire.solderTo(
                stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::handlePostconsensusSignatures));
        transactionHandlerSysTxnsOutputWire.solderTo(
                issDetectorWiring.getInputWire(IssDetector::handleStateSignatureTransactions));

        final OutputWire<StateWithHashComplexity> transactionHandlerStateWithComplexityOutput = transactionHandlerWiring
                .getOutputWire()
                .buildFilter(
                        "notNullStateFilter",
                        "transaction handler result",
                        thr -> thr.stateWithHashComplexity() != null)
                .buildAdvancedTransformer(
                        new StateWithHashComplexityReserver("postHandler_stateWithHashComplexityReserver"));

        transactionHandlerStateWithComplexityOutput.solderTo(
                savedStateControllerWiring.getInputWire(SavedStateController::markSavedState));

        final OutputWire<ReservedSignedState> transactionHandlerStateOnlyOutput =
                transactionHandlerStateWithComplexityOutput.buildAdvancedTransformer(
                        new StateWithHashComplexityToStateReserver(
                                "postHandler_stateWithHashComplexityToStateReserver"));

        transactionHandlerStateOnlyOutput.solderTo(
                latestImmutableStateNexusWiring.getInputWire(SignedStateNexus::setState));
        transactionHandlerStateOnlyOutput.solderTo(
                stateGarbageCollectorWiring.getInputWire(StateGarbageCollector::registerState));

        savedStateControllerWiring.getOutputWire().solderTo(stateHasherWiring.getInputWire(StateHasher::hashState));

        model.buildHeartbeatWire(config.stateGarbageCollectorHeartbeatPeriod())
                .solderTo(stateGarbageCollectorWiring.getInputWire(StateGarbageCollector::heartbeat), OFFER);
        model.buildHeartbeatWire(config.signedStateSentinelHeartbeatPeriod())
                .solderTo(signedStateSentinelWiring.getInputWire(SignedStateSentinel::checkSignedStates), OFFER);

        // The state hasher needs to pass its data through a bunch of transformers. Construct those here.
        final OutputWire<ReservedSignedState> hashedStateOutputWire = stateHasherWiring
                .getOutputWire()
                .buildAdvancedTransformer(new SignedStateReserver("postHasher_stateReserver"));

        hashedStateOutputWire.solderTo(hashLoggerWiring.getInputWire(HashLogger::logHashes));
        hashedStateOutputWire.solderTo(stateSignerWiring.getInputWire(StateSigner::signState));
        hashedStateOutputWire.solderTo(issDetectorWiring.getInputWire(IssDetector::handleState));
        hashedStateOutputWire
                .buildTransformer("postHasher_notifier", "hashed states", StateHashedNotification::from)
                .solderTo(notifierWiring.getInputWire(AppNotifier::sendStateHashedNotification));

        final OutputWire<Bytes> systemTransactionEncoderOutputWireForStateSigner = stateSignerWiring
                .getOutputWire()
                .buildTransformer(
                        "postSigner_encode_systemTransactions",
                        "system transactions from signer",
                        systemTransactionEncoder);
        systemTransactionEncoderOutputWireForStateSigner.solderTo(
                transactionPoolWiring.getInputWire(TransactionPool::submitSystemTransaction));

        // FUTURE WORK: combine the signedStateHasherWiring State and Round outputs into a single StateAndRound output.
        // FUTURE WORK: Split the single StateAndRound output into separate State and Round wires.

        // Solder the state output as input to the state signature collector.
        hashedStateOutputWire.solderTo(
                stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::addReservedState));

        stateSnapshotManagerWiring
                .getTransformedOutput(StateSnapshotManager::extractOldestMinimumBirthRoundOnDisk)
                .solderTo(
                        pcesInlineWriterWiring.getInputWire(InlinePcesWriter::setMinimumAncientIdentifierToStore),
                        INJECT);

        stateSnapshotManagerWiring
                .getTransformedOutput(StateSnapshotManager::toStateWrittenToDiskAction)
                .solderTo(statusStateMachineWiring.getInputWire(StatusStateMachine::submitStatusAction));

        runningEventHashOverrideWiring
                .runningHashUpdateOutput()
                .solderTo(transactionHandlerWiring.getInputWire(TransactionHandler::updateLegacyRunningEventHash));
        runningEventHashOverrideWiring
                .runningHashUpdateOutput()
                .solderTo(consensusEventStreamWiring.getInputWire(ConsensusEventStream::legacyHashOverride));

        final OutputWire<IssNotification> splitIssDetectorOutput = issDetectorWiring.getSplitOutput();
        splitIssDetectorOutput.solderTo(issHandlerWiring.getInputWire(IssHandler::issObserved));
        issDetectorWiring
                .getSplitAndTransformedOutput(IssDetector::getStatusAction)
                .solderTo(statusStateMachineWiring.getInputWire(StatusStateMachine::submitStatusAction));

        completeReservedSignedStatesWire.solderTo(latestCompleteStateNotifierWiring.getInputWire(
                LatestCompleteStateNotifier::latestCompleteStateHandler));

        statusStateMachineWiring
                .getOutputWire()
                .solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::updatePlatformStatus));
        statusStateMachineWiring
                .getOutputWire()
                .solderTo(consensusEngineWiring.getInputWire(ConsensusEngine::updatePlatformStatus), INJECT);
        statusStateMachineWiring
                .getOutputWire()
                .solderTo(transactionPoolWiring.getInputWire(TransactionPool::updatePlatformStatus));
        statusStateMachineWiring.getOutputWire().solderTo(gossipWiring.getPlatformStatusInput(), INJECT);

        solderNotifier();

        if (publishPreconsensusEvents) {
            splitOrphanBufferOutput.solderTo(
                    platformPublisherWiring.getInputWire(PlatformPublisher::publishPreconsensusEvent));
        }

        buildUnsolderedWires();
    }

    /**
     * {@link ComponentWiring} objects build their input wires when you first request them. Normally that happens when
     * we are soldering things together, but there are a few wires that aren't soldered and aren't used until later in
     * the lifecycle. This method forces those wires to be built.
     */
    private void buildUnsolderedWires() {
        eventDeduplicatorWiring.getInputWire(EventDeduplicator::clear);
        consensusEngineWiring.getInputWire(ConsensusEngine::outOfBandSnapshotUpdate);
        if (publishSnapshotOverrides) {
            platformPublisherWiring.getInputWire(PlatformPublisher::publishSnapshotOverride);
        }
        eventCreationManagerWiring.getInputWire(EventCreationManager::clear);
        notifierWiring.getInputWire(AppNotifier::sendReconnectCompleteNotification);
        notifierWiring.getInputWire(AppNotifier::sendPlatformStatusChangeNotification);
        eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::updateRosterHistory);
        eventWindowManagerWiring.getInputWire(EventWindowManager::updateEventWindow);
        orphanBufferWiring.getInputWire(OrphanBuffer::clear);
        pcesInlineWriterWiring.getInputWire(InlinePcesWriter::registerDiscontinuity);
        stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::clear);
        issDetectorWiring.getInputWire(IssDetector::overridingState);
        issDetectorWiring.getInputWire(IssDetector::signalEndOfPreconsensusReplay);
        staleEventDetectorWiring.getInputWire(StaleEventDetector::setInitialEventWindow);
        staleEventDetectorWiring.getInputWire(StaleEventDetector::clear);
        transactionPoolWiring.getInputWire(TransactionPool::clear);
        stateSnapshotManagerWiring.getInputWire(StateSnapshotManager::dumpStateTask);
        branchDetectorWiring.getInputWire(BranchDetector::clear);
        branchReporterWiring.getInputWire(BranchReporter::clear);
    }

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
        eventCreationManagerWiring.bind(builder::buildEventCreationManager);
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
        staleEventDetectorWiring.bind(builder::buildStaleEventDetector);
        transactionResubmitterWiring.bind(builder::buildTransactionResubmitter);
        transactionPoolWiring.bind(builder::buildTransactionPool);
        gossipWiring.bind(builder.buildGossip());
        branchDetectorWiring.bind(builder::buildBranchDetector);
        branchReporterWiring.bind(builder::buildBranchReporter);
    }

    /**
     * Start gossiping.
     */
    public void startGossip() {
        gossipWiring.getStartInput().inject(NoInput.getInstance());
    }

    /**
     * Get the input wire for the roster history update.
     * <p>
     * Future work: this is a temporary hook to update the rosters in the new intake pipeline.
     *
     * @return the input wire for the roster history update.
     */
    @NonNull
    public InputWire<RosterHistory> getRosterHistoryInput() {
        return eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::updateRosterHistory);
    }

    /**
     * Get the input wire for dumping a state to disk
     * <p>
     * Future work: this is a temporary hook to allow the components to dump a state to disk, prior to the whole system
     * being migrated to the new framework.
     *
     * @return the input wire for dumping a state to disk
     */
    @NonNull
    public InputWire<StateDumpRequest> getDumpStateToDiskInput() {
        return stateSnapshotManagerWiring.getInputWire(StateSnapshotManager::dumpStateTask);
    }

    /**
     * @return the input wire for states that need their signatures collected
     */
    @NonNull
    public InputWire<ReservedSignedState> getSignatureCollectorStateInput() {
        return stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::addReservedState);
    }

    /**
     * Get the input wire for passing a PCES iterator to the replayer.
     *
     * @return the input wire for passing a PCES iterator to the replayer
     */
    @NonNull
    public InputWire<IOIterator<PlatformEvent>> getPcesReplayerIteratorInput() {
        return pcesReplayerWiring.pcesIteratorInputWire();
    }

    /**
     * Get the output wire that the replayer uses to pass events from file into the intake pipeline.
     *
     * @return the output wire that the replayer uses to pass events from file into the intake pipeline
     */
    @NonNull
    public StandardOutputWire<PlatformEvent> getPcesReplayerEventOutput() {
        return pcesReplayerWiring.eventOutput();
    }

    /**
     * Get the input wire that the hashlogger uses to accept the signed state.
     *
     * @return the input wire that the hashlogger uses to accept the signed state
     */
    @NonNull
    public InputWire<ReservedSignedState> getHashLoggerInput() {
        return hashLoggerWiring.getInputWire(HashLogger::logHashes);
    }

    /**
     * Forward a state to the hash logger.
     *
     * @param signedState the state to forward
     */
    public void sendStateToHashLogger(@NonNull final SignedState signedState) {
        if (signedState.getState().getHash() != null) {
            final ReservedSignedState stateReservedForHasher = signedState.reserve("logging state hash");

            final boolean offerResult = getHashLoggerInput().offer(stateReservedForHasher);
            if (!offerResult) {
                stateReservedForHasher.close();
            }
        }
    }

    /**
     * Get the input wire for the PCES writer minimum generation to store
     *
     * @return the input wire for the PCES writer minimum generation to store
     */
    @NonNull
    public InputWire<Long> getPcesMinimumGenerationToStoreInput() {
        return pcesInlineWriterWiring.getInputWire(InlinePcesWriter::setMinimumAncientIdentifierToStore);
    }

    /**
     * Get the input wire for the PCES writer to register a discontinuity
     *
     * @return the input wire for the PCES writer to register a discontinuity
     */
    @NonNull
    public InputWire<Long> getPcesWriterRegisterDiscontinuityInput() {
        return pcesInlineWriterWiring.getInputWire(InlinePcesWriter::registerDiscontinuity);
    }

    /**
     * Get the wiring for the app notifier
     *
     * @return the wiring for the app notifier
     */
    @NonNull
    public ComponentWiring<AppNotifier, Void> getNotifierWiring() {
        return notifierWiring;
    }

    /**
     * Get the output wire for consensus engine
     *
     * @return the wiring for the consensus engine
     */
    @NonNull
    public OutputWire<List<ConsensusRound>> getConsensusEngineOutputWire() {
        return consensusEngineWiring.getOutputWire();
    }

    /**
     * Update the running hash for all components that need it.
     *
     * @param runningHashUpdate the object containing necessary information to update the running hash
     */
    public void updateRunningHash(@NonNull final RunningEventHashOverride runningHashUpdate) {
        runningEventHashOverrideWiring.runningHashUpdateInput().inject(runningHashUpdate);
    }

    /**
     * Pass an overriding state to the ISS detector.
     *
     * @param state the overriding state
     */
    public void overrideIssDetectorState(@NonNull final ReservedSignedState state) {
        issDetectorWiring.getInputWire(IssDetector::overridingState).put(state);
    }

    /**
     * Signal the end of the preconsensus replay to the ISS detector.
     */
    public void signalEndOfPcesReplay() {
        issDetectorWiring
                .getInputWire(IssDetector::signalEndOfPreconsensusReplay)
                .put(NoInput.getInstance());
    }

    /**
     * Get the status action submitter.
     *
     * @return the status action submitter
     */
    @NonNull
    public StatusActionSubmitter getStatusActionSubmitter() {
        return action -> statusStateMachineWiring
                .getInputWire(StatusStateMachine::submitStatusAction)
                .put(action);
    }

    /**
     * Inject a new event window into all components that need it.
     *
     * @param eventWindow the new event window
     */
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        // Future work: this method can merge with consensusSnapshotOverride
        eventWindowManagerWiring
                .getInputWire(EventWindowManager::updateEventWindow)
                .inject(eventWindow);

        staleEventDetectorWiring
                .getInputWire(StaleEventDetector::setInitialEventWindow)
                .inject(eventWindow);

        // Since there is asynchronous access to the shadowgraph, it's important to ensure that
        // it has fully ingested the new event window before continuing.
        gossipWiring.flush();
    }

    /**
     * Inject a new consensus snapshot into all components that need it. This will happen at restart and reconnect
     * boundaries.
     *
     * @param consensusSnapshot the new consensus snapshot
     */
    public void consensusSnapshotOverride(@NonNull final ConsensusSnapshot consensusSnapshot) {
        consensusEngineWiring
                .getInputWire(ConsensusEngine::outOfBandSnapshotUpdate)
                .inject(consensusSnapshot);

        if (publishSnapshotOverrides) {
            platformPublisherWiring
                    .getInputWire(PlatformPublisher::publishSnapshotOverride)
                    .inject(consensusSnapshot);
        }
    }

    /**
     * Flush the intake pipeline.
     */
    public void flushIntakePipeline() {
        platformCoordinator.flushIntakePipeline();
    }

    /**
     * Flush the transaction handler.
     */
    public void flushTransactionHandler() {
        transactionHandlerWiring.flush();
    }

    /**
     * Flush the state hasher.
     */
    public void flushStateHasher() {
        stateHasherWiring.flush();
    }

    /**
     * Start the wiring framework.
     */
    public void start() {
        model.start();
    }

    /**
     * Stop the wiring framework.
     */
    public void stop() {
        model.stop();
    }

    /**
     * Clear all the wiring objects.
     */
    public void clear() {
        platformCoordinator.clear();
    }
}
