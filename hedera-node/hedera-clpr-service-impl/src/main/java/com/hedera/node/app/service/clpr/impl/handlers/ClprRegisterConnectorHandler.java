// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INSUFFICIENT_STAKE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CONNECTOR_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.hapi.utils.keys.Secp256k1Utils.ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorRegistrationStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handler for {@link HederaFunctionality#CLPR_REGISTER_CONNECTOR} transactions.
 *
 */
@Singleton
public final class ClprRegisterConnectorHandler extends AbstractClprHandler {

    private final EntityIdFactory entityIdFactory;

    @Inject
    public ClprRegisterConnectorHandler(@NonNull final EntityIdFactory entityIdFactory) {
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprRegisterConnectorOrThrow();
        validateTruePreCheck(op.connectorId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.channelId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.salt().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasIdentityKey(), INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasConnectorContract(), INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasAdminKey(), INVALID_TRANSACTION_BODY);

        final var identityKey = op.identityKeyOrThrow();
        final Bytes publicKey;
        if (identityKey.hasEd25519()) {
            publicKey = identityKey.ed25519OrThrow();
            validateTruePreCheck(publicKey.length() == ED25519_KEY_LENGTH, INVALID_TRANSACTION_BODY);
        } else if (identityKey.hasEcdsaSecp256k1()) {
            final var compressed = identityKey.ecdsaSecp256k1OrThrow();
            validateTruePreCheck(
                    compressed.length() == ECDSA_SECP256K1_COMPRESSED_KEY_LENGTH, INVALID_TRANSACTION_BODY);
            try {
                publicKey = Bytes.wrap(MiscCryptoUtils.decompressSecp256k1(compressed.toByteArray()));
            } catch (IllegalArgumentException e) {
                throw new PreCheckException(INVALID_TRANSACTION_BODY);
            }
        } else {
            throw new PreCheckException(INVALID_TRANSACTION_BODY);
        }
        validateTruePreCheck(
                MiscCryptoUtils.keccak256DigestOf(Bytes.merge(publicKey, op.salt()))
                        .equals(op.connectorId()),
                INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        context.requireKeyOrThrow(
                context.body().clprRegisterConnectorOrThrow().identityKey(), INVALID_TRANSACTION_BODY);
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprRegisterConnectorOrThrow();
        final var clprConfig = context.configuration().getConfigData(ClprConfig.class);
        final var storeFactory = context.storeFactory();

        final var registrations = storeFactory.writableStore(WritableConnectorRegistrationStore.class);
        final var connectorStore = storeFactory.writableStore(WritableConnectorStore.class);
        final var channelStore = storeFactory.readableStore(ReadableChannelStore.class);
        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);

        requireChannel(channelStore, op.channelId());
        final var connectorKey = new ClprConnectorKey(op.channelId(), op.connectorId());
        validateTrue(
                connectorStore.getConnector(connectorKey) == null && !registrations.contains(connectorKey),
                CLPR_CONNECTOR_ALREADY_EXISTS);

        final var contractAccount = accountStore.getContractById(op.connectorContractOrThrow());
        validateTrue(
                contractAccount != null && contractAccount.smartContract() && !contractAccount.deleted(),
                CLPR_INVALID_CONNECTOR_CONTRACT);

        validateTrue(op.lockedStake() >= clprConfig.minLockedStake(), CLPR_INSUFFICIENT_STAKE);

        final var payerId = context.payer();
        final var payer = requireNonNull(accountStore.getAccountById(payerId));
        validateTrue(payer.tinybarBalance() >= op.lockedStake(), INSUFFICIENT_ACCOUNT_BALANCE);
        final var stakingAccountId = entityIdFactory.newAccountId(clprConfig.stakingAccount());
        storeFactory.serviceApi(TokenServiceApi.class).transferFromTo(payerId, stakingAccountId, op.lockedStake());

        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(op.connectorId())
                .channelId(op.channelId())
                .connectorContract(op.connectorContractOrThrow())
                .adminKey(op.adminKeyOrThrow())
                .lockedStake(op.lockedStake())
                .slashCount(0)
                .build());
        registrations.put(connectorKey);
    }
}
