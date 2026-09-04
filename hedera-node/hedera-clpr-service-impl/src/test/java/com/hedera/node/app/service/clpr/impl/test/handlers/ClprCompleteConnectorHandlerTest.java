// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_COMMITMENT_MISMATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INSUFFICIENT_STAKE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CONNECTOR_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CONNECTORS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.PENDING_CONNECTOR_COMMITMENTS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.lenient;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprCompleteConnectorTransactionBody;
import com.hedera.hapi.node.clpr.ClprSignatureScheme;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.impl.ReadableChannelStoreImpl;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritablePendingConnectorCommitmentStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprCompleteConnectorHandler;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprCompleteConnectorHandlerTest {

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(1001).build();
    private static final AccountID STAKING_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(803).build();
    private static final ContractID CONTRACT_ID =
            ContractID.newBuilder().shardNum(0).realmNum(0).contractNum(2001).build();
    private static final AccountID CONTRACT_ACCOUNT_ID =
            AccountID.newBuilder().shardNum(0).realmNum(0).accountNum(2001).build();
    private static final Key ADMIN_KEY =
            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
    private static final long MIN_LOCKED_STAKE = 100_000_000L;
    private static final long VALID_STAKE = 200_000_000L;

    private static final byte[] SECRET_KEY = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
    };

    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[] {
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42
    });
    private static final Bytes SALT = Bytes.wrap(new byte[32]);

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableStates writableStates;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private TokenServiceApi tokenServiceApi;

    private ClprCompleteConnectorHandler subject;
    private WritableConnectorStore connectorStore;
    private WritablePendingConnectorCommitmentStore commitmentStore;
    private ReadableChannelStoreImpl channelStore;

    private byte[] ecdsaPublicKey64;
    private Bytes derivedConnectorId;
    private Bytes ecdsaCommitment;
    private Bytes ecdsaSignature;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.createConfig();
        final EntityIdFactory idFactory = new AppEntityIdFactory(config);
        subject = new ClprCompleteConnectorHandler(idFactory);

        ecdsaPublicKey64 = CryptoTestHelpers.deriveEcdsaPublicKey(SECRET_KEY);
        derivedConnectorId =
                CryptoTestHelpers.computeConnectorId(CHANNEL_ID.toByteArray(), ecdsaPublicKey64, SALT.toByteArray());
        ecdsaCommitment = CryptoTestHelpers.computeCommitment(derivedConnectorId.toByteArray(), ecdsaPublicKey64);
        ecdsaSignature = CryptoTestHelpers.signEcdsa(SECRET_KEY, derivedConnectorId.toByteArray());

        // Connectors state
        final var writableConnectors = MapWritableKVState.<ClprConnectorKey, ClprConnector>builder(
                        CONNECTORS_STATE_ID, "ClprService:CONNECTORS")
                .build();
        lenient()
                .when(writableStates.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID))
                .thenReturn(writableConnectors);
        connectorStore = new WritableConnectorStore(writableStates);

        // Pending connector commitments state
        final var writableCommitments = MapWritableKVState.<ProtoBytes, ProtoBytes>builder(
                        PENDING_CONNECTOR_COMMITMENTS_STATE_ID, "ClprService:PENDING_CONNECTOR_COMMITMENTS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ProtoBytes>get(PENDING_CONNECTOR_COMMITMENTS_STATE_ID))
                .thenReturn(writableCommitments);
        commitmentStore = new WritablePendingConnectorCommitmentStore(writableStates);

        // Channels state — populated with one ACTIVE channel
        final var writableChannels = MapWritableKVState.<ProtoBytes, ClprChannel>builder(
                        CHANNELS_STATE_ID, "ClprService:CHANNELS")
                .build();
        final var activeChannel = ClprChannel.newBuilder()
                .channelId(CHANNEL_ID)
                .status(ClprChannelStatus.ACTIVE)
                .build();
        writableChannels.put(new ProtoBytes(CHANNEL_ID), activeChannel);
        lenient()
                .when(writableStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID))
                .thenReturn(writableChannels);
        channelStore = new ReadableChannelStoreImpl(writableStates);
    }

    // ── pureChecks ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should reject when connector_id is not 32 bytes")
    void rejectsWrongConnectorIdLength() throws PreCheckException {
        final var op = validOpBuilder().connectorId(Bytes.wrap(new byte[16])).build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when channel_id is not 32 bytes")
    void rejectsWrongChannelIdLength() throws PreCheckException {
        final var op = validOpBuilder().channelId(Bytes.wrap(new byte[16])).build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when salt is not 32 bytes")
    void rejectsWrongSaltLength() throws PreCheckException {
        final var op = validOpBuilder().salt(Bytes.wrap(new byte[16])).build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when ecdsa public_key is not 64 bytes")
    void rejectsWrongEcdsaKeyLength() throws PreCheckException {
        final var op = validOpBuilder()
                .publicKey(Bytes.wrap(new byte[33]))
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when signature is not 64 bytes")
    void rejectsWrongSignatureLength() throws PreCheckException {
        final var op = validOpBuilder().signature(Bytes.wrap(new byte[32])).build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject missing connector_contract")
    void rejectsMissingContract() throws PreCheckException {
        final var op = ClprCompleteConnectorTransactionBody.newBuilder()
                .connectorId(derivedConnectorId)
                .publicKey(Bytes.wrap(ecdsaPublicKey64))
                .signature(ecdsaSignature)
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .salt(SALT)
                .channelId(CHANNEL_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(VALID_STAKE)
                // connector_contract intentionally omitted
                .build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject missing admin_key")
    void rejectsMissingAdminKey() throws PreCheckException {
        final var op = ClprCompleteConnectorTransactionBody.newBuilder()
                .connectorId(derivedConnectorId)
                .publicKey(Bytes.wrap(ecdsaPublicKey64))
                .signature(ecdsaSignature)
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .salt(SALT)
                .channelId(CHANNEL_ID)
                .connectorContract(CONTRACT_ID)
                .lockedStake(VALID_STAKE)
                // admin_key intentionally omitted
                .build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should pass pureChecks with valid input")
    void passesWithValidInput() throws PreCheckException {
        lenient().when(pureChecksContext.body()).thenReturn(validTxn());
        subject.pureChecks(pureChecksContext);
    }

    // ── handle ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should reject when CLPR not enabled")
    void rejectsWhenClprNotEnabled() {
        commitmentStore.put(ecdsaCommitment);
        setupHandleContext(validTxn(), false);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("should reject when commitment not found")
    void rejectsWhenCommitmentNotFound() {
        // commitment NOT put
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_COMMITMENT_MISMATCH));
    }

    @Test
    @DisplayName("should reject when connectorId does not match re-derivation")
    void rejectsWhenConnectorIdMismatch() {
        final var wrongConnectorId = Bytes.wrap(new byte[32]); // all-zeros != derived
        final var wrongCommitment =
                CryptoTestHelpers.computeCommitment(wrongConnectorId.toByteArray(), ecdsaPublicKey64);
        commitmentStore.put(wrongCommitment);
        final var op = validOpBuilder().connectorId(wrongConnectorId).build();
        setupHandleContext(txnWith(op), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_COMMITMENT_MISMATCH));
    }

    @Test
    @DisplayName("should reject when channel does not exist")
    void rejectsWhenChannelNotFound() {
        final var missingChannelId = Bytes.wrap(new byte[] {
            0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55,
            0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55,
            0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55,
            0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55
        });
        final var connIdForMissing = CryptoTestHelpers.computeConnectorId(
                missingChannelId.toByteArray(), ecdsaPublicKey64, SALT.toByteArray());
        final var commitmentForMissing =
                CryptoTestHelpers.computeCommitment(connIdForMissing.toByteArray(), ecdsaPublicKey64);
        commitmentStore.put(commitmentForMissing);
        final var sig = CryptoTestHelpers.signEcdsa(SECRET_KEY, connIdForMissing.toByteArray());
        final var op = validOpBuilder()
                .connectorId(connIdForMissing)
                .channelId(missingChannelId)
                .signature(sig)
                .build();
        setupHandleContext(txnWith(op), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_NOT_FOUND));
    }

    @Test
    @DisplayName("should reject when connector already exists")
    void rejectsWhenConnectorAlreadyExists() {
        commitmentStore.put(ecdsaCommitment);
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(derivedConnectorId)
                .channelId(CHANNEL_ID)
                .connectorContract(CONTRACT_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(VALID_STAKE)
                .build());
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CONNECTOR_ALREADY_EXISTS));
    }

    @Test
    @DisplayName("should reject when signature is invalid")
    void rejectsInvalidSignature() {
        commitmentStore.put(ecdsaCommitment);
        setupSmartContractMock();
        final var op = validOpBuilder().signature(Bytes.wrap(new byte[64])).build();
        setupHandleContext(txnWith(op), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_SIGNATURE));
    }

    @Test
    @DisplayName("should reject when connector_contract is not a deployed smart contract")
    void rejectsWhenContractNotFound() {
        commitmentStore.put(ecdsaCommitment);
        lenient().when(accountStore.getContractById(CONTRACT_ID)).thenReturn(null);
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CONNECTOR_CONTRACT));
    }

    @Test
    @DisplayName("should reject when connector_contract account is not a smart contract")
    void rejectsWhenAccountIsNotSmartContract() {
        commitmentStore.put(ecdsaCommitment);
        final var regularAccount = Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .smartContract(false)
                .build();
        lenient().when(accountStore.getContractById(CONTRACT_ID)).thenReturn(regularAccount);
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CONNECTOR_CONTRACT));
    }

    @Test
    @DisplayName("should reject when locked_stake is below minimum")
    void rejectsInsufficientStake() {
        commitmentStore.put(ecdsaCommitment);
        setupSmartContractMock();
        final var op = validOpBuilder().lockedStake(MIN_LOCKED_STAKE - 1).build();
        setupHandleContext(txnWith(op), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INSUFFICIENT_STAKE));
    }

    @Test
    @DisplayName("should register connector, transfer stake, remove commitment on success")
    void registersConnectorSuccessfully() {
        commitmentStore.put(ecdsaCommitment);
        setupSmartContractMock();
        setupHandleContext(validTxn(), true);

        subject.handle(handleContext);

        final var key = new ClprConnectorKey(CHANNEL_ID, derivedConnectorId);
        final var connector = connectorStore.getConnector(key);
        assertThat(connector).isNotNull();
        assertThat(connector.connectorId()).isEqualTo(derivedConnectorId);
        assertThat(connector.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(connector.connectorContract()).isEqualTo(CONTRACT_ID);
        assertThat(connector.lockedStake()).isEqualTo(VALID_STAKE);
        assertThat(connector.slashCount()).isZero();
        assertThat(commitmentStore.contains(ecdsaCommitment)).isFalse();
        verify(tokenServiceApi).transferFromTo(PAYER_ID, STAKING_ACCOUNT_ID, VALID_STAKE);
    }

    @Test
    @DisplayName("should register connector with ED25519 signature scheme on success")
    void registersConnectorWithEd25519Successfully() {
        final var ed25519PublicKey = CryptoTestHelpers.deriveEd25519PublicKey(SECRET_KEY);
        final var ed25519ConnectorId =
                CryptoTestHelpers.computeConnectorId(CHANNEL_ID.toByteArray(), ed25519PublicKey, SALT.toByteArray());
        final var ed25519Commitment =
                CryptoTestHelpers.computeCommitment(ed25519ConnectorId.toByteArray(), ed25519PublicKey);
        final var ed25519Signature = CryptoTestHelpers.signEd25519(SECRET_KEY, ed25519ConnectorId.toByteArray());

        commitmentStore.put(ed25519Commitment);
        setupSmartContractMock();
        final var op = validOpBuilder()
                .connectorId(ed25519ConnectorId)
                .publicKey(Bytes.wrap(ed25519PublicKey))
                .signature(ed25519Signature)
                .signatureScheme(ClprSignatureScheme.ED25519)
                .build();
        setupHandleContext(txnWith(op), true);

        subject.handle(handleContext);

        final var connector = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, ed25519ConnectorId));
        assertThat(connector).isNotNull();
        assertThat(connector.connectorId()).isEqualTo(ed25519ConnectorId);
        assertThat(connector.lockedStake()).isEqualTo(VALID_STAKE);
        assertThat(commitmentStore.contains(ed25519Commitment)).isFalse();
        verify(tokenServiceApi).transferFromTo(PAYER_ID, STAKING_ACCOUNT_ID, VALID_STAKE);
    }

    @Test
    @DisplayName("should accept locked_stake exactly at minimum")
    void acceptsLockedStakeAtExactMinimum() {
        commitmentStore.put(ecdsaCommitment);
        setupSmartContractMock();
        final var op = validOpBuilder().lockedStake(MIN_LOCKED_STAKE).build();
        setupHandleContext(txnWith(op), true);

        subject.handle(handleContext);

        final var connector = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, derivedConnectorId));
        assertThat(connector).isNotNull();
        assertThat(connector.lockedStake()).isEqualTo(MIN_LOCKED_STAKE);
        verify(tokenServiceApi).transferFromTo(PAYER_ID, STAKING_ACCOUNT_ID, MIN_LOCKED_STAKE);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ClprCompleteConnectorTransactionBody.Builder validOpBuilder() {
        return ClprCompleteConnectorTransactionBody.newBuilder()
                .connectorId(derivedConnectorId)
                .publicKey(Bytes.wrap(ecdsaPublicKey64))
                .signature(ecdsaSignature)
                .signatureScheme(ClprSignatureScheme.ECDSA_SECP256K1)
                .salt(SALT)
                .channelId(CHANNEL_ID)
                .connectorContract(CONTRACT_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(VALID_STAKE);
    }

    private TransactionBody validTxn() {
        return txnWith(validOpBuilder().build());
    }

    private TransactionBody txnWith(final ClprCompleteConnectorTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprCompleteConnector(op)
                .build();
    }

    private void setupHandleContext(final TransactionBody txn, final boolean clprEnabled) {
        setupHandleContext(
                txn,
                HederaTestConfigBuilder.create()
                        .withValue("clpr.enabled", clprEnabled)
                        .withValue("clpr.minLockedStake", MIN_LOCKED_STAKE)
                        .withValue("clpr.stakingAccount", 803L)
                        .getOrCreateConfig());
    }

    private void setupHandleContext(final TransactionBody txn, final Configuration config) {
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient().when(handleContext.payer()).thenReturn(PAYER_ID);
        lenient().when(handleContext.configuration()).thenReturn(config);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient()
                .when(storeFactory.writableStore(WritablePendingConnectorCommitmentStore.class))
                .thenReturn(commitmentStore);
        lenient().when(storeFactory.writableStore(WritableConnectorStore.class)).thenReturn(connectorStore);
        lenient().when(storeFactory.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
        lenient().when(storeFactory.serviceApi(TokenServiceApi.class)).thenReturn(tokenServiceApi);
        lenient()
                .when(storeFactory.readableStore(com.hedera.node.app.service.clpr.ReadableChannelStore.class))
                .thenReturn(channelStore);
    }

    private void setupSmartContractMock() {
        final var smartContract = Account.newBuilder()
                .accountId(CONTRACT_ACCOUNT_ID)
                .smartContract(true)
                .build();
        lenient().when(accountStore.getContractById(CONTRACT_ID)).thenReturn(smartContract);
    }
}
