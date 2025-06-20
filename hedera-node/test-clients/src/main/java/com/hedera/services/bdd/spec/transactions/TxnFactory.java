// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions;

import static com.hedera.services.bdd.spec.HapiSpec.UTF8Mode.TRUE;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.getUniqueTimestampPlusSecs;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.hedera.hapi.platform.event.legacy.StateSignatureTransaction;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.utilops.mod.BodyMutation;
import com.hederahashgraph.api.proto.java.AtomicBatchTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusCreateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusDeleteTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusSubmitMessageTransactionBody;
import com.hederahashgraph.api.proto.java.ConsensusUpdateTopicTransactionBody;
import com.hederahashgraph.api.proto.java.ContractCallTransactionBody;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ContractDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ContractUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoApproveAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.EthereumTransactionBody;
import com.hederahashgraph.api.proto.java.FileAppendTransactionBody;
import com.hederahashgraph.api.proto.java.FileCreateTransactionBody;
import com.hederahashgraph.api.proto.java.FileDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.FreezeTransactionBody;
import com.hederahashgraph.api.proto.java.NodeCreateTransactionBody;
import com.hederahashgraph.api.proto.java.NodeDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.NodeUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.RealmID;
import com.hederahashgraph.api.proto.java.ScheduleCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleSignTransactionBody;
import com.hederahashgraph.api.proto.java.ShardID;
import com.hederahashgraph.api.proto.java.SystemDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.SystemUndeleteTransactionBody;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenAirdropTransactionBody;
import com.hederahashgraph.api.proto.java.TokenAssociateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenBurnTransactionBody;
import com.hederahashgraph.api.proto.java.TokenCancelAirdropTransactionBody;
import com.hederahashgraph.api.proto.java.TokenClaimAirdropTransactionBody;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.TokenDissociateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenFeeScheduleUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenFreezeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.TokenGrantKycTransactionBody;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.hederahashgraph.api.proto.java.TokenPauseTransactionBody;
import com.hederahashgraph.api.proto.java.TokenRejectTransactionBody;
import com.hederahashgraph.api.proto.java.TokenRevokeKycTransactionBody;
import com.hederahashgraph.api.proto.java.TokenUnfreezeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.TokenUnpauseTransactionBody;
import com.hederahashgraph.api.proto.java.TokenUpdateNftsTransactionBody;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenWipeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.UncheckedSubmitBody;
import com.hederahashgraph.api.proto.java.UtilPrngTransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Used by a {@link HapiSpec} to create transactions for submission to its target network.
 */
public class TxnFactory {
    private static final int MEMO_PREFIX_LIMIT = 100;
    private static final double TXN_ID_SAMPLE_PROBABILITY = 1.0 / 500;

    private final HapiSpecSetup setup;
    private final Supplier<TransactionID> nextTxnId;
    private final SplittableRandom r = new SplittableRandom();
    private final AtomicReference<TransactionID> sampleTxnId =
            new AtomicReference<>(TransactionID.getDefaultInstance());

    public TxnFactory(@NonNull final HapiSpecSetup setup) {
        this(setup, () -> getUniqueTimestampPlusSecs(setup.txnStartOffsetSecs()));
    }

    public TxnFactory(@NonNull final HapiSpecSetup setup, @NonNull final Supplier<Timestamp> nextValidStart) {
        this.setup = requireNonNull(setup);
        this.nextTxnId = defaultNextTxnIdFor(setup, nextValidStart);
    }

    /**
     * Return the next transaction ID this factory would have used in a transaction.
     *
     * @return the next transaction ID
     */
    public TransactionID nextTxnId() {
        return nextTxnId.get();
    }

    /**
     * Returns a recently used transaction ID, useful for constructing random queries in
     * fuzzing tests.
     *
     * @return a recently used transaction ID
     */
    public TransactionID sampleRecentTxnId() {
        return sampleTxnId.get();
    }

    /**
     * Given a {@link Consumer} that mutates a {@link TransactionBody.Builder}, return a {@link Transaction.Builder}
     * that incorporates this consumer along with default values for some notable
     * {@link com.hedera.hapi.node.transaction.TransactionBody} fields if not overridden by the consumer.
     *
     * <p>The fields given default values are,
     * <ol>
     *     <li>{@link com.hedera.hapi.node.transaction.TransactionBody#transactionID()}</li>
     *     <li>{@link com.hedera.hapi.node.transaction.TransactionBody#nodeAccountID()}</li>
     *     <li>{@link com.hedera.hapi.node.transaction.TransactionBody#transactionFee()}</li>
     *     <li>{@link com.hedera.hapi.node.transaction.TransactionBody#transactionValidDuration()}</li>
     *     <li>{@link com.hedera.hapi.node.transaction.TransactionBody#memo()}</li>
     * </ol>
     *
     * @param bodySpec the {@link Consumer} that mutates the {@link TransactionBody.Builder}
     * @param modification if non-null, the {@link BodyMutation} that is used to mutate the {@link TransactionBody.Builder}
     * @param spec if non-null, the {@link HapiSpec} that is used to mutate the {@link TransactionBody.Builder}
     * @return a {@link Transaction.Builder} that is ready to be signed
     */
    public Transaction.Builder getReadyToSign(
            @NonNull final Consumer<TransactionBody.Builder> bodySpec,
            @Nullable final BodyMutation modification,
            @Nullable final HapiSpec spec) {
        requireNonNull(bodySpec);
        final var composedBodySpec =
                defaultBodySpec(spec == null ? null : spec.getName()).andThen(bodySpec);
        var bodyBuilder = TransactionBody.newBuilder();
        composedBodySpec.accept(bodyBuilder);
        if (modification != null) {
            requireNonNull(spec);
            bodyBuilder = modification.apply(bodyBuilder, spec);
        }
        return Transaction.newBuilder()
                .setBodyBytes(ByteString.copyFrom(bodyBuilder.build().toByteArray()));
    }

    /**
     * Given a {@link Class} of a {@link TransactionBody} and a {@link Consumer} that mutates a
     * {@link Message.Builder}, return a {@link com.google.protobuf.Message} that incorporates this
     * consumer along with default values for some notable fields if not overridden by the consumer.
     *
     * @param tClass the {@link Class} of the {@link TransactionBody}
     * @param def the {@link Consumer} that mutates the {@link Message.Builder}
     * @return a {@link com.google.protobuf.Message} that is ready to be signed
     * @param <T> the type of the {@link com.google.protobuf.Message}
     * @param <B> the type of the {@link Message.Builder}
     * @throws NoSuchMethodException if there is no such body
     * @throws InvocationTargetException if there is no such body
     * @throws IllegalAccessException if there is no such body
     */
    @SuppressWarnings("unchecked")
    public <T, B extends Message.Builder> T body(@NonNull final Class<T> tClass, @NonNull final Consumer<B> def)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method newBuilder = tClass.getMethod("newBuilder");
        B opBuilder = (B) newBuilder.invoke(null);
        String defaultBodyMethod = String.format("defaultDef%s", tClass.getSimpleName());
        Method defaultBody = this.getClass().getMethod(defaultBodyMethod);
        ((Consumer<B>) defaultBody.invoke(this)).andThen(def).accept(opBuilder);
        return (T) opBuilder.build();
    }

    private Consumer<TransactionBody.Builder> defaultBodySpec(@Nullable final String specName) {
        final var defaultTxnId = nextTxnId.get();
        if (r.nextDouble() < TXN_ID_SAMPLE_PROBABILITY) {
            sampleTxnId.set(defaultTxnId);
        }
        final var memoToUse = (specName != null && setup.useSpecName())
                ? specName.substring(0, Math.min(specName.length(), MEMO_PREFIX_LIMIT))
                : (setup.isMemoUTF8() == TRUE ? setup.defaultUTF8memo() : setup.defaultMemo());
        return builder -> builder.setTransactionID(defaultTxnId)
                .setMemo(memoToUse)
                .setTransactionFee(setup.defaultFee())
                .setTransactionValidDuration(setup.defaultValidDuration())
                .setNodeAccountID(setup.defaultNode());
    }

    public Consumer<TokenAssociateTransactionBody.Builder> defaultDefTokenAssociateTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenDissociateTransactionBody.Builder> defaultDefTokenDissociateTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenWipeAccountTransactionBody.Builder> defaultDefTokenWipeAccountTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenRevokeKycTransactionBody.Builder> defaultDefTokenRevokeKycTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenGrantKycTransactionBody.Builder> defaultDefTokenGrantKycTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenBurnTransactionBody.Builder> defaultDefTokenBurnTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenMintTransactionBody.Builder> defaultDefTokenMintTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenDeleteTransactionBody.Builder> defaultDefTokenDeleteTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenFreezeAccountTransactionBody.Builder> defaultDefTokenFreezeAccountTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenUnfreezeAccountTransactionBody.Builder> defaultDefTokenUnfreezeAccountTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenUpdateTransactionBody.Builder> defaultDefTokenUpdateTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenUpdateNftsTransactionBody.Builder> defaultDefTokenUpdateNftsTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenPauseTransactionBody.Builder> defaultDefTokenPauseTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenUnpauseTransactionBody.Builder> defaultDefTokenUnpauseTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenCreateTransactionBody.Builder> defaultDefTokenCreateTransactionBody() {
        return builder -> {
            builder.setTreasury(setup.defaultPayer());
            builder.setDecimals(setup.defaultTokenDecimals());
            builder.setInitialSupply(setup.defaultTokenInitialSupply());
            builder.setSymbol(TxnUtils.randomUppercase(8));
        };
    }

    public Consumer<UncheckedSubmitBody.Builder> defaultDefUncheckedSubmitBody() {
        return builder -> {};
    }

    public Consumer<ConsensusSubmitMessageTransactionBody.Builder> defaultDefConsensusSubmitMessageTransactionBody() {
        return builder -> {
            builder.setMessage(
                    ByteString.copyFrom(setup.defaultConsensusMessage().getBytes()));
        };
    }

    public Consumer<ConsensusUpdateTopicTransactionBody.Builder> defaultDefConsensusUpdateTopicTransactionBody() {
        return builder -> {};
    }

    public Consumer<ConsensusCreateTopicTransactionBody.Builder> defaultDefConsensusCreateTopicTransactionBody() {
        return builder -> {
            builder.setAutoRenewPeriod(setup.defaultAutoRenewPeriod());
        };
    }

    public Consumer<ConsensusDeleteTopicTransactionBody.Builder> defaultDefConsensusDeleteTopicTransactionBody() {
        return builder -> {};
    }

    public Consumer<FreezeTransactionBody.Builder> defaultDefFreezeTransactionBody() {
        return builder -> {};
    }

    public Consumer<FileDeleteTransactionBody.Builder> defaultDefFileDeleteTransactionBody() {
        return builder -> {};
    }

    public Consumer<ContractDeleteTransactionBody.Builder> defaultDefContractDeleteTransactionBody() {
        return builder -> {
            builder.setTransferAccountID(setup.defaultTransfer());
        };
    }

    public Consumer<ContractUpdateTransactionBody.Builder> defaultDefContractUpdateTransactionBody() {
        return builder -> {};
    }

    public Consumer<ContractCallTransactionBody.Builder> defaultDefContractCallTransactionBody() {
        return builder -> builder.setGas(setup.defaultCallGas());
    }

    public Consumer<EthereumTransactionBody.Builder> defaultDefEthereumTransactionBody() {
        return builder -> builder.setMaxGasAllowance(setup.defaultCallGas());
    }

    public Consumer<ContractCreateTransactionBody.Builder> defaultDefContractCreateTransactionBody() {
        return builder -> builder.setAutoRenewPeriod(setup.defaultAutoRenewPeriod())
                .setGas(setup.defaultCreateGas())
                .setInitialBalance(setup.defaultContractBalance())
                .setMemo(setup.defaultMemo())
                .setShardID(shardID())
                .setRealmID(realmID());
    }

    public Consumer<FileCreateTransactionBody.Builder> defaultDefFileCreateTransactionBody() {
        return builder -> builder.setRealmID(realmID())
                .setShardID(shardID())
                .setContents(ByteString.copyFrom(setup.defaultFileContents()));
    }

    public Consumer<NodeCreateTransactionBody.Builder> defaultDefNodeCreateTransactionBody() {
        return builder -> builder.setAccountId(setup.defaultPayer())
                .addGossipEndpoint(setup.defaultGossipEndpointInternal())
                .addGossipEndpoint(setup.defaultGossipEndpointExternal())
                .addServiceEndpoint(setup.defaultServiceEndpoint())
                .setGossipCaCertificate(ByteString.copyFrom(setup.defaultGossipCaCertificate()));
    }

    public Consumer<NodeUpdateTransactionBody.Builder> defaultDefNodeUpdateTransactionBody() {
        return builder -> {};
    }

    public Consumer<NodeDeleteTransactionBody.Builder> defaultDefNodeDeleteTransactionBody() {
        return builder -> {};
    }

    public Consumer<FileAppendTransactionBody.Builder> defaultDefFileAppendTransactionBody() {
        return builder -> builder.setContents(ByteString.copyFrom(setup.defaultFileContents()));
    }

    public Consumer<FileUpdateTransactionBody.Builder> defaultDefFileUpdateTransactionBody() {
        return builder -> {};
    }

    public Consumer<StateSignatureTransaction.Builder> defaultDefStateSignatureTransaction() {
        return builder -> {};
    }

    /**
     * Returns a {@link Timestamp} that is the default expiry time for an entity being created
     * in the given spec context.
     *
     * @param spec the context in which the expiry time is being calculated
     * @return the default expiry time
     */
    public static Timestamp defaultExpiryNowFor(@NonNull final HapiSpec spec) {
        return Timestamp.newBuilder()
                .setSeconds(spec.consensusTime().getEpochSecond() + spec.setup().defaultExpirationSecs())
                .build();
    }

    /**
     * Returns a {@link Timestamp} that is the expiry time for an entity being created
     * in the given spec context with the given lifetime.
     *
     * @param spec the context in which the expiry time is being calculated
     * @param lifetime the lifetime of the entity
     * @return the default expiry time
     */
    public static Timestamp expiryNowFor(@NonNull final HapiSpec spec, final long lifetime) {
        return Timestamp.newBuilder()
                .setSeconds(spec.consensusTime().getEpochSecond() + lifetime)
                .build();
    }

    public Consumer<CryptoDeleteTransactionBody.Builder> defaultDefCryptoDeleteTransactionBody() {
        return builder -> builder.setTransferAccountID(setup.defaultTransfer());
    }

    public Consumer<SystemDeleteTransactionBody.Builder> defaultDefSystemDeleteTransactionBody() {
        return builder -> {};
    }

    public Consumer<SystemUndeleteTransactionBody.Builder> defaultDefSystemUndeleteTransactionBody() {
        return builder -> {};
    }

    public Consumer<CryptoCreateTransactionBody.Builder> defaultDefCryptoCreateTransactionBody() {
        return builder -> builder.setInitialBalance(setup.defaultBalance())
                .setAutoRenewPeriod(setup.defaultAutoRenewPeriod())
                .setReceiverSigRequired(setup.defaultReceiverSigRequired());
    }

    public Consumer<CryptoUpdateTransactionBody.Builder> defaultDefCryptoUpdateTransactionBody() {
        return builder -> {};
    }

    public Consumer<CryptoTransferTransactionBody.Builder> defaultDefCryptoTransferTransactionBody() {
        return builder -> {};
    }

    public Consumer<CryptoApproveAllowanceTransactionBody.Builder> defaultDefCryptoApproveAllowanceTransactionBody() {
        return builder -> {};
    }

    public Consumer<CryptoDeleteAllowanceTransactionBody.Builder> defaultDefCryptoDeleteAllowanceTransactionBody() {
        return builder -> {};
    }

    public Consumer<ScheduleCreateTransactionBody.Builder> defaultDefScheduleCreateTransactionBody() {
        return builder -> {};
    }

    public Consumer<ScheduleSignTransactionBody.Builder> defaultDefScheduleSignTransactionBody() {
        return builder -> {};
    }

    public Consumer<ScheduleDeleteTransactionBody.Builder> defaultDefScheduleDeleteTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenFeeScheduleUpdateTransactionBody.Builder> defaultDefTokenFeeScheduleUpdateTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenRejectTransactionBody.Builder> defaultDefTokenRejectTransactionBody() {
        return builder -> {};
    }

    public Consumer<UtilPrngTransactionBody.Builder> defaultDefUtilPrngTransactionBody() {
        return builder -> {};
    }

    private static Supplier<TransactionID> defaultNextTxnIdFor(
            @NonNull final HapiSpecSetup setup, @NonNull final Supplier<Timestamp> nextValidStart) {
        requireNonNull(setup);
        requireNonNull(nextValidStart);
        return () -> TransactionID.newBuilder()
                .setTransactionValidStart(nextValidStart.get())
                .setAccountID(setup.defaultPayer())
                .build();
    }

    public Consumer<TokenCancelAirdropTransactionBody.Builder> defaultDefTokenCancelAirdropTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenClaimAirdropTransactionBody.Builder> defaultDefTokenClaimAirdropTransactionBody() {
        return builder -> {};
    }

    public Consumer<TokenAirdropTransactionBody.Builder> defaultDefTokenAirdropTransactionBody() {
        return builder -> {};
    }

    public Consumer<AtomicBatchTransactionBody.Builder> defaultDefAtomicBatchTransactionBody() {
        return builder -> {};
    }

    private ShardID shardID() {
        return ShardID.newBuilder().setShardNum(setup.shard()).build();
    }

    private RealmID realmID() {
        return RealmID.newBuilder().setRealmNum(setup.realm()).build();
    }
}
