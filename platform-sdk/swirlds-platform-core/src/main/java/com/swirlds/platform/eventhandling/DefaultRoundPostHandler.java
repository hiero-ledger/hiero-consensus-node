package com.swirlds.platform.eventhandling;

import com.swirlds.platform.state.ConsensusStateEventHandler;

public class DefaultRoundPostHandler implements RoundPostHandler {
    private final ConsensusStateEventHandler<?> consensusStateEventHandler;

    public DefaultRoundPostHandler(final ConsensusStateEventHandler<?> consensusStateEventHandler) {
        this.consensusStateEventHandler = consensusStateEventHandler;
    }


    @Override
    public TransactionHandlerResult postHandleRound(TransactionHandlerResult result) {
        consensusStateEventHandler.onPostHandleConsensusRound();
        return result;
    }
}
