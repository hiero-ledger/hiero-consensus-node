// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static com.hedera.node.app.spi.fees.NoopFeeCharging.NOOP_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.EMPTY_METADATA;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.CUSTOM_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.SUPPRESSING_SIGNED_TX_CUSTOMIZER;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.workflows.HandleContext.ConsensusThrottling;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The options a {@link HandleContext} client can customize its dispatch with.
 */
public record DispatchOptions<T extends StreamBuilder>(
        @NonNull Commit commit,
        @NonNull AccountID payerId,
        @NonNull TransactionBody body,
        @NonNull UsePresetTxnId usePresetTxnId,
        @NonNull Predicate<Key> keyVerifier,
        @NonNull Set<Key> authorizingKeys,
        @NonNull TransactionCategory category,
        @NonNull ConsensusThrottling throttling,
        @NonNull Class<T> streamBuilderType,
        @NonNull ReversingBehavior reversingBehavior,
        @NonNull StreamBuilder.SignedTxCustomizer signedTxCustomizer,
        @NonNull DispatchMetadata dispatchMetadata,
        @Nullable FeeCharging customFeeCharging) {
    /**
     * The set of keys that are authorized to sign the transaction. This should be used only for the dispatches
     * that don't have any signatures to verify.
     */
    private static final Predicate<Key> PREAUTHORIZED_KEYS = k -> true;
    /**
     * The set of keys that are authorized to sign the transaction. If none of the keys sign
     * for atomic batch inner transaction dispatches, the transaction will fail.
     */
    private static final Predicate<Key> NO_AUTHORIZED_KEYS = k -> false;

    /**
     * The choice of when to commit the dispatched transaction's effects on state.
     */
    public enum Commit {
        /**
         * Wait to commit the dispatched transaction's effects with its parent; hence, only commit these effects
         * if the parent dispatch does not roll back.
         */
        WITH_PARENT,
        /**
         * Commit the transaction's effects immediately, along with any effects already accumulated by the parent
         * dispatch. (But note the parent dispatch will generally have no pending effects when this option makes
         * sense to use; i.e., to perform a preceding dispatch that sets up the parent dispatch state but does
         * not depend on the parent's success.)
         */
        IMMEDIATELY,
    }

    /**
     * Whether a dispatch can trigger staking rewards to involved accounts.
     */
    public enum StakingRewards {
        /**
         * The dispatch can trigger staking rewards.
         */
        ON,
        /**
         * The dispatch cannot trigger staking rewards.
         */
        OFF,
    }

    /**
     * Whether the dispatch's {@link TransactionBody} should use a preset transaction ID
     * instead of receiving one at the end of the dispatch.
     */
    public enum UsePresetTxnId {
        /**
         * The dispatch's {@link TransactionBody} should include the expected transaction ID.
         */
        YES,
        /**
         * The dispatch's {@link TransactionBody} should not include the expected transaction ID.
         */
        NO,
    }

    /**
     * Whether a dispatch's custom fee charging strategy should propagate from the receiving handler.
     */
    public enum PropagateFeeChargingStrategy {
        /**
         * The receiving handler should propagate the custom fee charging strategy.
         */
        YES,
        /**
         * The receiving handler should not propagate the custom fee charging strategy.
         */
        NO,
    }

    public DispatchOptions {
        requireNonNull(commit);
        requireNonNull(payerId);
        requireNonNull(body);
        requireNonNull(usePresetTxnId);
        requireNonNull(keyVerifier);
        requireNonNull(category);
        requireNonNull(throttling);
        requireNonNull(authorizingKeys);
        requireNonNull(streamBuilderType);
        requireNonNull(reversingBehavior);
        requireNonNull(signedTxCustomizer);
        requireNonNull(dispatchMetadata);
    }

    /**
     * Returns the effective key verifier for the dispatch ({@code null} if all keys should be treated as authorized).
     */
    public @Nullable Predicate<Key> effectiveKeyVerifier() {
        return keyVerifier == PREAUTHORIZED_KEYS ? null : keyVerifier;
    }

    /**
     * Returns whether the dispatch should commit immediately.
     */
    public boolean commitImmediately() {
        return commit == Commit.IMMEDIATELY;
    }

    /**
     * Returns options for a dispatch that is logically independent of the parent dispatch. Use cases include,
     * <ul>
     *     <li>Completing a hollow account whose public key was provided in the signature map of
     *     a parent HAPI transaction.</li>
     *     <li>Doing post-upgrade setup before the first transaction handled after the upgrade.</li>
     * </ul>
     * <b>Important:</b> Since such dispatches are not logically part of the parent dispatch, they should
     * really be done as a separate top-level transaction. Prefer not introducing any further uses of this
     * {@link DispatchOptions} factory.
     *
     * @param payerId the account to pay for the dispatch
     * @param body the transaction to dispatch
     * @param streamBuilderType the type of stream builder to use for the dispatch
     * @return the options for the setup dispatch
     * @param <T> the type of stream builder to use for the dispatch
     */
    public static <T extends StreamBuilder> DispatchOptions<T> independentDispatch(
            @NonNull final AccountID payerId,
            @NonNull final TransactionBody body,
            @NonNull final Class<T> streamBuilderType) {
        return new DispatchOptions<>(
                Commit.IMMEDIATELY,
                payerId,
                body,
                UsePresetTxnId.NO,
                PREAUTHORIZED_KEYS,
                emptySet(),
                TransactionCategory.PRECEDING,
                ConsensusThrottling.OFF,
                streamBuilderType,
                ReversingBehavior.IRREVERSIBLE,
                NOOP_SIGNED_TX_CUSTOMIZER,
                EMPTY_METADATA,
                NOOP_FEE_CHARGING);
    }

    /**
     * Returns options for a dispatch that is part of the parent dispatch's transactional unit, but in a setup role
     * that logically precedes the parent transaction's effects. Use cases include,
     * <ul>
     *     <li>Externalizing creation of a "hollow" account prior to a successful EVM transaction that
     *     first sends value to its EVM address.</li>
     *     <li>Externalizing auto-creation of an aliased account that receives value in a parent
     *     {@link HederaFunctionality#CRYPTO_TRANSFER} transaction.</li>
     * </ul>
     *
     * @param <T> the type of stream builder to use for the dispatch
     * @param payerId the account to pay for the dispatch
     * @param body the transaction to dispatch
     * @param streamBuilderType the type of stream builder to use for the dispatch
     * @param customFeeCharging the custom fee charging strategy for the dispatch, if any
     * @return the options for the setup dispatch
     */
    public static <T extends StreamBuilder> DispatchOptions<T> setupDispatch(
            @NonNull final AccountID payerId,
            @NonNull final TransactionBody body,
            @NonNull final Class<T> streamBuilderType,
            @Nullable final FeeCharging customFeeCharging) {
        return new DispatchOptions<>(
                Commit.WITH_PARENT,
                payerId,
                body,
                UsePresetTxnId.NO,
                PREAUTHORIZED_KEYS,
                emptySet(),
                TransactionCategory.PRECEDING,
                ConsensusThrottling.ON,
                streamBuilderType,
                ReversingBehavior.REMOVABLE,
                NOOP_SIGNED_TX_CUSTOMIZER,
                EMPTY_METADATA,
                customFeeCharging);
    }

    /**
     * Returns options for a dispatch that is part of the parent dispatch's transactional unit, as a sub-transaction
     * that forms a logical step explaining the entirety of the parent transaction's effects; no matter if the parent
     * transaction succeeds or fails. Use cases include,
     * <ul>
     *     <li>Triggering a scheduled transaction.</li>
     *     <li>Dispatching a native Hedera transaction from within the EVM via a system contract.</li>
     * </ul>
     *
     * @param <T> the type of stream builder to use for the dispatch
     * @param payerId the account to pay for the dispatch
     * @param body the transaction to dispatch
     * @param keyVerifier the key verifier to use for the dispatch
     * @param authorizingKeys the set of keys authorizing the dispatch
     * @param streamBuilderType the type of stream builder to use for the dispatch
     * @param stakingRewards whether the dispatch can trigger staking rewards
     * @param usePresetTxnId whether the dispatch's {@link TransactionBody} should include the expected txn id
     * @param customFeeCharging the custom fee charging strategy for the dispatch
     * @param propagateFeeChargingStrategy whether the dispatch's custom fee charging strategy should propagate
     * @return the options for the sub-dispatch
     */
    public static <T extends StreamBuilder> DispatchOptions<T> subDispatch(
            @NonNull final AccountID payerId,
            @NonNull final TransactionBody body,
            @NonNull final Predicate<Key> keyVerifier,
            @NonNull final Set<Key> authorizingKeys,
            @NonNull final Class<T> streamBuilderType,
            @NonNull final StakingRewards stakingRewards,
            @NonNull final UsePresetTxnId usePresetTxnId,
            @NonNull final FeeCharging customFeeCharging,
            @NonNull final PropagateFeeChargingStrategy propagateFeeChargingStrategy) {
        requireNonNull(customFeeCharging);
        requireNonNull(propagateFeeChargingStrategy);
        final var category =
                switch (requireNonNull(stakingRewards)) {
                    case ON -> TransactionCategory.SCHEDULED;
                    case OFF -> TransactionCategory.CHILD;
                };
        final var metadata =
                switch (propagateFeeChargingStrategy) {
                    case YES -> new DispatchMetadata(CUSTOM_FEE_CHARGING, customFeeCharging);
                    case NO -> EMPTY_METADATA;
                };
        return new DispatchOptions<>(
                Commit.WITH_PARENT,
                payerId,
                body,
                usePresetTxnId,
                keyVerifier,
                authorizingKeys,
                category,
                ConsensusThrottling.ON,
                streamBuilderType,
                ReversingBehavior.REVERSIBLE,
                NOOP_SIGNED_TX_CUSTOMIZER,
                metadata,
                customFeeCharging);
    }

    /**
     * Returns options for a dispatch that is a step in the parent dispatch's business logic, but only appropriate
     * to externalize if the parent succeeds.
     * <ul>
     *     <li>Dispatching an internal contract creation in the EVM.</li>
     * </ul>
     *
     * @param payerId the account to pay for the dispatch
     * @param body the transaction to dispatch
     * @param streamBuilderType the type of stream builder to use for the dispatch
     * @param signedTxCustomizer the customizer for the transaction
     * @return the options for the sub-dispatch
     * @param <T> the type of stream builder to use for the dispatch
     */
    public static <T extends StreamBuilder> DispatchOptions<T> stepDispatch(
            @NonNull final AccountID payerId,
            @NonNull final TransactionBody body,
            @NonNull final Class<T> streamBuilderType,
            @NonNull final StreamBuilder.SignedTxCustomizer signedTxCustomizer) {
        return new DispatchOptions<>(
                Commit.WITH_PARENT,
                payerId,
                body,
                UsePresetTxnId.NO,
                PREAUTHORIZED_KEYS,
                emptySet(),
                TransactionCategory.CHILD,
                ConsensusThrottling.OFF,
                streamBuilderType,
                ReversingBehavior.REMOVABLE,
                signedTxCustomizer,
                EMPTY_METADATA,
                NOOP_FEE_CHARGING);
    }

    /**
     * Returns options for a dispatch that is a step in the parent dispatch's business logic, but only appropriate
     * to externalize if the parent succeeds.
     * <ul>
     *     <li>Charging a custom topic fee from inside the consensus service.</li>
     * </ul>
     *
     * @param payerId the account to pay for the dispatch
     * @param body the transaction to dispatch
     * @param streamBuilderType the type of stream builder to use for the dispatch
     * @param signedTxCustomizer the customizer for the transaction
     * @return the options for the sub-dispatch
     * @param <T> the type of stream builder to use for the dispatch
     */
    public static <T extends StreamBuilder> DispatchOptions<T> stepDispatch(
            @NonNull final AccountID payerId,
            @NonNull final TransactionBody body,
            @NonNull final Class<T> streamBuilderType,
            @NonNull final StreamBuilder.SignedTxCustomizer signedTxCustomizer,
            @NonNull final DispatchMetadata metaData) {
        return new DispatchOptions<>(
                Commit.WITH_PARENT,
                payerId,
                body,
                UsePresetTxnId.NO,
                PREAUTHORIZED_KEYS,
                emptySet(),
                TransactionCategory.CHILD,
                ConsensusThrottling.OFF,
                streamBuilderType,
                ReversingBehavior.REMOVABLE,
                signedTxCustomizer,
                metaData,
                NOOP_FEE_CHARGING);
    }

    /**
     * returns options for a dispatch for atomic batch transaction
     *
     * @param <T> the type of stream builder to use for the dispatch
     * @param payerId the account to pay for the dispatch
     * @param body the transaction to dispatch
     * @param streamBuilderType the type of stream builder to use for the dispatch
     * @param customFeeCharging the custom fee charging strategy for the dispatch
     * @param innerTransactionBytes inner txn bytes used for pre-handling on dispatch
     * @return the options for the atomic batch
     */
    public static <T extends StreamBuilder> DispatchOptions<T> atomicBatchDispatch(
            @NonNull final AccountID payerId,
            @NonNull final TransactionBody body,
            @NonNull final Class<T> streamBuilderType,
            @NonNull final FeeCharging customFeeCharging,
            @NonNull final DispatchMetadata innerTransactionBytes) {
        return new DispatchOptions<>(
                Commit.WITH_PARENT,
                payerId,
                body,
                UsePresetTxnId.NO,
                NO_AUTHORIZED_KEYS,
                emptySet(),
                TransactionCategory.BATCH_INNER,
                ConsensusThrottling.ON,
                streamBuilderType,
                ReversingBehavior.REVERSIBLE,
                NOOP_SIGNED_TX_CUSTOMIZER,
                innerTransactionBytes,
                customFeeCharging);
    }

    /**
     * Returns options for a dispatch that is a step in the parent dispatch's business logic, but only appropriate
     * to externalize if the parent succeeds. This is used for hook dispatches.
     * @param payerId the account to pay for the dispatch
     * @param body the transaction to dispatch
     * @param streamBuilderType the type of stream builder to use for the dispatch
     * @return the options for the sub-dispatch
     * @param <T> the type of stream builder to use for the dispatch
     */
    public static <T extends StreamBuilder> DispatchOptions<T> hookDispatch(
            @NonNull final AccountID payerId,
            @NonNull final TransactionBody body,
            @NonNull final Class<T> streamBuilderType) {
        return stepDispatch(payerId, body, streamBuilderType, SUPPRESSING_SIGNED_TX_CUSTOMIZER);
    }
}
