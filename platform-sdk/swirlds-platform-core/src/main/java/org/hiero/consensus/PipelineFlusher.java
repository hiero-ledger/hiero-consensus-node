// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.StateModule;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;

public class PipelineFlusher {

    private final EventIntakeModule eventIntakeModule;
    private final PcesModule pcesModule;
    private final GossipModule gossipModule;
    private final HashgraphModule hashgraphModule;
    private final TransactionHandlingModule transactionHandlingModule;
    private final EventCreatorModule eventCreatorModule;
    private final StateModule stateModule;

    public PipelineFlusher(
            @NonNull final EventIntakeModule eventIntakeModule,
            @NonNull final PcesModule pcesModule,
            @NonNull final GossipModule gossipModule,
            @NonNull final HashgraphModule hashgraphModule,
            @NonNull final TransactionHandlingModule transactionHandlingModule,
            @NonNull final EventCreatorModule eventCreatorModule,
            @NonNull final StateModule stateModule) {
        this.eventIntakeModule = requireNonNull(eventIntakeModule);
        this.pcesModule = requireNonNull(pcesModule);
        this.gossipModule = requireNonNull(gossipModule);
        this.hashgraphModule = requireNonNull(hashgraphModule);
        this.transactionHandlingModule = requireNonNull(transactionHandlingModule);
        this.eventCreatorModule = requireNonNull(eventCreatorModule);
        this.stateModule = requireNonNull(stateModule);
    }

    /**
     * Flushes the primary consensus-layer pipeline component-by-component, in upstream-to-downstream order: intake →
     * pces → gossip → hashgraph → transaction handling (pre-handler then handler) → event creation → state management.
     *
     * <p>This only flushes the pipeline; it does not decide whether in-flight work is delivered or discarded. That
     * depends on the state of the components when it is called — live components deliver (the PCES-replay caller),
     * squelched components discard (the reconnect {@code clear()} caller).
     *
     * <p>A single ordered pass leaves no work behind only while (1) no new events enter the intake module during the
     * flush and (2) the orphan buffer releases nothing in response to an event-window update. Both callers satisfy
     * these; see {@code rules/RUL-002} in the consensus-layer knowledge base for why one pass suffices and what would
     * break it.
     *
     * <p>Do not change the order of the calls below without consulting the wiring diagram.
     */
    public void flushPrimaryPipeline() {
        // Important: the order of the lines within this function matters. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        eventIntakeModule.flush();
        pcesModule.flush();
        gossipModule.flush();
        hashgraphModule.flush();
        transactionHandlingModule.flush();
        eventCreatorModule.flush();
        stateModule.flush();
    }
}
