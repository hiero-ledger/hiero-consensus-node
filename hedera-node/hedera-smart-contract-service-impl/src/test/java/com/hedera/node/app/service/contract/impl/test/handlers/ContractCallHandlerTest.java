// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HALT_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertFailsWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.ContextTransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.handlers.ContractCallHandler;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStateFactory;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStates;
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
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractCallHandlerTest extends ContractHandlerTestBase {
    public static final long INTRINSIC_GAS_FOR_0_ARG_METHOD = 21064L;

    @Mock
    private TransactionComponent component;

    @Mock
    private HandleContext context;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private TransactionComponent.Factory factory;

    @Mock
    private ContextTransactionProcessor processor;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    @Mock
    private HandleContext.SavepointStack stack;

    @Mock
    private RootProxyWorldUpdater baseProxyWorldUpdater;

    @Mock
    private HederaOperations hederaOperations;

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private CodeFactory codeFactory;

    @Mock
    private GasCalculator gasCalculator;

    @Mock(strictness = Strictness.LENIENT)
    private ContractServiceComponent contractServiceComponent;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private EvmFrameStates evmFrameStates;

    @Mock
    private EvmFrameStateFactory evmFrameStateFactory;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private final Metrics metrics = new NoOpMetrics();
    private final ContractMetrics contractMetrics =
            new ContractMetrics(metrics, () -> contractsConfig, systemContractMethodRegistry);

    private ContractCallHandler subject;

    @BeforeEach
    void setUp() {
        contractMetrics.createContractPrimaryMetrics();
        given(contractServiceComponent.contractMetrics()).willReturn(contractMetrics);
        subject = new ContractCallHandler(() -> factory, gasCalculator, contractServiceComponent);
    }

    @Test
    void delegatesToCreatedComponentAndExposesSuccess() {
        given(factory.create(context, HederaFunctionality.CONTRACT_CALL, EvmFrameStates.DEFAULT))
                .willReturn(component);
        given(component.contextTransactionProcessor()).willReturn(processor);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(ContractCallStreamBuilder.class)).willReturn(recordBuilder);
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        final var expectedResult = SUCCESS_RESULT.asProtoResultOf(null, baseProxyWorldUpdater, null);
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT.finalStatus(),
                CALLED_CONTRACT_ID,
                null,
                null,
                null,
                null,
                SUCCESS_RESULT.asEvmTxResultOf(null, null),
                SUCCESS_RESULT.signerNonce(),
                null,
                null);
        given(processor.call()).willReturn(expectedOutcome);
        given(component.hederaOperations()).willReturn(hederaOperations);

        given(recordBuilder.contractID(CALLED_CONTRACT_ID)).willReturn(recordBuilder);
        given(recordBuilder.contractCallResult(expectedResult)).willReturn(recordBuilder);
        given(recordBuilder.withCommonFieldsSetFrom(expectedOutcome, context)).willReturn(recordBuilder);

        assertDoesNotThrow(() -> subject.handle(context));
    }

    @Test
    void delegatesToCreatedComponentAndThrowsOnFailure() {
        given(factory.create(context, HederaFunctionality.CONTRACT_CALL, EvmFrameStates.DEFAULT))
                .willReturn(component);
        given(component.contextTransactionProcessor()).willReturn(processor);
        given(component.hederaOperations()).willReturn(hederaOperations);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(ContractCallStreamBuilder.class)).willReturn(recordBuilder);
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

        given(recordBuilder.contractID(null)).willReturn(recordBuilder);
        given(recordBuilder.contractCallResult(expectedResult)).willReturn(recordBuilder);
        given(recordBuilder.withCommonFieldsSetFrom(expectedOutcome, context)).willReturn(recordBuilder);

        assertFailsWith(INVALID_SIGNATURE, () -> subject.handle(context));
    }

    @Test
    @DisplayName("Succeeds for valid payer account")
    void validPayer() throws PreCheckException {
        final var txn = contractCallTransaction();
        final var context = new FakePreHandleContext(accountStore, txn);
        subject.preHandle(context);
        basicMetaAssertions(context, 0);
        assertThat(context.payerKey()).isEqualTo(payerKey);
    }

    @Test
    void validatePureChecks() {
        // check null contact id
        final var txn1 = contractCallTransactionWithNoContractId();
        given(pureChecksContext.body()).willReturn(txn1);
        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));

        // check at least intrinsic gas
        final var txn2 = contractCallTransactionWithInsufficientGas();
        given(gasCalculator.transactionIntrinsicGasCost(org.apache.tuweni.bytes.Bytes.wrap(new byte[0]), false, 0L))
                .willReturn(INTRINSIC_GAS_FOR_0_ARG_METHOD);
        given(pureChecksContext.body()).willReturn(txn2);
        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));

        // check that invalid contract id is rejected
        final var txn3 = contractCallTransactionWithInvalidContractId();
        given(pureChecksContext.body()).willReturn(txn3);
        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void testCalculateFeesWithoutCallBody() {
        final var txn = TransactionBody.newBuilder().build();
        final var feeCtx = mock(FeeContext.class);
        given(feeCtx.body()).willReturn(txn);

        final var feeCalcFactory = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFactory);
        given(feeCalcFactory.feeCalculator(notNull())).willReturn(feeCalc);

        assertDoesNotThrow(() -> subject.calculateFees(feeCtx));
    }

    private TransactionBody contractCallTransaction() {
        final var transactionID = TransactionID.newBuilder().accountID(payer).transactionValidStart(consensusTimestamp);
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .gas(1_234)
                        .amount(1_234L)
                        .contractID(targetContract))
                .build();
    }

    private TransactionBody contractCallTransactionWithNoContractId() {
        final var transactionID = TransactionID.newBuilder().accountID(payer).transactionValidStart(consensusTimestamp);
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .contractCall(ContractCallTransactionBody.newBuilder())
                .build();
    }

    private TransactionBody contractCallTransactionWithInvalidContractId() {
        final var transactionID = TransactionID.newBuilder().accountID(payer).transactionValidStart(consensusTimestamp);
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .gas(INTRINSIC_GAS_FOR_0_ARG_METHOD - 1)
                        .contractID(invalidContract))
                .build();
    }

    private TransactionBody contractCallTransactionWithInsufficientGas() {
        final var transactionID = TransactionID.newBuilder().accountID(payer).transactionValidStart(consensusTimestamp);
        return TransactionBody.newBuilder()
                .transactionID(transactionID)
                .contractCall(ContractCallTransactionBody.newBuilder()
                        .gas(INTRINSIC_GAS_FOR_0_ARG_METHOD - 1)
                        .contractID(targetContract))
                .build();
    }
}
