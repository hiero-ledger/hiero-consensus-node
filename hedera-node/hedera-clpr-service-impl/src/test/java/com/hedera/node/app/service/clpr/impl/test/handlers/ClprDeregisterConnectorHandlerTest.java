// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_HAS_IN_FLIGHT_MESSAGES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_UNAUTHORIZED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CONNECTORS_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.clpr.ClprDeregisterConnectorTransactionBody;
import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.clpr.ReadableConnectorStore;
import com.hedera.node.app.service.clpr.impl.WritableConnectorStore;
import com.hedera.node.app.service.clpr.impl.handlers.ClprDeregisterConnectorHandler;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprDeregisterConnectorHandlerTest {

    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(1001).build();
    private static final AccountID STAKE_RECIPIENT_ID =
            AccountID.newBuilder().accountNum(9999).build();
    private static final AccountID STAKING_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(803).build();
    private static final ContractID CONTRACT_ID =
            ContractID.newBuilder().contractNum(2001).build();
    private static final Key ADMIN_KEY =
            Key.newBuilder().ed25519(Bytes.wrap(new byte[32])).build();
    private static final Key STAKE_RECIPIENT_KEY = Key.newBuilder()
            .ecdsaSecp256k1(Bytes.wrap(validCompressedSecp256k1Key()))
            .build();

    private static byte[] validCompressedSecp256k1Key() {
        // 33 bytes: leading parity byte (0x02 = even y) + 32 payload bytes.
        final var key = new byte[33];
        key[0] = 0x02;
        for (int i = 1; i < key.length; i++) {
            key[i] = (byte) i;
        }
        return key;
    }

    // Real 32-byte values — no Bytes.EMPTY
    private static final Bytes CHANNEL_ID = Bytes.wrap(new byte[] {
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42
    });
    private static final Bytes CONNECTOR_ID = Bytes.wrap(new byte[] {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
        0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20
    });
    private static final long VALID_STAKE = 200_000_000L;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableStates writableStates;

    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private ReadableAccountStore readableAccountStore;

    private ClprDeregisterConnectorHandler subject;
    private WritableConnectorStore connectorStore;

    @BeforeEach
    void setUp() {
        final var config = HederaTestConfigBuilder.createConfig();
        final EntityIdFactory idFactory = new AppEntityIdFactory(config);
        subject = new ClprDeregisterConnectorHandler(idFactory);

        final var writableConnectors = MapWritableKVState.<ClprConnectorKey, ClprConnector>builder(
                        CONNECTORS_STATE_ID, "ClprService:CONNECTORS")
                .build();
        lenient()
                .when(writableStates.<ClprConnectorKey, ClprConnector>get(CONNECTORS_STATE_ID))
                .thenReturn(writableConnectors);
        connectorStore = new WritableConnectorStore(writableStates);
    }

    // ── pureChecks ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should reject when channel_id is not 32 bytes")
    void rejectsWrongChannelIdLength() {
        final var op = ClprDeregisterConnectorTransactionBody.newBuilder()
                .channelId(Bytes.wrap(new byte[16]))
                .connectorId(CONNECTOR_ID)
                .stakeRecipient(STAKE_RECIPIENT_ID)
                .build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when connector_id is not 32 bytes")
    void rejectsWrongConnectorIdLength() {
        final var op = ClprDeregisterConnectorTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .connectorId(Bytes.wrap(new byte[16]))
                .stakeRecipient(STAKE_RECIPIENT_ID)
                .build();
        lenient().when(pureChecksContext.body()).thenReturn(txnWith(op));
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_TRANSACTION_BODY));
    }

    @Test
    @DisplayName("should reject when stake_recipient is missing")
    void rejectsMissingStakeRecipient() {
        final var op = ClprDeregisterConnectorTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .connectorId(CONNECTOR_ID)
                // stake_recipient intentionally omitted
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
    @DisplayName("should reject when CLPR is not enabled")
    void rejectsWhenClprNotEnabled() {
        putConnector();
        setupHandleContext(validTxn(), false);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_NOT_ENABLED));
    }

    @Test
    @DisplayName("should reject when connector has in-flight messages")
    void rejectsWithInFlightMessages() {
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(CONNECTOR_ID)
                .channelId(CHANNEL_ID)
                .connectorContract(CONTRACT_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(VALID_STAKE)
                .inFlightMessageCount(3L)
                .build());
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CONNECTOR_HAS_IN_FLIGHT_MESSAGES));
    }

    @Test
    @DisplayName("should reject when connector not found")
    void rejectsWhenConnectorNotFound() {
        // connector NOT in state
        setupHandleContext(validTxn(), true);
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(CLPR_CONNECTOR_NOT_FOUND));
    }

    @Test
    @DisplayName("should deregister connector, remove from state, and transfer stake")
    void deregistersConnectorAndTransfersStake() {
        putConnector();
        setupHandleContext(validTxn(), true);

        subject.handle(handleContext);

        final var key = new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ID);
        assertThat(connectorStore.getConnector(key)).isNull();
        verify(tokenServiceApi).transferFromTo(STAKING_ACCOUNT_ID, STAKE_RECIPIENT_ID, VALID_STAKE);
    }

    @Test
    @DisplayName("should deregister connector without transfer when stake is zero")
    void deregistersConnectorWithZeroStake() {
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(CONNECTOR_ID)
                .channelId(CHANNEL_ID)
                .connectorContract(CONTRACT_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(0L)
                .build());
        setupHandleContext(validTxn(), true);

        subject.handle(handleContext);

        assertThat(connectorStore.getConnector(new ClprConnectorKey(CHANNEL_ID, CONNECTOR_ID)))
                .isNull();
    }

    // ── preHandle ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("preHandle rejects when connector is not in state")
    void preHandleRejectsWhenConnectorNotFound() throws PreCheckException {
        // connector NOT put — store returns null
        final var ctx = newPreHandleContext(validTxn());

        assertThatThrownBy(() -> subject.preHandle(ctx))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(CLPR_CONNECTOR_NOT_FOUND));
    }

    @Test
    @DisplayName("preHandle rejects when connector admin_key is missing")
    void preHandleRejectsWhenAdminKeyMissing() throws PreCheckException {
        // Connector exists but with no admin_key — adminKeyOrElse(null) returns null,
        // which FakePreHandleContext.requireKeyOrThrow rejects.
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(CONNECTOR_ID)
                .channelId(CHANNEL_ID)
                .connectorContract(CONTRACT_ID)
                .lockedStake(VALID_STAKE)
                .build());
        final var ctx = newPreHandleContext(validTxn());

        assertThatThrownBy(() -> subject.preHandle(ctx))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(CLPR_CONNECTOR_UNAUTHORIZED));
    }

    @Test
    @DisplayName("preHandle requires both admin_key and stake_recipient key on success")
    void preHandleRequiresAdminKeyAndStakeRecipientKey() throws PreCheckException {
        putConnector();
        // stake_recipient account must exist with a valid key for the AccountID-based
        // requireKeyOrThrow lookup to succeed.
        final var stakeRecipientAccount = Account.newBuilder()
                .accountId(STAKE_RECIPIENT_ID)
                .key(STAKE_RECIPIENT_KEY)
                .build();
        lenient().when(readableAccountStore.getAccountById(STAKE_RECIPIENT_ID)).thenReturn(stakeRecipientAccount);

        final var ctx = newPreHandleContext(validTxn());
        subject.preHandle(ctx);

        assertThat(ctx.requiredNonPayerKeys()).contains(ADMIN_KEY, STAKE_RECIPIENT_KEY);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private FakePreHandleContext newPreHandleContext(final TransactionBody txn) throws PreCheckException {
        // FakePreHandleContext requires the payer account to exist with a valid key.
        // Key must differ from ADMIN_KEY so requiredNonPayerKeys captures the admin key
        // (FakePreHandleContext drops keys that equal the payer key).
        final var payerKeyBytes = new byte[32];
        java.util.Arrays.fill(payerKeyBytes, (byte) 0xFF);
        final var payerKey = Key.newBuilder().ed25519(Bytes.wrap(payerKeyBytes)).build();
        final var payerAccount =
                Account.newBuilder().accountId(PAYER_ID).key(payerKey).build();
        when(readableAccountStore.getAccountById(PAYER_ID)).thenReturn(payerAccount);
        final var ctx = new FakePreHandleContext(readableAccountStore, txn);
        ctx.registerStore(ReadableConnectorStore.class, connectorStore);
        return ctx;
    }

    private void putConnector() {
        connectorStore.put(ClprConnector.newBuilder()
                .connectorId(CONNECTOR_ID)
                .channelId(CHANNEL_ID)
                .connectorContract(CONTRACT_ID)
                .adminKey(ADMIN_KEY)
                .lockedStake(VALID_STAKE)
                .build());
    }

    private void setupHandleContext(final TransactionBody txn, final boolean clprEnabled) {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", clprEnabled)
                .withValue("clpr.stakingAccount", 803L)
                .getOrCreateConfig();
        lenient().when(handleContext.body()).thenReturn(txn);
        lenient().when(handleContext.configuration()).thenReturn(config);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(storeFactory.writableStore(WritableConnectorStore.class)).thenReturn(connectorStore);
        lenient().when(storeFactory.serviceApi(TokenServiceApi.class)).thenReturn(tokenServiceApi);
    }

    private TransactionBody validTxn() {
        return txnWith(ClprDeregisterConnectorTransactionBody.newBuilder()
                .channelId(CHANNEL_ID)
                .connectorId(CONNECTOR_ID)
                .stakeRecipient(STAKE_RECIPIENT_ID)
                .build());
    }

    private TransactionBody txnWith(final ClprDeregisterConnectorTransactionBody op) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(PAYER_ID))
                .clprDeregisterConnector(op)
                .build();
    }
}
