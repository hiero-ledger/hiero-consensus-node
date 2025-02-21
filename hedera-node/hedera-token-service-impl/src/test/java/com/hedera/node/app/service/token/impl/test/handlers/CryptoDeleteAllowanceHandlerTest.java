// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_ALLOWANCES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftRemoveAllowance;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.handlers.CryptoDeleteAllowanceHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.CryptoTokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.validators.DeleteAllowanceValidator;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CryptoDeleteAllowanceHandlerTest extends CryptoTokenHandlerTestBase {
    @Mock(strictness = LENIENT)
    private HandleContext handleContext;

    @Mock(strictness = LENIENT)
    private ExpiryValidator expiryValidator;

    @Mock
    private PureChecksContext pureChecksContext;

    private CryptoDeleteAllowanceHandler subject;

    @BeforeEach
    @Override
    public void setUp() {
        super.setUp();
        final var deleteAllowanceValidator = new DeleteAllowanceValidator();
        subject = new CryptoDeleteAllowanceHandler(deleteAllowanceValidator);
        refreshWritableStores();
        givenStoresAndConfig(handleContext);

        given(handleContext.configuration()).willReturn(configuration);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
    }

    @Test
    void cryptoDeleteAllowanceVanilla() throws PreCheckException {
        final var txn = cryptoDeleteAllowanceTransaction(payerId);
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 1);
        assertEquals(key, context.payerKey());
        assertThat(context.requiredNonPayerKeys()).containsExactlyInAnyOrder(ownerKey);
    }

    @Test
    void cryptoDeleteAllowanceDoesntAddIfOwnerSameAsPayer() throws PreCheckException {
        final var txn = cryptoDeleteAllowanceTransaction(ownerId);
        final var context = new FakePreHandleContext(readableAccountStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 0);
        assertEquals(ownerKey, context.payerKey());
        assertIterableEquals(List.of(), context.requiredNonPayerKeys());
    }

    @Test
    void happyPathDeletesAllowances() {
        writableNftStore.put(nftSl1.copyBuilder().spenderId(spenderId).build());
        writableNftStore.put(nftSl2.copyBuilder().spenderId(spenderId).build());

        final var txn = cryptoDeleteAllowanceTransaction(payerId);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(payerId);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);

        subject.handle(handleContext);

        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();
    }

    @Test
    void testNoOpDeleteAllowancesWhenListIsEmpty() {
        writableNftStore.put(nftSl1.copyBuilder().spenderId(spenderId).build());
        writableNftStore.put(nftSl2.copyBuilder().spenderId(spenderId).build());

        final var txn = allowancesTxn(payerId, List.of());
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(payerId);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);

        subject.handle(handleContext);

        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        // we expect the allowances to not be removed because of no op when list is empty
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNotNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNotNull();
    }

    @Test
    void validateIfSerialsEmpty() {
        final var nftAllowance = NftRemoveAllowance.newBuilder()
                .owner(payerId)
                .tokenId(nonFungibleTokenId)
                .serialNumbers(List.of())
                .build();
        final var txn = allowancesTxn(payerId, List.of(nftAllowance));
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(EMPTY_ALLOWANCES));
    }

    @Test
    void validateIfPureChecksDoesNotThrow() {
        final var nftAllowance = NftRemoveAllowance.newBuilder()
                .owner(payerId)
                .tokenId(nonFungibleTokenId)
                .serialNumbers(List.of(1L, 2L))
                .build();
        final var txn = allowancesTxn(payerId, List.of(nftAllowance));
        given(pureChecksContext.body()).willReturn(txn);

        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void checksEmptyAllowancesInTxn() {
        final var txn = TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder().accountID(payerId).build())
                .cryptoDeleteAllowance(CryptoDeleteAllowanceTransactionBody.newBuilder())
                .build();
        given(pureChecksContext.body()).willReturn(txn);

        assertThatThrownBy(() -> subject.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(EMPTY_ALLOWANCES));
    }

    @Test
    void failsDeleteAllowancesOnInvalidTreasury() {
        writableTokenStore.put(nonFungibleToken
                .copyBuilder()
                .treasuryAccountId(idFactory.newAccountId(200L))
                .build());
        writableNftStore.put(nftSl1.copyBuilder().spenderId(spenderId).build());
        writableNftStore.put(nftSl2.copyBuilder().spenderId(spenderId).build());

        final var txn = cryptoDeleteAllowanceTransaction(payerId);
        given(handleContext.payer()).willReturn(payerId);
        given(handleContext.body()).willReturn(txn);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);

        subject.handle(handleContext);

        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();
    }

    @Test
    void doesntThrowIfAllowanceToBeDeletedDoesNotExist() {
        final var nftAllowance = NftRemoveAllowance.newBuilder()
                .tokenId(nonFungibleTokenId)
                .owner(ownerId)
                .serialNumbers(List.of(1L, 2L))
                .build();

        writableNftStore.put(nftSl1.copyBuilder().ownerId(ownerId).build());
        writableNftStore.put(nftSl2.copyBuilder().ownerId(ownerId).build());

        final var txn = txnWithAllowance(payerId, nftAllowance);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(payerId);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();

        subject.handle(handleContext);
        // No error thrown and no changes to state
        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();
    }

    @Test
    void considersPayerIfOwnerNotSpecifiedAndFailIfDoesntOwn() {
        final var nftAllowance = NftRemoveAllowance.newBuilder()
                .tokenId(nonFungibleTokenId)
                .serialNumbers(List.of(1L, 2L))
                .build();

        writableNftStore.put(nftSl1.copyBuilder().spenderId(spenderId).build());
        writableNftStore.put(nftSl2.copyBuilder().spenderId(spenderId).build());

        final var txn = txnWithAllowance(payerId, nftAllowance);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(payerId);

        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(ownerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);

        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO));
    }

    @Test
    void considersPayerIfOwnerNotSpecified() {
        final var nftAllowance = NftRemoveAllowance.newBuilder()
                .tokenId(nonFungibleTokenId)
                .serialNumbers(List.of(1L, 2L))
                .build();

        writableNftStore.put(
                nftSl1.copyBuilder().ownerId(payerId).spenderId(spenderId).build());
        writableNftStore.put(
                nftSl2.copyBuilder().ownerId(payerId).spenderId(spenderId).build());

        final var txn = txnWithAllowance(payerId, nftAllowance);
        given(handleContext.body()).willReturn(txn);
        given(handleContext.payer()).willReturn(payerId);

        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(payerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(payerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isEqualTo(spenderId);
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isEqualTo(spenderId);

        subject.handle(handleContext);

        assertThat(ownerAccount.approveForAllNftAllowances()).hasSize(1);
        assertThat(writableNftStore.get(nftIdSl1).ownerId()).isEqualTo(payerId);
        assertThat(writableNftStore.get(nftIdSl2).ownerId()).isEqualTo(payerId);
        assertThat(writableNftStore.get(nftIdSl1).spenderId()).isNull();
        assertThat(writableNftStore.get(nftIdSl2).spenderId()).isNull();
    }

    @Test
    @DisplayName("check that fees are 1 for delete account allowance trx")
    void testCalculateFeesReturnsCorrectFeeForDeleteAccountAllowance() {
        final var feeCtx = mock(FeeContext.class);
        final var feeCalcFact = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        final var txnBody = cryptoDeleteAllowanceTransaction(payerId);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFact);
        given(feeCtx.body()).willReturn(txnBody);
        given(feeCalcFact.feeCalculator(any())).willReturn(feeCalc);
        given(feeCalc.addBytesPerTransaction(anyLong())).willReturn(feeCalc);
        given(feeCalc.calculate()).willReturn(new Fees(1, 0, 0));

        assertThat(subject.calculateFees(feeCtx)).isEqualTo(new Fees(1, 0, 0));
    }

    @Test
    @DisplayName("calculate fees correctly considering bytes per transaction")
    void testCalculateFeesConsideringBytesPerTransaction() {
        final var feeCtx = mock(FeeContext.class);
        final var feeCalcFact = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        final var txnBody = cryptoDeleteAllowanceTransaction(payerId);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFact);
        given(feeCtx.body()).willReturn(txnBody);
        given(feeCalcFact.feeCalculator(any())).willReturn(feeCalc);
        final var cryptoDeleteAllowanceTransactionBody = txnBody.cryptoDeleteAllowanceOrThrow();
        final var longSize = 8L;
        final var nftDeleteAllowanceSize = 6 * longSize;
        final var bytesPerTransaction =
                cryptoDeleteAllowanceTransactionBody.nftAllowances().size() * nftDeleteAllowanceSize + (2 * longSize);
        given(feeCalc.addBytesPerTransaction(bytesPerTransaction)).willReturn(feeCalc);
        given(feeCalc.calculate()).willReturn(new Fees(1, 0, 0));

        assertThat(subject.calculateFees(feeCtx)).isEqualTo(new Fees(1, 0, 0));
    }

    private TransactionBody cryptoDeleteAllowanceTransaction(final AccountID txnPayer) {
        final var nftAllowance = NftRemoveAllowance.newBuilder()
                .owner(ownerId)
                .tokenId(nonFungibleTokenId)
                .serialNumbers(List.of(1L, 2L))
                .build();
        return allowancesTxn(txnPayer, List.of(nftAllowance));
    }

    private TransactionBody txnWithAllowance(final AccountID id, NftRemoveAllowance allowance) {
        return allowancesTxn(id, List.of(allowance));
    }

    private TransactionBody allowancesTxn(final AccountID id, List<NftRemoveAllowance> nftAllowances) {
        final var transactionID = TransactionID.newBuilder().accountID(id).transactionValidStart(consensusTimestamp);
        final var allowanceTxnBody = CryptoDeleteAllowanceTransactionBody.newBuilder()
                .nftAllowances(nftAllowances)
                .build();
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .cryptoDeleteAllowance(allowanceTxnBody)
                .build();
    }
}
