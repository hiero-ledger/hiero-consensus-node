package com.swirlds.platform.adapter;

import static java.util.Objects.requireNonNull;

import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.StaleEventConsumer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.ExecutionLayerCallbacks;
import org.hiero.consensus.main.model.TimestampedTransaction;
import org.hiero.consensus.main.model.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.state.nexus.SignedStateNexus;

public class AdapterCallbacks implements ExecutionLayerCallbacks {

    @NonNull
    private final ConsensusStateEventHandler consensusStateEventHandler;

    @NonNull
    private final ExecutionLayer executionLayer;

    /**
     * A source to get the latest immutable state
     */
    private final SignedStateNexus signedStateNexus;

    @NonNull
    private final StaleEventConsumer staleEventConsumer;

    public AdapterCallbacks(@NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final ExecutionLayer executionLayer,
            @NonNull final SignedStateNexus signedStateNexus,
            @NonNull final StaleEventConsumer staleEventConsumer) {
        this.consensusStateEventHandler = requireNonNull(consensusStateEventHandler);
        this.executionLayer = requireNonNull(executionLayer);
        this.signedStateNexus = requireNonNull(signedStateNexus);
        this.staleEventConsumer = requireNonNull(staleEventConsumer);
    }


    @Override
    public void onBehind() {

    }

    @Override
    public List<TimestampedTransaction> getTransactionForNewEvent() {
        return executionLayer.getTransactionsForEvent();
    }

    @Override
    public void onStaleEvent(@NonNull final Event event) {
        staleEventConsumer.processStaleEvent(event);
    }

    @Override
    public void onPreHandle(Event event) {

    }

    @Override
    public void onRound(Round consensusRound) {

    }

    @Override
    public void onPlatformStatusChange(PlatformStatus status) {

    }

    @Override
    public void onSealConsensusRound(Round consensusRound) {

    }

    @Override
    public void onUnhealthySignal(Duration unhealthyDuration) {

    }
}
