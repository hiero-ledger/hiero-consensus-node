// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TRANSFER_ACCOUNT_SAME_AS_DELETE_ACCOUNT;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_KEY;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoDeleteTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.api.TokenServiceApiImpl;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.StakingValidator;
import com.hedera.node.app.service.token.records.CryptoDeleteStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.swirlds.state.spi.WritableStates;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoDeleteHandlerTest extends CryptoHandlerTestBase {
    @Mock
    private HandleContext handleContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private ExpiryValidator expiryValidator;

    @Mock
    private StakingValidator stakingValidator;

    @Mock
    private WritableStates writableStates;

    @Mock
    private CryptoDeleteStreamBuilder recordBuilder;

    @Mock
    private HandleContext.SavepointStack stack;

    @Mock
    private WritableEntityCounters entityCounters;

    @Mock
    private PureChecksContext pureChecksContext;

    private CryptoDeleteHandler subject = new CryptoDeleteHandler();

    @BeforeEach
    public void setUp() {
        super.setUp();
        updateReadableStore(
                Map.of(accountNum, account, deleteAccountNum, deleteAccount, transferAccountNum, transferAccount));
        updateWritableStore(
                Map.of(accountNum, account, deleteAccountNum, deleteAccount, transferAccountNum, transferAccount));

        lenient().when(handleContext.configuration()).thenReturn(configuration);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(storeFactory.writableStore(WritableAccountStore.class)).thenReturn(writableStore);
        lenient().when(handleContext.savepointStack()).thenReturn(stack);
    }

    @Test
    void preHandlesCryptoDeleteIfNoReceiverSigRequired() throws PreCheckException {
        final var txn = deleteAccountTransaction(deleteAccountId, transferAccountId);

        final var context = new FakePreHandleContext(readableStore, txn);
        subject.preHandle(context);

        assertEquals(txn, context.body());
        assertEquals(key, context.payerKey());
        basicMetaAssertions(context, 0);
    }

    @Test
    void preHandlesCryptoDeleteIfReceiverSigRequiredVanilla() throws PreCheckException {
        final var txn = deleteAccountTransaction(deleteAccountId, transferAccountId);

        final var context = new FakePreHandleContext(readableStore, txn);
        subject.preHandle(context);

        assertEquals(txn, context.body());
        basicMetaAssertions(context, 0);
        assertEquals(key, context.payerKey());
    }

    @Test
    void doesntAddBothKeysAccountsSameAsPayerForCryptoDelete() throws PreCheckException {
        final var txn = deleteAccountTransaction(deleteAccountId, transferAccountId);

        updateReadableStore(Map.of(
                accountNum,
                account,
                deleteAccountNum,
                deleteAccount.copyBuilder().key(key).build(),
                transferAccountNum,
                transferAccount));

        final var context = new FakePreHandleContext(readableStore, txn);
        subject.preHandle(context);

        assertEquals(txn, context.body());
        basicMetaAssertions(context, 0);
        assertEquals(key, context.payerKey());
        assertIterableEquals(List.of(), context.requiredNonPayerKeys());
    }

    @Test
    void doesntAddTransferKeyIfAccountSameAsPayerForCryptoDelete() throws PreCheckException {
        final var txn = deleteAccountTransaction(deleteAccountId, id);

        updateReadableStore(
                Map.of(accountNum, account, deleteAccountNum, deleteAccount, transferAccountNum, transferAccount));

        final var context = new FakePreHandleContext(readableStore, txn);
        subject.preHandle(context);

        assertEquals(txn, context.body());
        assertEquals(key, context.payerKey());
        basicMetaAssertions(context, 0);
        assertEquals(0, context.requiredNonPayerKeys().size());
    }

    @Test
    void doesntAddDeleteKeyIfAccountSameAsPayerForCryptoDelete() throws PreCheckException {
        final var txn = deleteAccountTransaction(id, transferAccountId);

        final var context = new FakePreHandleContext(readableStore, txn);
        subject.preHandle(context);

        assertEquals(txn, context.body());
        basicMetaAssertions(context, 0);
        assertEquals(key, context.payerKey());
    }

    @Test
    void failsWithResponseCodeIfAnyAccountMissingForCryptoDelete() throws PreCheckException {
        /* ------ payerAccount missing, so deleteAccount and transferAccount will not be added  ------ */
        final var txn = deleteAccountTransaction(deleteAccountId, transferAccountId);
        updateReadableStore(Map.of());

        assertThrowsPreCheck(() -> new FakePreHandleContext(readableStore, txn), INVALID_PAYER_ACCOUNT_ID);

        /* ------ deleteAccount missing, so transferAccount will not be added ------ */
        updateReadableStore(Map.of(accountNum, account, transferAccountNum, transferAccount));

        final var context2 = new FakePreHandleContext(readableStore, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context2), INVALID_ACCOUNT_ID);

        /* ------ transferAccount missing ------ */
        updateReadableStore(Map.of(accountNum, account, deleteAccountNum, deleteAccount));

        final var context3 = new FakePreHandleContext(readableStore, txn);
        assertThrowsPreCheck(() -> subject.preHandle(context3), INVALID_TRANSFER_ACCOUNT_ID);
    }

    @Test
    void doesntExecuteIfAccountIdIsDefaultInstance() throws PreCheckException {
        final var txn = deleteAccountTransaction(deleteAccountId, AccountID.DEFAULT);

        final var context = new FakePreHandleContext(readableStore, txn);
        subject.preHandle(context);

        assertEquals(txn, context.body());
        basicMetaAssertions(context, 0);
        assertEquals(key, context.payerKey());
        assertIterableEquals(List.of(), context.requiredNonPayerKeys());
    }

    @Test
    void pureChecksFailWhenTargetSameAsBeneficiary() throws PreCheckException {
        final var txn = deleteAccountTransaction(deleteAccountId, deleteAccountId);
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(TRANSFER_ACCOUNT_SAME_AS_DELETE_ACCOUNT));
    }

    @Test
    void pureChecksPassForValidTxn() {
        final var txn = deleteAccountTransaction(deleteAccountId, transferAccountId);
        given(pureChecksContext.body()).willReturn(txn);

        assertThatNoException().isThrownBy(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void handleFailsIfDeleteAccountAccountMissing() {
        updateWritableStore(Map.of(accountNum, account, transferAccountNum, transferAccount));
        givenTxnWith(deleteAccountId, transferAccountId);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
    }

    @Test
    void handleFailsIfTransferAccountAccountMissing() {
        updateWritableStore(Map.of(accountNum, account, deleteAccountNum, deleteAccount));

        givenTxnWith(deleteAccountId, transferAccountId);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(INVALID_TRANSFER_ACCOUNT_ID));
    }

    @Test
    void failsIfAccountIsAlreadyDeleted() {
        updateWritableStore(Map.of(
                accountNum,
                account,
                deleteAccountNum,
                deleteAccount.copyBuilder().deleted(true).build(),
                transferAccountNum,
                transferAccount));

        givenTxnWith(deleteAccountId, transferAccountId);
        given(expiryValidator.isDetached(eq(EntityType.ACCOUNT), anyBoolean(), anyLong()))
                .willReturn(false);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_DELETED));
    }

    @Test
    void happyPathWorks() {
        given(writableStates.<ProtoBytes, AccountID>get(ALIASES_KEY)).willReturn(writableAliases);
        updateWritableStore(
                Map.of(accountNum, account, deleteAccountNum, deleteAccount, transferAccountNum, transferAccount));

        givenTxnWith(deleteAccountId, transferAccountId);
        given(expiryValidator.isDetached(eq(EntityType.ACCOUNT), anyBoolean(), anyLong()))
                .willReturn(false);
        given(stack.getBaseBuilder(CryptoDeleteStreamBuilder.class)).willReturn(recordBuilder);

        subject.handle(handleContext);

        // When an account is deleted, marks the value of the account deleted flag to true
        assertThat(writableStore.get(deleteAccountId).deleted()).isTrue();
        verify(recordBuilder).addBeneficiaryForDeletedAccount(deleteAccountId, transferAccountId);
    }

    @Test
    void failsIfDeleteAccountIsDetached() {
        updateWritableStore(
                Map.of(accountNum, account, deleteAccountNum, deleteAccount, transferAccountNum, transferAccount));

        givenTxnWith(deleteAccountId, transferAccountId);
        given(expiryValidator.isDetached(eq(EntityType.ACCOUNT), anyBoolean(), anyLong()))
                .willReturn(true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void failsIfTransferAccountIsDetached() {
        updateWritableStore(
                Map.of(accountNum, account, deleteAccountNum, deleteAccount, transferAccountNum, transferAccount));

        givenTxnWith(deleteAccountId, transferAccountId);
        given(expiryValidator.isDetached(eq(EntityType.ACCOUNT), anyBoolean(), anyLong()))
                .willReturn(true);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL));
    }

    @Test
    void failsIfDeleteAccountIsTreasury() {
        updateWritableStore(Map.of(
                accountNum,
                account,
                deleteAccountNum,
                deleteAccount.copyBuilder().numberTreasuryTitles(2).build(),
                transferAccountNum,
                transferAccount));

        givenTxnWith(deleteAccountId, transferAccountId);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_IS_TREASURY));
    }

    @Test
    void failsIfTargetHasNonZeroBalances() {
        updateWritableStore(Map.of(
                accountNum,
                account,
                deleteAccountNum,
                deleteAccount.copyBuilder().numberPositiveBalances(2).build(),
                transferAccountNum,
                transferAccount));
        givenTxnWith(deleteAccountId, transferAccountId);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES));
    }

    @Test
    void failsIfEitherDeleteOrTransferAccountDoesntExist() throws PreCheckException {
        var txn = deleteAccountTransaction(null, transferAccountId);
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ACCOUNT_ID_DOES_NOT_EXIST));

        txn = deleteAccountTransaction(deleteAccountId, null);
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ACCOUNT_ID_DOES_NOT_EXIST));

        txn = deleteAccountTransaction(null, null);
        given(pureChecksContext.body()).willReturn(txn);
        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ACCOUNT_ID_DOES_NOT_EXIST));
    }

    @Test
    @DisplayName("check that fees are 1 for delete account trx")
    void testCalculateFeesReturnsCorrectFeeForDeleteAccount() {
        final var feeCtx = mock(FeeContext.class);
        final var feeCalcFact = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFact);
        given(feeCalcFact.feeCalculator(any())).willReturn(feeCalc);
        given(feeCalc.legacyCalculate(any())).willReturn(new Fees(1, 0, 0));

        Assertions.assertThat(subject.calculateFees(feeCtx)).isEqualTo(new Fees(1, 0, 0));
    }

    @Test
    void happyPathWithEcdsaKeyWorks() {
        writableAliases = writableAliasesStateWithEcdsaKey();
        given(writableStates.<ProtoBytes, AccountID>get(ALIASES_KEY)).willReturn(writableAliases);

        deleteAccount =
                deleteAccount.copyBuilder().alias(ecdsaAlias.aliasOrThrow()).build();
        writableAccounts = emptyWritableAccountStateBuilder()
                .value(idFactory.newAccountId(accountNum), account)
                .value(idFactory.newAccountId(deleteAccountNum), deleteAccount)
                .value(idFactory.newAccountId(transferAccountNum), transferAccount)
                .build();
        given(writableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(writableAccounts);
        writableStore = new WritableAccountStore(writableStates, entityCounters);

        givenTxnWith(deleteAccountId, transferAccountId);
        given(expiryValidator.isDetached(eq(EntityType.ACCOUNT), anyBoolean(), anyLong()))
                .willReturn(false);
        given(stack.getBaseBuilder(CryptoDeleteStreamBuilder.class)).willReturn(recordBuilder);

        subject.handle(handleContext);

        assertThat(writableStore.get(deleteAccountId).deleted()).isTrue();
        assertThat(writableAliases.get(ecdsaKeyAlias)).isNull();
        assertThat(writableAliases.get(new ProtoBytes(aliasEvmAddress))).isNull();
        verify(recordBuilder).addBeneficiaryForDeletedAccount(deleteAccountId, transferAccountId);
    }

    private TransactionBody deleteAccountTransaction(
            final AccountID deleteAccountId, final AccountID transferAccountId) {
        final var transactionID = TransactionID.newBuilder().accountID(id).transactionValidStart(consensusTimestamp);
        final var deleteTxBody = CryptoDeleteTransactionBody.newBuilder()
                .deleteAccountID(deleteAccountId)
                .transferAccountID(transferAccountId);

        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .cryptoDelete(deleteTxBody)
                .build();
    }

    private void updateReadableStore(Map<Long, Account> accountsToAdd) {
        final var emptyStateBuilder = emptyReadableAccountStateBuilder();
        for (final var entry : accountsToAdd.entrySet()) {
            emptyStateBuilder.value(idFactory.newAccountId(entry.getKey()), entry.getValue());
        }
        readableAccounts = emptyStateBuilder.build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(readableAccounts);
        readableStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
    }

    private void updateWritableStore(Map<Long, Account> accountsToAdd) {
        final var emptyStateBuilder = emptyWritableAccountStateBuilder();
        for (final var entry : accountsToAdd.entrySet()) {
            emptyStateBuilder.value(idFactory.newAccountId(entry.getKey()), entry.getValue());
        }
        writableAccounts = emptyStateBuilder.build();
        given(writableStates.<AccountID, Account>get(ACCOUNTS_KEY)).willReturn(writableAccounts);
        writableStore = new WritableAccountStore(writableStates, entityCounters);
    }

    private void givenTxnWith(AccountID deleteAccountId, AccountID transferAccountId) {
        final var txn = deleteAccountTransaction(deleteAccountId, transferAccountId);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        final var impl = new TokenServiceApiImpl(configuration, writableStates, op -> false, entityCounters);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(impl);
    }
}
