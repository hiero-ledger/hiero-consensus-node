package com.swirlds.platform.adapter;

import static java.util.Objects.requireNonNull;

import com.swirlds.platform.state.ConsensusStateEventHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.ExecutionLayerCallbacks;
import org.hiero.consensus.main.model.TimestampedTransaction;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.state.nexus.SignedStateNexus;

public class AdapterCallbacks implements ExecutionLayerCallbacks {

    @NonNull
    private final ConsensusStateEventHandler consensusStateEventHandler;

    /**
     * A source to get the latest immutable state
     */
    private final SignedStateNexus signedStateNexus;

    public AdapterCallbacks(@NonNull final ConsensusStateEventHandler consensusStateEventHandler,
            @NonNull final SignedStateNexus signedStateNexus) {
        this.consensusStateEventHandler = requireNonNull(consensusStateEventHandler);
        this.signedStateNexus = requireNonNull(signedStateNexus);
    }


    @Override
    public void onBehind() {

    }

    @Override
    public List<TimestampedTransaction> getTransactionForNewEvent() {
        return List.of();
    }

    @Override
    public void onStaleEvent(Event event) {

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
