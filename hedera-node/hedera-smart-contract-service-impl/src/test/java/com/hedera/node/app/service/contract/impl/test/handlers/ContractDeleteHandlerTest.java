// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OBTAINER_DOES_NOT_EXIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OBTAINER_REQUIRED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_NEW_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_EOA_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALID_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asNumericContractId;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertFailsWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.contract.ContractDeleteTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.handlers.ContractDeleteHandler;
import com.hedera.node.app.service.contract.impl.records.ContractDeleteStreamBuilder;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractDeleteHandlerTest {
    @Mock
    private HandleContext context;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private ReadableAccountStore readableAccountStore;

    @Mock
    private ContractDeleteStreamBuilder recordBuilder;

    @Mock
    private HandleContext.SavepointStack stack;

    private ContractDeleteHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ContractDeleteHandler(entityIdFactory);
    }

    @Test
    void preHandleRecognizesContractIdKeyAsImmutable() {
        given(preHandleContext.createStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(readableAccountStore.getContractById(VALID_CONTRACT_ADDRESS)).willReturn(TBD_CONTRACT);
        final var txn = TransactionBody.newBuilder()
                .contractDeleteInstance(deletion(VALID_CONTRACT_ADDRESS, CALLED_EOA_ID))
                .build();
        given(preHandleContext.body()).willReturn(txn);

        final var ex = assertThrows(PreCheckException.class, () -> subject.preHandle(preHandleContext));
        assertEquals(MODIFYING_IMMUTABLE_CONTRACT, ex.responseCode());
    }

    @Test
    void pureChecksRejectsPermanentRemoval() {
        final var txn = TransactionBody.newBuilder()
                .contractDeleteInstance(
                        ContractDeleteTransactionBody.newBuilder().permanentRemoval(true))
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        final var exc = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION, exc.responseCode(), "Incorrect response code");
    }

    @Test
    void delegatesUsingObtainerAccountIfSet() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(readableAccountStore.getContractById(VALID_CONTRACT_ADDRESS)).willReturn(TBD_CONTRACT);
        given(readableAccountStore.getAccountById(CALLED_EOA_ID)).willReturn(OBTAINER_ACCOUNT);
        givenSuccessContextWith(deletion(VALID_CONTRACT_ADDRESS, CALLED_EOA_ID));

        subject.handle(context);

        verify(recordBuilder).contractID(asNumericContractId(TBD_CONTRACT.accountIdOrThrow()));
    }

    @Test
    void delegatesUsingObtainerContractIfSet() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(readableAccountStore.getContractById(VALID_CONTRACT_ADDRESS)).willReturn(TBD_CONTRACT);
        given(readableAccountStore.getContractById(CALLED_CONTRACT_ID)).willReturn(OBTAINER_CONTRACT);
        givenSuccessContextWith(deletion(VALID_CONTRACT_ADDRESS, CALLED_CONTRACT_ID));

        subject.handle(context);

        verify(recordBuilder).contractID(asNumericContractId(TBD_CONTRACT.accountIdOrThrow()));
    }

    @Test
    void failsWithoutObtainerSet() {
        final var txn = TransactionBody.newBuilder()
                .contractDeleteInstance(missingObtainer(VALID_CONTRACT_ADDRESS))
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        final var ex = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(OBTAINER_REQUIRED, ex.responseCode());
    }

    @Test
    void failsWithoutObtainerExtant() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(readableAccountStore.getContractById(VALID_CONTRACT_ADDRESS)).willReturn(TBD_CONTRACT);
        givenFailContextWith(deletion(VALID_CONTRACT_ADDRESS, CALLED_EOA_ID));

        assertFailsWith(OBTAINER_DOES_NOT_EXIST, () -> subject.handle(context));
    }

    @Test
    void failsWithInvalidContractId() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        final var deletedObtainer =
                OBTAINER_ACCOUNT.copyBuilder().smartContract(true).deleted(true).build();
        given(readableAccountStore.getContractById(VALID_CONTRACT_ADDRESS)).willReturn(TBD_CONTRACT);
        given(readableAccountStore.getContractById(CALLED_CONTRACT_ID)).willReturn(deletedObtainer);
        givenFailContextWith(deletion(VALID_CONTRACT_ADDRESS, CALLED_CONTRACT_ID));

        assertFailsWith(INVALID_CONTRACT_ID, () -> subject.handle(context));
    }

    @Test
    void failsWithObtainerDeleted() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        final var deletedObtainer = OBTAINER_ACCOUNT
                .copyBuilder()
                .smartContract(false)
                .deleted(true)
                .build();
        given(readableAccountStore.getContractById(VALID_CONTRACT_ADDRESS)).willReturn(TBD_CONTRACT);
        given(readableAccountStore.getContractById(CALLED_CONTRACT_ID)).willReturn(deletedObtainer);
        givenFailContextWith(deletion(VALID_CONTRACT_ADDRESS, CALLED_CONTRACT_ID));

        assertFailsWith(OBTAINER_DOES_NOT_EXIST, () -> subject.handle(context));
    }

    private ContractDeleteTransactionBody missingObtainer(final ContractID targetId) {
        return ContractDeleteTransactionBody.newBuilder().contractID(targetId).build();
    }

    @Test
    void testCalculateFeesWithNoDeleteBody() {
        final var txn = TransactionBody.newBuilder().build();
        final var feeCtx = mock(FeeContext.class);
        given(feeCtx.body()).willReturn(txn);

        final var feeCalcFactory = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFactory);
        given(feeCalcFactory.feeCalculator(notNull())).willReturn(feeCalc);

        assertDoesNotThrow(() -> subject.calculateFees(feeCtx));
    }

    private ContractDeleteTransactionBody deletion(final ContractID targetId, final ContractID transferId) {
        return ContractDeleteTransactionBody.newBuilder()
                .contractID(targetId)
                .transferContractID(transferId)
                .build();
    }

    private ContractDeleteTransactionBody deletion(final ContractID targetId, final AccountID transferId) {
        return ContractDeleteTransactionBody.newBuilder()
                .contractID(targetId)
                .transferAccountID(transferId)
                .build();
    }

    private void givenFailContextWith(@NonNull final ContractDeleteTransactionBody body) {
        final var txn =
                TransactionBody.newBuilder().contractDeleteInstance(body).build();
        given(context.body()).willReturn(txn);
    }

    private void givenSuccessContextWith(@NonNull final ContractDeleteTransactionBody body) {
        final var txn =
                TransactionBody.newBuilder().contractDeleteInstance(body).build();
        given(context.body()).willReturn(txn);
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.serviceApi(TokenServiceApi.class)).willReturn(tokenServiceApi);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(ContractDeleteStreamBuilder.class)).willReturn(recordBuilder);
    }

    private static final Account TBD_CONTRACT = Account.newBuilder()
            .accountId(CALLED_EOA_ID)
            .smartContract(true)
            .key(Key.newBuilder().contractID(CALLED_CONTRACT_ID))
            .build();

    private static final Account OBTAINER_ACCOUNT =
            Account.newBuilder().accountId(A_NEW_ACCOUNT_ID).build();
    private static final Account OBTAINER_CONTRACT =
            Account.newBuilder().accountId(B_NEW_ACCOUNT_ID).smartContract(true).build();
}
