package com.swirlds.platform.eventhandling;

public interface RoundPostHandler {

    TransactionHandlerResult postHandleRound(TransactionHandlerResult result);
}
