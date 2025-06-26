// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HALT_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertFailsWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.handlers.ContractCallHandlerTest.INTRINSIC_GAS_FOR_0_ARG_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.metrics.api.Metrics;
import java.util.List;
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
    private HandleContext handleContext;

    @Mock
    private HederaOperations hederaOperations;

    @Mock
    private TransactionComponent.Factory factory;

    @Mock
    private ContextTransactionProcessor processor;

    @Mock
    private ContractCreateStreamBuilder recordBuilder;

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
        given(factory.create(handleContext, HederaFunctionality.CONTRACT_CREATE))
                .willReturn(component);
        given(component.contextTransactionProcessor()).willReturn(processor);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(ContractCreateStreamBuilder.class)).willReturn(recordBuilder);
        given(baseProxyWorldUpdater.getCreatedContractIds()).willReturn(List.of(CALLED_CONTRACT_ID));
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        given(component.hederaOperations()).willReturn(hederaOperations);
        final var expectedResult = SUCCESS_RESULT.asProtoResultOf(baseProxyWorldUpdater);
        final var expectedOutcome =
                new CallOutcome(expectedResult, SUCCESS_RESULT.finalStatus(), null, null, null, null, null);
        given(processor.call()).willReturn(expectedOutcome);

        given(recordBuilder.createdContractID(CALLED_CONTRACT_ID)).willReturn(recordBuilder);
        given(recordBuilder.contractCreateResult(expectedResult)).willReturn(recordBuilder);
        given(recordBuilder.withCommonFieldsSetFrom(expectedOutcome)).willReturn(recordBuilder);

        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void delegatesToCreatedComponentAndThrowsFailure() {
        given(factory.create(handleContext, HederaFunctionality.CONTRACT_CREATE))
                .willReturn(component);
        given(component.contextTransactionProcessor()).willReturn(processor);
        given(component.hederaOperations()).willReturn(hederaOperations);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(ContractCreateStreamBuilder.class)).willReturn(recordBuilder);
        final var expectedResult = HALT_RESULT.asProtoResultOf(baseProxyWorldUpdater);
        final var expectedOutcome =
                new CallOutcome(expectedResult, HALT_RESULT.finalStatus(), null, null, null, null, null);
        given(processor.call()).willReturn(expectedOutcome);

        given(recordBuilder.createdContractID(null)).willReturn(recordBuilder);
        given(recordBuilder.contractCreateResult(expectedResult)).willReturn(recordBuilder);
        given(recordBuilder.withCommonFieldsSetFrom(expectedOutcome)).willReturn(recordBuilder);
        assertFailsWith(INVALID_SIGNATURE, () -> subject.handle(handleContext));
    }

    @Test
    @DisplayName("Adds valid admin key")
    void validAdminKey() throws PreCheckException {
        final var txn = contractCreateTransaction(adminKey, null);
        final var context = new FakePreHandleContext(accountStore, txn);
        subject.preHandle(context);

        basicMetaAssertions(context, 1);
        assertThat(context.payerKey()).isEqualTo(payerKey);
        //        FUTURE: uncomment this after JKey removal
        //        assertIterableEquals(List.of(adminHederaKey), meta.requiredNonPayerKeys());
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
        //        FUTURE: uncomment this after JKey removal
        //        assertEquals(List.of(adminHederaKey, autoRenewHederaKey),
        // meta.requiredNonPayerKeys());
    }

    @Test
    @DisplayName("validate checks in pureChecks()")
    void validatePureChecks() {
        // check at least intrinsic gas
        final var txn1 = contractCreateTransactionWithInsufficientGas();
        given(gasCalculator.transactionIntrinsicGasCost(org.apache.tuweni.bytes.Bytes.wrap(new byte[0]), true))
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

    private TransactionBody contractCreateTransactionWithInsufficientGas() {
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .contractCreateInstance(
                        ContractCreateTransactionBody.newBuilder().gas(INTRINSIC_GAS_FOR_0_ARG_METHOD - 1))
                .build();
    }
}
