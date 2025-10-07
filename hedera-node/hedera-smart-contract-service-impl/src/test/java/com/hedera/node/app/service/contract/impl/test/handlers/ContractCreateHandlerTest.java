// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HALT_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertFailsWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.handlers.ContractCallHandlerTest.INTRINSIC_GAS_FOR_0_ARG_METHOD;
import static com.hedera.node.app.service.contract.impl.test.handlers.ContractUpdateHandlerTest.hookDetails;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.ContextTransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.handlers.ContractCreateHandler;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStates;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.metrics.api.Metrics;
import java.util.List;
import java.util.Set;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;

class ContractCreateHandlerTest extends ContractHandlerTestBase {

    private final TransactionID transactionID = TransactionID.newBuilder()
            .accountID(payer)
            .transactionValidStart(consensusTimestamp)
            .build();

    @Mock
    private RootProxyWorldUpdater baseProxyWorldUpdater;

    @Mock
    private TransactionComponent component;

    @Mock
    private HandleContext context;

    @Mock
    private HederaOperations hederaOperations;

    @Mock
    private TransactionComponent.Factory factory;

    @Mock
    private ContextTransactionProcessor processor;

    @Mock
    private ContractCreateStreamBuilder streamBuilder;

    @Mock
    private HookDispatchStreamBuilder hookDispatchStreamBuilder;

    @Mock
    private HandleContext.SavepointStack stack;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private GasCalculator gasCalculator;

    @Mock(strictness = Strictness.LENIENT)
    private ContractServiceComponent contractServiceComponent;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private TokenServiceApi tokenServiceApi;

    @Mock
    private EvmFrameStates evmFrameStates;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private final Metrics metrics = new NoOpMetrics();
    private final ContractMetrics contractMetrics =
            new ContractMetrics(metrics, () -> contractsConfig, systemContractMethodRegistry);

    private ContractCreateHandler subject;

    @BeforeEach
    void setUp() {
        contractMetrics.createContractPrimaryMetrics();
        given(contractServiceComponent.contractMetrics()).willReturn(contractMetrics);
        subject = new ContractCreateHandler(() -> factory, gasCalculator, contractServiceComponent);
    }

    @Test
    void delegatesToCreatedComponentAndExposesSuccess() {
        final var account = mock(Account.class);
        final var builder = Account.newBuilder();

        given(factory.create(context, HederaFunctionality.CONTRACT_CREATE, EvmFrameStates.DEFAULT))
                .willReturn(component);
        given(component.contextTransactionProcessor()).willReturn(processor);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(ContractCreateStreamBuilder.class)).willReturn(streamBuilder);
        given(baseProxyWorldUpdater.getCreatedContractIds()).willReturn(List.of(CALLED_CONTRACT_ID));
        given(context.body()).willReturn(contractCreateTransactionWithInsufficientGas());
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        given(component.hederaOperations()).willReturn(hederaOperations);
        final var expectedResult = SUCCESS_RESULT.asProtoResultOf(null, baseProxyWorldUpdater, null);
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT.finalStatus(),
                null,
                null,
                null,
                null,
                null,
                SUCCESS_RESULT.asEvmTxResultOf(null, null),
                SUCCESS_RESULT.signerNonce(),
                Bytes.EMPTY,
                null);
        given(processor.call()).willReturn(expectedOutcome);

        given(streamBuilder.createdContractID(CALLED_CONTRACT_ID)).willReturn(streamBuilder);
        given(streamBuilder.createdEvmAddress(any())).willReturn(streamBuilder);
        given(streamBuilder.evmCreateTransactionResult(any())).willReturn(streamBuilder);
        given(streamBuilder.contractCreateResult(expectedResult)).willReturn(streamBuilder);
        given(streamBuilder.withCommonFieldsSetFrom(expectedOutcome, context)).willReturn(streamBuilder);
        when(storeFactory.serviceApi(TokenServiceApi.class)).thenReturn(tokenServiceApi);

        given(context.storeFactory()).willReturn(storeFactory);
        when(storeFactory.readableStore(ReadableAccountStore.class)).thenReturn(accountStore);
        when(accountStore.getContractById(CALLED_CONTRACT_ID)).thenReturn(account);
        when(account.copyBuilder()).thenReturn(builder);
        given(context.payer()).willReturn(payer);
        given(context.dispatch(any(DispatchOptions.class))).willReturn(hookDispatchStreamBuilder);
        when(hookDispatchStreamBuilder.status()).thenReturn(SUCCESS);

        assertDoesNotThrow(() -> subject.handle(context));

        verify(tokenServiceApi, times(1)).updateContract(any());
        verify(context, times(2)).dispatch(any(DispatchOptions.class));
        assertThat(builder.build().numberHooksInUse()).isEqualTo(2);
        assertThat(builder.build().firstHookId()).isEqualTo(1);
    }

    @Test
    void delegatesToCreatedComponentAndThrowsFailure() {
        given(factory.create(context, HederaFunctionality.CONTRACT_CREATE, EvmFrameStates.DEFAULT))
                .willReturn(component);
        given(component.contextTransactionProcessor()).willReturn(processor);
        given(component.hederaOperations()).willReturn(hederaOperations);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(ContractCreateStreamBuilder.class)).willReturn(streamBuilder);
        final var expectedResult = HALT_RESULT.asProtoResultOf(null, baseProxyWorldUpdater, null);
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                HALT_RESULT.finalStatus(),
                null,
                null,
                null,
                null,
                null,
                HALT_RESULT.asEvmTxResultOf(null, null),
                null,
                null,
                null);
        given(processor.call()).willReturn(expectedOutcome);

        given(streamBuilder.createdContractID(null)).willReturn(streamBuilder);
        given(streamBuilder.contractCreateResult(expectedResult)).willReturn(streamBuilder);
        given(streamBuilder.createdEvmAddress(any())).willReturn(streamBuilder);
        given(streamBuilder.evmCreateTransactionResult(any())).willReturn(streamBuilder);
        given(streamBuilder.withCommonFieldsSetFrom(expectedOutcome, context)).willReturn(streamBuilder);
        assertFailsWith(INVALID_SIGNATURE, () -> subject.handle(context));
    }

    @Test
    @DisplayName("Adds valid admin key")
    void validAdminKey() throws PreCheckException {
        final var txn = contractCreateTransaction(adminKey, null);
        final var context = new FakePreHandleContext(accountStore, txn);
        subject.preHandle(context);

        basicMetaAssertions(context, 1);
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertIterableEquals(Set.of(adminKey), context.requiredNonPayerKeys());
    }

    @Test
    @DisplayName("admin key with contractID is not added")
    void adminKeyWithContractID() throws PreCheckException {
        final var txn = contractCreateTransaction(adminContractKey, null);
        final var context = new FakePreHandleContext(accountStore, txn);
        subject.preHandle(context);

        basicMetaAssertions(context, 0);
        assertThat(context.payerKey()).isEqualTo(payerKey);
    }

    @Test
    @DisplayName("autoRenew account key is added")
    void autoRenewAccountIdAdded() throws PreCheckException {
        final var txn = contractCreateTransaction(adminContractKey, autoRenewAccountId);
        final var context = new FakePreHandleContext(accountStore, txn);
        subject.preHandle(context);

        basicMetaAssertions(context, 1);
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertThat(context.requiredNonPayerKeys()).containsExactlyInAnyOrder(autoRenewKey);
    }

    @Test
    @DisplayName("autoRenew account key is not added when it is sentinel value")
    void autoRenewAccountIdAsSentinelNotAdded() throws PreCheckException {
        final var txn = contractCreateTransaction(adminContractKey, asAccount("0.0.0"));
        final var context = new FakePreHandleContext(accountStore, txn);
        subject.preHandle(context);

        basicMetaAssertions(context, 0);
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertThat(context.requiredNonPayerKeys()).isEmpty();
    }

    @Test
    @DisplayName("autoRenew account and adminKey both added")
    void autoRenewAccountIdAndAdminBothAdded() throws PreCheckException {
        final var txn = contractCreateTransaction(adminKey, autoRenewAccountId);
        final var context = new FakePreHandleContext(accountStore, txn);
        subject.preHandle(context);

        basicMetaAssertions(context, 2);
        assertThat(context.payerKey()).isEqualTo(payerKey);
        assertEquals(Set.of(adminKey, autoRenewKey), context.requiredNonPayerKeys());
    }

    @Test
    @DisplayName("validate checks in pureChecks()")
    void validatePureChecks() {
        // check at least intrinsic gas
        final var txn1 = contractCreateTransactionWithInsufficientGas();
        given(gasCalculator.transactionIntrinsicGasCost(org.apache.tuweni.bytes.Bytes.wrap(new byte[0]), true, 0L))
                .willReturn(INTRINSIC_GAS_FOR_0_ARG_METHOD);
        given(pureChecksContext.body()).willReturn(txn1);
        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
    }

    private TransactionBody contractCreateTransaction(final Key adminKey, final AccountID autoRenewId) {
        final var createTxnBody = ContractCreateTransactionBody.newBuilder().memo("Create Contract");
        if (adminKey != null) {
            createTxnBody.adminKey(adminKey);
        }

        if (autoRenewId != null) {
            if (!autoRenewId.equals(asAccount("0.0.0"))) {
                final var autoRenewAccount = mock(Account.class);
                given(accountStore.getAccountById(autoRenewId)).willReturn(autoRenewAccount);
                given(autoRenewAccount.key()).willReturn(autoRenewKey);
            }
            createTxnBody.autoRenewAccountId(autoRenewId);
        }

        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .contractCreateInstance(createTxnBody)
                .build();
    }

    @Test
    void testCalculateFeesWithNoCreateBody() {
        final var txn =
                TransactionBody.newBuilder().transactionID(transactionID).build();
        final var feeCtx = mock(FeeContext.class);
        given(feeCtx.body()).willReturn(txn);

        final var feeCalcFactory = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFactory);
        given(feeCalcFactory.feeCalculator(notNull())).willReturn(feeCalc);

        assertDoesNotThrow(() -> subject.calculateFees(feeCtx));
    }

    @Test
    void validateRepeatedHookIds() {
        final var txn = TransactionBody.newBuilder()
                .transactionID(transactionID)
                .contractCreateInstance(ContractCreateTransactionBody.newBuilder()
                        .gas(INTRINSIC_GAS_FOR_0_ARG_METHOD)
                        .hookCreationDetails(hookDetails(1L), hookDetails(1L)))
                .build();
        given(pureChecksContext.body()).willReturn(txn);
        assertThrowsPreCheck(() -> subject.pureChecks(pureChecksContext), HOOK_ID_REPEATED_IN_CREATION_DETAILS);
    }

    private TransactionBody contractCreateTransactionWithInsufficientGas() {
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .contractCreateInstance(ContractCreateTransactionBody.newBuilder()
                        .gas(INTRINSIC_GAS_FOR_0_ARG_METHOD - 1)
                        .hookCreationDetails(hookDetails(1L), hookDetails(2L)))
                .build();
    }
}
