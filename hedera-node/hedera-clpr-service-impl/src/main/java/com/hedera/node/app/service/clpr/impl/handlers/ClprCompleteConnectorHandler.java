// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_COMMITMENT_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INSUFFICIENT_STAKE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CONNECTOR_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.ClprServiceConstants.CLPR_EVM_ADDRESS_BYTES;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritablePendingConnectorCommitmentStore;
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
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.SignatureType;

/**
 * Handler for {@link HederaFunctionality#CLPR_COMPLETE_CONNECTOR} transactions.
 *
 * <p>Phase 2 (Reveal): validates the commitment, re-derives the connectorId, verifies
 * the signature, then creates the Connector keyed by (channelId, connectorId).
 */
@Singleton
public final class ClprCompleteConnectorHandler extends AbstractClprHandler {

    private final EntityIdFactory entityIdFactory;

    @Inject
    public ClprCompleteConnectorHandler(@NonNull final EntityIdFactory entityIdFactory) {
        this.entityIdFactory = requireNonNull(entityIdFactory);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().clprCompleteConnectorOrThrow();
        validateTruePreCheck(op.connectorId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.channelId().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.salt().length() == CHANNEL_ID_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.signature().length() == SIGNATURE_LENGTH, INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasConnectorContract(), INVALID_TRANSACTION_BODY);
        validateTruePreCheck(op.hasAdminKey(), INVALID_TRANSACTION_BODY);

        final var expectedKeyLength =
                switch (op.signatureScheme()) {
                    case ECDSA_SECP256K1 -> ECDSA_UNCOMPRESSED_KEY_LENGTH;
                    case ED25519 -> ED25519_KEY_LENGTH;
                    default -> throw new PreCheckException(INVALID_TRANSACTION_BODY);
                };
        validateTruePreCheck(op.publicKey().length() == expectedKeyLength, INVALID_TRANSACTION_BODY);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        // Permissionless — no additional key requirements beyond payer
    }

    @Override
    protected void doHandle(@NonNull final HandleContext context) throws HandleException {
        final var op = context.body().clprCompleteConnectorOrThrow();
        final var clprConfig = context.configuration().getConfigData(ClprConfig.class);
        final var storeFactory = context.storeFactory();

        final var commitmentStore = storeFactory.writableStore(WritablePendingConnectorCommitmentStore.class);
        final var connectorStore = storeFactory.writableStore(WritableConnectorStore.class);
        final var channelStore = storeFactory.readableStore(ReadableChannelStore.class);
        final var accountStore = storeFactory.readableStore(ReadableAccountStore.class);

        final var pubKeyBytes = op.publicKey().toByteArray();
        final var saltBytes = op.salt().toByteArray();
        final var channelIdBytes = op.channelId().toByteArray();

        // 1. Re-derive connectorId and check it matches submitted value
        final var derivedConnectorId = deriveConnectorId(channelIdBytes, pubKeyBytes, saltBytes);
        final var submittedConnectorId = op.connectorId();
        final var expectedCommitment = computeCommitment(derivedConnectorId.toByteArray(), pubKeyBytes);

        // 2. Check connectorId matches derivation AND commitment was registered
        if (!derivedConnectorId.equals(submittedConnectorId) || !commitmentStore.contains(expectedCommitment)) {
            throw new HandleException(CLPR_COMMITMENT_MISMATCH);
        }

        // 3. Verify the referenced channel exists
        requireChannel(channelStore, op.channelId());

        // 4. Check connector does not already exist
        final var connectorKey = new ClprConnectorKey(op.channelId(), submittedConnectorId);
        validateTrue(connectorStore.getConnector(connectorKey) == null, CLPR_CONNECTOR_ALREADY_EXISTS);

        // 5. Verify signature over keccak256(connectorId || clprServiceAddress)
        final var sigMsgHash = computeSignatureMessage(derivedConnectorId.toByteArray());
        final var signatureType =
                switch (op.signatureScheme()) {
                    case ECDSA_SECP256K1 -> SignatureType.ECDSA_SECP256K1;
                    case ED25519 -> SignatureType.ED25519;
                    default -> throw new HandleException(INVALID_TRANSACTION_BODY);
                };
        final var isValid = CryptographyProvider.getInstance()
                .verifySync(sigMsgHash, op.signature().toByteArray(), pubKeyBytes, signatureType);
        if (!isValid) {
            throw new HandleException(CLPR_INVALID_SIGNATURE);
        }

        // 6. Verify connector_contract is a deployed smart contract
        final var contractAccount = accountStore.getContractById(op.connectorContractOrThrow());
        validateTrue(contractAccount != null && contractAccount.smartContract(), CLPR_INVALID_CONNECTOR_CONTRACT);

        // 7. Verify locked_stake meets minimum
        validateTrue(op.lockedStake() >= clprConfig.minLockedStake(), CLPR_INSUFFICIENT_STAKE);

        // 8. Transfer locked_stake from payer to CLPR staking account
        final var stakingAccountId = entityIdFactory.newAccountId(clprConfig.stakingAccount());
        storeFactory
                .serviceApi(TokenServiceApi.class)
                .transferFromTo(context.payer(), stakingAccountId, op.lockedStake());

        // 9. Store connector and remove consumed commitment
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(submittedConnectorId)
                .channelId(op.channelId())
                .connectorContract(op.connectorContractOrThrow())
                .adminKey(op.adminKeyOrThrow())
                .lockedStake(op.lockedStake())
                .slashCount(0)
                .build());
        commitmentStore.remove(expectedCommitment);
    }

    private static Bytes deriveConnectorId(final byte[] channelId, final byte[] pubKey, final byte[] salt) {
        final var preimage = new byte[channelId.length + pubKey.length + salt.length];
        System.arraycopy(channelId, 0, preimage, 0, channelId.length);
        System.arraycopy(pubKey, 0, preimage, channelId.length, pubKey.length);
        System.arraycopy(salt, 0, preimage, channelId.length + pubKey.length, salt.length);
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage));
    }

    private static Bytes computeCommitment(final byte[] connectorId, final byte[] pubKey) {
        final var preimage = new byte[connectorId.length + pubKey.length];
        System.arraycopy(connectorId, 0, preimage, 0, connectorId.length);
        System.arraycopy(pubKey, 0, preimage, connectorId.length, pubKey.length);
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage));
    }

    private static byte[] computeSignatureMessage(final byte[] connectorId) {
        final var clprServiceAddress = CLPR_EVM_ADDRESS_BYTES.toByteArray();
        final var preimage = new byte[connectorId.length + clprServiceAddress.length];
        System.arraycopy(connectorId, 0, preimage, 0, connectorId.length);
        System.arraycopy(clprServiceAddress, 0, preimage, connectorId.length, clprServiceAddress.length);
        return MiscCryptoUtils.keccak256DigestOf(Bytes.wrap(preimage)).toByteArray();
    }
}
