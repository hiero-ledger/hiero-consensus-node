// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ALIAS_ALREADY_ASSIGNED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_INITIAL_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RECEIVE_RECORD_THRESHOLD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SEND_RECORD_THRESHOLD;
import static com.hedera.hapi.node.base.ResponseCodeEnum.KEY_REQUIRED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_ID;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.RealmID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ShardID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.CryptoCreateHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.CryptoCreateValidator;
import com.hedera.node.app.service.token.records.CryptoCreateStreamBuilder;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.fees.FakeFeeCalculator;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.ids.EntityNumGenerator;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.AttributeValidator;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CryptoCreateHandler}.
 */
@ExtendWith(MockitoExtension.class)
class CryptoCreateHandlerTest extends CryptoHandlerTestBase {

    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private StoreFactory storeFactory;

    @Mock(strictness = LENIENT)
    private FeeContext feeContext;

    @Mock
    private CryptoCreateStreamBuilder recordBuilder;

    @Mock
    private HookDispatchStreamBuilder hookDispatchStreamBuilder;

    @Mock
    private HandleContext.SavepointStack stack;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private NodeInfo nodeInfo;

    @Mock(strictness = LENIENT)
    private ExpiryValidator expiryValidator;

    @Mock
    private AttributeValidator attributeValidator;

    @Mock
    private EntityNumGenerator entityNumGenerator;

    @Mock
    private WritableEntityCounters entityCounters;

    @Mock
    private PureChecksContext pureChecksContext;

    private FakeEntityIdFactoryImpl idFactory = new FakeEntityIdFactoryImpl(0, 0);

    private CryptoCreateHandler subject;

    private TransactionBody txn;

    private static final long defaultInitialBalance = 100L;
    private static final long stakeNodeId = 3L;

    @BeforeEach
    public void setUp() {
        super.setUp();
        refreshStoresWithCurrentTokenInWritable();
        txn = new CryptoCreateBuilder().build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.savepointStack()).willReturn(stack);
        lenient().when(stack.getBaseBuilder(any())).thenReturn(recordBuilder);
        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableStore);

        given(handleContext.attributeValidator()).willReturn(attributeValidator);
        lenient().when(handleContext.entityNumGenerator()).thenReturn(entityNumGenerator);

        given(handleContext.networkInfo()).willReturn(networkInfo);
        subject = new CryptoCreateHandler(new CryptoCreateValidator(), null, idFactory);
    }

    @Test
    @DisplayName("test CalculateFees When Free")
    void testCalculateFeesWhenFree(@Mock FeeCalculatorFactory feeCalculatorFactory) {
        var transactionBody = new CryptoCreateBuilder()
                .withStakedAccountId(3)
                .withMemo("blank")
                .withKey(A_COMPLEX_KEY)
                .build();
        final var feeCalculator = new FakeFeeCalculator();
        given(feeContext.body()).willReturn(transactionBody);
        given(feeContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(DEFAULT)).willReturn(feeCalculator);
        given(feeContext.configuration()).willReturn(configuration);
        final var result = subject.calculateFees(feeContext);
        assertThat(result).isEqualTo(Fees.FREE);
    }

    @Test
    @DisplayName("preHandle works when there is a receiverSigRequired")
    void preHandleCryptoCreateVanilla() throws PreCheckException {
        final var context = new FakePreHandleContext(readableStore, txn);
        given(pureChecksContext.body()).willReturn(txn);
        subject.pureChecks(pureChecksContext);
        subject.preHandle(context);
        assertThat(txn).isEqualTo(context.body());
        basicMetaAssertions(context, 1);
        assertThat(key).isEqualTo(context.payerKey());
    }

    @Test
    @DisplayName("pureChecks fail when initial balance is not greater than zero")
    void whenInitialBalanceIsNegative() {
        txn = new CryptoCreateBuilder().withInitialBalance(-1L).build();
        given(pureChecksContext.body()).willReturn(txn);
        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(INVALID_INITIAL_BALANCE).isEqualTo(msg.responseCode());
    }

    @Test
    @DisplayName("pureChecks fail without auto-renew period specified")
    void whenNoAutoRenewPeriodSpecified() {
        txn = new CryptoCreateBuilder().withNoAutoRenewPeriod().build();
        given(pureChecksContext.body()).willReturn(txn);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(INVALID_RENEWAL_PERIOD).isEqualTo(msg.responseCode());
    }

    @Test
    @DisplayName("pureChecks succeeds when expected shardId is specified")
    void validateWhenZeroShardId() {
        txn = new CryptoCreateBuilder().withShardId(0).build();
        given(pureChecksContext.body()).willReturn(txn);

        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    @DisplayName("pureChecks succeeds when expected shardId is specified")
    void validateNonZeroShardAndRealm() {
        final long shard = 5;
        final long realm = 10;
        txn = new CryptoCreateBuilder()
                .withStakedAccountId(3)
                .withShardId(shard)
                .withRealmId(realm)
                .build();
        given(handleContext.body()).willReturn(txn);
        given(pureChecksContext.body()).willReturn(txn);

        given(handleContext.consensusNow()).willReturn(consensusInstant);
        given(entityNumGenerator.newEntityNum()).willReturn(1000L);
        given(handleContext.payer()).willReturn(id);
        final var config = HederaTestConfigBuilder.create()
                .withValue("ledger.maxAutoAssociations", 5000)
                .withValue("entities.limitTokenAssociations", false)
                .withValue("tokens.maxPerAccount", 1000)
                .withValue("hedera.shard", shard)
                .withValue("hedera.realm", realm)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        given(pureChecksContext.body()).willReturn(txn);
        setupExpiryValidator();

        // newly created account is not modified.
        assertFalse(writableStore.modifiedAccountsInState().contains(accountIDWithShardAndRealm(1000L, shard, realm)));
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
        subject.handle(handleContext);

        // newly created account and payer account are modified
        assertTrue(writableStore.modifiedAccountsInState().contains(accountIDWithShardAndRealm(1000L, shard, realm)));

        // Validate created account exists and check record builder has created account recorded
        final var createdAccount = writableStore.get(AccountID.newBuilder()
                .shardNum(shard)
                .realmNum(realm)
                .accountNum(1000L)
                .build());
        assertThat(createdAccount).isNotNull();
        final var accountID = AccountID.newBuilder()
                .shardNum(shard)
                .realmNum(realm)
                .accountNum(1000L)
                .build();
        verify(recordBuilder).accountID(accountID);
    }

    @Test
    @DisplayName("pureChecks fail when invalid maxAutoAssociations is specified")
    void failsWhenInvalidMaxAutoAssociations() {
        txn = new CryptoCreateBuilder().withMaxAutoAssociations(-5).build();
        given(pureChecksContext.body()).willReturn(txn);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_MAX_AUTO_ASSOCIATIONS);
    }

    @Test
    @DisplayName("pureChecks fail when negative send record threshold is specified")
    void sendRecordThresholdIsNegative() throws PreCheckException {
        txn = new CryptoCreateBuilder().withSendRecordThreshold(-1).build();
        given(pureChecksContext.body()).willReturn(txn);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_SEND_RECORD_THRESHOLD);
    }

    @Test
    @DisplayName("pureChecks fail when negative receive record threshold is specified")
    void receiveRecordThresholdIsNegative() throws PreCheckException {
        txn = new CryptoCreateBuilder().withReceiveRecordThreshold(-1).build();
        given(pureChecksContext.body()).willReturn(txn);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(INVALID_RECEIVE_RECORD_THRESHOLD);
    }

    @Test
    @DisplayName("pureChecks fail when proxy accounts id is specified")
    void whenProxyAccountIdIsSpecified() throws PreCheckException {
        txn = new CryptoCreateBuilder().withProxyAccountNum(1).build();
        given(pureChecksContext.body()).willReturn(txn);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(msg.responseCode()).isEqualTo(PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED);
    }

    @Test
    @DisplayName("preHandle succeeds when initial balance is zero")
    void preHandleWorksWhenInitialBalanceIsZero() throws PreCheckException {
        txn = new CryptoCreateBuilder().withInitialBalance(0L).build();
        given(pureChecksContext.body()).willReturn(txn);

        final var context = new FakePreHandleContext(readableStore, txn);
        subject.pureChecks(pureChecksContext);
        subject.preHandle(context);
        assertThat(txn).isEqualTo(context.body());
        basicMetaAssertions(context, 1);
        assertThat(key).isEqualTo(context.payerKey());
    }

    @Test
    @DisplayName("preHandle succeeds when has non zero evm alias")
    void preHandleWorksWhenHasEvmAlias() throws PreCheckException {
        final byte[] evmAddress = CommonUtils.unhex("6aeb3773ea468a814d954e6dec795bfee7d76e26");
        txn = new CryptoCreateBuilder()
                .withAlias(Bytes.wrap(evmAddress))
                .withStakedAccountId(3)
                .build();
        final var context = new FakePreHandleContext(readableStore, txn);
        subject.preHandle(context);
        assertThat(txn).isEqualTo(context.body());
        basicMetaAssertions(context, 1);
        assertThat(key).isEqualTo(context.payerKey());
    }

    @Test
    @DisplayName("preHandle fails when invalid alias key")
    void preHandleWorksWhenHasAlias() throws PreCheckException {
        final Bytes SENDER_ALIAS =
                Bytes.fromHex("3a21030edcc130e13fb5102e7c883535af8c2b0a5a617231f77fd127ce5f3b9a620591");
        txn = new CryptoCreateBuilder()
                .withAlias(SENDER_ALIAS)
                .withStakedAccountId(3)
                .build();
        final var context = new FakePreHandleContext(readableStore, txn);
        assertThatThrownBy(() -> subject.preHandle(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ALIAS_KEY));
    }

    @Test
    @DisplayName("preHandle works when there is no receiverSigRequired")
    void noReceiverSigRequiredPreHandleCryptoCreate() throws PreCheckException {
        final var noReceiverSigTxn =
                new CryptoCreateBuilder().withReceiverSigReq(false).build();
        final var expected = new FakePreHandleContext(readableStore, noReceiverSigTxn);

        final var context = new FakePreHandleContext(readableStore, noReceiverSigTxn);
        subject.preHandle(context);
        assertThat(expected.body()).isEqualTo(context.body());
        assertFalse(context.requiredNonPayerKeys().contains(key));
        basicMetaAssertions(context, 0);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
        assertThat(key).isEqualTo(context.payerKey());
    }

    @Test
    @DisplayName("handle works when account can be created without any alias")
    // Suppressing the warning that we have too many assertions
    @SuppressWarnings("java:S5961")
    void handleCryptoCreateVanilla() {
        txn = new CryptoCreateBuilder().withStakedAccountId(3).build();
        given(handleContext.body()).willReturn(txn);

        given(handleContext.consensusNow()).willReturn(consensusInstant);
        given(entityNumGenerator.newEntityNum()).willReturn(1000L);
        given(handleContext.payer()).willReturn(id);
        setupConfig();
        setupExpiryValidator();

        // newly created account and payer account are not modified. Validate payers balance
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(id.accountNum())));
        assertEquals(payerBalance, writableStore.get(id).tinybarBalance());

        subject.handle(handleContext);

        // newly created account and payer account are modified
        assertTrue(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
        assertTrue(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(id.accountNum())));

        // Validate created account exists and check record builder has created account recorded
        final var createdAccount = writableStore.get(idFactory.newAccountId(1000L));
        assertThat(createdAccount).isNotNull();
        final var accountID = idFactory.newAccountId(1000L);
        verify(recordBuilder).accountID(accountID);

        // validate fields on created account
        assertTrue(createdAccount.receiverSigRequired());
        assertEquals(1000L, createdAccount.accountId().accountNum());
        assertEquals(Bytes.EMPTY, createdAccount.alias());
        assertEquals(otherKey, createdAccount.key());
        assertEquals(consensusTimestamp.seconds() + defaultAutoRenewPeriod, createdAccount.expirationSecond());
        assertEquals(defaultInitialBalance, createdAccount.tinybarBalance());
        assertEquals("Create Account", createdAccount.memo());
        assertFalse(createdAccount.deleted());
        assertEquals(0L, createdAccount.stakedToMe());
        assertEquals(-1L, createdAccount.stakePeriodStart());
        // staked node id is stored in state as negative long
        assertEquals(3, createdAccount.stakedAccountId().accountNum());
        assertFalse(createdAccount.declineReward());
        assertTrue(createdAccount.receiverSigRequired());
        assertNull(createdAccount.headTokenId());
        assertNull(createdAccount.headNftId());
        assertEquals(0L, createdAccount.headNftSerialNumber());
        assertEquals(0L, createdAccount.numberOwnedNfts());
        assertEquals(0, createdAccount.maxAutoAssociations());
        assertEquals(0, createdAccount.usedAutoAssociations());
        assertEquals(0, createdAccount.numberAssociations());
        assertFalse(createdAccount.smartContract());
        assertEquals(0, createdAccount.numberPositiveBalances());
        assertEquals(0L, createdAccount.ethereumNonce());
        assertEquals(-1L, createdAccount.stakeAtStartOfLastRewardedPeriod());
        assertNull(createdAccount.autoRenewAccountId());
        assertEquals(defaultAutoRenewPeriod, createdAccount.autoRenewSeconds());
        assertEquals(0, createdAccount.contractKvPairsNumber());
        assertTrue(createdAccount.cryptoAllowances().isEmpty());
        assertTrue(createdAccount.approveForAllNftAllowances().isEmpty());
        assertTrue(createdAccount.tokenAllowances().isEmpty());
        assertEquals(0, createdAccount.numberTreasuryTitles());
        assertFalse(createdAccount.expiredAndPendingRemoval());
        assertEquals(0, createdAccount.firstContractStorageKey().length());

        // validate payer balance reduced
        assertEquals(9_900L, writableStore.get(id).tinybarBalance());
    }

    @Test
    @DisplayName("handle works when account can be created without any alias using staked account id")
    // Suppressing the warning that we have too many assertions
    @SuppressWarnings("java:S5961")
    void handleCryptoCreateVanillaWithStakedAccountId() {
        txn = new CryptoCreateBuilder().withStakedAccountId(3).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(idFactory.newAccountId(id.accountNum()));
        given(handleContext.consensusNow()).willReturn(consensusInstant);
        given(entityNumGenerator.newEntityNum()).willReturn(1000L);
        setupConfig();
        setupExpiryValidator();

        // newly created account and payer account are not modified. Validate payers balance
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(id.accountNum())));
        assertEquals(payerBalance, writableStore.get(id).tinybarBalance());

        subject.handle(handleContext);

        // newly created account and payer account are modified
        assertTrue(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
        assertTrue(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(id.accountNum())));

        // Validate created account exists and check record builder has created account recorded
        final var createdAccount = writableStore.get(idFactory.newAccountId(1000L));
        assertThat(createdAccount).isNotNull();
        final var accountID = idFactory.newAccountId(1000L);
        verify(recordBuilder).accountID(accountID);

        // validate fields on created account
        assertTrue(createdAccount.receiverSigRequired());
        assertEquals(1000L, createdAccount.accountId().accountNum());
        assertEquals(Bytes.EMPTY, createdAccount.alias());
        assertEquals(otherKey, createdAccount.key());
        assertEquals(consensusTimestamp.seconds() + defaultAutoRenewPeriod, createdAccount.expirationSecond());
        assertEquals(defaultInitialBalance, createdAccount.tinybarBalance());
        assertEquals("Create Account", createdAccount.memo());
        assertFalse(createdAccount.deleted());
        assertEquals(0L, createdAccount.stakedToMe());
        assertEquals(-1L, createdAccount.stakePeriodStart());
        // staked node id is stored in state as negative long
        assertEquals(3, createdAccount.stakedAccountId().accountNum());
        assertFalse(createdAccount.declineReward());
        assertTrue(createdAccount.receiverSigRequired());
        assertNull(createdAccount.headTokenId());
        assertNull(createdAccount.headNftId());
        assertEquals(0L, createdAccount.headNftSerialNumber());
        assertEquals(0L, createdAccount.numberOwnedNfts());
        assertEquals(0, createdAccount.maxAutoAssociations());
        assertEquals(0, createdAccount.usedAutoAssociations());
        assertEquals(0, createdAccount.numberAssociations());
        assertFalse(createdAccount.smartContract());
        assertEquals(0, createdAccount.numberPositiveBalances());
        assertEquals(0L, createdAccount.ethereumNonce());
        assertEquals(-1L, createdAccount.stakeAtStartOfLastRewardedPeriod());
        assertNull(createdAccount.autoRenewAccountId());
        assertEquals(defaultAutoRenewPeriod, createdAccount.autoRenewSeconds());
        assertEquals(0, createdAccount.contractKvPairsNumber());
        assertTrue(createdAccount.cryptoAllowances().isEmpty());
        assertTrue(createdAccount.approveForAllNftAllowances().isEmpty());
        assertTrue(createdAccount.tokenAllowances().isEmpty());
        assertEquals(0, createdAccount.numberTreasuryTitles());
        assertFalse(createdAccount.expiredAndPendingRemoval());
        assertEquals(0, createdAccount.firstContractStorageKey().length());

        // validate payer balance reduced
        assertEquals(9_900L, writableStore.get(id).tinybarBalance());
    }

    @Test
    @DisplayName("handle fails when autoRenewPeriod is not set. This should not happen as there should"
            + " be a semantic check in `preHandle` and handle workflow should reject the "
            + "transaction before reaching handle")
    void handleFailsWhenAutoRenewPeriodNotSet() {
        txn = new CryptoCreateBuilder().withNoAutoRenewPeriod().build();
        // newly created account and payer account are not modified. Validate payers balance
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(id.accountNum())));
        assertEquals(payerBalance, writableStore.get(id).tinybarBalance());

        assertThrows(NullPointerException.class, () -> subject.handle(handleContext));
    }

    @Test
    @DisplayName("handle fails when payer account can't pay for the newly created account initial balance")
    void handleFailsWhenPayerHasInsufficientBalance() {
        txn = new CryptoCreateBuilder().withInitialBalance(payerBalance + 1L).build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.networkInfo().nodeInfo(stakeNodeId)).willReturn(nodeInfo);
        given(handleContext.payer()).willReturn(idFactory.newAccountId(id.accountNum()));
        setupConfig();
        setupExpiryValidator();

        // newly created account and payer account are not modified. Validate payers balance
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(id.accountNum())));
        assertEquals(payerBalance, writableStore.get(id).tinybarBalance());

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INSUFFICIENT_PAYER_BALANCE, msg.getStatus());

        verify(recordBuilder, never()).accountID(any());

        // newly created account and payer account are not modified
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(id.accountNum())));
    }

    @Test
    @DisplayName("handle fails when payer account is deleted")
    void handleFailsWhenPayerIsDeleted() {
        given(handleContext.networkInfo().nodeInfo(stakeNodeId)).willReturn(nodeInfo);
        given(handleContext.payer()).willReturn(idFactory.newAccountId(id.accountNum()));
        changeAccountToDeleted();
        setupConfig();
        setupExpiryValidator();
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ACCOUNT_DELETED, msg.getStatus());

        verify(recordBuilder, never()).accountID(any());

        // newly created account and payer account are not modified
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
    }

    @Test
    @DisplayName("handle fails when payer account doesn't exist")
    void handleFailsWhenPayerInvalid() {
        given(handleContext.networkInfo().nodeInfo(stakeNodeId)).willReturn(nodeInfo);
        given(handleContext.payer()).willReturn(idFactory.newAccountId(invalidId.accountNum()));
        txn = new CryptoCreateBuilder()
                .withPayer(AccountID.newBuilder().accountNum(600L).build())
                .build();
        given(handleContext.body()).willReturn(txn);
        setupConfig();
        setupExpiryValidator();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_PAYER_ACCOUNT_ID, msg.getStatus());

        verify(recordBuilder, never()).accountID(any());

        // newly created account and payer account are not modified
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
    }

    @Test
    @DisplayName("handle commits when alias is mentioned in the transaction")
    void handleCommitsAnyAlias() {
        final byte[] evmAddress = CommonUtils.unhex("6aeb3773ea468a814d954e6dec795bfee7d76e26");
        txn = new CryptoCreateBuilder()
                .withAlias(Bytes.wrap(evmAddress))
                .withStakedAccountId(3)
                .build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(idFactory.newAccountId(id.accountNum()));

        given(handleContext.consensusNow()).willReturn(consensusInstant);
        given(entityNumGenerator.newEntityNum()).willReturn(1000L);

        setupConfig();
        setupExpiryValidator();

        // newly created account and payer account are not modified. Validate payers balance
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
        assertFalse(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(id.accountNum())));
        assertEquals(payerBalance, writableStore.get(id).tinybarBalance());

        subject.handle(handleContext);

        // newly created account and payer account are modified
        assertTrue(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(1000L)));
        assertTrue(writableStore.modifiedAccountsInState().contains(idFactory.newAccountId(id.accountNum())));
        assertEquals(
                Bytes.wrap(evmAddress),
                writableStore.get(idFactory.newAccountId(1000L)).alias());
    }

    @Test
    void validateMemo() {
        txn = new CryptoCreateBuilder()
                .withStakedAccountId(3)
                .withMemo("some long memo that is too long")
                .build();
        given(handleContext.body()).willReturn(txn);
        doThrow(new HandleException(MEMO_TOO_LONG)).when(attributeValidator).validateMemo(any());
        setupConfig();
        setupExpiryValidator();
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(MEMO_TOO_LONG, msg.getStatus());
    }

    @Test
    void validateKeyRequired() {
        txn = new CryptoCreateBuilder().withStakedAccountId(3).withKey(null).build();
        setupConfig();
        setupExpiryValidator();
        given(pureChecksContext.body()).willReturn(txn);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(KEY_REQUIRED, msg.responseCode());
    }

    @Test
    void validateKeyRequiredWithAlias() {
        txn = new CryptoCreateBuilder()
                .withStakedAccountId(3)
                .withKey(null)
                .withAlias(Bytes.wrap("alias"))
                .build();
        setupConfig();
        setupExpiryValidator();
        given(pureChecksContext.body()).willReturn(txn);

        final var msg = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(INVALID_ALIAS_KEY, msg.responseCode());
    }

    @Test
    void validateAlias() {
        txn = new CryptoCreateBuilder()
                .withStakedAccountId(3)
                .withKey(key)
                .withAlias(Bytes.wrap("alias"))
                .build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(idFactory.newAccountId(id.accountNum()));
        given(handleContext.consensusNow()).willReturn(consensusInstant);
        setupConfig();
        setupExpiryValidator();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_ALIAS_KEY, msg.getStatus());
    }

    @Test
    void validateAliasInvalid() {
        txn = new CryptoCreateBuilder()
                .withStakedAccountId(3)
                .withKey(key)
                .withAlias(Bytes.wrap("alias"))
                .build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(idFactory.newAccountId(id.accountNum()));
        given(handleContext.consensusNow()).willReturn(consensusInstant);
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
        setupExpiryValidator();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_ALIAS_KEY, msg.getStatus());
    }

    @Test
    void validateContractKey() {
        final var newContractId = ContractID.newBuilder().contractNum(1000L).build();
        txn = new CryptoCreateBuilder()
                .withStakedAccountId(3)
                .withKey(Key.newBuilder().contractID(newContractId).build())
                .build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.consensusNow()).willReturn(consensusInstant);
        given(entityNumGenerator.newEntityNum()).willReturn(1000L);
        given(handleContext.payer()).willReturn(id);
        setupConfig();
        setupExpiryValidator();

        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void validateKeyAlias() {
        txn = new CryptoCreateBuilder()
                .withStakedAccountId(3)
                .withKey(key)
                .withAlias(Bytes.wrap("alias"))
                .build();
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(idFactory.newAccountId(id.accountNum()));
        given(handleContext.consensusNow()).willReturn(consensusInstant);
        setupConfig();
        setupExpiryValidator();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(INVALID_ALIAS_KEY, msg.getStatus());
    }

    @Test
    void validateAliasSigned() {
        txn = new CryptoCreateBuilder()
                .withStakedAccountId(3)
                .withAlias(Bytes.wrap(evmAddress))
                .build();
        given(handleContext.body()).willReturn(txn);
        setupConfig();
        setupExpiryValidator();
        final var writableAliases = emptyWritableAliasStateBuilder()
                .value(new ProtoBytes(Bytes.wrap(evmAddress)), idFactory.newAccountId(accountNum))
                .build();
        given(writableStates.<ProtoBytes, AccountID>get(ALIASES_STATE_ID)).willReturn(writableAliases);
        writableStore = new WritableAccountStore(writableStates, entityCounters);
        when(storeFactory.writableStore(WritableAccountStore.class)).thenReturn(writableStore);

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(ALIAS_ALREADY_ASSIGNED, msg.getStatus());
    }

    @Test
    void validateAutoRenewPeriod() {
        txn = new CryptoCreateBuilder().withStakedAccountId(3).build();
        doThrow(new HandleException(AUTORENEW_DURATION_NOT_IN_RANGE))
                .when(attributeValidator)
                .validateAutoRenewPeriod(anyLong());
        given(handleContext.body()).willReturn(txn);
        setupConfig();
        setupExpiryValidator();
        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(AUTORENEW_DURATION_NOT_IN_RANGE, msg.getStatus());
    }

    @Test
    void validateProxyAccount() {
        txn = new CryptoCreateBuilder()
                .withStakedAccountId(3)
                .withProxyAccountNum(accountNum)
                .build();
        given(handleContext.body()).willReturn(txn);
        setupConfig();
        setupExpiryValidator();

        final var msg = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertEquals(PROXY_ACCOUNT_ID_FIELD_IS_DEPRECATED, msg.getStatus());
    }

    private void changeAccountToDeleted() {
        final var copy = account.copyBuilder().deleted(true).build();
        writableAccounts.put(id, copy);
        given(writableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(writableAccounts);
        writableStore = new WritableAccountStore(writableStates, entityCounters);
    }

    private void setupConfig() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("ledger.maxAutoAssociations", 5000)
                .withValue("entities.limitTokenAssociations", false)
                .withValue("tokens.maxPerAccount", 1000)
                .getOrCreateConfig();
        given(handleContext.configuration()).willReturn(config);
    }

    private void setupExpiryValidator() {
        given(expiryValidator.expirationStatus(notNull(), anyBoolean(), anyLong()))
                .willReturn(ResponseCodeEnum.OK);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
    }

    /**
     * A builder for {@link TransactionBody} instances.
     */
    private class CryptoCreateBuilder {
        private AccountID payer = id;
        private long initialBalance = defaultInitialBalance;
        private long autoRenewPeriod = defaultAutoRenewPeriod;
        private boolean receiverSigReq = true;
        private Bytes alias = null;
        private long sendRecordThreshold = 0;
        private long receiveRecordThreshold = 0;
        private AccountID proxyAccountId = null;
        private long stakedAccountId = 0;
        private long shardId = 0;
        private long realmId = 0;
        private int maxAutoAssociations = -1;

        private Key key = otherKey;

        private String memo = null;

        private CryptoCreateBuilder() {}

        public TransactionBody build() {
            final var transactionID =
                    TransactionID.newBuilder().accountID(payer).transactionValidStart(consensusTimestamp);
            final var createTxnBody = CryptoCreateTransactionBody.newBuilder()
                    .key(key)
                    .shardID(ShardID.newBuilder().shardNum(shardId))
                    .realmID(RealmID.newBuilder().shardNum(shardId).realmNum(realmId))
                    .receiverSigRequired(receiverSigReq)
                    .initialBalance(initialBalance)
                    .memo("Create Account")
                    .sendRecordThreshold(sendRecordThreshold)
                    .receiveRecordThreshold(receiveRecordThreshold);

            if (autoRenewPeriod > 0) {
                createTxnBody.autoRenewPeriod(
                        Duration.newBuilder().seconds(autoRenewPeriod).build());
            }
            if (alias != null) {
                createTxnBody.alias(alias);
            }
            if (proxyAccountId != null) {
                createTxnBody.proxyAccountID(proxyAccountId);
            }
            if (stakedAccountId > 0) {
                createTxnBody.stakedAccountId(idFactory.newAccountId(stakedAccountId));
            } else {
                createTxnBody.stakedNodeId(stakeNodeId);
            }
            if (memo != null) {
                createTxnBody.memo(memo);
            }
            if (maxAutoAssociations != -1) {
                createTxnBody.maxAutomaticTokenAssociations(maxAutoAssociations);
            }

            return TransactionBody.newBuilder()
                    .transactionID(transactionID)
                    .cryptoCreateAccount(createTxnBody.build())
                    .build();
        }

        public CryptoCreateBuilder withPayer(final AccountID payer) {
            this.payer = payer;
            return this;
        }

        public CryptoCreateBuilder withInitialBalance(final long initialBalance) {
            this.initialBalance = initialBalance;
            return this;
        }

        public CryptoCreateBuilder withProxyAccountNum(final long proxyAccountNum) {
            this.proxyAccountId =
                    AccountID.newBuilder().accountNum(proxyAccountNum).build();
            return this;
        }

        public CryptoCreateBuilder withSendRecordThreshold(final long threshold) {
            this.sendRecordThreshold = threshold;
            return this;
        }

        public CryptoCreateBuilder withReceiveRecordThreshold(final long threshold) {
            this.receiveRecordThreshold = threshold;
            return this;
        }

        public CryptoCreateBuilder withAlias(final Bytes alias) {
            this.alias = alias;
            return this;
        }

        public CryptoCreateBuilder withNoAutoRenewPeriod() {
            this.autoRenewPeriod = -1;
            return this;
        }

        public CryptoCreateBuilder withStakedAccountId(final long id) {
            this.stakedAccountId = id;
            return this;
        }

        public CryptoCreateBuilder withReceiverSigReq(final boolean receiverSigReq) {
            this.receiverSigReq = receiverSigReq;
            return this;
        }

        public CryptoCreateBuilder withMemo(final String memo) {
            this.memo = memo;
            return this;
        }

        public CryptoCreateBuilder withKey(final Key key) {
            this.key = key;
            return this;
        }

        public CryptoCreateBuilder withShardId(final long id) {
            this.shardId = id;
            return this;
        }

        public CryptoCreateBuilder withRealmId(final long id) {
            this.realmId = id;
            return this;
        }

        public CryptoCreateBuilder withMaxAutoAssociations(final int maxAutoAssociations) {
            this.maxAutoAssociations = maxAutoAssociations;
            return this;
        }
    }
}
