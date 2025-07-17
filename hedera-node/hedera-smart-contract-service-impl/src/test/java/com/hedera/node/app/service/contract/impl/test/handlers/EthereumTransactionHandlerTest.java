// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITHOUT_TO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITH_TO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HEVM_CREATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SIGNER_NONCE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT_WITH_SIGNER_NONCE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.test.handlers.ContractCallHandlerTest.INTRINSIC_GAS_FOR_0_ARG_METHOD;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.EMPTY_METADATA;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.ETHEREUM_NONCE_INCREMENT_CALLBACK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.ContextTransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.tracers.EvmActionTracer;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaOpsDuration;
import com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData;
import com.hedera.node.app.service.contract.impl.infra.EthTxSigsCache;
import com.hedera.node.app.service.contract.impl.infra.EthereumCallDataHydration;
import com.hedera.node.app.service.contract.impl.infra.HevmTransactionFactory;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.EthereumTransactionStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.metrics.api.Metrics;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EthereumTransactionHandlerTest {
    @Mock
    private EthereumCallDataHydration callDataHydration;

    @Mock
    private EthTxSigsCache ethereumSignatures;

    @Mock
    private ReadableFileStore fileStore;

    @Mock
    private TransactionComponent component;

    @Mock
    private HandleContext handleContext;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private TransactionComponent.Factory factory;

    @Mock
    private EthereumTransactionStreamBuilder recordBuilder;

    @Mock
    private ContractCallStreamBuilder callRecordBuilder;

    @Mock
    private ContractCreateStreamBuilder createRecordBuilder;

    @Mock
    private HandleContext.SavepointStack stack;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private RootProxyWorldUpdater baseProxyWorldUpdater;

    @Mock
    private HevmTransactionFactory hevmTransactionFactory;

    @Mock
    private HederaEvmContext hederaEvmContext;

    @Mock
    private EvmActionTracer tracer;

    @Mock
    private TransactionProcessor transactionProcessor;

    @Mock
    private CustomGasCharging customGasCharging;

    @Mock
    private HederaOperations hederaOperations;

    private EthereumTransactionHandler subject;

    @Mock
    private HederaEvmAccount senderAccount;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private EthTxData ethTxDataReturned;

    @Mock(strictness = Strictness.LENIENT)
    private ContractServiceComponent contractServiceComponent;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private HederaOpsDuration hederaOpsDuration;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private final Metrics metrics = new NoOpMetrics();
    private final ContractMetrics contractMetrics =
            new ContractMetrics(metrics, () -> contractsConfig, systemContractMethodRegistry);

    @BeforeEach
    void setUp() {
        contractMetrics.createContractPrimaryMetrics();
        given(contractServiceComponent.contractMetrics()).willReturn(contractMetrics);
        subject = new EthereumTransactionHandler(
                ethereumSignatures, callDataHydration, () -> factory, gasCalculator, contractServiceComponent);
    }

    void setUpTransactionProcessing() {
        final var defaultContractsConfig = DEFAULT_CONFIG.getConfigData(ContractsConfig.class);

        final var contextTransactionProcessor = new ContextTransactionProcessor(
                HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false),
                handleContext,
                defaultContractsConfig,
                DEFAULT_CONFIG,
                hederaEvmContext,
                null,
                tracer,
                baseProxyWorldUpdater,
                hevmTransactionFactory,
                transactionProcessor,
                customGasCharging,
                hederaOpsDuration);

        given(component.contextTransactionProcessor()).willReturn(contextTransactionProcessor);
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.DEFAULT)
                .build();
        given(handleContext.body()).willReturn(body);
        given(handleContext.payer()).willReturn(AccountID.DEFAULT);
        given(handleContext.dispatchMetadata()).willReturn(EMPTY_METADATA);
        given(hevmTransactionFactory.fromHapiTransaction(handleContext.body(), handleContext.payer()))
                .willReturn(HEVM_CREATION);

        given(transactionProcessor.processTransaction(
                        HEVM_CREATION, baseProxyWorldUpdater, hederaEvmContext, tracer, DEFAULT_CONFIG))
                .willReturn(SUCCESS_RESULT_WITH_SIGNER_NONCE);
    }

    @Test
    void delegatesToCreatedComponentAndExposesEthTxDataCallWithToAddress() {
        given(factory.create(handleContext, ETHEREUM_TRANSACTION)).willReturn(component);
        given(component.hydratedEthTxData()).willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(component.hederaOperations()).willReturn(hederaOperations);
        setUpTransactionProcessing();
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(EthereumTransactionStreamBuilder.class)).willReturn(recordBuilder);
        given(stack.getBaseBuilder(ContractCallStreamBuilder.class)).willReturn(callRecordBuilder);
        givenSenderAccountWithNonce(SIGNER_NONCE);
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        final var expectedResult = SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(
                ETH_DATA_WITH_TO_ADDRESS, baseProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.callData()));
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT_WITH_SIGNER_NONCE.finalStatus(),
                CALLED_CONTRACT_ID,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(
                        ETH_DATA_WITH_TO_ADDRESS, Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.callData())),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                null);
        given(callRecordBuilder.contractID(CALLED_CONTRACT_ID)).willReturn(callRecordBuilder);
        given(callRecordBuilder.contractCallResult(expectedResult)).willReturn(callRecordBuilder);
        given(recordBuilder.ethereumHash(Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.getEthereumHash()), false))
                .willReturn(recordBuilder);
        given(callRecordBuilder.withCommonFieldsSetFrom(expectedOutcome)).willReturn(callRecordBuilder);

        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void setsEthHashOnThrottledContext() {
        given(factory.create(handleContext, ETHEREUM_TRANSACTION)).willReturn(component);
        given(component.hydratedEthTxData()).willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(EthereumTransactionStreamBuilder.class)).willReturn(recordBuilder);
        given(recordBuilder.ethereumHash(Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.getEthereumHash()), false))
                .willReturn(recordBuilder);

        assertDoesNotThrow(() -> subject.handleThrottled(handleContext));
    }

    @Test
    void delegatesToCreatedComponentAndExposesEthTxDataCreateWithoutToAddress() {
        given(factory.create(handleContext, ETHEREUM_TRANSACTION)).willReturn(component);
        given(component.hydratedEthTxData())
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITHOUT_TO_ADDRESS, false));
        given(component.hederaOperations()).willReturn(hederaOperations);
        setUpTransactionProcessing();
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(EthereumTransactionStreamBuilder.class)).willReturn(recordBuilder);
        given(stack.getBaseBuilder(ContractCreateStreamBuilder.class)).willReturn(createRecordBuilder);
        given(baseProxyWorldUpdater.getCreatedContractIds()).willReturn(List.of(CALLED_CONTRACT_ID));
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        final var expectedResult = SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(
                ETH_DATA_WITHOUT_TO_ADDRESS, baseProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData()));
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT_WITH_SIGNER_NONCE.finalStatus(),
                CALLED_CONTRACT_ID,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(CALLED_CONTRACT_ID),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(
                        ETH_DATA_WITHOUT_TO_ADDRESS, Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData())),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.evmAddressIfCreatedIn(baseProxyWorldUpdater));

        given(createRecordBuilder.createdContractID(CALLED_CONTRACT_ID)).willReturn(createRecordBuilder);
        given(createRecordBuilder.contractCreateResult(expectedResult)).willReturn(createRecordBuilder);
        given(createRecordBuilder.evmCreateTransactionResult(any())).willReturn(createRecordBuilder);
        given(createRecordBuilder.createdEvmAddress(any())).willReturn(createRecordBuilder);
        given(createRecordBuilder.withCommonFieldsSetFrom(expectedOutcome)).willReturn(createRecordBuilder);
        given(recordBuilder.ethereumHash(Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.getEthereumHash()), false))
                .willReturn(recordBuilder);
        givenSenderAccountWithNonce(SIGNER_NONCE);

        assertDoesNotThrow(() -> subject.handle(handleContext));
    }

    @Test
    void preHandleCachesTheSignaturesIfDataCanBeHydrated() throws PreCheckException {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(TestHelpers.ETH_WITH_TO_ADDRESS)
                .build();
        final var body =
                TransactionBody.newBuilder().ethereumTransaction(ethTxn).build();
        given(preHandleContext.body()).willReturn(body);
        given(preHandleContext.createStore(ReadableFileStore.class)).willReturn(fileStore);
        given(preHandleContext.configuration()).willReturn(DEFAULT_CONFIG);
        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        subject.preHandle(preHandleContext);
        verify(ethereumSignatures).computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS);
    }

    @Test
    void preHandleTranslatesIseAsInvalidEthereumTransaction() {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(TestHelpers.ETH_WITH_TO_ADDRESS)
                .build();
        final var body =
                TransactionBody.newBuilder().ethereumTransaction(ethTxn).build();
        given(preHandleContext.body()).willReturn(body);
        given(preHandleContext.createStore(ReadableFileStore.class)).willReturn(fileStore);
        given(preHandleContext.configuration()).willReturn(DEFAULT_CONFIG);
        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(ethereumSignatures.computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS))
                .willThrow(new IllegalStateException("Oops"));
        assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), INVALID_ETHEREUM_TRANSACTION);
    }

    @Test
    void preHandleDoesNotIgnoreFailureToHydrate() {
        final var ethTxn =
                EthereumTransactionBody.newBuilder().ethereumData(Bytes.EMPTY).build();
        final var body =
                TransactionBody.newBuilder().ethereumTransaction(ethTxn).build();
        given(preHandleContext.body()).willReturn(body);
        given(preHandleContext.createStore(ReadableFileStore.class)).willReturn(fileStore);
        given(preHandleContext.configuration()).willReturn(DEFAULT_CONFIG);
        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.failureFrom(INVALID_ETHEREUM_TRANSACTION));
        assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), INVALID_ETHEREUM_TRANSACTION);
        verifyNoInteractions(ethereumSignatures);
    }

    @Test
    void testCalculateFeesWithNoEthereumTransactionBody() {
        final var txn = TransactionBody.newBuilder().build();
        final var feeCtx = mock(FeeContext.class);
        given(feeCtx.body()).willReturn(txn);

        final var feeCalcFactory = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFactory);
        given(feeCalcFactory.feeCalculator(notNull())).willReturn(feeCalc);

        assertDoesNotThrow(() -> subject.calculateFees(feeCtx));
    }

    @Test
    void testCalculateFeesWithZeroHapiFeesConfigDisabled() {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(TestHelpers.ETH_WITH_TO_ADDRESS)
                .build();
        final var txn = TransactionBody.newBuilder().ethereumTransaction(ethTxn).build();
        final var feeCtx = mock(FeeContext.class);
        given(feeCtx.body()).willReturn(txn);

        final var feeCalcFactory = mock(FeeCalculatorFactory.class);
        final var feeCalc = mock(FeeCalculator.class);
        given(feeCtx.feeCalculatorFactory()).willReturn(feeCalcFactory);
        given(feeCalcFactory.feeCalculator(notNull())).willReturn(feeCalc);

        assertDoesNotThrow(() -> subject.calculateFees(feeCtx));
        verify(feeCalc).legacyCalculate(any());
    }

    @Test
    void validatePureChecks() {
        // check bad eth txn body
        final var txn1 = ethTxWithNoTx();
        given(pureChecksContext.body()).willReturn(txn1);
        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));

        // check bad to evm address
        try (MockedStatic<EthTxData> ethTxData = Mockito.mockStatic(EthTxData.class)) {
            final var toAddress = new byte[] {1, 0, 1, 0};
            ethTxData.when(() -> EthTxData.populateEthTxData(any())).thenReturn(ethTxDataReturned);
            given(ethTxDataReturned.gasLimit()).willReturn(INTRINSIC_GAS_FOR_0_ARG_METHOD + 1);
            given(ethTxDataReturned.value()).willReturn(BigInteger.ZERO);
            given(ethTxDataReturned.hasToAddress()).willReturn(true);
            given(gasCalculator.transactionIntrinsicGasCost(org.apache.tuweni.bytes.Bytes.wrap(new byte[0]), false))
                    .willReturn(INTRINSIC_GAS_FOR_0_ARG_METHOD);
            given(ethTxDataReturned.to()).willReturn(toAddress);
            given(pureChecksContext.body()).willReturn(ethTxWithTx());
            assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        }

        // check at least intrinsic gas
        try (MockedStatic<EthTxData> ethTxData = Mockito.mockStatic(EthTxData.class)) {
            ethTxData.when(() -> EthTxData.populateEthTxData(any())).thenReturn(ethTxDataReturned);
            given(gasCalculator.transactionIntrinsicGasCost(org.apache.tuweni.bytes.Bytes.wrap(new byte[0]), false))
                    .willReturn(INTRINSIC_GAS_FOR_0_ARG_METHOD);
            given(pureChecksContext.body()).willReturn(ethTxWithTx());
            assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleSetsNewSenderNonceWhenPresent() {
        given(factory.create(handleContext, ETHEREUM_TRANSACTION)).willReturn(component);
        given(component.hydratedEthTxData())
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITHOUT_TO_ADDRESS, false));
        given(component.hederaOperations()).willReturn(hederaOperations);
        setUpTransactionProcessing();
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(EthereumTransactionStreamBuilder.class)).willReturn(recordBuilder);
        given(stack.getBaseBuilder(ContractCreateStreamBuilder.class)).willReturn(createRecordBuilder);
        given(baseProxyWorldUpdater.getCreatedContractIds()).willReturn(List.of(CALLED_CONTRACT_ID));
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        final var expectedResult = SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(
                ETH_DATA_WITHOUT_TO_ADDRESS, baseProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData()));
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT_WITH_SIGNER_NONCE.finalStatus(),
                CALLED_CONTRACT_ID,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(CALLED_CONTRACT_ID),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(
                        ETH_DATA_WITHOUT_TO_ADDRESS, Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData())),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.evmAddressIfCreatedIn(baseProxyWorldUpdater));

        given(createRecordBuilder.createdContractID(CALLED_CONTRACT_ID)).willReturn(createRecordBuilder);
        given(createRecordBuilder.evmCreateTransactionResult(any())).willReturn(createRecordBuilder);
        given(createRecordBuilder.createdEvmAddress(any())).willReturn(createRecordBuilder);
        given(createRecordBuilder.withCommonFieldsSetFrom(expectedOutcome)).willReturn(createRecordBuilder);
        given(recordBuilder.ethereumHash(Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.getEthereumHash()), false))
                .willReturn(recordBuilder);
        givenSenderAccountWithNonce(SIGNER_NONCE);

        // Mock the dispatch metadata with a callback
        final var nonceCallback = mock(BiConsumer.class);
        final var dispatchMetadata = mock(HandleContext.DispatchMetadata.class);
        final var optionalCallback = Optional.of(nonceCallback);
        given(handleContext.dispatchMetadata()).willReturn(dispatchMetadata);
        given(dispatchMetadata.getMetadata(ETHEREUM_NONCE_INCREMENT_CALLBACK, BiConsumer.class))
                .willReturn(optionalCallback);

        // Execute the handler
        assertDoesNotThrow(() -> subject.handle(handleContext));

        // Verify the callback was called with the expected arguments
        verify(nonceCallback).accept(SENDER_ID, SIGNER_NONCE);
        // Verify the stream builder was updated with the new nonce
        verify(recordBuilder).newSenderNonce(SIGNER_NONCE);
    }

    private TransactionBody ethTxWithNoTx() {
        return TransactionBody.newBuilder()
                .ethereumTransaction(EthereumTransactionBody.newBuilder().build())
                .build();
    }

    private TransactionBody ethTxWithTx() {
        return TransactionBody.newBuilder()
                .ethereumTransaction(EthereumTransactionBody.newBuilder()
                        .ethereumData(Bytes.EMPTY)
                        .build())
                .build();
    }

    void givenSenderAccountWithNonce(final long nonce) {
        given(baseProxyWorldUpdater.getHederaAccount(SENDER_ID)).willReturn(senderAccount);
        given(senderAccount.getNonce()).willReturn(nonce);
    }
}
