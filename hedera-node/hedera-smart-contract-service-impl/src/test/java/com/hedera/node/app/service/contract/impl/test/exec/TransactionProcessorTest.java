// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_BALANCES_FOR_RENEWAL_FEES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.TRACKER_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CHARGING_RESULT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.FrameRunner;
import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmBlocks;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.TxStorageUsage;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private HederaEvmAccount senderAccount;

    @Mock
    private StorageAccessTracker tracker;

    @Mock
    private HederaEvmAccount relayerAccount;

    @Mock
    private HederaEvmAccount receiverAccount;

    @Mock
    private CustomGasCharging gasCharging;

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private ContractOperationStreamBuilder recordBuilder;

    private final Deque<MessageFrame> stack = new ArrayDeque<>();

    private TransactionProcessor subject;

    private OpsDurationCounter opsDurationCounter;

    @BeforeEach
    void setUp() {
        subject = new TransactionProcessor(
                frameBuilder, frameRunner, gasCharging, messageCallProcessor, contractCreationProcessor, featureFlags);
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
                null);
        given(messageCallProcessor.isImplicitCreationEnabled()).willReturn(true);
        assertAbortsWith(invalidCreation, INVALID_CONTRACT_ID);
    }

    @Test
    void lazyCreationAttemptWithValidAddress() {
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
                VALUE,
                GAS_LIMIT,
                USER_OFFERED_GAS_PRICE,
                MAX_GAS_ALLOWANCE,
                null,
                null);
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
                        CHARGING_RESULT.intrinsicGas()))
                .willReturn(initialFrame);
        given(senderAccount.getNonce()).willReturn(NONCE);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor))
                .willReturn(SUCCESS_RESULT);

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
        verify(worldUpdater).setupTopLevelLazyCreate(expectedToAddress);
    }

    @Test
    void lazyCreationAttemptCanCallNotExistingFeatureFlagOn() {
        givenSenderAccount();
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
                null);
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
                        CHARGING_RESULT.intrinsicGas()))
                .willReturn(initialFrame);
        given(senderAccount.hederaId()).willReturn(SENDER_ID);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor))
                .willReturn(SUCCESS_RESULT);
        given(featureFlags.isAllowCallsToNonContractAccountsEnabled(any(), any()))
                .willReturn(true);
        given(senderAccount.getNonce()).willReturn(NONCE);

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
                null);
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
                        CHARGING_RESULT.intrinsicGas()))
                .willReturn(initialFrame);
        given(senderAccount.hederaId()).willReturn(SENDER_ID);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor))
                .willReturn(SUCCESS_RESULT);
        given(featureFlags.isAllowCallsToNonContractAccountsEnabled(any(), any()))
                .willReturn(true);
        given(senderAccount.getNonce()).willReturn(NONCE);

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
                null);
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
                null);
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
    @SuppressWarnings("unchecked")
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
                        CHARGING_RESULT.intrinsicGas()))
                .willReturn(initialFrame);
        given(senderAccount.getNonce()).willReturn(NONCE);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor))
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
                        CHARGING_RESULT.intrinsicGas());
        inOrder.verify(frameRunner)
                .runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor);
        inOrder.verify(gasCharging)
                .maybeRefundGiven(
                        GAS_LIMIT - SUCCESS_RESULT.gasUsed(),
                        CHARGING_RESULT.relayerAllowanceUsed(),
                        senderAccount,
                        relayerAccount,
                        context,
                        worldUpdater);
        inOrder.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        inOrder.verify(worldUpdater).commit();
        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
    }

    @Test
    @SuppressWarnings("unchecked")
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
                        CHARGING_RESULT.intrinsicGas()))
                .willReturn(initialFrame);
        given(frameRunner.runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor))
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
                        CHARGING_RESULT.intrinsicGas());
        inOrder.verify(frameRunner)
                .runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor);
        inOrder.verify(gasCharging)
                .maybeRefundGiven(GAS_LIMIT - SUCCESS_RESULT.gasUsed(), 0, senderAccount, null, context, worldUpdater);
        inOrder.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        inOrder.verify(worldUpdater).commit();
        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
        verify(senderAccount, never()).incrementNonce();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ethCallHappyPathAsExpected() {
        final var inOrder =
                inOrder(worldUpdater, frameBuilder, frameRunner, gasCharging, messageCallProcessor, senderAccount);

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
                        CHARGING_RESULT.intrinsicGas()))
                .willReturn(initialFrame);
        given(frameRunner.runToCompletion(
                        eq(transaction.gasLimit()),
                        eq(SENDER_ID),
                        eq(initialFrame),
                        eq(tracer),
                        any(),
                        eq(contractCreationProcessor)))
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
                        CHARGING_RESULT.intrinsicGas());
        inOrder.verify(frameRunner)
                .runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor);
        inOrder.verify(gasCharging)
                .maybeRefundGiven(
                        GAS_LIMIT - SUCCESS_RESULT.gasUsed(),
                        CHARGING_RESULT.relayerAllowanceUsed(),
                        senderAccount,
                        relayerAccount,
                        context,
                        worldUpdater);
        inOrder.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        inOrder.verify(worldUpdater).commit();
        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void ethCallHappyPathAsExpectedAndCanCallNotExistingFeatureFlagOn() {
        final var inOrder =
                inOrder(worldUpdater, frameBuilder, frameRunner, gasCharging, messageCallProcessor, senderAccount);

        givenSenderAccount();
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
                        CHARGING_RESULT.intrinsicGas()))
                .willReturn(initialFrame);
        given(senderAccount.hederaId()).willReturn(SENDER_ID);
        given(frameRunner.runToCompletion(
                        eq(transaction.gasLimit()),
                        eq(SENDER_ID),
                        eq(initialFrame),
                        eq(tracer),
                        any(),
                        eq(contractCreationProcessor)))
                .willReturn(SUCCESS_RESULT);
        given(initialFrame.getSelfDestructs()).willReturn(Set.of(NON_SYSTEM_LONG_ZERO_ADDRESS));
        given(featureFlags.isAllowCallsToNonContractAccountsEnabled(any(), any()))
                .willReturn(true);
        given(senderAccount.getNonce()).willReturn(NONCE);

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
                        CHARGING_RESULT.intrinsicGas());
        inOrder.verify(frameRunner)
                .runToCompletion(
                        transaction.gasLimit(),
                        SENDER_ID,
                        initialFrame,
                        tracer,
                        messageCallProcessor,
                        contractCreationProcessor);
        inOrder.verify(gasCharging)
                .maybeRefundGiven(
                        GAS_LIMIT - SUCCESS_RESULT.gasUsed(),
                        CHARGING_RESULT.relayerAllowanceUsed(),
                        senderAccount,
                        relayerAccount,
                        context,
                        worldUpdater);
        inOrder.verify(worldUpdater).deleteAccount(NON_SYSTEM_LONG_ZERO_ADDRESS);
        inOrder.verify(worldUpdater).commit();
        assertEquals(SUCCESS_RESULT.withTxStorageUsage(new TxStorageUsage(List.of(), null)), result);
    }

    @Test
    @SuppressWarnings("unchecked")
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
                        CHARGING_RESULT.intrinsicGas()))
                .willReturn(initialFrame);
        given(frameRunner.runToCompletion(
                        eq(transaction.gasLimit()),
                        eq(SENDER_ID),
                        eq(initialFrame),
                        eq(tracer),
                        any(),
                        eq(contractCreationProcessor)))
                .willReturn(SUCCESS_RESULT);
        given(initialFrame.getSelfDestructs()).willReturn(Set.of(NON_SYSTEM_LONG_ZERO_ADDRESS));

        willThrow(new ResourceExhaustedException(INSUFFICIENT_BALANCES_FOR_RENEWAL_FEES))
                .given(worldUpdater)
                .commit();

        final var result =
                subject.processTransaction(transaction, worldUpdater, context, tracer, config, opsDurationCounter);

        assertResourceExhaustion(INSUFFICIENT_BALANCES_FOR_RENEWAL_FEES, result);
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
}
