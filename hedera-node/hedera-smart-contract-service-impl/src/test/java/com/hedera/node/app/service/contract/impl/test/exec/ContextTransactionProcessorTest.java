// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITHOUT_TO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ETH_DATA_WITH_TO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HEVM_CREATION;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.HEVM_Exception;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT_WITH_SIGNER_NONCE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertFailsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.ContextTransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.tracers.EvmActionTracer;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaOpsDuration;
import com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData;
import com.hedera.node.app.service.contract.impl.infra.HevmTransactionFactory;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.OpsDurationConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.EntityIdFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContextTransactionProcessorTest {
    private static final Configuration CONFIGURATION = HederaTestConfigBuilder.create()
            .withValue("contracts.evm.ethTransaction.zeroHapiFees.enabled", true)
            .getOrCreateConfig();

    private static final Configuration CONFIG_NO_CHARGE_ON_EXCEPTION = HederaTestConfigBuilder.create()
            .withValue("contracts.evm.chargeGasOnEvmHandleException", false)
            .getOrCreateConfig();

    @Mock
    private HandleContext context;

    @Mock
    private HederaEvmContext hederaEvmContext;

    @Mock
    private EvmActionTracer tracer;

    @Mock
    private HevmTransactionFactory hevmTransactionFactory;

    @Mock
    private TransactionProcessor processor;

    @Mock
    private RootProxyWorldUpdater rootProxyWorldUpdater;

    @Mock
    private CustomGasCharging customGasCharging;

    @Mock
    private TransactionID transactionID;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private HederaEvmAccount senderAccount;

    @Mock
    private EntityIdFactory entityIdFactory;

    @Mock
    private HederaOpsDuration hederaOpsDuration;

    @Test
    void callsComponentInfraAsExpectedForValidEthTx() {
        final var contractsConfig = CONFIGURATION.getConfigData(ContractsConfig.class);
        final var hydratedEthTxData = HydratedEthTxData.successFrom(ETH_DATA_WITH_TO_ADDRESS, false);
        final var subject = new ContextTransactionProcessor(
                hydratedEthTxData,
                context,
                contractsConfig,
                CONFIGURATION,
                hederaEvmContext,
                null,
                tracer,
                rootProxyWorldUpdater,
                hevmTransactionFactory,
                processor,
                customGasCharging,
                hederaOpsDuration);

        givenSenderAccount();
        givenBodyWithTxnIdWillReturnHEVM();
        given(processor.processTransaction(
                        HEVM_CREATION, rootProxyWorldUpdater, hederaEvmContext, tracer, CONFIGURATION))
                .willReturn(SUCCESS_RESULT_WITH_SIGNER_NONCE);

        final var protoResult = SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(
                ETH_DATA_WITH_TO_ADDRESS, rootProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITH_TO_ADDRESS.callData()));
        final var expectedResult = new CallOutcome(
                protoResult,
                SUCCESS,
                HEVM_CREATION.contractId(),
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(
                        ETH_DATA_WITHOUT_TO_ADDRESS, Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData())),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                null);
        verify(rootProxyWorldUpdater, never()).collectGasFee(any(), anyLong(), anyBoolean());
        assertEquals(expectedResult, subject.call());
    }

    @Test
    void callsComponentInfraAsExpectedForValidEthTxWithoutTo() {
        final var contractsConfig = CONFIGURATION.getConfigData(ContractsConfig.class);
        final var hydratedEthTxData = HydratedEthTxData.successFrom(ETH_DATA_WITHOUT_TO_ADDRESS, false);
        final var subject = new ContextTransactionProcessor(
                hydratedEthTxData,
                context,
                contractsConfig,
                CONFIGURATION,
                hederaEvmContext,
                null,
                tracer,
                rootProxyWorldUpdater,
                hevmTransactionFactory,
                processor,
                customGasCharging,
                hederaOpsDuration);

        givenSenderAccount();
        givenBodyWithTxnIdWillReturnHEVM();
        given(processor.processTransaction(
                        HEVM_CREATION, rootProxyWorldUpdater, hederaEvmContext, tracer, CONFIGURATION))
                .willReturn(SUCCESS_RESULT_WITH_SIGNER_NONCE);

        final var protoResult = SUCCESS_RESULT_WITH_SIGNER_NONCE.asProtoResultOf(
                ETH_DATA_WITHOUT_TO_ADDRESS, rootProxyWorldUpdater, Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData()));
        final var expectedResult = new CallOutcome(
                protoResult,
                SUCCESS,
                HEVM_CREATION.contractId(),
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.asEvmTxResultOf(
                        ETH_DATA_WITHOUT_TO_ADDRESS, Bytes.wrap(ETH_DATA_WITHOUT_TO_ADDRESS.callData())),
                SUCCESS_RESULT_WITH_SIGNER_NONCE.signerNonce(),
                null);
        assertEquals(expectedResult, subject.call());
        verify(rootProxyWorldUpdater, never()).collectGasFee(any(), anyLong(), anyBoolean());
    }

    @Test
    void callsComponentInfraAsExpectedForNonEthTx() {
        final var contractsConfig = CONFIGURATION.getConfigData(ContractsConfig.class);
        final var subject = new ContextTransactionProcessor(
                null,
                context,
                contractsConfig,
                CONFIGURATION,
                hederaEvmContext,
                null,
                tracer,
                rootProxyWorldUpdater,
                hevmTransactionFactory,
                processor,
                customGasCharging,
                hederaOpsDuration);

        givenBodyWithTxnIdWillReturnHEVM();
        given(processor.processTransaction(
                        HEVM_CREATION, rootProxyWorldUpdater, hederaEvmContext, tracer, CONFIGURATION))
                .willReturn(SUCCESS_RESULT);
        given(rootProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);

        final var protoResult = SUCCESS_RESULT.asProtoResultOf(null, rootProxyWorldUpdater, null);
        final var expectedResult = new CallOutcome(
                protoResult,
                SUCCESS,
                HEVM_CREATION.contractId(),
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                SUCCESS_RESULT.asEvmTxResultOf(null, null),
                SUCCESS_RESULT.signerNonce(),
                null);
        assertEquals(expectedResult, subject.call());
        verify(rootProxyWorldUpdater, never()).collectGasFee(any(), anyLong(), anyBoolean());
    }

    @Test
    void stillChargesHapiFeesOnAbort() {
        final var contractsConfig = CONFIGURATION.getConfigData(ContractsConfig.class);
        final var subject = new ContextTransactionProcessor(
                null,
                context,
                contractsConfig,
                CONFIGURATION,
                hederaEvmContext,
                null,
                tracer,
                rootProxyWorldUpdater,
                hevmTransactionFactory,
                processor,
                customGasCharging,
                hederaOpsDuration);

        givenBodyWithTxnIdWillReturnHEVM();
        given(processor.processTransaction(
                        HEVM_CREATION, rootProxyWorldUpdater, hederaEvmContext, tracer, CONFIGURATION))
                .willThrow(new HandleException(INVALID_CONTRACT_ID));

        subject.call();

        verify(customGasCharging).chargeGasForAbortedTransaction(any(), any(), any(), any());
        verify(rootProxyWorldUpdater).commit();
    }

    @Test
    void stillChargesGasFeesOnHevmException() {
        final var contractsConfig = CONFIGURATION.getConfigData(ContractsConfig.class);
        final var subject = new ContextTransactionProcessor(
                null,
                context,
                contractsConfig,
                CONFIGURATION,
                hederaEvmContext,
                null,
                tracer,
                rootProxyWorldUpdater,
                hevmTransactionFactory,
                processor,
                customGasCharging,
                hederaOpsDuration);

        given(context.body()).willReturn(transactionBody);
        final var payer = AccountID.DEFAULT;
        given(context.payer()).willReturn(payer);
        given(hevmTransactionFactory.fromHapiTransaction(transactionBody, payer))
                .willReturn(HEVM_Exception);
        given(transactionBody.transactionIDOrThrow()).willReturn(transactionID);
        given(transactionID.accountIDOrThrow()).willReturn(SENDER_ID);

        final var outcome = subject.call();

        verify(customGasCharging).chargeGasForAbortedTransaction(any(), any(), any(), any());
        verify(rootProxyWorldUpdater).commit();
        verify(hederaOpsDuration).applyDurationFromConfig(CONFIGURATION.getConfigData(OpsDurationConfig.class));
        assertEquals(INVALID_CONTRACT_ID, outcome.status());
    }

    @Test
    void doesNotChargeGasFeesOnHevmExceptionIfSoConfigured() {
        final var contractsConfig = CONFIG_NO_CHARGE_ON_EXCEPTION.getConfigData(ContractsConfig.class);
        final var subject = new ContextTransactionProcessor(
                null,
                context,
                contractsConfig,
                CONFIG_NO_CHARGE_ON_EXCEPTION,
                hederaEvmContext,
                null,
                tracer,
                rootProxyWorldUpdater,
                hevmTransactionFactory,
                processor,
                customGasCharging,
                hederaOpsDuration);

        given(context.body()).willReturn(transactionBody);
        final var payer = AccountID.DEFAULT;
        given(context.payer()).willReturn(payer);
        given(hevmTransactionFactory.fromHapiTransaction(transactionBody, payer))
                .willReturn(HEVM_Exception);
        given(transactionBody.transactionIDOrThrow()).willReturn(transactionID);
        given(transactionID.accountIDOrThrow()).willReturn(SENDER_ID);

        final var outcome = subject.call();

        verify(customGasCharging, never()).chargeGasForAbortedTransaction(any(), any(), any(), any());
        verify(rootProxyWorldUpdater).commit();
        assertEquals(INVALID_CONTRACT_ID, outcome.status());
    }

    @Test
    void stillChargesGasFeesOnExceptionThrown() {
        final var contractsConfig = CONFIGURATION.getConfigData(ContractsConfig.class);
        final var subject = new ContextTransactionProcessor(
                null,
                context,
                contractsConfig,
                CONFIGURATION,
                hederaEvmContext,
                null,
                tracer,
                rootProxyWorldUpdater,
                hevmTransactionFactory,
                processor,
                customGasCharging,
                hederaOpsDuration);

        given(context.body()).willReturn(transactionBody);
        final var payer = AccountID.DEFAULT;
        given(context.payer()).willReturn(payer);
        given(hevmTransactionFactory.fromHapiTransaction(transactionBody, payer))
                .willThrow(new HandleException(INVALID_CONTRACT_ID));
        given(hevmTransactionFactory.fromContractTxException(any(), any())).willReturn(HEVM_Exception);
        given(transactionBody.transactionIDOrThrow()).willReturn(transactionID);
        given(transactionID.accountIDOrThrow()).willReturn(SENDER_ID);

        final var outcome = subject.call();

        verify(customGasCharging).chargeGasForAbortedTransaction(any(), any(), any(), any());
        // Verify that disabled zeroHapi fees flag won't charge on error
        verify(rootProxyWorldUpdater, never()).collectGasFee(any(), anyLong(), anyBoolean());
        verify(rootProxyWorldUpdater).commit();
        assertEquals(INVALID_CONTRACT_ID, outcome.status());
    }

    @Test
    void doesNotChargeGasAndHapiFeesOnExceptionThrownIfSoConfigured() {
        final var contractsConfig = CONFIG_NO_CHARGE_ON_EXCEPTION.getConfigData(ContractsConfig.class);
        final var subject = new ContextTransactionProcessor(
                null,
                context,
                contractsConfig,
                CONFIG_NO_CHARGE_ON_EXCEPTION,
                hederaEvmContext,
                null,
                tracer,
                rootProxyWorldUpdater,
                hevmTransactionFactory,
                processor,
                customGasCharging,
                hederaOpsDuration);

        given(context.body()).willReturn(transactionBody);
        final var payer = AccountID.DEFAULT;
        given(context.payer()).willReturn(payer);
        given(hevmTransactionFactory.fromHapiTransaction(transactionBody, payer))
                .willThrow(new HandleException(INVALID_CONTRACT_ID));
        given(hevmTransactionFactory.fromContractTxException(any(), any())).willReturn(HEVM_Exception);
        given(transactionBody.transactionIDOrThrow()).willReturn(transactionID);
        given(transactionID.accountIDOrThrow()).willReturn(SENDER_ID);

        final var outcome = subject.call();

        verify(customGasCharging, never()).chargeGasForAbortedTransaction(any(), any(), any(), any());
        // Verify that disabled zeroHapi fees flag won't charge on error
        verify(rootProxyWorldUpdater, never()).collectGasFee(any(), anyLong(), anyBoolean());
        verify(rootProxyWorldUpdater).commit();
        assertEquals(INVALID_CONTRACT_ID, outcome.status());
    }

    @Test
    void reThrowsExceptionWhenNotContractCall() {
        final var contractsConfig = CONFIGURATION.getConfigData(ContractsConfig.class);
        final var subject = new ContextTransactionProcessor(
                null,
                context,
                contractsConfig,
                CONFIGURATION,
                hederaEvmContext,
                null,
                tracer,
                rootProxyWorldUpdater,
                hevmTransactionFactory,
                processor,
                customGasCharging,
                hederaOpsDuration);

        given(context.body()).willReturn(transactionBody);
        given(context.payer()).willReturn(SENDER_ID);
        given(transactionBody.transactionIDOrThrow()).willReturn(transactionID);
        given(transactionID.accountIDOrThrow()).willReturn(SENDER_ID);
        given(hevmTransactionFactory.fromHapiTransaction(transactionBody, SENDER_ID))
                .willThrow(new HandleException(INVALID_CONTRACT_ID));
        given(hevmTransactionFactory.fromContractTxException(any(), any())).willReturn(HEVM_Exception);

        final var outcome = subject.call();
        verify(rootProxyWorldUpdater).commit();
        assertEquals(INVALID_CONTRACT_ID, outcome.status());
    }

    @Test
    void failsImmediatelyIfEthTxInvalid() {
        final var contractsConfig = CONFIGURATION.getConfigData(ContractsConfig.class);
        final var subject = new ContextTransactionProcessor(
                HydratedEthTxData.failureFrom(INVALID_ETHEREUM_TRANSACTION),
                context,
                contractsConfig,
                CONFIGURATION,
                hederaEvmContext,
                null,
                tracer,
                rootProxyWorldUpdater,
                hevmTransactionFactory,
                processor,
                customGasCharging,
                hederaOpsDuration);

        assertFailsWith(INVALID_ETHEREUM_TRANSACTION, subject::call);
    }

    void givenSenderAccount() {
        given(rootProxyWorldUpdater.getHederaAccount(SENDER_ID)).willReturn(senderAccount);
        given(rootProxyWorldUpdater.entityIdFactory()).willReturn(entityIdFactory);
        given(senderAccount.getNonce()).willReturn(1L);
    }

    void givenBodyWithTxnIdWillReturnHEVM() {
        final var body = TransactionBody.newBuilder()
                .transactionID(TransactionID.DEFAULT)
                .build();
        final var payer = AccountID.DEFAULT;
        given(context.body()).willReturn(body);
        given(context.payer()).willReturn(payer);
        given(hevmTransactionFactory.fromHapiTransaction(body, payer)).willReturn(HEVM_CREATION);
    }
}
