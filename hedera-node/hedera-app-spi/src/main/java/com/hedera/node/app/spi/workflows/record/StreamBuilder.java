// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows.record;

import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.PRECEDING;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.SCHEDULED;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Defines API for constructing stream items of a single transaction dispatch.
 * The implementation may produce only records or could produce block items
 */
public interface StreamBuilder {
    /**
     * Adds state changes to this stream builder.
     * @return this builder
     */
    default StreamBuilder stateChanges(@NonNull List<StateChange> stateChanges) {
        return this;
    }

    /**
     * Sets the transaction for this stream item builder.
     * @param transaction the transaction
     * @return this builder
     */
    StreamBuilder transaction(@NonNull Transaction transaction);

    /**
     * Sets the functionality for this stream item builder.
     * @param functionality the functionality
     * @return this builder
     */
    StreamBuilder functionality(@NonNull HederaFunctionality functionality);

    /**
     * Sets the serialized bytes for the transaction; if known, we can avoid re-serializing the transaction.
     * @param serializedTransaction if non-null, the serialized transaction
     * @return this builder
     */
    StreamBuilder serializedTransaction(@Nullable Bytes serializedTransaction);

    /**
     * Returns the transaction for this stream item builder.
     * @return the transaction
     */
    Transaction transaction();

    /**
     * Returns any ids recorded as having explicit reward situations during the construction of this builder so far.
     * @return the ids with explicit reward situations
     */
    Set<AccountID> explicitRewardSituationIds();

    /**
     * Returns any staking rewards recorded during the construction of this builder so far.
     * @return the staking rewards paid so far
     */
    List<AccountAmount> getPaidStakingRewards();

    /**
     * Returns whether this builder has a contract result.
     * @return true if the builder has a contract result; otherwise false
     */
    boolean hasContractResult();

    /**
     * Returns the gas used already set in construction of this builder.
     * @return the gas used
     */
    long getGasUsedForContractTxn();

    /**
     * Returns the evm ops duration already set in construction of this builder.
     * @return the evm ops duration
     */
    long getOpsDurationForContractTxn();

    /**
     * Returns the status that is currently set in the record builder.
     *
     * @return the status of the transaction
     */
    @NonNull
    ResponseCodeEnum status();

    /**
     * Returns the parsed transaction body for this record.
     *
     * @return the transaction body
     */
    @NonNull
    TransactionBody transactionBody();

    /**
     * Returns the current transaction fee.
     *
     * @return the current transaction fee
     */
    long transactionFee();

    /**
     * Sets the receipt status.
     *
     * @param status the receipt status
     * @return the builder
     */
    StreamBuilder status(@NonNull ResponseCodeEnum status);

    /**
     * Returns the category of the builder's transaction.
     * @return the category
     */
    HandleContext.TransactionCategory category();

    /**
     * The behavior of the record when the parent transaction fails
     * @return the behavior
     */
    ReversingBehavior reversingBehavior();

    /**
     * Removes all the side effects on the record when the parent transaction fails
     */
    void nullOutSideEffectFields();

    /**
     * Sets the transactionID of the record based on the user transaction record.
     * @return the builder
     */
    StreamBuilder syncBodyIdFromRecordId();

    /**
     * Sets the memo of the record.
     * @param memo the memo
     * @return the builder
     */
    StreamBuilder memo(@NonNull String memo);

    /**
     * Sets the consensus timestamp of the record.
     * @param now the consensus timestamp
     * @return the builder
     */
    StreamBuilder consensusTimestamp(@NonNull final Instant now);

    /**
     * Returns the transaction ID of the record.
     * @return the transaction ID
     */
    TransactionID transactionID();

    /**
     * Sets the transaction ID of the record.
     * @param transactionID the transaction ID
     * @return the builder
     */
    StreamBuilder transactionID(@NonNull TransactionID transactionID);

    /**
     * Sets the parent consensus timestamp of the record.
     * @param parentConsensus the parent consensus timestamp
     * @return the builder
     */
    StreamBuilder parentConsensus(@NonNull Instant parentConsensus);

    /**
     * Sets the transaction bytes of this builder.
     * @param transactionBytes the transaction bytes
     * @return this builder
     */
    StreamBuilder transactionBytes(@NonNull Bytes transactionBytes);

    /**
     * Sets the exchange rate of this builder.
     * @param exchangeRate the exchange rate
     * @return this builder
     */
    StreamBuilder exchangeRate(@Nullable ExchangeRateSet exchangeRate);

    /**
     * Returns the number of automatic token associations
     *
     * @return the number of associations
     */
    int getNumAutoAssociations();

    HederaFunctionality functionality();

    /**
     * Sets the congestion multiplier used for charging the fees for this transaction. This is set if non-zero.
     * @param congestionMultiplier the congestion multiplier
     * @return this builder
     */
    StreamBuilder congestionMultiplier(long congestionMultiplier);

    /**
     * Returns true if this builder's transaction originated from inside another handler or workflow; and not
     * a user transaction (or scheduled user transaction).
     * @return true if this transaction is internal
     */
    default boolean isInternalDispatch() {
        return category() == CHILD || category() == PRECEDING;
    }

    /**
     * Returns true if this builder's transaction originated from a user transaction or scheduled user transaction; and
     * not from inside another handler or workflow.
     * @return true if this transaction is internal
     */
    default boolean isUserDispatch() {
        return category() == USER || category() == SCHEDULED || category() == NODE;
    }

    /**
     * Convenience method to package as {@link TransactionBody} as a {@link Transaction} whose
     * {@link SignedTransaction} has an unset {@link SignatureMap}.
     * @param body the transaction body
     * @return the transaction
     */
    static Transaction transactionWith(@NonNull final TransactionBody body) {
        requireNonNull(body);
        return transactionWith(body, null);
    }

    /**
     * Convenience method to package a {@link TransactionBody} as a {@link Transaction} whose
     * {@link SignedTransaction} has an empty {@link SignatureMap}.
     * @param body the transaction body
     * @return the transaction
     */
    static Transaction nodeTransactionWith(@NonNull final TransactionBody body) {
        requireNonNull(body);
        return transactionWith(body, SignatureMap.DEFAULT);
    }

    /**
     * Returns whether the transaction is a preceding transaction.
     * @return {@code true} if the transaction is a preceding transaction; otherwise {@code false}
     */
    default boolean isPreceding() {
        return category().equals(HandleContext.TransactionCategory.PRECEDING);
    }

    /**
     * Possible behavior of a SingleTransactionRecord when a parent transaction fails,
     * and it is asked to be reverted
     */
    enum ReversingBehavior {
        /**
         * Changes are not committed. The record is kept in the record stream,
         * but the status is set to {@link ResponseCodeEnum#REVERTED_SUCCESS}
         */
        REVERSIBLE,

        /**
         * Changes are not committed and the record is removed from the record stream.
         */
        REMOVABLE,

        /**
         * Changes are committed independent of the user and parent transactions.
         */
        IRREVERSIBLE
    }

    private static Transaction transactionWith(
            @NonNull final TransactionBody body, @Nullable final SignatureMap sigMap) {
        final var bodyBytes = TransactionBody.PROTOBUF.toBytes(body);
        final var signedTransaction = SignedTransaction.newBuilder()
                .sigMap(sigMap)
                .bodyBytes(bodyBytes)
                .build();
        final var signedTransactionBytes = SignedTransaction.PROTOBUF.toBytes(signedTransaction);
        return Transaction.newBuilder()
                .signedTransactionBytes(signedTransactionBytes)
                .build();
    }

    /**
     * Allows a {@link com.hedera.node.app.spi.workflows.TransactionHandler} that dispatches child transactions
     * to customize exactly how these records are externalized. Specifically, it allows the handler to,
     * <ul>
     *     <li>Completely suppress the record because it contains redundant information (as in the case of
     *     the child transaction dispatched to implement a top-level HAPI {@code ContractCreate}).</li>
     *     <li>Transform the dispatched {@link Transaction} immediately before it is externalized (as
     *     in the case of the child {@link com.hedera.hapi.node.token.CryptoCreateTransactionBody} dispatched
     *     to implement an internal contract creation, which should be externalized as an equivalent
     *     {@link com.hedera.hapi.node.contract.ContractCreateTransactionBody}.</li>
     * </ul>
     *
     * <b>IMPORTANT:</b> implementations that suppress the record should throw if they nonetheless receive an
     * {@link TransactionCustomizer#apply(Object)} call. (With the current scope of this interface, the
     * provided {@link #SUPPRESSING_TRANSACTION_CUSTOMIZER} can simply be used.)
     */
    @FunctionalInterface
    interface TransactionCustomizer extends UnaryOperator<Transaction> {
        TransactionCustomizer NOOP_TRANSACTION_CUSTOMIZER = t -> t;

        TransactionCustomizer SUPPRESSING_TRANSACTION_CUSTOMIZER = new TransactionCustomizer() {
            @Override
            public Transaction apply(@NonNull final Transaction transaction) {
                throw new UnsupportedOperationException(
                        "Will not customize a transaction that should have been suppressed");
            }

            @Override
            public boolean isSuppressed() {
                return true;
            }
        };

        /**
         * Indicates whether the record of a dispatched transaction should be suppressed.
         *
         * @return {@code true} if the record should be suppressed; {@code false} otherwise
         */
        default boolean isSuppressed() {
            return false;
        }
    }
}
