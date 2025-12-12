// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.selfDestructBeneficiariesFor;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.synthHollowAccountCreation;
import static com.hedera.node.app.spi.fees.NoopFeeCharging.DISPATCH_ONLY_NOOP_FEE_CHARGING;
import static com.hedera.node.app.spi.workflows.DispatchOptions.independentChildDispatch;
import static com.hedera.node.app.spi.workflows.DispatchOptions.setupDispatch;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.schedule.ScheduleServiceApi;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.SortedSet;
import javax.inject.Inject;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * A fully-mutable {@link HederaNativeOperations} implemented with a {@link HandleContext}.
 */
@TransactionScope
public class HandleHederaNativeOperations implements HederaNativeOperations {
    private final HandleContext context;

    @Nullable
    private final Key maybeEthSenderKey;

    private final EntityIdFactory entityIdFactory;

    @Inject
    public HandleHederaNativeOperations(
            @NonNull final HandleContext context,
            @Nullable final Key maybeEthSenderKey,
            @NonNull final EntityIdFactory entityIdFactory) {
        this.context = requireNonNull(context);
        this.maybeEthSenderKey = maybeEthSenderKey;
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReadableNftStore readableNftStore() {
        return context.storeFactory().readableStore(ReadableNftStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReadableTokenRelationStore readableTokenRelationStore() {
        return context.storeFactory().readableStore(ReadableTokenRelationStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReadableTokenStore readableTokenStore() {
        return context.storeFactory().readableStore(ReadableTokenStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReadableAccountStore readableAccountStore() {
        return context.storeFactory().readableStore(ReadableAccountStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReadableScheduleStore readableScheduleStore() {
        return context.storeFactory().readableStore(ReadableScheduleStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    public WritableEvmHookStore writableEvmHookStore() {
        return context.storeFactory().writableStore(WritableEvmHookStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setNonce(final long contractNumber, final long nonce) {
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        final var hederaConfig = context.configuration().getConfigData(HederaConfig.class);
        tokenServiceApi.setNonce(
                AccountID.newBuilder()
                        .shardNum(hederaConfig.shard())
                        .realmNum(hederaConfig.realm())
                        .accountNum(contractNumber)
                        .build(),
                nonce);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ResponseCodeEnum createHollowAccount(
            @NonNull final Bytes evmAddress, @NonNull final Bytes delegationAddress) {
        final boolean unlimitedAutoAssociations =
                context.configuration().getConfigData(EntitiesConfig.class).unlimitedAutoAssociationsEnabled();
        final var synthTxn = TransactionBody.newBuilder()
                .cryptoCreateAccount(
                        synthHollowAccountCreation(evmAddress, unlimitedAutoAssociations, delegationAddress))
                .build();

        try {
            return context.dispatch(
                            dispatchOptionsOf(!delegationAddress.equals(Bytes.EMPTY), context.payer(), synthTxn))
                    .status();
        } catch (HandleException e) {
            // It is critically important we don't let HandleExceptions propagate to the workflow because
            // it doesn't rollback for contract operations so we can commit gas charges; that is, the
            // EVM transaction should always either run to completion or (if it must) throw an internal
            // failure like an IllegalArgumentException---but not a HandleException!
            return e.getStatus();
        }
    }

    /**
     * Returns the {@link DispatchOptions} to use.  If the transaction has a delegation address, it will
     * dispatch an independent transaction that immediately writes to state but will create a child record.
     * Otherwise, it will dispatch a transaction that is linked to the parent transaction and any state changes will
     * be rolled back if the parent transaction fails.
     *
     * @param hasDelegationAddress whether the transaction has a delegation address
     * @param payerId the ID of the account that will pay for the transaction
     * @param body the transaction body to dispatch
     * @return the dispatch options to use for the dispatch
     */
    private static DispatchOptions<CryptoCreateStreamBuilder> dispatchOptionsOf(
            final boolean hasDelegationAddress, @NonNull final AccountID payerId, @NonNull final TransactionBody body) {
        if (hasDelegationAddress) {
            return independentChildDispatch(payerId, body, CryptoCreateStreamBuilder.class);
        } else {
            return setupDispatch(payerId, body, CryptoCreateStreamBuilder.class, DISPATCH_ONLY_NOOP_FEE_CHARGING);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void finalizeHollowAccountAsContract(@NonNull final Bytes evmAddress) {
        requireNonNull(evmAddress);
        final var accountStore = context.storeFactory().readableStore(ReadableAccountStore.class);
        final var config = context.configuration().getConfigData(HederaConfig.class);
        final var hollowAccountId =
                requireNonNull(accountStore.getAccountIDByAlias(config.shard(), config.realm(), evmAddress));
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        tokenServiceApi.finalizeHollowAccountAsContract(hollowAccountId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canScheduleContractCall(final long expiry, final long gasLimit, @NonNull final AccountID payerId) {
        requireNonNull(payerId);
        final var scheduleServiceApi = context.storeFactory().serviceApi(ScheduleServiceApi.class);
        return scheduleServiceApi.hasContractCallCapacity(expiry, context.consensusNow(), gasLimit, payerId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ResponseCodeEnum transferWithReceiverSigCheck(
            final long amount,
            final AccountID fromEntityId,
            final AccountID toEntityId,
            @NonNull final VerificationStrategy strategy) {
        final var to = requireNonNull(getAccount(toEntityId));
        final var signatureTest = strategy.asSignatureTestIn(context, maybeEthSenderKey);
        if (to.receiverSigRequired() && !signatureTest.test(to.keyOrThrow())) {
            return INVALID_SIGNATURE;
        }
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        tokenServiceApi.transferFromTo(fromEntityId, toEntityId, amount);
        return OK;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void trackSelfDestructBeneficiary(
            final AccountID deletedId, final AccountID beneficiaryId, @NonNull final MessageFrame frame) {
        requireNonNull(frame);
        selfDestructBeneficiariesFor(frame).addBeneficiaryForDeletedAccount(deletedId, beneficiaryId);
    }

    @Override
    public boolean checkForCustomFees(@NonNull final CryptoTransferTransactionBody op) {
        final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
        return tokenServiceApi.checkForCustomFees(op);
    }

    @Override
    @NonNull
    public SortedSet<Key> authorizingSimpleKeys() {
        return context.keyVerifier().authorizingSimpleKeys();
    }

    @Override
    public TransactionID getTransactionID() {
        return context.body().transactionIDOrThrow();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public EntityIdFactory entityIdFactory() {
        return entityIdFactory;
    }

    @Override
    public Configuration configuration() {
        return context.configuration();
    }
}
