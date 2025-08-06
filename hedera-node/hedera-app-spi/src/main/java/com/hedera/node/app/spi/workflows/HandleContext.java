// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.authorization.SystemPrivilege;
import com.hedera.node.app.spi.fees.ExchangeRateInfo;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fees.ResourcePriceCalculator;
import com.hedera.node.app.spi.ids.EntityNumGenerator;
import com.hedera.node.app.spi.key.KeyVerifier;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.throttle.ThrottleAdviser;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Represents the context of a single {@code handle()}-call.
 * <p>
 * The information and functionality provided by a {@code HandleContext} is valid only for the duration of the call. It
 * can be grouped into five categories:
 * <ul>
 *     <li>Information about the transaction being handled, such as its consensus time, its body, and its category</li>
 *     <li>Configuration data and objects that depend on the current configuration</li>
 *     <li>Verification data, that has been assembled during pre-handle</li>
 *     <li>State related data and the possibility to rollback changes</li>
 *     <li>Data related to the record stream</li>
 *     <li>Functionality to dispatch preceding and child transactions</li>
 * </ul>
 */
@SuppressWarnings("UnusedReturnValue")
public interface HandleContext {
    /**
     * Category of the current transaction.
     */
    enum TransactionCategory {
        /**
         * A transaction submitted by a user via HAPI or by a node via {@link com.hedera.node.app.spi.AppContext.Gossip}.
         * */
        USER,
        /**
         * An independent, top-level transaction that is executed before the user transaction.
         * */
        PRECEDING,
        /**
         * A child transaction that is executed as part of a user transaction.
         * */
        CHILD,
        /**
         * A transaction executed via the schedule service.
         * */
        SCHEDULED,
        /**
         * A transaction submitted by Node for TSS service
         */
        NODE,
        /**
         * A child transaction submitted via atomic batch user transaction.
         */
        BATCH_INNER
    }

    /**
     * Enumerates the possible kinds of limits on preceding transaction records.
     */
    enum PrecedingTransactionCategory {
        /**
         * No limit on preceding transactions, true at genesis since there are no previous consensus
         * times to collide with.
         */
        UNLIMITED_CHILD_RECORDS,
        /**
         * The number of preceding transactions is limited by the number of nanoseconds between the
         * last-assigned consensus time and the current platform-assigned consensus time. (Or,
         * before block streams, even further artificially limited to three records for
         * backward compatibility.)
         */
        LIMITED_CHILD_RECORDS
    }

    /**
     * Metadata that can be attached to a dispatch.
     * This metadata is passed when dispatching a child transaction and can
     * be used to pass additional information to the targeted handlers.
     */
    class DispatchMetadata {
        public static final DispatchMetadata EMPTY_METADATA = new DispatchMetadata(Map.of());

        private final Map<Type, Object> metadata;

        /**
         * Constructs a new DispatchMetadata instance with the given metadata map.
         *
         * @param metadata the metadata map
         */
        public DispatchMetadata(@NonNull final Map<Type, Object> metadata) {
            this.metadata = requireNonNull(metadata);
        }

        /**
         * Constructs a new DispatchMetadata instance with a single metadata entry.
         *
         * @param type the metadata key
         * @param value the metadata value
         */
        public DispatchMetadata(@NonNull final Type type, @NonNull Object value) {
            this.metadata = new HashMap<>(Map.of(type, value));
        }

        /**
         * Adds or updates a metadata entry.
         *
         * @param type the metadata key
         * @param value the metadata value
         */
        public void putMetadata(@NonNull final Type type, @NonNull final Object value) {
            metadata.put(type, value);
        }

        /**
         * Retrieves the metadata value associated with the given key.
         *
         * @param type the metadata key
         * @param javaType the Java type of the metadata value
         * @return the metadata value, if present
         */
        public <T> @Nullable T getMetadataIfPresent(@NonNull final Type type, @NonNull final Class<T> javaType) {
            requireNonNull(type);
            requireNonNull(javaType);
            final var v = metadata.get(type);
            if (v == null) {
                return null;
            } else {
                return javaType.cast(v);
            }
        }

        /**
         * Retrieves the metadata value associated with the given key.
         *
         * @param type the metadata key
         * @param javaType the Java type of the metadata value
         * @return the metadata value, if present
         */
        public <T> Optional<T> getMetadata(@NonNull final Type type, @NonNull final Class<T> javaType) {
            requireNonNull(type);
            requireNonNull(javaType);
            return Optional.ofNullable(metadata.get(type)).map(javaType::cast);
        }

        /**
         * Enumerates the possible types of dispatch metadata.
         */
        public enum Type {
            /**
             * The fixed fee of a transaction.
             */
            TRANSACTION_FIXED_FEE,
            /**
             * A fee charging strategy that should be used to customize further dispatches.
             */
            CUSTOM_FEE_CHARGING,
            /**
             * Whether a contract dispatch should externalize explicit writes with its slot usage traces.
             */
            EXPLICIT_WRITE_TRACING,
            /**
             * Batch inner transaction bytes. Used to prehandle inner transaction while dispatching them.
             */
            INNER_TRANSACTION_BYTES,
            /**
             * A callback to be invoked to increment the nonce of the payer account.
             * This is used to ensure that the nonce is incremented when ethereum transaction fails inside a batch.
             */
            ETHEREUM_NONCE_INCREMENT_CALLBACK
        }
    }

    /**
     * Returns the current consensus time.
     *
     * @return the current consensus time
     */
    @NonNull
    Instant consensusNow();

    /**
     * Returns the {@link TransactionBody}
     *
     * @return the {@code TransactionBody}
     */
    @NonNull
    TransactionBody body();

    /**
     * Gets the payer {@link AccountID}.
     *
     * @return the {@link AccountID} of the payer in this context
     */
    @NonNull
    AccountID payer();

    /**
     * Attempts to charge the payer in this context the given amount of tinybar.
     * @param amount the amount to charge
     * @return true if the entire amount was successfully charged, false otherwise
     */
    default boolean tryToChargePayer(final long amount) {
        return tryToCharge(payer(), amount);
    }

    /**
     * Tries to charge the requested account in this context the given amount of tinybar,
     * distributing the fees proportionally to the active collection accounts.
     * @param amount the amount to charge
     * @return true if the entire amount was successfully charged, false otherwise
     */
    boolean tryToCharge(AccountID accountId, long amount);

    /**
     * Tries to refund the requested account in this context the given amount of tinybar,
     * reclaiming the fees proportionally from the active collection accounts. This is
     * a best effort operation, and in some extremely unusual edge cases (like the fee
     * collection accounts themselves being debited in the transaction), may not refund
     * the full amount.
     * @param amount the amount to refund.
     */
    void refundBestEffort(AccountID accountId, long amount);

    /**
     * Returns the current {@link Configuration} for the node.
     *
     * @return the {@code Configuration}
     */
    @NonNull
    Configuration configuration();

    /**
     * Returns information on current block and record file
     *
     * @return current BlockRecordInfo
     */
    @NonNull
    BlockRecordInfo blockRecordInfo();

    /**
     * Returns a {@link ResourcePriceCalculator} that provides functionality to calculate fees for transactions.
     *
     * @return the {@link ResourcePriceCalculator}
     */
    @NonNull
    ResourcePriceCalculator resourcePriceCalculator();

    /**
     * Gets a {@link ExchangeRateInfo} which provides information about the current exchange rate.
     *
     * @return The {@link ExchangeRateInfo} .
     */
    @NonNull
    ExchangeRateInfo exchangeRateInfo();

    /**
     * Returns an {@link EntityNumGenerator} that can be used to generate entity numbers.
     *
     * @return the entity number generator
     */
    EntityNumGenerator entityNumGenerator();

    /**
     * Returns the validator for attributes of entities created or updated by handlers.
     *
     * @return the validator for attributes
     */
    @NonNull
    AttributeValidator attributeValidator();

    /**
     * Returns the validator for expiry metadata (both explicit expiration times and auto-renew configuration) of
     * entities created or updated by handlers.
     *
     * @return the validator for expiry metadata
     */
    @NonNull
    ExpiryValidator expiryValidator();

    /**
     * Returns all (required and optional) keys of a nested transaction.
     *
     * @param nestedTxn the {@link TransactionBody} which keys are needed
     * @param payerForNested the payer for the nested transaction
     * @return the set of keys
     * @throws PreCheckException If there is a problem with the nested transaction
     */
    @NonNull
    TransactionKeys allKeysForTransaction(@NonNull TransactionBody nestedTxn, @NonNull AccountID payerForNested)
            throws PreCheckException;

    /**
     * Returns the {@link KeyVerifier} which can be used to verify signatures.
     *
     * @return the {@link KeyVerifier}
     */
    @NonNull
    KeyVerifier keyVerifier();

    /**
     * Checks whether the current transaction is a privileged transaction and the payer has sufficient rights.
     *
     * @return the {@code SystemPrivilege} of the current transaction
     */
    SystemPrivilege hasPrivilegedAuthorization();

    /**
     * Returns a {@link StoreFactory} that can create readable and writable stores as well as service APIs.
     *
     * @return the {@link StoreFactory}
     */
    @NonNull
    StoreFactory storeFactory();

    /**
     * Returns the information about the network this transaction is being handled in.
     *
     * @return the network information
     */
    @NonNull
    NetworkInfo networkInfo();

    /**
     * Dispatches the fee calculation for a child transaction (that might then be dispatched).
     *
     * <p>The override payer id still matters for this purpose, because a transaction can add
     * state whose lifetime is scoped to a payer account (the main current example is a
     * {@link HederaFunctionality#CRYPTO_APPROVE_ALLOWANCE} dispatch).
     *
     * @param txBody the {@link TransactionBody} of the child transaction to compute fees for
     * @param syntheticPayerId the child payer
     * @param computeDispatchFeesAsTopLevel for mono fidelity, whether to compute fees as a top-level transaction
     * @return the calculated fees
     */
    Fees dispatchComputeFees(
            @NonNull TransactionBody txBody,
            @NonNull AccountID syntheticPayerId,
            @NonNull ComputeDispatchFeesAsTopLevel computeDispatchFeesAsTopLevel);

    /**
     * Dispatches a child transaction with the given options.
     * @param options the options to use
     * @return the stream builder of the child transaction
     * @param <T> the type of the stream builder
     */
    <T extends StreamBuilder> T dispatch(@NonNull DispatchOptions<T> options);

    /**
     * Returns the current {@link SavepointStack}.
     *
     * @return the current {@code TransactionStack}
     */
    @NonNull
    SavepointStack savepointStack();

    /**
     * Returns the {@link ThrottleAdviser} for this transaction, which provides information about throttles.
     *
     * @return the {@link ThrottleAdviser} for this transaction
     */
    @NonNull
    ThrottleAdviser throttleAdviser();

    /**
     * A stack of savepoints.
     *
     * <p>A new savepoint can be created manually. In addition, a new entry is added to the savepoint stack every time
     * a child transaction is dispatched and executed successfully. The transaction stack allows to rollback an
     * arbitrary number of transactions. Please be aware that rolling back a child transaction will also rollbacks all
     * state changes that were introduced afterward.
     */
    interface SavepointStack {
        /**
         * Create a savepoint manually.
         * <p>
         * This method will add a new entry to the savepoint stack. A subsequent rollback will roll back all state
         * changes that were introduced after the savepoint was added.
         */
        void createSavepoint();

        /**
         * Commits all changes since the last savepoint.
         *
         * @throws IllegalStateException if the savepoint stack is empty
         */
        void commit();

        /**
         * Rolls back the changes up until the last savepoint.
         *
         * @throws IllegalStateException if the savepoint stack is empty
         */
        void rollback();

        /**
         * Returns the depth of the savepoint stack.
         *
         * @return the depth of the savepoint stack
         */
        int depth();

        /**
         * Returns a record builder for the given record builder subtype.
         *
         * @param recordBuilderClass the record type
         * @param <T> the record type
         * @return a builder for the given record type
         * @throws NullPointerException if {@code recordBuilderClass} is {@code null}
         * @throws IllegalArgumentException if the record builder type is unknown to the app
         */
        @NonNull
        <T extends StreamBuilder> T getBaseBuilder(@NonNull Class<T> recordBuilderClass);

        /**
         * Adds a child record builder to the list of record builders. If the current {@link HandleContext} (or any parent
         * context) is rolled back, all child record builders will be reverted.
         *
         * @param <T> the record type
         * @param recordBuilderClass the record type
         * @param functionality the functionality of the record
         * @return the new child record builder
         * @throws NullPointerException if {@code recordBuilderClass} is {@code null}
         * @throws IllegalArgumentException if the record builder type is unknown to the app
         */
        @NonNull
        <T> T addChildRecordBuilder(@NonNull Class<T> recordBuilderClass, @NonNull HederaFunctionality functionality);

        /**
         * Adds a removable child record builder to the list of record builders. Unlike a regular child record builder,
         * a removable child record builder is removed, if the current {@link HandleContext} (or any parent context) is
         * rolled back.
         *
         * @param <T> the record type
         * @param recordBuilderClass the record type
         * @param functionality the functionality of the record
         * @return the new child record builder
         * @throws NullPointerException if {@code recordBuilderClass} is {@code null}
         * @throws IllegalArgumentException if the record builder type is unknown to the app
         */
        @NonNull
        <T> T addRemovableChildRecordBuilder(
                @NonNull Class<T> recordBuilderClass, @NonNull HederaFunctionality functionality);
    }

    /**
     * Gets the pre-paid rewards for the current transaction. This can be non-empty for scheduled transactions.
     * Since we use the parent record finalizer to finalize schedule transactions, we need to deduct any paid staking rewards
     * already happened in the parent transaction.
     * @return the paid rewards
     */
    @NonNull
    Map<AccountID, Long> dispatchPaidRewards();

    /**
     * Returns the {@link NodeInfo} for the node this transaction is created from.
     * @return the node info
     */
    NodeInfo creatorInfo();

    /**
     * Whether a dispatch should be throttled at consensus. True for everything except certain dispatches
     * internal to the EVM which are only constrained by gas.
     */
    enum ConsensusThrottling {
        ON,
        OFF
    }

    /**
     * Metadata that can be attached to a dispatch.
     * This metadata is passed when dispatching a child transaction and can
     * be used to pass additional information to the targeted handlers.
     */
    DispatchMetadata dispatchMetadata();
}
