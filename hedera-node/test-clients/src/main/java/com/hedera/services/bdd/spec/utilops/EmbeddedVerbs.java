// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.CommonPbjConverters;
import com.hedera.node.app.throttle.ThrottleAccumulator;
import com.hedera.node.app.version.ServicesSoftwareVersion;
import com.hedera.node.app.workflows.TransactionInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.embedded.EmbeddedNetwork;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.embedded.MutateAccountOp;
import com.hedera.services.bdd.spec.utilops.embedded.MutateKVStateOp;
import com.hedera.services.bdd.spec.utilops.embedded.MutateNodeOp;
import com.hedera.services.bdd.spec.utilops.embedded.MutateScheduleCountsOp;
import com.hedera.services.bdd.spec.utilops.embedded.MutateSingletonOp;
import com.hedera.services.bdd.spec.utilops.embedded.MutateStakingInfosOp;
import com.hedera.services.bdd.spec.utilops.embedded.MutateTokenOp;
import com.hedera.services.bdd.spec.utilops.embedded.ViewAccountOp;
import com.hedera.services.bdd.spec.utilops.embedded.ViewKVStateOp;
import com.hedera.services.bdd.spec.utilops.embedded.ViewMappingValueOp;
import com.hedera.services.bdd.spec.utilops.embedded.ViewNodeOp;
import com.hedera.services.bdd.spec.utilops.embedded.ViewPendingAirdropOp;
import com.hedera.services.bdd.spec.utilops.embedded.ViewSingletonOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.WritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;

/**
 * Contains operations that are usable only with an {@link EmbeddedNetwork}.
 */
public final class EmbeddedVerbs {
    private EmbeddedVerbs() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns an operation that allows the test author to handle the next round in a repeatable embedded network,
     * typically useful to handle the query payment from a just-submitted transaction.
     */
    public static SpecOperation handleAnyRepeatableQueryPayment() {
        return doingContextual(spec -> spec.repeatableEmbeddedHederaOrThrow().handleNextRoundIfPresent());
    }

    /**
     * Returns an operation that allows the test author to directly mutate an account.
     *
     * @param name the name of the account to mutate
     * @param mutation the mutation to apply to the account
     * @return the operation that will mutate the account
     */
    public static MutateAccountOp mutateAccount(
            @NonNull final String name, @NonNull final Consumer<Account.Builder> mutation) {
        return new MutateAccountOp(name, mutation);
    }

    /**
     * Returns an operation that allows the test author to directly mutate a token.
     *
     * @param name the identifier of the token to mutate
     * @param mutation the mutation to apply to the token
     * @return the operation that will mutate the token
     */
    public static MutateTokenOp mutateToken(
            @NonNull final String name, @NonNull final Consumer<Token.Builder> mutation) {
        return new MutateTokenOp(name, mutation);
    }

    /**
     * Returns an operation that allows the test author to directly mutate the schedule counts.
     *
     * @param mutation the mutation to apply to the schedule counts
     * @return the operation that will mutate the schedule counts
     */
    public static MutateScheduleCountsOp mutateScheduleCounts(
            @NonNull final Consumer<WritableKVState<TimestampSeconds, ScheduledCounts>> mutation) {
        return new MutateScheduleCountsOp(mutation);
    }

    /**
     * Returns an operation that allows the test author to directly mutate the staking infos.
     *
     * @param mutation the mutation to apply to the staking infos
     * @return the operation that will mutate the staking infos
     */
    public static MutateStakingInfosOp mutateStakingInfos(
            final String nodeId, @NonNull final Consumer<StakingNodeInfo.Builder> mutation) {
        return new MutateStakingInfosOp(nodeId, mutation);
    }

    /**
     * Returns an operation that allows the test author to directly mutate an account.
     *
     * @param name the name of the account to mutate
     * @param observer the mutation to apply to the account
     * @return the operation that will mutate the account
     */
    public static ViewAccountOp viewAccount(@NonNull final String name, @NonNull final Consumer<Account> observer) {
        return new ViewAccountOp(name, observer);
    }

    /**
     * Returns an operation that allows the test author to view a singleton record in an embedded state.
     * @param serviceName the name of the service that manages the record
     * @param stateKey the key of the record in the state
     * @param observer the observer that will receive the record
     * @return the operation that will expose the record to the observer
     * @param <T> the type of the record
     */
    public static <T> ViewSingletonOp<T> viewSingleton(
            @NonNull final String serviceName, @NonNull final String stateKey, @NonNull final Consumer<T> observer) {
        requireNonNull(serviceName);
        requireNonNull(stateKey);
        requireNonNull(observer);
        return new ViewSingletonOp<>(serviceName, stateKey, observer);
    }

    /**
     * Returns an operation that allows the test author to mutate a singleton record in an embedded state.
     * @param serviceName the name of the service that manages the record
     * @param stateKey the key of the record in the state
     * @param mutator the observer that will receive the record
     * @return the operation that will expose the record to the mutator
     * @param <T> the type of the record
     */
    public static <T> MutateSingletonOp<T> mutateSingleton(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final UnaryOperator<T> mutator) {
        requireNonNull(serviceName);
        requireNonNull(stateKey);
        requireNonNull(mutator);
        return new MutateSingletonOp<>(serviceName, stateKey, mutator);
    }

    /**
     * Returns an operation that allows the test author to view a key/value state an embedded state.
     * @param serviceName the name of the service that manages the mapping
     * @param stateKey the mapping state key
     *
     * @param observer the observer that will receive the key/value state
     * @return the operation that will expose the mapped value to the observer
     * @param <K> the type of the key
     * @param <V> the type of the value
     */
    public static <K, V> ViewKVStateOp<K, V> viewKVState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Consumer<ReadableKVState<K, V>> observer) {
        requireNonNull(serviceName);
        requireNonNull(stateKey);
        requireNonNull(observer);
        return new ViewKVStateOp<>(serviceName, stateKey, observer);
    }

    /**
     * Returns an operation that allows the test author to mutate a key/value state an embedded state.
     * @param serviceName the name of the service that manages the mapping
     * @param stateKey the mapping state key
     *
     * @param observer the consumer that will receive the key/value state
     * @return the operation that will expose the mapped value to the observer
     * @param <K> the type of the key
     * @param <V> the type of the value
     */
    public static <K, V> MutateKVStateOp<K, V> mutateKVState(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final Consumer<WritableKVState<K, V>> observer) {
        requireNonNull(serviceName);
        requireNonNull(stateKey);
        requireNonNull(observer);
        return new MutateKVStateOp<>(serviceName, stateKey, observer);
    }

    /**
     * Returns an operation that allows the test author to view a key's mapped value in an embedded state.
     * @param serviceName the name of the service that manages the mapping
     * @param stateKey the mapping state key
     *
     * @param observer the observer that will receive the value
     * @return the operation that will expose the mapped value to the observer
     * @param <K> the type of the key
     * @param <V> the type of the value
     */
    public static <K, V> ViewMappingValueOp<K, V> viewMappedValue(
            @NonNull final String serviceName,
            @NonNull final String stateKey,
            @NonNull final K key,
            @NonNull final Consumer<V> observer) {
        requireNonNull(serviceName);
        requireNonNull(stateKey);
        requireNonNull(key);
        requireNonNull(observer);
        return new ViewMappingValueOp<>(serviceName, stateKey, key, observer);
    }

    /**
     * Returns an operation that allows the test author to directly mutate an account.
     *
     * @param name the name of the account to mutate
     * @param mutation the mutation to apply to the account
     * @return the operation that will mutate the account
     */
    public static MutateNodeOp mutateNode(@NonNull final String name, @NonNull final Consumer<Node.Builder> mutation) {
        return new MutateNodeOp(name, mutation);
    }

    /**
     * Returns an operation that allows the test author to view a node.
     *
     * @param name the name of the node to view
     * @param observer the mutation to receive the node
     * @return the operation that will expose the node to the observer
     */
    public static ViewNodeOp viewNode(@NonNull final String name, @NonNull final Consumer<Node> observer) {
        return new ViewNodeOp(name, observer);
    }

    /**
     * Returns an operation that exposes the maximum number of the given functionality that can be scheduled
     * in a single consensus second with the embedded network's active throttles and configuration.
     * @param function the functionality to check
     * @param observer the observer to receive to the maximum number of transactions
     * @return the operation that will expose the maximum number of transactions
     */
    public static SpecOperation exposeMaxSchedulable(
            @NonNull final HederaFunctionality function, @NonNull final IntConsumer observer) {
        requireNonNull(function);
        requireNonNull(observer);
        return doingContextual(spec -> {
            final var properties = spec.startupProperties();
            final var capacityUtilization = properties.getScaleFactor("scheduling.schedulableCapacityFraction");
            final var hedera = spec.embeddedHederaOrThrow().hedera();
            final var throttleAccumulator = new ThrottleAccumulator(
                    hedera.configProvider()::getConfiguration,
                    capacityUtilization::asApproxCapacitySplit,
                    ThrottleAccumulator.ThrottleType.BACKEND_THROTTLE,
                    v -> new ServicesSoftwareVersion());
            throttleAccumulator.applyGasConfig();
            throttleAccumulator.rebuildFor(hedera.activeThrottleDefinitions());
            final var now = spec.consensusTime();
            final var state = spec.embeddedStateOrThrow();
            final var pbjFunction = CommonPbjConverters.toPbj(function);
            final var throttledPayerId = AccountID.newBuilder()
                    .accountNum(properties.getLong("accounts.lastThrottleExempt") + 1)
                    .build();
            final var txnInfo = new TransactionInfo(
                    Transaction.DEFAULT,
                    TransactionBody.DEFAULT,
                    TransactionID.DEFAULT,
                    throttledPayerId,
                    SignatureMap.DEFAULT,
                    Bytes.EMPTY,
                    pbjFunction,
                    null);
            int numSchedulable = 0;
            for (; !throttleAccumulator.checkAndEnforceThrottle(txnInfo, now, state); numSchedulable++) {
                // Count until we are throttled
            }
            observer.accept(numSchedulable);
        });
    }

    /***
     * Returns an operation that allows the test author to view the pending airdrop of an account.
     * @param tokenName the name of the token
     * @param senderName the name of the sender
     * @param receiverName the name of the receiver
     * @param observer the observer to apply to the account
     * @return the operation that will expose the pending airdrop of the account
     */
    public static ViewPendingAirdropOp viewAccountPendingAirdrop(
            @NonNull final String tokenName,
            @NonNull final String senderName,
            @NonNull final String receiverName,
            @NonNull final Consumer<AccountPendingAirdrop> observer) {
        return new ViewPendingAirdropOp(tokenName, senderName, receiverName, observer);
    }

    /**
     * Returns an operation that sleeps until the given instant when in repeatable mode.
     * @param then the instant to sleep until
     * @return the operation that will sleep until the given instant in repeatable mode
     */
    public static SpecOperation sleepToExactly(@NonNull final Instant then) {
        return doingContextual(spec -> {
            final var embeddedHedera = spec.repeatableEmbeddedHederaOrThrow();
            embeddedHedera.tick(Duration.between(spec.consensusTime(), then));
        });
    }

    /**
     * Returns an operation that changes the state of an embedded network to appear to be handling
     * the first transaction after an upgrade.
     *
     * @return the operation that simulates the first transaction after an upgrade
     */
    public static SpecOperation simulatePostUpgradeTransaction() {
        return withOpContext((spec, opLog) -> {
            if (spec.targetNetworkOrThrow() instanceof EmbeddedNetwork embeddedNetwork) {
                // Ensure there are no in-flight transactions that will overwrite our state changes
                spec.sleepConsensusTime(Duration.ofSeconds(1));
                final var embeddedHedera = embeddedNetwork.embeddedHederaOrThrow();
                final var fakeState = embeddedHedera.state();
                final var streamMode = spec.startupProperties().getStreamMode("blockStream.streamMode");
                if (streamMode == RECORDS) {
                    // Mark the migration records as not streamed
                    final var writableStates = fakeState.getWritableStates("BlockRecordService");
                    final var blockInfo = writableStates.<BlockInfo>getSingleton("BLOCKS");
                    blockInfo.put(requireNonNull(blockInfo.get())
                            .copyBuilder()
                            .migrationRecordsStreamed(false)
                            .build());
                    ((CommittableWritableStates) writableStates).commit();
                    // Ensure the next transaction is in a new round with ConcurrentEmbeddedHedera
                    spec.sleepConsensusTime(Duration.ofMillis(10L));
                } else {
                    final var writableStates = fakeState.getWritableStates("BlockStreamService");
                    final var state = writableStates.<BlockStreamInfo>getSingleton("BLOCK_STREAM_INFO");
                    final var blockStreamInfo = requireNonNull(state.get());
                    state.put(blockStreamInfo
                            .copyBuilder()
                            .postUpgradeWorkDone(false)
                            .build());
                    ((CommittableWritableStates) writableStates).commit();
                }
                // Ensure the next transaction is in a new block period
                spec.sleepConsensusTime(Duration.ofSeconds(2L));
            } else {
                throw new IllegalStateException("Cannot simulate post-upgrade transaction on non-embedded network");
            }
        });
    }
}
