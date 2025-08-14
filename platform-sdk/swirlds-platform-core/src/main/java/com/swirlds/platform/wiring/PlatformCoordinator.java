// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.transformers.RoutableData;
import com.swirlds.platform.components.consensus.ConsensusEngine;
import com.swirlds.platform.components.consensus.ConsensusEngineOutput;
import com.swirlds.platform.event.branching.BranchDetector;
import com.swirlds.platform.event.branching.BranchReporter;
import com.swirlds.platform.event.deduplication.EventDeduplicator;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.event.preconsensus.InlinePcesWriter;
import com.swirlds.platform.event.validation.EventSignatureValidator;
import com.swirlds.platform.event.validation.InternalEventValidator;
import com.swirlds.platform.eventhandling.TransactionHandler;
import com.swirlds.platform.eventhandling.TransactionHandlerResult;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.state.hasher.StateHasher;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.system.status.StatusStateMachine;
import com.swirlds.platform.wiring.components.GossipWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import org.hiero.consensus.event.creator.impl.EventCreationManager;
import org.hiero.consensus.event.creator.impl.pool.TransactionPool;
import org.hiero.consensus.event.creator.impl.stale.StaleEventDetector;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.StaleEventDetectorOutput;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Responsible for coordinating the clearing of the platform wiring objects.
 */
public class PlatformCoordinator {

    /**
     * Flushes the event hasher.
     */
    private final Runnable flushTheEventHasher;

    private final ComponentWiring<InternalEventValidator, PlatformEvent> internalEventValidatorWiring;
    private final ComponentWiring<EventDeduplicator, PlatformEvent> eventDeduplicatorWiring;
    private final ComponentWiring<EventSignatureValidator, PlatformEvent> eventSignatureValidatorWiring;
    private final ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring;
    private final GossipWiring gossipWiring;
    private final ComponentWiring<ConsensusEngine, ConsensusEngineOutput> consensusEngineWiring;
    private final ComponentWiring<EventCreationManager, PlatformEvent> eventCreationManagerWiring;
    private final ComponentWiring<TransactionPrehandler, Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
            applicationTransactionPrehandlerWiring;
    private final ComponentWiring<StateSignatureCollector, List<ReservedSignedState>> stateSignatureCollectorWiring;
    private final ComponentWiring<TransactionHandler, TransactionHandlerResult> transactionHandlerWiring;
    private final ComponentWiring<StateHasher, ReservedSignedState> stateHasherWiring;
    private final ComponentWiring<StaleEventDetector, List<RoutableData<StaleEventDetectorOutput>>>
            staleEventDetectorWiring;
    private final ComponentWiring<TransactionPool, Void> transactionPoolWiring;
    private final ComponentWiring<StatusStateMachine, PlatformStatus> statusStateMachineWiring;
    private final ComponentWiring<BranchDetector, PlatformEvent> branchDetectorWiring;
    private final ComponentWiring<BranchReporter, Void> branchReporterWiring;
    private final ComponentWiring<InlinePcesWriter, PlatformEvent> pcesInlineWriterWiring;

    /**
     * Constructor
     *
     * @param flushTheEventHasher                    a lambda that flushes the event hasher
     * @param internalEventValidatorWiring           the internal event validator wiring
     * @param eventDeduplicatorWiring                the event deduplicator wiring
     * @param eventSignatureValidatorWiring          the event signature validator wiring
     * @param orphanBufferWiring                     the orphan buffer wiring
     * @param gossipWiring                           gossip wiring
     * @param consensusEngineWiring                  the consensus engine wiring
     * @param eventCreationManagerWiring             the event creation manager wiring
     * @param applicationTransactionPrehandlerWiring the application transaction prehandler wiring
     * @param stateSignatureCollectorWiring          the system transaction prehandler wiring
     * @param transactionHandlerWiring               the transaction handler wiring
     * @param stateHasherWiring                      the state hasher wiring
     * @param staleEventDetectorWiring               the stale event detector wiring
     * @param transactionPoolWiring                  the transaction pool wiring
     * @param statusStateMachineWiring               the status state machine wiring
     * @param branchDetectorWiring                   the branch detector wiring
     * @param branchReporterWiring                   the branch reporter wiring
     * @param pcesInlineWriterWiring                 the inline PCES writer wiring
     */
    public PlatformCoordinator(
            @NonNull final Runnable flushTheEventHasher,
            @NonNull final ComponentWiring<InternalEventValidator, PlatformEvent> internalEventValidatorWiring,
            @NonNull final ComponentWiring<EventDeduplicator, PlatformEvent> eventDeduplicatorWiring,
            @NonNull final ComponentWiring<EventSignatureValidator, PlatformEvent> eventSignatureValidatorWiring,
            @NonNull final ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring,
            @NonNull final GossipWiring gossipWiring,
            @NonNull final ComponentWiring<ConsensusEngine, ConsensusEngineOutput> consensusEngineWiring,
            @NonNull final ComponentWiring<EventCreationManager, PlatformEvent> eventCreationManagerWiring,
            @NonNull
                    final ComponentWiring<
                                    TransactionPrehandler, Queue<ScopedSystemTransaction<StateSignatureTransaction>>>
                            applicationTransactionPrehandlerWiring,
            @NonNull
                    final ComponentWiring<StateSignatureCollector, List<ReservedSignedState>>
                            stateSignatureCollectorWiring,
            @NonNull final ComponentWiring<TransactionHandler, TransactionHandlerResult> transactionHandlerWiring,
            @NonNull final ComponentWiring<StateHasher, ReservedSignedState> stateHasherWiring,
            @NonNull
                    final ComponentWiring<StaleEventDetector, List<RoutableData<StaleEventDetectorOutput>>>
                            staleEventDetectorWiring,
            @NonNull final ComponentWiring<TransactionPool, Void> transactionPoolWiring,
            @NonNull final ComponentWiring<StatusStateMachine, PlatformStatus> statusStateMachineWiring,
            @NonNull final ComponentWiring<BranchDetector, PlatformEvent> branchDetectorWiring,
            @NonNull final ComponentWiring<BranchReporter, Void> branchReporterWiring,
            @Nullable final ComponentWiring<InlinePcesWriter, PlatformEvent> pcesInlineWriterWiring) {

        this.flushTheEventHasher = Objects.requireNonNull(flushTheEventHasher);
        this.internalEventValidatorWiring = Objects.requireNonNull(internalEventValidatorWiring);
        this.eventDeduplicatorWiring = Objects.requireNonNull(eventDeduplicatorWiring);
        this.eventSignatureValidatorWiring = Objects.requireNonNull(eventSignatureValidatorWiring);
        this.orphanBufferWiring = Objects.requireNonNull(orphanBufferWiring);
        this.gossipWiring = Objects.requireNonNull(gossipWiring);
        this.consensusEngineWiring = Objects.requireNonNull(consensusEngineWiring);
        this.eventCreationManagerWiring = Objects.requireNonNull(eventCreationManagerWiring);
        this.applicationTransactionPrehandlerWiring = Objects.requireNonNull(applicationTransactionPrehandlerWiring);
        this.stateSignatureCollectorWiring = Objects.requireNonNull(stateSignatureCollectorWiring);
        this.transactionHandlerWiring = Objects.requireNonNull(transactionHandlerWiring);
        this.stateHasherWiring = Objects.requireNonNull(stateHasherWiring);
        this.staleEventDetectorWiring = Objects.requireNonNull(staleEventDetectorWiring);
        this.transactionPoolWiring = Objects.requireNonNull(transactionPoolWiring);
        this.statusStateMachineWiring = Objects.requireNonNull(statusStateMachineWiring);
        this.branchDetectorWiring = Objects.requireNonNull(branchDetectorWiring);
        this.branchReporterWiring = Objects.requireNonNull(branchReporterWiring);
        this.pcesInlineWriterWiring = pcesInlineWriterWiring;
    }

    /**
     * Flushes the intake pipeline. After this method is called, all components in the intake pipeline (i.e. components
     * prior to the consensus engine) will have been flushed. Additionally, things will be flushed an order that
     * guarantees that there will be no remaining work in the intake pipeline (as long as there are no additional events
     * added to the intake pipeline, and as long as there are no events released by the orphan buffer).
     */
    public void flushIntakePipeline() {
        // Important: the order of the lines within this function matters. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        flushTheEventHasher.run();
        internalEventValidatorWiring.flush();
        eventDeduplicatorWiring.flush();
        eventSignatureValidatorWiring.flush();
        orphanBufferWiring.flush();
        if (pcesInlineWriterWiring != null) {
            pcesInlineWriterWiring.flush();
        }
        gossipWiring.flush();
        consensusEngineWiring.flush();
        applicationTransactionPrehandlerWiring.flush();
        eventCreationManagerWiring.flush();
        branchDetectorWiring.flush();
    }

    /**
     * Safely clears the system in preparation for reconnect. After this method is called, there should be no work
     * sitting in any of the wiring queues, and all internal data structures within wiring components that need to be
     * cleared to prepare for a reconnect should be cleared.
     */
    public void clear() {
        // Important: the order of the lines within this function are important. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        // Phase 0: flush the status state machine.
        // When reconnecting, this will force us to adopt a status that will halt event creation and gossip.
        statusStateMachineWiring.flush();

        // Phase 1: squelch
        // Break cycles in the system. Flush squelched components just in case there is a task being executed when
        // squelch is activated.
        consensusEngineWiring.startSquelching();
        consensusEngineWiring.flush();
        eventCreationManagerWiring.startSquelching();
        eventCreationManagerWiring.flush();
        staleEventDetectorWiring.startSquelching();

        // Also squelch the transaction handler. It isn't strictly necessary to do this to prevent dataflow through
        // the system, but it prevents the transaction handler from wasting time handling rounds that don't need to
        // be handled.
        transactionHandlerWiring.startSquelching();
        transactionHandlerWiring.flush();

        // Phase 2: flush
        // All cycles have been broken via squelching, so now it's time to flush everything out of the system.
        flushIntakePipeline();
        stateHasherWiring.flush();
        stateSignatureCollectorWiring.flush();
        transactionHandlerWiring.flush();
        staleEventDetectorWiring.flush();
        branchDetectorWiring.flush();
        branchReporterWiring.flush();

        // Phase 3: stop squelching
        // Once everything has been flushed out of the system, it's safe to stop squelching.
        consensusEngineWiring.stopSquelching();
        eventCreationManagerWiring.stopSquelching();
        transactionHandlerWiring.stopSquelching();
        staleEventDetectorWiring.stopSquelching();

        // Phase 4: clear
        // Data is no longer moving through the system. Clear all the internal data structures in the wiring objects.
        eventDeduplicatorWiring.getInputWire(EventDeduplicator::clear).inject(NoInput.getInstance());
        orphanBufferWiring.getInputWire(OrphanBuffer::clear).inject(NoInput.getInstance());
        gossipWiring.getClearInput().inject(NoInput.getInstance());
        stateSignatureCollectorWiring
                .getInputWire(StateSignatureCollector::clear)
                .inject(NoInput.getInstance());
        eventCreationManagerWiring.getInputWire(EventCreationManager::clear).inject(NoInput.getInstance());
        staleEventDetectorWiring.getInputWire(StaleEventDetector::clear).inject(NoInput.getInstance());
        transactionPoolWiring.getInputWire(TransactionPool::clear).inject(NoInput.getInstance());
        branchDetectorWiring.getInputWire(BranchDetector::clear).inject(NoInput.getInstance());
        branchReporterWiring.getInputWire(BranchReporter::clear).inject(NoInput.getInstance());
    }
}
