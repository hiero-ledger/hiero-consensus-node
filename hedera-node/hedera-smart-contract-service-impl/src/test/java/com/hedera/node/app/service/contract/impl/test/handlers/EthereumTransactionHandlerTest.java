// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.*;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hedera.hapi.util.HapiUtils.EMPTY_KEY_LIST;
import static com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler.adminKeyMatchesEcdsaPubKey;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.A_SECP256K1_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.B_SECP256K1_KEY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITHOUT_TO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITH_TO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HEVM_CREATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SIGNER_NONCE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT_WITH_SIGNER_NONCE;
import static com.hedera.node.app.service.contract.impl.test.handlers.ContractCallHandlerTest.INTRINSIC_GAS_FOR_0_ARG_METHOD;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.EMPTY_METADATA;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.BATCH_ROLLBACK_CALLBACK_CONSUMER;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.node.app.hapi.utils.ethereum.EthTxSigs;
import com.hedera.node.app.service.contract.impl.ContractServiceComponent;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.ContextTransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.TransactionComponent;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.delegation.CodeDelegationResult;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.gas.GasCharges;
import com.hedera.node.app.service.contract.impl.exec.gas.HederaGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.tracers.EvmActionTracer;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData;
import com.hedera.node.app.service.contract.impl.infra.EthTxSigsCache;
import com.hedera.node.app.service.contract.impl.infra.EthereumCallDataHydration;
import com.hedera.node.app.service.contract.impl.infra.HevmTransactionFactory;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.ContractCreateStreamBuilder;
import com.hedera.node.app.service.contract.impl.records.EthereumTransactionStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.AbstractMutableEvmAccount;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStates;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
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
    private static final Configuration BOTH_MODE_CONFIG = HederaTestConfigBuilder.create()
            .withValue("blockStream.streamMode", "BOTH")
            .getOrCreateConfig();
    private static final Configuration BLOCKS_MODE_CONFIG = HederaTestConfigBuilder.create()
            .withValue("blockStream.streamMode", "BLOCKS")
            .getOrCreateConfig();

    @Mock
    private EthereumCallDataHydration callDataHydration;

    @Mock
    private EthTxSigsCache ethereumSignatures;

    @Mock
    private ReadableFileStore fileStore;

    @Mock
    private ReadableAccountStore accountStore;

    @Mock
    private TransactionComponent component;

    @Mock
    private HandleContext context;

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

    @Mock
    private HederaWorldUpdater.Enhancement enhancement;

    private EthereumTransactionHandler subject;

    @Mock
    private AbstractMutableEvmAccount senderAccount;

    @Mock
    private HederaGasCalculator gasCalculator;

    @Mock
    private EthTxData ethTxDataReturned;

    @Mock(strictness = Strictness.LENIENT)
    private ContractServiceComponent contractServiceComponent;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private EntityIdFactory entityIdFactory;

    private final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    private final Metrics metrics = new NoOpMetrics();
    private final ContractMetrics contractMetrics =
            new ContractMetrics(metrics, () -> contractsConfig, systemContractMethodRegistry);

    private OpsDurationCounter opsDurationCounter;

    @BeforeEach
    void setUp() {
        contractMetrics.createContractPrimaryMetrics();
        given(contractServiceComponent.contractMetrics()).willReturn(contractMetrics);
        subject = new EthereumTransactionHandler(
                ethereumSignatures,
                callDataHydration,
                () -> factory,
                gasCalculator,
                entityIdFactory,
                contractServiceComponent);
        opsDurationCounter = OpsDurationCounter.disabled();
    }

    void setUpTransactionProcessing() {
        final var defaultContractsConfig = DEFAULT_CONFIG.getConfigData(ContractsConfig.class);

        final var contextTransactionProcessor = new ContextTransactionProcessor(
                HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false),
                context,
                defaultContractsConfig,
                DEFAULT_CONFIG,
                hederaEvmContext,
                null,
                tracer,
                baseProxyWorldUpdater,
                hevmTransactionFactory,
                transactionProcessor,
                customGasCharging,
                contractMetrics);

        given(component.contextTransactionProcessor()).willReturn(contextTransactionProcessor);
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.DEFAULT)
                .build();
        given(context.body()).willReturn(body);
        given(context.payer()).willReturn(AccountID.DEFAULT);
        given(context.dispatchMetadata()).willReturn(EMPTY_METADATA);
        given(hevmTransactionFactory.fromHapiTransaction(context.body(), context.payer()))
                .willReturn(HEVM_CREATION);

        given(transactionProcessor.processTransaction(
                        HEVM_CREATION,
                        baseProxyWorldUpdater,
                        hederaEvmContext,
                        tracer,
                        DEFAULT_CONFIG,
                        opsDurationCounter))
                .willReturn(SUCCESS_RESULT_WITH_SIGNER_NONCE);
    }

    @Test
    void delegatesToCreatedComponentAndExposesEthTxDataCallWithToAddress() {
        given(factory.create(context, ETHEREUM_TRANSACTION, EvmFrameStates.DEFAULT))
                .willReturn(component);
        given(component.hydratedEthTxData()).willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(component.hederaOperations()).willReturn(hederaOperations);
        setUpTransactionProcessing();
        given(context.configuration()).willReturn(BOTH_MODE_CONFIG);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(EthereumTransactionStreamBuilder.class)).willReturn(recordBuilder);
        given(stack.getBaseBuilder(ContractCallStreamBuilder.class)).willReturn(callRecordBuilder);
        givenSenderAccountWithNonce(SIGNER_NONCE);
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        given(enhancement.operations()).willReturn(hederaOperations);
        given(baseProxyWorldUpdater.enhancement()).willReturn(enhancement);

        final var expectedResult = SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(
                ETH_DATA_WITH_TO_ADDRESS, baseProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.callData()));
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT_WITH_SIGNER_NONCE.finalStatus(),
                CALLED_CONTRACT_ID,
                null,
                null,
                List.of(),
                List.of(),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(
                        ETH_DATA_WITH_TO_ADDRESS,
                        baseProxyWorldUpdater,
                        Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.callData()),
                        null),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                null,
                null,
                CodeDelegationResult.EMPTY);
        given(callRecordBuilder.contractID(CALLED_CONTRACT_ID)).willReturn(callRecordBuilder);
        given(callRecordBuilder.contractCallResult(expectedResult)).willReturn(callRecordBuilder);
        given(recordBuilder.ethereumHash(Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.getEthereumHash())))
                .willReturn(recordBuilder);
        given(callRecordBuilder.withCommonFieldsSetFrom(expectedOutcome, context, entityIdFactory))
                .willReturn(callRecordBuilder);

        assertDoesNotThrow(() -> subject.handle(context));
    }

    @Test
    void setsEthHashOnThrottledContext() {
        given(factory.create(context, ETHEREUM_TRANSACTION, EvmFrameStates.DEFAULT))
                .willReturn(component);
        given(component.hydratedEthTxData()).willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(EthereumTransactionStreamBuilder.class)).willReturn(recordBuilder);
        given(recordBuilder.ethereumHash(Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.getEthereumHash())))
                .willReturn(recordBuilder);

        assertDoesNotThrow(() -> subject.handleThrottled(context));
    }

    @Test
    void delegatesToCreatedComponentAndExposesEthTxDataCreateWithoutToAddress() {
        given(factory.create(context, ETHEREUM_TRANSACTION, EvmFrameStates.DEFAULT))
                .willReturn(component);
        given(component.hydratedEthTxData())
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITHOUT_TO_ADDRESS, false));
        given(component.hederaOperations()).willReturn(hederaOperations);
        setUpTransactionProcessing();
        given(context.configuration()).willReturn(BOTH_MODE_CONFIG);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(EthereumTransactionStreamBuilder.class)).willReturn(recordBuilder);
        given(stack.getBaseBuilder(ContractCreateStreamBuilder.class)).willReturn(createRecordBuilder);
        given(baseProxyWorldUpdater.getCreatedContractIds()).willReturn(List.of(CALLED_CONTRACT_ID));
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        given(enhancement.operations()).willReturn(hederaOperations);
        given(baseProxyWorldUpdater.enhancement()).willReturn(enhancement);

        final var expectedResult = SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(
                ETH_DATA_WITHOUT_TO_ADDRESS, baseProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData()));
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT_WITH_SIGNER_NONCE.finalStatus(),
                CALLED_CONTRACT_ID,
                null,
                null,
                List.of(),
                List.of(CALLED_CONTRACT_ID),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(
                        ETH_DATA_WITHOUT_TO_ADDRESS,
                        baseProxyWorldUpdater,
                        Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData()),
                        null),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.evmAddressIfCreatedIn(baseProxyWorldUpdater),
                null,
                CodeDelegationResult.EMPTY);

        given(createRecordBuilder.createdContractID(CALLED_CONTRACT_ID)).willReturn(createRecordBuilder);
        given(createRecordBuilder.contractCreateResult(expectedResult)).willReturn(createRecordBuilder);
        given(createRecordBuilder.evmCreateTransactionResult(any())).willReturn(createRecordBuilder);
        given(createRecordBuilder.createdEvmAddress(any())).willReturn(createRecordBuilder);
        given(createRecordBuilder.withCommonFieldsSetFrom(expectedOutcome, context, entityIdFactory))
                .willReturn(createRecordBuilder);
        given(recordBuilder.ethereumHash(Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.getEthereumHash())))
                .willReturn(recordBuilder);
        givenSenderAccountWithNonce(SIGNER_NONCE);

        assertDoesNotThrow(() -> subject.handle(context));
    }

    @Test
    void delegatesToCreatedComponentAndExposesEthTxDataCallInBlocksMode() {
        given(factory.create(context, ETHEREUM_TRANSACTION, EvmFrameStates.DEFAULT))
                .willReturn(component);
        given(component.hydratedEthTxData()).willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(component.hederaOperations()).willReturn(hederaOperations);
        setUpTransactionProcessing();
        given(context.configuration()).willReturn(BLOCKS_MODE_CONFIG);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(EthereumTransactionStreamBuilder.class)).willReturn(recordBuilder);
        given(stack.getBaseBuilder(ContractCallStreamBuilder.class)).willReturn(callRecordBuilder);
        givenSenderAccountWithNonce(SIGNER_NONCE);
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        given(enhancement.operations()).willReturn(hederaOperations);
        given(baseProxyWorldUpdater.enhancement()).willReturn(enhancement);

        final var expectedResult = SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(
                ETH_DATA_WITH_TO_ADDRESS, baseProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.callData()));
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT_WITH_SIGNER_NONCE.finalStatus(),
                CALLED_CONTRACT_ID,
                null,
                null,
                List.of(),
                List.of(),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(
                        ETH_DATA_WITH_TO_ADDRESS,
                        baseProxyWorldUpdater,
                        Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.callData()),
                        null),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                null,
                null,
                CodeDelegationResult.EMPTY);
        given(callRecordBuilder.contractID(CALLED_CONTRACT_ID)).willReturn(callRecordBuilder);
        given(callRecordBuilder.evmCallTransactionResult(any())).willReturn(callRecordBuilder);
        given(recordBuilder.ethereumHash(Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.getEthereumHash())))
                .willReturn(recordBuilder);
        given(callRecordBuilder.withCommonFieldsSetFrom(expectedOutcome, context, entityIdFactory))
                .willReturn(callRecordBuilder);

        assertDoesNotThrow(() -> subject.handle(context));

        verify(callRecordBuilder, never()).contractCallResult(any());
    }

    @Test
    void delegatesToCreatedComponentAndExposesEthTxDataCreateInBlocksMode() {
        given(factory.create(context, ETHEREUM_TRANSACTION, EvmFrameStates.DEFAULT))
                .willReturn(component);
        given(component.hydratedEthTxData())
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITHOUT_TO_ADDRESS, false));
        given(component.hederaOperations()).willReturn(hederaOperations);
        setUpTransactionProcessing();
        given(context.configuration()).willReturn(BLOCKS_MODE_CONFIG);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(EthereumTransactionStreamBuilder.class)).willReturn(recordBuilder);
        given(stack.getBaseBuilder(ContractCreateStreamBuilder.class)).willReturn(createRecordBuilder);
        given(baseProxyWorldUpdater.getCreatedContractIds()).willReturn(List.of(CALLED_CONTRACT_ID));
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        given(enhancement.operations()).willReturn(hederaOperations);
        given(baseProxyWorldUpdater.enhancement()).willReturn(enhancement);

        final var expectedResult = SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(
                ETH_DATA_WITHOUT_TO_ADDRESS, baseProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData()));
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT_WITH_SIGNER_NONCE.finalStatus(),
                CALLED_CONTRACT_ID,
                null,
                null,
                List.of(),
                List.of(CALLED_CONTRACT_ID),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(
                        ETH_DATA_WITHOUT_TO_ADDRESS,
                        baseProxyWorldUpdater,
                        Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData()),
                        null),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.evmAddressIfCreatedIn(baseProxyWorldUpdater),
                null,
                CodeDelegationResult.EMPTY);

        given(createRecordBuilder.createdContractID(CALLED_CONTRACT_ID)).willReturn(createRecordBuilder);
        given(createRecordBuilder.evmCreateTransactionResult(any())).willReturn(createRecordBuilder);
        given(createRecordBuilder.createdEvmAddress(any())).willReturn(createRecordBuilder);
        given(createRecordBuilder.withCommonFieldsSetFrom(expectedOutcome, context, entityIdFactory))
                .willReturn(createRecordBuilder);
        given(recordBuilder.ethereumHash(Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.getEthereumHash())))
                .willReturn(recordBuilder);
        givenSenderAccountWithNonce(SIGNER_NONCE);

        assertDoesNotThrow(() -> subject.handle(context));

        verify(createRecordBuilder, never()).contractCreateResult(any());
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
        given(preHandleContext.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(preHandleContext.configuration()).willReturn(DEFAULT_CONFIG);
        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(ethereumSignatures.computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS))
                .willReturn(EthTxSigs.extractSignatures(ETH_DATA_WITH_TO_ADDRESS));
        given(accountStore.getAliasedAccountById(any())).willReturn(null);
        subject.preHandle(preHandleContext);
        verify(ethereumSignatures).computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS);
    }

    @Test
    void preHandleSkipsKeyMatchForHollowAccount() throws PreCheckException {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(TestHelpers.ETH_WITH_TO_ADDRESS)
                .build();
        final var body =
                TransactionBody.newBuilder().ethereumTransaction(ethTxn).build();
        final var ethSigs = EthTxSigs.extractSignatures(ETH_DATA_WITH_TO_ADDRESS);
        final var hollowAccount = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(1234L).build())
                .key(EMPTY_KEY_LIST)
                .alias(Bytes.wrap(ethSigs.address()))
                .build();
        given(preHandleContext.body()).willReturn(body);
        given(preHandleContext.createStore(ReadableFileStore.class)).willReturn(fileStore);
        given(preHandleContext.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(preHandleContext.configuration()).willReturn(DEFAULT_CONFIG);
        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(ethereumSignatures.computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS)).willReturn(ethSigs);
        given(accountStore.getAliasedAccountById(any())).willReturn(hollowAccount);

        subject.preHandle(preHandleContext);
    }

    @Test
    void preHandleRejectsMismatchedEcdsaAdminKey() {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(TestHelpers.ETH_WITH_TO_ADDRESS)
                .build();
        final var body =
                TransactionBody.newBuilder().ethereumTransaction(ethTxn).build();
        final var ethSigs = EthTxSigs.extractSignatures(ETH_DATA_WITH_TO_ADDRESS);
        final var account = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(1234L).build())
                .key(B_SECP256K1_KEY)
                .alias(Bytes.wrap(ethSigs.address()))
                .build();
        given(preHandleContext.body()).willReturn(body);
        given(preHandleContext.createStore(ReadableFileStore.class)).willReturn(fileStore);
        given(preHandleContext.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(preHandleContext.configuration()).willReturn(DEFAULT_CONFIG);
        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(ethereumSignatures.computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS)).willReturn(ethSigs);
        given(accountStore.getAliasedAccountById(any())).willReturn(account);

        assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), INVALID_SIGNATURE);
    }

    @Test
    void preHandleAcceptsMatchingEcdsaAdminKey() throws PreCheckException {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(TestHelpers.ETH_WITH_TO_ADDRESS)
                .build();
        final var body =
                TransactionBody.newBuilder().ethereumTransaction(ethTxn).build();
        final var ethSigs = EthTxSigs.extractSignatures(ETH_DATA_WITH_TO_ADDRESS);
        final var matchingKey =
                Key.newBuilder().ecdsaSecp256k1(Bytes.wrap(ethSigs.publicKey())).build();
        final var account = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(1234L).build())
                .key(matchingKey)
                .alias(Bytes.wrap(ethSigs.address()))
                .build();
        given(preHandleContext.body()).willReturn(body);
        given(preHandleContext.createStore(ReadableFileStore.class)).willReturn(fileStore);
        given(preHandleContext.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(preHandleContext.configuration()).willReturn(DEFAULT_CONFIG);
        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(ethereumSignatures.computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS)).willReturn(ethSigs);
        given(accountStore.getAliasedAccountById(any())).willReturn(account);

        subject.preHandle(preHandleContext);
    }

    @Test
    void preHandleRejectsForThresholdAdminKey() throws PreCheckException {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(TestHelpers.ETH_WITH_TO_ADDRESS)
                .build();
        final var body =
                TransactionBody.newBuilder().ethereumTransaction(ethTxn).build();
        final var ethSigs = EthTxSigs.extractSignatures(ETH_DATA_WITH_TO_ADDRESS);
        final var thresholdKey = Key.newBuilder()
                .thresholdKey(ThresholdKey.newBuilder()
                        .threshold(1)
                        .keys(KeyList.newBuilder().keys(A_SECP256K1_KEY, B_SECP256K1_KEY)))
                .build();
        final var account = Account.newBuilder()
                .accountId(AccountID.newBuilder().accountNum(1234L).build())
                .key(thresholdKey)
                .alias(Bytes.wrap(ethSigs.address()))
                .build();
        given(preHandleContext.body()).willReturn(body);
        given(preHandleContext.createStore(ReadableFileStore.class)).willReturn(fileStore);
        given(preHandleContext.createStore(ReadableAccountStore.class)).willReturn(accountStore);
        given(preHandleContext.configuration()).willReturn(DEFAULT_CONFIG);
        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(ethereumSignatures.computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS)).willReturn(ethSigs);
        given(accountStore.getAliasedAccountById(any())).willReturn(account);

        assertThrowsPreCheck(() -> subject.preHandle(preHandleContext), INVALID_SIGNATURE);
    }

    @Test
    void maybeEthTxSigsForReturnsSigsWhenHydrationSucceeds() {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(TestHelpers.ETH_WITH_TO_ADDRESS)
                .build();
        final var expected = EthTxSigs.extractSignatures(ETH_DATA_WITH_TO_ADDRESS);
        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(ethereumSignatures.computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS)).willReturn(expected);

        assertSame(expected, subject.maybeEthTxSigsFor(ethTxn, fileStore, DEFAULT_CONFIG));
    }

    @Test
    void maybeEthTxSigsForReturnsNullWhenHydrationFails() {
        final var ethTxn =
                EthereumTransactionBody.newBuilder().ethereumData(Bytes.EMPTY).build();

        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.failureFrom(INVALID_ETHEREUM_TRANSACTION));

        assertNull(subject.maybeEthTxSigsFor(ethTxn, fileStore, DEFAULT_CONFIG));
        verifyNoInteractions(ethereumSignatures);
    }

    @Test
    void maybeEthTxSigsForReturnsNullWhenSignatureComputationFails() {
        final var ethTxn = EthereumTransactionBody.newBuilder()
                .ethereumData(TestHelpers.ETH_WITH_TO_ADDRESS)
                .build();
        given(callDataHydration.tryToHydrate(ethTxn, fileStore, 1001L))
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false));
        given(ethereumSignatures.computeIfAbsent(ETH_DATA_WITH_TO_ADDRESS))
                .willThrow(new IllegalStateException("bad sig"));

        assertNull(subject.maybeEthTxSigsFor(ethTxn, fileStore, DEFAULT_CONFIG));
    }

    @Test
    void adminKeyMatchesEcdsaPubKeyReturnsTrueForMatchingKeys() {
        final var compressedPubKey = A_SECP256K1_KEY.ecdsaSecp256k1OrThrow().toByteArray();

        assertTrue(adminKeyMatchesEcdsaPubKey(A_SECP256K1_KEY, compressedPubKey));
    }

    @Test
    void adminKeyMatchesEcdsaPubKeyReturnsFalseForNonEcdsaKey() {
        assertFalse(adminKeyMatchesEcdsaPubKey(EMPTY_KEY_LIST, new byte[33]));
    }

    @Test
    void adminKeyMatchesEcdsaPubKeyReturnsFalseForMismatchedLength() {
        final var compressedPubKey = A_SECP256K1_KEY.ecdsaSecp256k1OrThrow().toByteArray();
        final var shortKey =
                Key.newBuilder().ecdsaSecp256k1(Bytes.wrap(new byte[32])).build();

        assertFalse(adminKeyMatchesEcdsaPubKey(shortKey, compressedPubKey));
    }

    @Test
    void adminKeyMatchesEcdsaPubKeyReturnsFalseForMismatchedBytes() {
        final var compressedPubKey = A_SECP256K1_KEY.ecdsaSecp256k1OrThrow().toByteArray();

        assertFalse(adminKeyMatchesEcdsaPubKey(B_SECP256K1_KEY, compressedPubKey));
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
    void validatePureChecksHappyPath() {
        // check bad to evm address
        try (MockedStatic<EthTxData> ethTxData = Mockito.mockStatic(EthTxData.class)) {
            ethTxData.when(() -> EthTxData.populateEthTxData(any())).thenReturn(ethTxDataReturned);
            given(pureChecksContext.body()).willReturn(ethTxWithTx());
            given(ethTxDataReturned.value()).willReturn(BigInteger.ONE);
            given(ethTxDataReturned.hasToAddress()).willReturn(true);
            final var toAddress = RECEIVER_ADDRESS.toByteArray();
            given(ethTxDataReturned.to()).willReturn(toAddress);
            given(ethTxDataReturned.type()).willReturn(EthTransactionType.EIP2930);
            GasCharges gasCharges = TestHelpers.gasChargesFromIntrinsicGas(INTRINSIC_GAS_FOR_0_ARG_METHOD);
            given(gasCalculator.transactionGasRequirements(any(), anyBoolean(), any(), any()))
                    .willReturn(gasCharges);
            given(ethTxDataReturned.gasLimit()).willReturn(INTRINSIC_GAS_FOR_0_ARG_METHOD);
            assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
        }
    }

    @Test
    void validatePureChecksCheckBadEthTxnBody() {
        // check bad eth txn body
        final var txn1 = ethTxWithNoTx();
        given(pureChecksContext.body()).willReturn(txn1);
        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        PreCheckException exception =
                assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertEquals(INVALID_ETHEREUM_TRANSACTION, exception.responseCode());
    }

    @Test
    void validatePureChecksHbarsToBurnAddress() {
        // check bad to evm address
        try (MockedStatic<EthTxData> ethTxData = Mockito.mockStatic(EthTxData.class)) {
            ethTxData.when(() -> EthTxData.populateEthTxData(any())).thenReturn(ethTxDataReturned);
            given(pureChecksContext.body()).willReturn(ethTxWithTx());
            given(ethTxDataReturned.value()).willReturn(BigInteger.ONE);
            given(ethTxDataReturned.to()).willReturn(new byte[20]);
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
            assertEquals(INVALID_SOLIDITY_ADDRESS, exception.responseCode());
        }
    }

    @Test
    void validatePureChecksBadToEvmAddress() {
        // check bad to evm address
        try (MockedStatic<EthTxData> ethTxData = Mockito.mockStatic(EthTxData.class)) {
            final var toAddress = new byte[] {1, 0, 1, 0};
            ethTxData.when(() -> EthTxData.populateEthTxData(any())).thenReturn(ethTxDataReturned);
            given(pureChecksContext.body()).willReturn(ethTxWithTx());
            given(ethTxDataReturned.value()).willReturn(BigInteger.ZERO);
            given(ethTxDataReturned.hasToAddress()).willReturn(true);
            given(ethTxDataReturned.to()).willReturn(toAddress);
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
            assertEquals(INVALID_CONTRACT_ID, exception.responseCode());
        }
    }

    @Test
    void validatePureChecksAtLeastIntrinsicGas() {
        // check at least intrinsic gas
        try (MockedStatic<EthTxData> ethTxData = Mockito.mockStatic(EthTxData.class)) {
            ethTxData.when(() -> EthTxData.populateEthTxData(any())).thenReturn(ethTxDataReturned);
            given(pureChecksContext.body()).willReturn(ethTxWithTx());
            given(ethTxDataReturned.value()).willReturn(BigInteger.ZERO);
            given(ethTxDataReturned.hasToAddress()).willReturn(true);
            final var toAddress = RECEIVER_ADDRESS.toByteArray();
            given(ethTxDataReturned.to()).willReturn(toAddress);
            given(ethTxDataReturned.type()).willReturn(EthTransactionType.EIP2930);
            GasCharges gasCharges = TestHelpers.gasChargesFromIntrinsicGas(INTRINSIC_GAS_FOR_0_ARG_METHOD);
            given(gasCalculator.transactionGasRequirements(any(), anyBoolean(), any(), any()))
                    .willReturn(gasCharges);
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
            assertEquals(INSUFFICIENT_GAS, exception.responseCode());
        }
    }

    @Test
    void validatePureChecksAtLeastIntrinsicGasForContractCreate() {
        // check at least intrinsic gas for contract create (hasToAddress() == false)
        try (MockedStatic<EthTxData> ethTxData = Mockito.mockStatic(EthTxData.class)) {
            ethTxData.when(() -> EthTxData.populateEthTxData(any())).thenReturn(ethTxDataReturned);
            given(pureChecksContext.body()).willReturn(ethTxWithTx());
            given(ethTxDataReturned.value()).willReturn(BigInteger.ZERO);
            given(ethTxDataReturned.hasToAddress()).willReturn(true);
            final var toAddress = RECEIVER_ADDRESS.toByteArray();
            given(ethTxDataReturned.to()).willReturn(toAddress);
            given(ethTxDataReturned.type()).willReturn(EthTransactionType.EIP2930);
            GasCharges gasCharges = TestHelpers.gasChargesFromIntrinsicGas(INTRINSIC_GAS_FOR_0_ARG_METHOD);
            given(gasCalculator.transactionGasRequirements(any(), anyBoolean(), any(), any()))
                    .willReturn(gasCharges);
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
            assertEquals(INSUFFICIENT_GAS, exception.responseCode());
        }
    }

    @Test
    void validatePureChecksIncorrectAccessList() {
        try (MockedStatic<EthTxData> ethTxData = Mockito.mockStatic(EthTxData.class)) {
            ethTxData.when(() -> EthTxData.populateEthTxData(any())).thenReturn(ethTxDataReturned);
            given(pureChecksContext.body()).willReturn(ethTxWithTx());
            given(ethTxDataReturned.value()).willReturn(BigInteger.ZERO);
            given(ethTxDataReturned.extractAccessList()).willCallRealMethod();
            given(ethTxDataReturned.accessList()).willReturn(new byte[] {1, 2});
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
            assertEquals(INVALID_ETHEREUM_TRANSACTION, exception.responseCode());
        }
    }

    @Test
    void validatePureChecksNonEmptyAuthorizationListForType4() {
        try (MockedStatic<EthTxData> ethTxData = Mockito.mockStatic(EthTxData.class)) {
            ethTxData.when(() -> EthTxData.populateEthTxData(any())).thenReturn(ethTxDataReturned);
            given(pureChecksContext.body()).willReturn(ethTxWithTx());
            given(ethTxDataReturned.value()).willReturn(BigInteger.ZERO);
            given(ethTxDataReturned.hasToAddress()).willReturn(true);
            final var toAddress = RECEIVER_ADDRESS.toByteArray();
            given(ethTxDataReturned.to()).willReturn(toAddress);
            given(ethTxDataReturned.type()).willReturn(EthTransactionType.EIP7702);
            given(ethTxDataReturned.authorizationList()).willReturn(new byte[0]);
            given(ethTxDataReturned.authorizationListAsRlp()).willReturn(new Object[0]);
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
            assertEquals(INVALID_ETHEREUM_TRANSACTION, exception.responseCode());
        }
    }

    @Test
    void validatePureChecksIfParsingInnerThrowsException() {
        try (MockedStatic<EthTxData> ethTxData = Mockito.mockStatic(EthTxData.class)) {
            ethTxData.when(() -> EthTxData.populateEthTxData(any())).thenReturn(ethTxDataReturned);
            given(pureChecksContext.body()).willReturn(ethTxWithTx());
            given(ethTxDataReturned.value()).willReturn(BigInteger.ZERO);
            given(ethTxDataReturned.hasToAddress()).willReturn(true);
            final var toAddress = RECEIVER_ADDRESS.toByteArray();
            given(ethTxDataReturned.to()).willReturn(toAddress);
            given(ethTxDataReturned.type()).willReturn(EthTransactionType.EIP7702);
            given(ethTxDataReturned.authorizationList()).willReturn(new byte[1]);
            given(ethTxDataReturned.authorizationListAsRlp()).willReturn(new Object[0]);
            given(ethTxDataReturned.authorizationListAsRlp()).willReturn(new Object[] {});
            PreCheckException exception =
                    assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
            assertEquals(INVALID_ETHEREUM_TRANSACTION, exception.responseCode());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleSetsRollbackCallback() {
        given(factory.create(context, ETHEREUM_TRANSACTION, EvmFrameStates.DEFAULT))
                .willReturn(component);
        given(component.hydratedEthTxData())
                .willReturn(HydratedEthTxData.successFrom(ETH_DATA_WITHOUT_TO_ADDRESS, false));
        given(component.hederaOperations()).willReturn(hederaOperations);
        setUpTransactionProcessing();
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        given(context.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(EthereumTransactionStreamBuilder.class)).willReturn(recordBuilder);
        given(stack.getBaseBuilder(ContractCreateStreamBuilder.class)).willReturn(createRecordBuilder);
        given(baseProxyWorldUpdater.getCreatedContractIds()).willReturn(List.of(CALLED_CONTRACT_ID));
        given(baseProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        given(baseProxyWorldUpdater.enhancement()).willReturn(enhancement);
        given(enhancement.operations()).willReturn(hederaOperations);
        final var expectedResult = SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(
                ETH_DATA_WITHOUT_TO_ADDRESS, baseProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData()));
        final var expectedOutcome = new CallOutcome(
                expectedResult,
                SUCCESS_RESULT_WITH_SIGNER_NONCE.finalStatus(),
                CALLED_CONTRACT_ID,
                null,
                null,
                List.of(),
                List.of(CALLED_CONTRACT_ID),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(
                        ETH_DATA_WITHOUT_TO_ADDRESS,
                        baseProxyWorldUpdater,
                        Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData()),
                        null),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.evmAddressIfCreatedIn(baseProxyWorldUpdater),
                null,
                CodeDelegationResult.EMPTY);

        given(createRecordBuilder.createdContractID(CALLED_CONTRACT_ID)).willReturn(createRecordBuilder);
        given(createRecordBuilder.evmCreateTransactionResult(any())).willReturn(createRecordBuilder);
        given(createRecordBuilder.createdEvmAddress(any())).willReturn(createRecordBuilder);
        given(createRecordBuilder.withCommonFieldsSetFrom(expectedOutcome, context, entityIdFactory))
                .willReturn(createRecordBuilder);
        given(recordBuilder.ethereumHash(Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.getEthereumHash())))
                .willReturn(recordBuilder);
        givenSenderAccountWithNonce(SIGNER_NONCE);

        // Mock the dispatch metadata with a callback
        final var dispatchMetadata = mock(HandleContext.DispatchMetadata.class);
        final AtomicReference<HandleException.OnRollback> rollbackCallback = new AtomicReference<>();
        given(context.dispatchMetadata()).willReturn(dispatchMetadata);
        given(dispatchMetadata.getMetadata(BATCH_ROLLBACK_CALLBACK_CONSUMER, Consumer.class))
                .willReturn(Optional.of(o -> rollbackCallback.set((HandleException.OnRollback) o)));

        // Execute the handler
        assertDoesNotThrow(() -> subject.handle(context));

        // Verify that the rollback callback was provided
        assertNotNull(rollbackCallback.get());
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
