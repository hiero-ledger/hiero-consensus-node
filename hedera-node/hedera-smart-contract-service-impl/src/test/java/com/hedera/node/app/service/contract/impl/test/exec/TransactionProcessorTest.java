// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_BALANCES_FOR_RENEWAL_FEES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations.GasChargingAction.CHARGE;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations.GasChargingAction.REFUND;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.TRACKER_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CHARGING_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.GAS_CALCULATOR;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.GAS_LIMIT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.INVALID_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.MAINNET_CHAIN_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.MAX_GAS_ALLOWANCE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NETWORK_GAS_PRICE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NONCE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NO_ALLOWANCE_CHARGING_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RECEIVER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RELAYER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SUCCESS_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.USER_OFFERED_GAS_PRICE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALID_CONTRACT_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALUE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertFailsWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownContextWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownHapiCall;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownHapiCreate;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownRelayedHapiCall;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownRelayedHapiCreate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.FrameRunner;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.gas.GasCharges;
import com.hedera.node.app.service.contract.impl.exec.gas.HederaGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations.GasChargingEvent;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmBlocks;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.AbstractMutableEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.RootProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.TxStorageUsage;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.service.contract.impl.utils.EthereumTransactionRollbackHandler;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionProcessorTest {
    @Mock
    private MessageFrame initialFrame;

    @Mock
    private FrameBuilder frameBuilder;

    @Mock
    private FrameRunner frameRunner;

    @Mock
    private CustomMessageCallProcessor messageCallProcessor;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private ContractCreationProcessor contractCreationProcessor;

    @Mock
    private HederaEvmBlocks blocks;

    @Mock
    private TinybarValues tinybarValues;

    @Mock
    private ProxyWorldUpdater worldUpdater;

    @Mock
    private ActionSidecarContentTracer tracer;

    @Mock
    private Configuration config;

    @Mock
    private AbstractMutableEvmAccount senderAccount;

    @Mock
    private StorageAccessTracker tracker;

    @Mock
    private AbstractMutableEvmAccount relayerAccount;

    @Mock
    private AbstractMutableEvmAccount receiverAccount;

    @Mock
    private CustomGasCharging gasCharging;

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private ContractOperationStreamBuilder recordBuilder;

    private TransactionProcessor subject;

    private OpsDurationCounter opsDurationCounter;

    @BeforeEach
    void setUp() {
        subject = new TransactionProcessor(
                frameBuilder,
                frameRunner,
                gasCharging,
                messageCallProcessor,
                contractCreationProcessor,
                featureFlags,
                GAS_CALCULATOR,
                null);
        opsDurationCounter = OpsDurationCounter.disabled();
    }

    @Test
    void abortsOnMissingSender() {
        assertAbortsWith(INVALID_ACCOUNT_ID);
    }

    @Test
    void lazyCreationAttemptWithNoValueFailsFast() {
        givenSenderAccountWithNoHederaAccount();
        givenRelayerAccount();
        given(messageCallProcessor.isImplicitCreationEnabled()).willReturn(true);
        assertAbortsWith(wellKnownRelayedHapiCall(0), INVALID_CONTRACT_ID);
    }

    @Test
    void lazyCreationAttemptWithInvalidAddress() {
        givenSenderAccountWithNoHederaAccount();
        givenRelayerAccount();
        final var invalidCreation = new HederaEvmTransaction(
                SENDER_ID,
                RELAYER_ID,
                INVALID_CONTRACT_ADDRESS,
                NONCE,
                CALL_DATA,
                MAINNET_CHAIN_ID,
                VALUE,
                GAS_LIMIT,
                USER_OFFERED_GAS_PRICE,
                MAX_GAS_ALLOWANCE,
                null,
                null,
                null,
                false);
        given(messageCallProcessor.isImplicitCreationEnabled()).willReturn(true);
        assertAbortsWith(invalidCreation, INVALID_CONTRACT_ID);
    }

    @Test
    void lazyCreationAttemptWithValidAddress() {
        givenSenderAccount();
        given(senderAccount.getNonce()).willReturn(NONCE);
        givenRelayerAccount();
        givenAccessTracker(mock(StorageAccessTracker.class));
        final var transaction = new HederaEvmTransaction(
                SENDER_ID,
                RELAYER_ID,
                VALID_CONTRACT_ADDRESS,
                NONCE,
                CALL_DATA,
                MAINNET_CHAIN_ID,
                VALUE,
                GAS_LIMIT,
                USER_OFFERED_GAS_PRICE,
                MAX_GAS_ALLOWANCE,
                null,
                null,
                null,
                false);
        given(messageCallProcessor.isImplicitCreationEnabled()).willReturn(true);
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        given(gasCharging.chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction))
                .willReturn(CHARGING_RESULT);
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        final var expectedToAddress = ConversionUtils.pbjToBesuAddress(VALID_CONTRACT_ADDRESS.evmAddressOrThrow());
        given(frameBuilder.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        expectedToAddress,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of()))
                .willReturn(initialFrame);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor,
                        CHARGING_RESULT,
                        null))
                .willReturn(SUCCESS_RESULT);

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
        verify(worldUpdater).setupTopLevelLazyCreate(expectedToAddress);
    }

    @Test
    void lazyCreationAttemptCanCallNotExistingFeatureFlagOn() {
        givenSenderAccount();
        given(senderAccount.getNonce()).willReturn(NONCE);
        givenRelayerAccount();
        given(tracker.getJustReads()).willReturn(List.of());
        givenAccessTracker(tracker);
        final var transaction = new HederaEvmTransaction(
                SENDER_ID,
                RELAYER_ID,
                VALID_CONTRACT_ADDRESS,
                NONCE,
                CALL_DATA,
                MAINNET_CHAIN_ID,
                VALUE,
                GAS_LIMIT,
                USER_OFFERED_GAS_PRICE,
                MAX_GAS_ALLOWANCE,
                null,
                null,
                null,
                false);
        given(messageCallProcessor.isImplicitCreationEnabled()).willReturn(true);
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        given(gasCharging.chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction))
                .willReturn(CHARGING_RESULT);
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        final var expectedToAddress = ConversionUtils.pbjToBesuAddress(VALID_CONTRACT_ADDRESS.evmAddressOrThrow());
        given(frameBuilder.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        expectedToAddress,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of()))
                .willReturn(initialFrame);
        given(senderAccount.hederaId()).willReturn(SENDER_ID);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor,
                        CHARGING_RESULT,
                        null))
                .willReturn(SUCCESS_RESULT);
        given(featureFlags.isAllowCallsToNonContractAccountsEnabled(any(), any()))
                .willReturn(true);

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
        verify(worldUpdater).setupTopLevelLazyCreate(expectedToAddress);
    }

    @Test
    void callWithNoValueAndCanCallNotExistingFeatureFlagOn() {
        givenSenderAccount();
        givenRelayerAccount();
        givenAccessTracker(mock(StorageAccessTracker.class));
        final var transaction = new HederaEvmTransaction(
                SENDER_ID,
                RELAYER_ID,
                VALID_CONTRACT_ADDRESS,
                NONCE,
                CALL_DATA,
                MAINNET_CHAIN_ID,
                0L,
                GAS_LIMIT,
                USER_OFFERED_GAS_PRICE,
                MAX_GAS_ALLOWANCE,
                null,
                null,
                null,
                false);
        given(messageCallProcessor.isImplicitCreationEnabled()).willReturn(true);
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        given(gasCharging.chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction))
                .willReturn(CHARGING_RESULT);
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        final var expectedToAddress = ConversionUtils.pbjToBesuAddress(VALID_CONTRACT_ADDRESS.evmAddressOrThrow());
        given(frameBuilder.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        expectedToAddress,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of()))
                .willReturn(initialFrame);
        given(senderAccount.hederaId()).willReturn(SENDER_ID);
        given(senderAccount.getNonce()).willReturn(NONCE);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor,
                        CHARGING_RESULT,
                        null))
                .willReturn(SUCCESS_RESULT);
        given(featureFlags.isAllowCallsToNonContractAccountsEnabled(any(), any()))
                .willReturn(true);

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
    }

    @Test
    void callWhenComputePartiesThrowsException() {
        final var transaction = new HederaEvmTransaction(
                SENDER_ID,
                null,
                VALID_CONTRACT_ADDRESS,
                NONCE,
                CALL_DATA,
                MAINNET_CHAIN_ID,
                0L,
                GAS_LIMIT,
                USER_OFFERED_GAS_PRICE,
                MAX_GAS_ALLOWANCE,
                null,
                null,
                null,
                false);
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        given(worldUpdater.getHederaAccount(SENDER_ID)).willReturn(null);

        final var handleException = catchThrowableOfType(
                () -> subject.processTransaction(
                        transaction, worldUpdater, context, tracer, config, opsDurationCounter),
                HandleException.class);
        assertThat(handleException.getStatus()).isEqualTo(INVALID_ACCOUNT_ID);
    }

    @Test
    void callWhenInvalidContractIdThrowsException() {
        final var transaction = new HederaEvmTransaction(
                SENDER_ID,
                null,
                INVALID_CONTRACT_ADDRESS,
                NONCE,
                CALL_DATA,
                MAINNET_CHAIN_ID,
                0L,
                GAS_LIMIT,
                USER_OFFERED_GAS_PRICE,
                MAX_GAS_ALLOWANCE,
                null,
                null,
                null,
                false);
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        given(worldUpdater.getHederaAccount(SENDER_ID)).willReturn(senderAccount);
        given(worldUpdater.getHederaAccount(INVALID_CONTRACT_ADDRESS)).willThrow(IllegalArgumentException.class);

        final var handleException = catchThrowableOfType(
                () -> subject.processTransaction(
                        transaction, worldUpdater, context, tracer, config, opsDurationCounter),
                HandleException.class);
        assertThat(handleException.getStatus()).isEqualTo(INVALID_TRANSACTION_BODY);
    }

    @Test
    void requiresEthTxToHaveNonNullRelayer() {
        givenSenderAccountWithNoHederaAccount();
        assertAbortsWith(wellKnownRelayedHapiCall(0), INVALID_ACCOUNT_ID);
    }

    @Test
    void nonLazyCreateCandidateMustHaveReceiver() {
        givenSenderAccountWithNoHederaAccount();
        givenRelayerAccount();
        assertAbortsWith(wellKnownRelayedHapiCall(0), INVALID_CONTRACT_ID);
    }

    @Test
    void ethCreateHappyPathAsExpected() {
        final var inOrder = inOrder(worldUpdater, frameBuilder, frameRunner, gasCharging, senderAccount);

        givenSenderAccount();
        givenRelayerAccount();
        givenAccessTracker(mock(StorageAccessTracker.class));

        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        final var transaction = wellKnownRelayedHapiCreate();

        given(gasCharging.chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction))
                .willReturn(CHARGING_RESULT);
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        given(senderAccount.getNonce()).willReturn(NONCE);
        final var expectedToAddress = Address.contractAddress(EIP_1014_ADDRESS, NONCE);
        given(frameBuilder.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        expectedToAddress,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of()))
                .willReturn(initialFrame);
        given(senderAccount.getNonce()).willReturn(NONCE);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor,
                        CHARGING_RESULT,
                        null))
                .willReturn(SUCCESS_RESULT);
        final var parsedAccount =
                Account.newBuilder().accountId(senderAccount.hederaId()).build();
        given(senderAccount.toNativeAccount()).willReturn(parsedAccount);
        given(initialFrame.getSelfDestructs()).willReturn(Set.of(NON_SYSTEM_LONG_ZERO_ADDRESS));

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        inOrder.verify(worldUpdater)
                .setupAliasedTopLevelCreate(ContractCreateTransactionBody.DEFAULT, expectedToAddress);
        inOrder.verify(gasCharging).chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction);
        inOrder.verify(frameBuilder)
                .buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        expectedToAddress,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of());
        inOrder.verify(frameRunner)
                .runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor,
                        CHARGING_RESULT,
                        null);
        inOrder.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        inOrder.verify(worldUpdater).commit();
        inOrder.verify(gasCharging)
                .maybeRefundGiven(
                        GAS_LIMIT - SUCCESS_RESULT.gasUsed(),
                        CHARGING_RESULT.relayerAllowanceUsed(),
                        senderAccount,
                        relayerAccount,
                        context,
                        worldUpdater);
        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
    }

    @Test
    void hapiCreateHappyPathAsExpected() {
        final var inOrder = inOrder(worldUpdater, frameBuilder, frameRunner, gasCharging, senderAccount);

        givenSenderAccount();
        givenAccessTracker(mock(StorageAccessTracker.class));

        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        final var transaction = wellKnownHapiCreate();

        given(gasCharging.chargeForGas(senderAccount, null, context, worldUpdater, transaction))
                .willReturn(NO_ALLOWANCE_CHARGING_RESULT);
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        given(worldUpdater.setupTopLevelCreate(ContractCreateTransactionBody.DEFAULT))
                .willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frameBuilder.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        NON_SYSTEM_LONG_ZERO_ADDRESS,
                        transaction.gasLimit() - NO_ALLOWANCE_CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of()))
                .willReturn(initialFrame);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor,
                        NO_ALLOWANCE_CHARGING_RESULT,
                        null))
                .willReturn(SUCCESS_RESULT);
        given(initialFrame.getSelfDestructs()).willReturn(Set.of(NON_SYSTEM_LONG_ZERO_ADDRESS));

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        inOrder.verify(worldUpdater).setupTopLevelCreate(ContractCreateTransactionBody.DEFAULT);
        inOrder.verify(gasCharging).chargeForGas(senderAccount, null, context, worldUpdater, transaction);
        inOrder.verify(frameBuilder)
                .buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        NON_SYSTEM_LONG_ZERO_ADDRESS,
                        transaction.gasLimit() - NO_ALLOWANCE_CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of());
        inOrder.verify(frameRunner)
                .runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor,
                        NO_ALLOWANCE_CHARGING_RESULT,
                        null);
        inOrder.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        inOrder.verify(worldUpdater).commit();
        inOrder.verify(gasCharging)
                .maybeRefundGiven(GAS_LIMIT - SUCCESS_RESULT.gasUsed(), 0, senderAccount, null, context, worldUpdater);
        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
        verify(senderAccount, never()).incrementNonce();
    }

    @Test
    void ethCallHappyPathAsExpected() {
        final var inOrder =
                inOrder(worldUpdater, frameBuilder, frameRunner, gasCharging, messageCallProcessor, senderAccount);

        givenSenderAccount();
        given(senderAccount.getNonce()).willReturn(NONCE);
        givenRelayerAccount();
        givenReceiverAccount();
        givenAccessTracker(mock(StorageAccessTracker.class));

        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator, recordBuilder);
        final var transaction = wellKnownRelayedHapiCall(0);

        given(gasCharging.chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction))
                .willReturn(CHARGING_RESULT);
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        given(receiverAccount.getAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frameBuilder.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        NON_SYSTEM_LONG_ZERO_ADDRESS,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of()))
                .willReturn(initialFrame);
        given(frameRunner.runToCompletion(
                        eq(transaction.gasLimit()),
                        eq(SENDER_ID),
                        eq(initialFrame),
                        eq(tracer),
                        any(),
                        eq(contractCreationProcessor),
                        eq(CHARGING_RESULT),
                        any()))
                .willReturn(SUCCESS_RESULT);
        given(initialFrame.getSelfDestructs()).willReturn(Set.of(NON_SYSTEM_LONG_ZERO_ADDRESS));

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        inOrder.verify(gasCharging).chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction);
        inOrder.verify(frameBuilder)
                .buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        NON_SYSTEM_LONG_ZERO_ADDRESS,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of());
        inOrder.verify(frameRunner)
                .runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor,
                        CHARGING_RESULT,
                        null);
        inOrder.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        inOrder.verify(worldUpdater).commit();
        inOrder.verify(gasCharging)
                .maybeRefundGiven(
                        GAS_LIMIT - SUCCESS_RESULT.gasUsed(),
                        CHARGING_RESULT.relayerAllowanceUsed(),
                        senderAccount,
                        relayerAccount,
                        context,
                        worldUpdater);
        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
    }

    @Test
    void ethCallHappyPathAsExpectedAndCanCallNotExistingFeatureFlagOn() {
        final var inOrder =
                inOrder(worldUpdater, frameBuilder, frameRunner, gasCharging, messageCallProcessor, senderAccount);

        givenSenderAccount();
        given(senderAccount.getNonce()).willReturn(NONCE);
        givenRelayerAccount();
        givenReceiverAccount();
        givenAccessTracker(mock(StorageAccessTracker.class));

        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator, recordBuilder);
        final var transaction = wellKnownRelayedHapiCall(0);

        given(gasCharging.chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction))
                .willReturn(CHARGING_RESULT);
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        given(receiverAccount.getAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frameBuilder.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        NON_SYSTEM_LONG_ZERO_ADDRESS,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of()))
                .willReturn(initialFrame);
        given(senderAccount.hederaId()).willReturn(SENDER_ID);
        given(frameRunner.runToCompletion(
                        eq(transaction.gasLimit()),
                        eq(SENDER_ID),
                        eq(initialFrame),
                        eq(tracer),
                        any(),
                        eq(contractCreationProcessor),
                        eq(CHARGING_RESULT),
                        any()))
                .willReturn(SUCCESS_RESULT);
        given(initialFrame.getSelfDestructs()).willReturn(Set.of(NON_SYSTEM_LONG_ZERO_ADDRESS));
        given(featureFlags.isAllowCallsToNonContractAccountsEnabled(any(), any()))
                .willReturn(true);

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        inOrder.verify(gasCharging).chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction);
        inOrder.verify(frameBuilder)
                .buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        NON_SYSTEM_LONG_ZERO_ADDRESS,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of());
        inOrder.verify(frameRunner)
                .runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor,
                        CHARGING_RESULT,
                        null);
        inOrder.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        inOrder.verify(worldUpdater).commit();
        inOrder.verify(gasCharging)
                .maybeRefundGiven(
                        GAS_LIMIT - SUCCESS_RESULT.gasUsed(),
                        CHARGING_RESULT.relayerAllowanceUsed(),
                        senderAccount,
                        relayerAccount,
                        context,
                        worldUpdater);
        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
    }

    @Test
    void ethCallAsExpectedWithResourceExhaustionInCommit() {
        givenSenderAccount();
        givenRelayerAccount();
        givenReceiverAccount();
        givenAccessTracker(mock(StorageAccessTracker.class));

        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator, recordBuilder);
        final var transaction = wellKnownRelayedHapiCall(0);

        given(gasCharging.chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction))
                .willReturn(CHARGING_RESULT);
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        given(senderAccount.getNonce()).willReturn(NONCE);
        given(receiverAccount.getAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frameBuilder.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        NON_SYSTEM_LONG_ZERO_ADDRESS,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of()))
                .willReturn(initialFrame);
        given(frameRunner.runToCompletion(
                        eq(transaction.gasLimit()),
                        eq(SENDER_ID),
                        eq(initialFrame),
                        eq(tracer),
                        any(),
                        eq(contractCreationProcessor),
                        eq(CHARGING_RESULT),
                        any()))
                .willReturn(SUCCESS_RESULT);
        given(initialFrame.getSelfDestructs()).willReturn(Set.of(NON_SYSTEM_LONG_ZERO_ADDRESS));

        willThrow(new ResourceExhaustedException(INSUFFICIENT_BALANCES_FOR_RENEWAL_FEES))
                .given(worldUpdater)
                .commit();

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        assertResourceExhaustion(INSUFFICIENT_BALANCES_FOR_RENEWAL_FEES, result);
        final var inOrder = inOrder(worldUpdater);
        inOrder.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        inOrder.verify(worldUpdater).commit();
        inOrder.verify(worldUpdater).revert();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selfDestructsAreDeletedInAddressOrderRegardlessOfSetIterationOrder(final boolean reverseOrder) {
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        final var transaction = clprDispatchCall(null);
        givenClprDispatchRunsToCompletion(transaction, context);
        final var first = Address.fromHexString("0x0000000000000000000000000000000000001001");
        final var second = Address.fromHexString("0x8000000000000000000000000000000000000000");
        final var third = Address.fromHexString("0xff00000000000000000000000000000000000000");
        final var selfDestructs =
                new LinkedHashSet<>(reverseOrder ? List.of(third, second, first) : List.of(second, first, third));
        given(initialFrame.getSelfDestructs()).willReturn(selfDestructs);

        subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        final var inOrder = inOrder(worldUpdater);
        inOrder.verify(worldUpdater).deleteAccount(first);
        inOrder.verify(worldUpdater).deleteAccount(second);
        inOrder.verify(worldUpdater).deleteAccount(third);
        inOrder.verify(worldUpdater).commit();
        verify(worldUpdater, times(3)).deleteAccount(any());
    }

    @Test
    void resourceExhaustionWhileExternalizingSelfDestructsRevertsBeforeCommit() {
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        final var transaction = clprDispatchCall(null);
        givenClprDispatchRunsToCompletion(transaction, context);
        final var laterDeletion = Address.fromHexString("0xff00000000000000000000000000000000000000");
        given(initialFrame.getSelfDestructs()).willReturn(Set.of(laterDeletion, NON_SYSTEM_LONG_ZERO_ADDRESS));
        willDoNothing().given(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        willThrow(new ResourceExhaustedException(ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED))
                .given(worldUpdater)
                .deleteAccount(laterDeletion);

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        assertResourceExhaustion(ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED, result);
        final var inOrder = inOrder(worldUpdater);
        inOrder.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        inOrder.verify(worldUpdater).deleteAccount(laterDeletion);
        inOrder.verify(worldUpdater).revert();
        verify(worldUpdater, never()).commit();
    }

    private enum FinalizationOutcome {
        SUCCESS,
        EXECUTION_REVERT,
        DELETION_CAPACITY_EXHAUSTED,
        COMMIT_CAPACITY_EXHAUSTED,
        MISSING_BENEFICIARY
    }

    @ParameterizedTest
    @EnumSource(FinalizationOutcome.class)
    void gasRefundsAndRollbackReplayMatchTheFinalResult(final FinalizationOutcome finalization) {
        // Exercise real charging and rollback replay, so gasUsed cannot disagree with the amount charged.
        subject = new TransactionProcessor(
                frameBuilder,
                frameRunner,
                new CustomGasCharging((HederaGasCalculator) GAS_CALCULATOR),
                messageCallProcessor,
                contractCreationProcessor,
                featureFlags,
                GAS_CALCULATOR,
                null);
        givenSenderAccount();
        givenRelayerAccount();
        givenReceiverAccount();
        givenAccessTracker(null);
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        given(senderAccount.getNonce()).willReturn(NONCE);
        given(senderAccount.getBalance()).willReturn(Wei.of(Long.MAX_VALUE / 2));
        given(relayerAccount.hederaId()).willReturn(RELAYER_ID);
        given(relayerAccount.getBalance()).willReturn(Wei.of(Long.MAX_VALUE / 2));
        given(receiverAccount.getAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        final var transaction = wellKnownRelayedHapiCall(0);
        given(frameBuilder.buildInitialFrameWith(
                        eq(transaction),
                        eq(worldUpdater),
                        eq(context),
                        eq(config),
                        eq(opsDurationCounter),
                        eq(featureFlags),
                        eq(EIP_1014_ADDRESS),
                        eq(NON_SYSTEM_LONG_ZERO_ADDRESS),
                        anyLong(),
                        eq(GAS_CALCULATOR),
                        eq(List.of())))
                .willReturn(initialFrame);
        final var executionResult = finalization == FinalizationOutcome.EXECUTION_REVERT
                ? HederaEvmTransactionResult.resourceExhaustionFrom(
                        SENDER_ID, SUCCESS_RESULT.gasUsed(), NETWORK_GAS_PRICE, CONTRACT_REVERT_EXECUTED)
                : SUCCESS_RESULT;
        given(frameRunner.runToCompletion(
                        eq(transaction.gasLimit()),
                        eq(SENDER_ID),
                        eq(initialFrame),
                        eq(tracer),
                        eq(messageCallProcessor),
                        eq(contractCreationProcessor),
                        any(),
                        any()))
                .willReturn(executionResult);
        final var laterDeletion = Address.fromHexString("0xff00000000000000000000000000000000000000");
        given(initialFrame.getSelfDestructs()).willReturn(Set.of(laterDeletion, NON_SYSTEM_LONG_ZERO_ADDRESS));
        final var failsFinalization =
                finalization != FinalizationOutcome.SUCCESS && finalization != FinalizationOutcome.EXECUTION_REVERT;
        if (finalization == FinalizationOutcome.DELETION_CAPACITY_EXHAUSTED
                || finalization == FinalizationOutcome.MISSING_BENEFICIARY) {
            willDoNothing().given(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        }
        if (finalization == FinalizationOutcome.DELETION_CAPACITY_EXHAUSTED) {
            willThrow(new ResourceExhaustedException(MAX_CHILD_RECORDS_EXCEEDED))
                    .given(worldUpdater)
                    .deleteAccount(laterDeletion);
        } else if (finalization == FinalizationOutcome.COMMIT_CAPACITY_EXHAUSTED) {
            willThrow(new ResourceExhaustedException(MAX_CHILD_RECORDS_EXCEEDED))
                    .given(worldUpdater)
                    .commit();
        } else if (finalization == FinalizationOutcome.MISSING_BENEFICIARY) {
            willThrow(new HandleException(CONTRACT_EXECUTION_EXCEPTION))
                    .given(worldUpdater)
                    .deleteAccount(laterDeletion);
        }
        final List<GasChargingEvent> events = new ArrayList<>();
        doAnswer(invocation -> {
                    events.add(new GasChargingEvent(
                            CHARGE, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
                    return null;
                })
                .when(worldUpdater)
                .collectGasFee(any(), anyLong(), anyBoolean());
        if (!failsFinalization) {
            doAnswer(invocation -> {
                        events.add(new GasChargingEvent(
                                REFUND, invocation.getArgument(0), invocation.getArgument(1), false));
                        return null;
                    })
                    .when(worldUpdater)
                    .refundGasFee(any(), anyLong());
        }

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        final var expectedStatus =
                switch (finalization) {
                    case SUCCESS -> SUCCESS;
                    case EXECUTION_REVERT -> CONTRACT_REVERT_EXECUTED;
                    case MISSING_BENEFICIARY -> CONTRACT_EXECUTION_EXCEPTION;
                    default -> MAX_CHILD_RECORDS_EXCEEDED;
                };
        assertEquals(expectedStatus, result.finalStatus());
        assertEquals(failsFinalization ? GAS_LIMIT : SUCCESS_RESULT.gasUsed(), result.gasUsed());
        if (failsFinalization) {
            verify(worldUpdater, never()).refundGasFee(any(), anyLong());
            final var ordered = inOrder(worldUpdater);
            ordered.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
            ordered.verify(worldUpdater).deleteAccount(laterDeletion);
            ordered.verify(worldUpdater).revert();
            if (finalization != FinalizationOutcome.COMMIT_CAPACITY_EXHAUSTED) {
                verify(worldUpdater, never()).commit();
            }
        } else {
            // Successful finalization refunds the relayer first, including an ordinary EVM revert.
            final var ordered = inOrder(worldUpdater);
            ordered.verify(worldUpdater).commit();
            ordered.verify(worldUpdater).refundGasFee(eq(RELAYER_ID), anyLong());
            ordered.verify(worldUpdater).refundGasFee(eq(SENDER_ID), anyLong());
            verify(worldUpdater, never()).revert();
        }
        assertGasReplayMatches(result.gasUsed(), failsFinalization, events);
    }

    private void assertGasReplayMatches(
            final long gasUsed, final boolean fullGasCharged, final List<GasChargingEvent> events) {
        // This replay also runs after a successful EVM transaction when its enclosing batch later fails.
        final var rootUpdater = mock(RootProxyWorldUpdater.class);
        given(rootUpdater.entityIdFactory()).willReturn(new FakeEntityIdFactoryImpl(0, 0));
        final var replay = new EthereumTransactionRollbackHandler(mock(CallOutcome.class), events, rootUpdater);
        final var fees = mock(FeeCharging.Context.class);
        final var handleContext = mock(HandleContext.class);
        final var tokenApi = mock(TokenServiceApi.class);
        final var stores = mock(StoreFactory.class);
        given(stores.serviceApi(TokenServiceApi.class)).willReturn(tokenApi);
        given(handleContext.storeFactory()).willReturn(stores);
        final var replayConfig = mock(Configuration.class);
        given(replayConfig.getConfigData(EntitiesConfig.class)).willReturn(new EntitiesConfig(0, false, false));
        given(handleContext.configuration()).willReturn(replayConfig);

        replay.replay(fees, handleContext);

        final var relayerCharge = fullGasCharged ? GAS_LIMIT * (NETWORK_GAS_PRICE - USER_OFFERED_GAS_PRICE) : 0;
        final var senderCharge = gasUsed * NETWORK_GAS_PRICE - relayerCharge;
        verify(fees).charge(RELAYER_ID, new Fees(0, relayerCharge, 0), null);
        verify(fees).charge(SENDER_ID, new Fees(0, senderCharge, 0), null);
        verifyNoMoreInteractions(fees);
        verify(tokenApi).incrementSenderNonce(SENDER_ID);
        verifyNoMoreInteractions(tokenApi);
    }

    @Test
    void failedExecutionDoesNotDeleteContractsFromStaleSelfDestructSet() {
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        final var transaction = clprDispatchCall(null);
        final var failedResult = HederaEvmTransactionResult.resourceExhaustionFrom(
                SENDER_ID, GAS_LIMIT, NETWORK_GAS_PRICE, ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED);
        givenClprDispatchRunsToCompletion(transaction, context, failedResult);
        given(initialFrame.getSelfDestructs()).willReturn(Set.of(NON_SYSTEM_LONG_ZERO_ADDRESS));

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        assertFalse(result.isSuccess());
        verify(worldUpdater, never()).deleteAccount(any());
        verify(worldUpdater).commit();
    }

    @Test
    void clprDispatchWithoutSenderAccountUsesTransactionSenderAndPrepaidGas() {
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        final var transaction = clprDispatchCall(null);
        givenClprDispatchRunsToCompletion(transaction, context);

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
        verify(gasCharging, never()).chargeForGas(any(), any(), any(), any(), any());
        verify(gasCharging, never()).maybeRefundGiven(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void clprDispatchStillRequiresSenderAccountForEthereumTransactions() {
        assertAbortsWith(clprDispatchCall(RELAYER_ID), INVALID_ACCOUNT_ID);
    }

    @Test
    void clprDispatchResourceExhaustionWithoutSenderAccountReportsTransactionSender() {
        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        final var transaction = clprDispatchCall(null);
        givenClprDispatchRunsToCompletion(transaction, context);
        willThrow(new ResourceExhaustedException(INSUFFICIENT_BALANCES_FOR_RENEWAL_FEES))
                .given(worldUpdater)
                .commit();

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        assertResourceExhaustion(INSUFFICIENT_BALANCES_FOR_RENEWAL_FEES, result);
        assertEquals(SENDER_ID, result.senderId());
    }

    @Test
    void senderNonceIncrementedExactlyOnceWithNormalFees() {
        givenSenderAccount();
        given(senderAccount.getNonce()).willReturn(NONCE);
        givenRelayerAccount();
        givenReceiverAccount();
        givenAccessTracker(mock(StorageAccessTracker.class));

        final var context = wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator);
        final var transaction = wellKnownRelayedHapiCall(0);

        given(gasCharging.chargeForGas(senderAccount, relayerAccount, context, worldUpdater, transaction))
                .willReturn(CHARGING_RESULT);
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        given(receiverAccount.getAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frameBuilder.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        NON_SYSTEM_LONG_ZERO_ADDRESS,
                        transaction.gasLimit() - CHARGING_RESULT.intrinsicGas(),
                        GAS_CALCULATOR,
                        List.of()))
                .willReturn(initialFrame);
        given(frameRunner.runToCompletion(
                        eq(GAS_LIMIT),
                        eq(SENDER_ID),
                        eq(initialFrame),
                        eq(tracer),
                        any(),
                        eq(contractCreationProcessor),
                        any(),
                        any()))
                .willReturn(SUCCESS_RESULT);
        given(initialFrame.getSelfDestructs()).willReturn(Set.of());

        subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        verify(senderAccount, times(1)).incrementNonce();
    }

    @Test
    void senderNonceIncrementedExactlyOnceWithFreeFees() {
        // Uses the real CustomGasCharging to verify no double-increment when fees are free.
        // Before the fix, CustomGasCharging would call incrementNonce() AND TransactionProcessor
        // would call it again, resulting in a double increment.
        final var realGasCharging = new CustomGasCharging((HederaGasCalculator) GAS_CALCULATOR);
        final var processor = new TransactionProcessor(
                frameBuilder,
                frameRunner,
                realGasCharging,
                messageCallProcessor,
                contractCreationProcessor,
                featureFlags,
                GAS_CALCULATOR,
                null);

        givenSenderAccount();
        given(senderAccount.getNonce()).willReturn(NONCE);
        givenRelayerAccount();
        givenReceiverAccount();
        givenAccessTracker(mock(StorageAccessTracker.class));
        given(senderAccount.getAddress()).willReturn(EIP_1014_ADDRESS);
        given(receiverAccount.getAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);

        final var freeFeesContext = new HederaEvmContext(
                NETWORK_GAS_PRICE, false, false, blocks, tinybarValues, systemContractGasCalculator, null, null);
        final var transaction = wellKnownRelayedHapiCall(0);

        given(frameBuilder.buildInitialFrameWith(
                        eq(transaction),
                        eq(worldUpdater),
                        eq(freeFeesContext),
                        eq(config),
                        eq(opsDurationCounter),
                        eq(featureFlags),
                        eq(EIP_1014_ADDRESS),
                        eq(NON_SYSTEM_LONG_ZERO_ADDRESS),
                        anyLong(),
                        eq(GAS_CALCULATOR),
                        eq(List.of())))
                .willReturn(initialFrame);
        given(frameRunner.runToCompletion(
                        eq(GAS_LIMIT),
                        eq(SENDER_ID),
                        eq(initialFrame),
                        eq(tracer),
                        any(),
                        eq(contractCreationProcessor),
                        any(),
                        any()))
                .willReturn(SUCCESS_RESULT);
        given(initialFrame.getSelfDestructs()).willReturn(Set.of());

        processor.processTransaction(transaction, worldUpdater, freeFeesContext, tracer, config, opsDurationCounter);

        verify(senderAccount, times(1)).incrementNonce();
        // Free fees must still record a zero-amount charge event carrying the nonce increment,
        // so a revert replays it (see EthereumTransactionRollbackHandler)
        verify(worldUpdater).collectGasFee(SENDER_ID, 0L, true);
    }

    private void assertResourceExhaustion(
            @NonNull final ResponseCodeEnum reason, @NonNull final HederaEvmTransactionResult result) {
        assertFalse(result.isSuccess());
        assertEquals(GAS_LIMIT, result.gasUsed());
        assertEquals(NETWORK_GAS_PRICE, result.gasPrice());
        assertNull(result.haltReason());
        assertEquals(Bytes.wrap(reason.name()), result.revertReason());
    }

    private void assertAbortsWith(@NonNull final ResponseCodeEnum reason) {
        assertAbortsWith(wellKnownHapiCall(), reason);
    }

    private void assertAbortsWith(
            @NonNull final HederaEvmTransaction transaction, @NonNull final ResponseCodeEnum reason) {
        assertFailsWith(
                reason,
                () -> subject.processTransaction(
                        transaction,
                        worldUpdater,
                        wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                        tracer,
                        config,
                        opsDurationCounter));
    }

    private void givenSenderAccountWithNoHederaAccount() {
        given(worldUpdater.getHederaAccount(SENDER_ID)).willReturn(senderAccount);
    }

    private void givenSenderAccount() {
        givenSenderAccountWithNoHederaAccount();
        given(senderAccount.hederaId()).willReturn(SENDER_ID);
    }

    private void givenRelayerAccount() {
        given(worldUpdater.getHederaAccount(RELAYER_ID)).willReturn(relayerAccount);
    }

    private void givenReceiverAccount() {
        given(worldUpdater.getHederaAccount(CALLED_CONTRACT_ID)).willReturn(receiverAccount);
        given(receiverAccount.hederaId()).willReturn(RECEIVER_ID);
    }

    private void givenAccessTracker(@Nullable final StorageAccessTracker tracker) {
        final Deque<MessageFrame> stack = new ArrayDeque<>();
        given(initialFrame.getMessageFrameStack()).willReturn(stack);
        stack.push(initialFrame);
        given(initialFrame.getContextVariable(TRACKER_CONTEXT_VARIABLE)).willReturn(tracker);
    }

    private void givenClprDispatchRunsToCompletion(
            @NonNull final HederaEvmTransaction transaction, @NonNull final HederaEvmContext context) {
        givenClprDispatchRunsToCompletion(transaction, context, SUCCESS_RESULT);
    }

    private void givenClprDispatchRunsToCompletion(
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaEvmContext context,
            @NonNull final HederaEvmTransactionResult result) {
        // A native CLPR dispatch has no sender account
        given(worldUpdater.getHederaAccount(SENDER_ID)).willReturn(null);
        givenReceiverAccount();
        givenAccessTracker(mock(StorageAccessTracker.class));
        given(receiverAccount.getAddress()).willReturn(NON_SYSTEM_LONG_ZERO_ADDRESS);
        given(frameBuilder.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        context,
                        config,
                        opsDurationCounter,
                        featureFlags,
                        EIP_1014_ADDRESS,
                        NON_SYSTEM_LONG_ZERO_ADDRESS,
                        transaction.gasLimit(),
                        GAS_CALCULATOR,
                        List.of()))
                .willReturn(initialFrame);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor,
                        GasCharges.NONE,
                        null))
                .willReturn(result);
    }

    private static HederaEvmTransaction clprDispatchCall(@Nullable final AccountID relayer) {
        return new HederaEvmTransaction(
                SENDER_ID,
                relayer,
                CALLED_CONTRACT_ID,
                NONCE,
                CALL_DATA,
                MAINNET_CHAIN_ID,
                0L,
                GAS_LIMIT,
                USER_OFFERED_GAS_PRICE,
                MAX_GAS_ALLOWANCE,
                null,
                null,
                null,
                null,
                null,
                EIP_1014_ADDRESS,
                true);
    }
}
