// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.contract.impl.annotations.QueryScope;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * A read-only {@link HederaNativeOperations} based on a {@link QueryContext}.
 */
@QueryScope
public class QueryHederaNativeOperations implements HederaNativeOperations {
    private final QueryContext context;

    private final EntityIdFactory entityIdFactory;

    @Override
    public boolean checkForCustomFees(@NonNull final CryptoTransferTransactionBody op) {
        throw new UnsupportedOperationException("Cannot dispatch child transfers in query context");
    }

    @Inject
    public QueryHederaNativeOperations(
            @NonNull final QueryContext context, @NonNull final EntityIdFactory entityIdFactory) {
        this.context = Objects.requireNonNull(context);
        this.entityIdFactory = Objects.requireNonNull(entityIdFactory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReadableNftStore readableNftStore() {
        return context.createStore(ReadableNftStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReadableTokenRelationStore readableTokenRelationStore() {
        return context.createStore(ReadableTokenRelationStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReadableTokenStore readableTokenStore() {
        return context.createStore(ReadableTokenStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReadableAccountStore readableAccountStore() {
        return context.createStore(ReadableAccountStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull ReadableScheduleStore readableScheduleStore() {
        return context.createStore(ReadableScheduleStore.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull WritableEvmHookStore writableEvmHookStore() {
        return context.createStore(WritableEvmHookStore.class);
    }

    /**
     * Refuses to set the nonce of a contract.
     *
     * @param contractNumber the contract number
     * @param nonce          the new nonce
     * @throws UnsupportedOperationException always
     */
    @Override
    public void setNonce(final long contractNumber, final long nonce) {
        throw new UnsupportedOperationException("Cannot set nonce in query context");
    }

    /**
     * Refuses to create a new hollow account.
     * @param evmAddress the EVM address of the new hollow account
     * @throws UnsupportedOperationException always
     */
    @Override
    public ResponseCodeEnum createHollowAccount(@NonNull final Bytes evmAddress) {
        throw new UnsupportedOperationException("Cannot create hollow account in query context");
    }

    /**
     * Refuses to finalize a hollow account as a contract.
     * @param evmAddress the EVM address of the hollow account to finalize as a contract
     * @throws UnsupportedOperationException always
     */
    @Override
    public void finalizeHollowAccountAsContract(@NonNull final Bytes evmAddress) {
        throw new UnsupportedOperationException("Cannot finalize hollow account as contract in query context");
    }

    /**
     * Refuses to schedule a contract call.
     * @param expiry the consensus second at which the call is to be scheduled
     * @param gasLimit the gas limit for the contract call
     * @param payerId the ID of the account that will pay for the contract call
     * @return false
     */
    @Override
    public boolean canScheduleContractCall(final long expiry, final long gasLimit, @NonNull final AccountID payerId) {
        return false;
    }

    /**
     * Refuses to transfer value.
     *
     * @param amount           the amount to transfer
     * @param fromEntityId the id of the account to transfer from
     * @param toEntityId   the id of the account to transfer to
     * @param strategy         the {@link VerificationStrategy} to use
     * @throws UnsupportedOperationException always
     */
    @Override
    public ResponseCodeEnum transferWithReceiverSigCheck(
            final long amount,
            final AccountID fromEntityId,
            final AccountID toEntityId,
            @NonNull final VerificationStrategy strategy) {
        throw new UnsupportedOperationException("Cannot transfer value in query context");
    }

    /**
     * Refuses to track a deletion.
     *
     * @param deletedId the number of the deleted contract
     * @param beneficiaryId the number of the beneficiary
     * @param frame the frame in which to track the beneficiary
     */
    @Override
    public void trackSelfDestructBeneficiary(
            final AccountID deletedId, final AccountID beneficiaryId, @NonNull final MessageFrame frame) {
        throw new UnsupportedOperationException("Cannot track deletion in query context");
    }

    /**
     * Refuses to get the transactionID of the top level call in this context.
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public TransactionID getTransactionID() {
        throw new UnsupportedOperationException("Cannot get top level transaction ID in query context");
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
