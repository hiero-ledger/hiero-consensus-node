package org.hiero.consensus.model.transaction;

/**
 * A temporary interface used by the event creator to check if there are any buffered signature transactions. This is
 * used to ensure that state signatures are put into events when the platform is in the FREEZING state, thus having
 * fully signed freeze states.
 * <p>
 * NOTE: This mechanism can be removed once the quiescence feature is done, at which point execution can control when
 * event creation is stopped.
 */
@FunctionalInterface
public interface SignatureTransactionCheck {
    /**
     * Check if there are any buffered signature transactions waiting to be put into events.
     *
     * @return true if there are any buffered signature transactions
     */
    boolean hasBufferedSignatureTransactions();
}
