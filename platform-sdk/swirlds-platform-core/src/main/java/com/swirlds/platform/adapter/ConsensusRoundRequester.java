package com.swirlds.platform.adapter;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.ConsensusLayer;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;

public class ConsensusRoundRequester {

    @NonNull
    private final TransactionHandlingModule transactionHandlingModule;

    @NonNull
    private final ConsensusLayer consensusLayer;

    @NonNull
    private final Roster roster;

    public ConsensusRoundRequester(@NonNull final ConsensusLayer consensusLayer, @NonNull final Roster roster, @NonNull final TransactionHandlingModule transactionHandlingModule) {
        this.consensusLayer = requireNonNull(consensusLayer);
        this.roster = requireNonNull(roster);
        this.transactionHandlingModule = requireNonNull(transactionHandlingModule);
    }

    public void onNewConsensusRound(@NonNull final ConsensusRound consensusRound) {
        transactionHandlingModule.handleConsensusRoundInputWire().put(consensusRound);
        // TODO populate freeze time appropriately.
        consensusLayer.requestNextRound(roster, null);
    }

    // TODO call this from somewhere
    public void requestFirstRound() {
        consensusLayer.requestNextRound(roster, null);
    }
}
