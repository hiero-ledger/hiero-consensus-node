// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_ALREADY_EXISTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INSUFFICIENT_STAKE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CONNECTOR_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CHANNELS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CONNECTORS_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.PENDING_CONNECTOR_COMMITMENTS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprRegisterConnectorTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.hapi.utils.keys.Ed25519Utils;
import com.hedera.node.app.service.clpr.impl.ReadableChannelStoreImpl;
import com.hedera.node.app.service.clpr.impl.WritableConnectorRegistrationStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprRegisterConnectorHandler;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.hiero.base.crypto.ECDSASecp256k1;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprRegisterConnectorHandlerTest {

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
    // Independent Keccak-256 vectors for SECRET_KEY (01..20) with an all-zero salt.
    private static final Bytes ECDSA_CONNECTOR_ID =
            Bytes.fromHex("6afec4c25e7ffd0d3622d40865460b80b36c98aa64538a13ef7083ccf288557b");
    private static final Bytes ED25519_CONNECTOR_ID =
            Bytes.fromHex("6b4d455e3f0dd81a3cb4762c36a2170b57d4ed7dda33c03c9a78950a88bf3336");
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

    private ClprRegisterConnectorHandler subject;
    private WritableConnectorStore connectorStore;
    private WritableConnectorRegistrationStore registrations;
    private ReadableChannelStoreImpl channelStore;

    private Key identityKey;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.createConfig();
        final EntityIdFactory idFactory = new AppEntityIdFactory(config);
        subject = new ClprRegisterConnectorHandler(idFactory);

        final var ecdsaPublicKey64 = ECDSASecp256k1.derivePublicKey(SECRET_KEY);
        identityKey = Key.newBuilder()
                .ecdsaSecp256k1(Bytes.wrap(MiscCryptoUtils.compressSecp256k1(ecdsaPublicKey64)))
                .build();

        // Connectors state
        final var writableConnectors = MapWritableKVState.<ClprConnectorKey, ClprConnector>builder(
                        CONNECTORS_STATE_ID, "ClprService:CONNECTORS")
                .build();
        lenient()
                .when(writableStates.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID))
                .thenReturn(writableConnectors);
        connectorStore = new WritableConnectorStore(writableStates);

        // Permanent channel-scoped registrations
        final var writableRegistrations = MapWritableKVState.<ProtoBytes, ProtoBytes>builder(
                        PENDING_CONNECTOR_COMMITMENTS_STATE_ID, "ClprService:PENDING_CONNECTOR_COMMITMENTS")
                .build();
        lenient()
                .when(writableStates.<ProtoBytes, ProtoBytes>get(PENDING_CONNECTOR_COMMITMENTS_STATE_ID))
                .thenReturn(writableRegistrations);
        registrations = new WritableConnectorRegistrationStore(writableStates);

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
    @DisplayName("should reject a malformed compressed secp256k1 key")
    void rejectsMalformedSecp256k1Key() throws PreCheckException {
        final var op = validOpBuilder()
                .identityKey(Key.newBuilder().ecdsaSecp256k1(Bytes.wrap(new byte[33])))
                .build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject missing connector_contract")
    void rejectsMissingContract() throws PreCheckException {
        final var op = ClprRegisterConnectorTransactionBody.newBuilder()
                .connectorId(ECDSA_CONNECTOR_ID)
                .identityKey(identityKey)
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
        final var op = ClprRegisterConnectorTransactionBody.newBuilder()
                .connectorId(ECDSA_CONNECTOR_ID)
                .identityKey(identityKey)
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

    @Test
    void rejectsMismatchedConnectorId() {
        final var op = validOpBuilder().connectorId(Bytes.wrap(new byte[32])).build();
        when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext)).has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void rejectsNonCryptographicIdentityKey() {
        final var op = validOpBuilder()
                .identityKey(Key.newBuilder().contractID(CONTRACT_ID))
                .build();
        when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext)).has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    void requiresIdentityKeyToSignRegistration() throws PreCheckException {
        final var preHandle = mock(PreHandleContext.class);
        when(preHandle.body()).thenReturn(validTxn());
        subject.preHandle(preHandle);
        verify(preHandle).requireKeyOrThrow(identityKey, INVALID_TRANSACTION_BODY);
    }

    @Test
    void canRegisterTheSameIdentityOnAnotherChannel() {
        final var otherChannel = Bytes.wrap(new byte[32]);
        final var channels = writableStates.<ProtoBytes, ClprChannel>get(CHANNELS_STATE_ID);
        channels.put(
                new ProtoBytes(otherChannel),
                channels.get(new ProtoBytes(CHANNEL_ID))
                        .copyBuilder()
                        .channelId(otherChannel)
                        .build());
        setupSmartContractMock();
        setupHandleContext(validTxn(), true);
        subject.handle(handleContext);
        setupHandleContext(txnWith(validOpBuilder().channelId(otherChannel).build()), true);
        subject.handle(handleContext);
        assertThat(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, ECDSA_CONNECTOR_ID)))
                .isNotNull();
        assertThat(connectorStore.getConnector(new ClprConnectorKey(otherChannel, ECDSA_CONNECTOR_ID)))
                .isNotNull();
    }

    // ── handle ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should reject when CLPR not enabled")
    void rejectsWhenClprNotEnabled() {
        setupHandleContext(validTxn(), false);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
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
        final var op = validOpBuilder().channelId(missingChannelId).build();
        setupHandleContext(txnWith(op), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CHANNEL_NOT_FOUND));
    }

    @Test
    @DisplayName("should reject when connector already exists")
    void rejectsWhenConnectorAlreadyExists() {
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(ECDSA_CONNECTOR_ID)
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
    @DisplayName("should reject when connector_contract is not a deployed smart contract")
    void rejectsWhenContractNotFound() {
        lenient().when(accountStore.getContractById(CONTRACT_ID)).thenReturn(null);
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INVALID_CONNECTOR_CONTRACT));
    }

    @Test
    @DisplayName("should reject when connector_contract account is not a smart contract")
    void rejectsWhenAccountIsNotSmartContract() {
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
        setupSmartContractMock();
        final var op = validOpBuilder().lockedStake(MIN_LOCKED_STAKE - 1).build();
        setupHandleContext(txnWith(op), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_INSUFFICIENT_STAKE));
    }

    @Test
    @DisplayName("should reject when the payer is one tinybar short of the requested stake")
    void rejectsWhenPayerCannotCoverStake() {
        setupSmartContractMock();
        setupHandleContext(validTxn(), true);
        when(accountStore.getAccountById(PAYER_ID))
                .thenReturn(Account.newBuilder()
                        .accountId(PAYER_ID)
                        .tinybarBalance(VALID_STAKE - 1)
                        .build());

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INSUFFICIENT_ACCOUNT_BALANCE));

        verifyNoInteractions(tokenServiceApi);
        assertThat(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, ECDSA_CONNECTOR_ID)))
                .isNull();
        assertThat(registrations.contains(new ClprConnectorKey(CHANNEL_ID, ECDSA_CONNECTOR_ID)))
                .isFalse();
    }

    @Test
    @DisplayName("should register the connector when the payer balance exactly covers the stake")
    void registersConnectorSuccessfully() {
        setupSmartContractMock();
        setupHandleContext(validTxn(), true);

        subject.handle(handleContext);

        final var key = new ClprConnectorKey(CHANNEL_ID, ECDSA_CONNECTOR_ID);
        final var connector = connectorStore.getConnector(key);
        assertThat(connector).isNotNull();
        assertThat(connector.connectorId()).isEqualTo(ECDSA_CONNECTOR_ID);
        assertThat(connector.channelId()).isEqualTo(CHANNEL_ID);
        assertThat(connector.connectorContract()).isEqualTo(CONTRACT_ID);
        assertThat(connector.lockedStake()).isEqualTo(VALID_STAKE);
        assertThat(connector.slashCount()).isZero();
        assertThat(registrations.contains(key)).isTrue();
        verify(tokenServiceApi).transferFromTo(PAYER_ID, STAKING_ACCOUNT_ID, VALID_STAKE);
    }

    @Test
    @DisplayName("should register connector with ED25519 signature scheme on success")
    void registersConnectorWithEd25519Successfully() throws PreCheckException {
        final var ed25519PublicKey = Ed25519Utils.extractEd25519PublicKey(Ed25519Utils.keyFrom(SECRET_KEY));
        setupSmartContractMock();
        final var op = validOpBuilder()
                .connectorId(ED25519_CONNECTOR_ID)
                .identityKey(Key.newBuilder().ed25519(Bytes.wrap(ed25519PublicKey)))
                .build();
        when(pureChecksContext.body()).thenReturn(txnWith(op));
        subject.pureChecks(pureChecksContext);
        setupHandleContext(txnWith(op), true);

        subject.handle(handleContext);

        final var connector = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, ED25519_CONNECTOR_ID));
        assertThat(connector).isNotNull();
        assertThat(connector.connectorId()).isEqualTo(ED25519_CONNECTOR_ID);
        assertThat(connector.lockedStake()).isEqualTo(VALID_STAKE);
        assertThat(registrations.contains(new ClprConnectorKey(CHANNEL_ID, ED25519_CONNECTOR_ID)))
                .isTrue();
        verify(tokenServiceApi).transferFromTo(PAYER_ID, STAKING_ACCOUNT_ID, VALID_STAKE);
    }

    @Test
    @DisplayName("should accept locked_stake exactly at minimum")
    void acceptsLockedStakeAtExactMinimum() {
        setupSmartContractMock();
        final var op = validOpBuilder().lockedStake(MIN_LOCKED_STAKE).build();
        setupHandleContext(txnWith(op), true);

        subject.handle(handleContext);

        final var connector = connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, ECDSA_CONNECTOR_ID));
        assertThat(connector).isNotNull();
        assertThat(connector.lockedStake()).isEqualTo(MIN_LOCKED_STAKE);
        verify(tokenServiceApi).transferFromTo(PAYER_ID, STAKING_ACCOUNT_ID, MIN_LOCKED_STAKE);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ClprRegisterConnectorTransactionBody.Builder validOpBuilder() {
        return ClprRegisterConnectorTransactionBody.newBuilder()
                .connectorId(ECDSA_CONNECTOR_ID)
                .identityKey(identityKey)
                .salt(SALT)
                .channelId(CHANNEL_ID)
                .connectorContract(CONTRACT_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(VALID_STAKE);
    }

    private TransactionBody validTxn() {
        return txnWith(validOpBuilder().build());
    }

    private TransactionBody txnWith(final ClprRegisterConnectorTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprRegisterConnector(op)
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
        lenient()
                .when(accountStore.getAccountById(PAYER_ID))
                .thenReturn(Account.newBuilder()
                        .accountId(PAYER_ID)
                        .tinybarBalance(VALID_STAKE)
                        .build());
        lenient().when(handleContext.configuration()).thenReturn(config);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient()
                .when(storeFactory.writableStore(WritableConnectorRegistrationStore.class))
                .thenReturn(registrations);
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
