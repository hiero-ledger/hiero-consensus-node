// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.utils;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.accessTrackerFor;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.selfDestructBeneficiariesFor;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.tinybarValuesFor;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALL_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CONTRACT_CODE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_COINBASE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.GAS_LIMIT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.INTRINSIC_GAS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NETWORK_GAS_PRICE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SOME_BLOCK_NO;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.VALUE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownContextWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownHapiCall;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownHapiCreate;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownRelayedHapiCall;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmBlocks;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FrameBuilderTest {
    @Mock
    private HederaEvmAccount account;

    @Mock
    private BlockValues blockValues;

    @Mock
    private TinybarValues tinybarValues;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private HederaEvmBlocks blocks;

    @Mock
    private HederaWorldUpdater worldUpdater;

    @Mock
    private HederaWorldUpdater stackedUpdater;

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private Enhancement enhancement;

    @Mock
    private HederaNativeOperations hederaNativeOperations;

    @Mock
    private ReadableAccountStore readableAccountStore;

    @Mock
    private Account contract;

    private final FrameBuilder subject = new FrameBuilder();

    @Test
    void constructsExpectedFrameForCallToExtantContractIncludingOptionalContextVariables() {
        final var transaction = wellKnownHapiCall();
        givenContractExists();
        final var recordBuilder = mock(ContractOperationStreamBuilder.class);
        given(worldUpdater.getHederaAccount(CALLED_CONTRACT_ID)).willReturn(account);
        given(account.getEvmCode(Bytes.wrap(CALL_DATA.toByteArray()))).willReturn(CONTRACT_CODE);
        given(worldUpdater.updater()).willReturn(stackedUpdater);
        given(blocks.blockValuesOf(GAS_LIMIT)).willReturn(blockValues);
        given(blocks.blockHashOf(SOME_BLOCK_NO)).willReturn(Hash.EMPTY);
        final var config = HederaTestConfigBuilder.create()
                .withValue("ledger.fundingAccount", DEFAULT_COINBASE)
                .getOrCreateConfig();

        final var frame = subject.buildInitialFrameWith(
                transaction,
                worldUpdater,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator, recordBuilder),
                config,
                OpsDurationCounter.disabled(),
                featureFlags,
                EIP_1014_ADDRESS,
                NON_SYSTEM_LONG_ZERO_ADDRESS,
                INTRINSIC_GAS);

        assertEquals(1024, frame.getMaxStackSize());
        assertSame(stackedUpdater, frame.getWorldUpdater());
        assertEquals(transaction.gasAvailable(INTRINSIC_GAS), frame.getRemainingGas());
        assertSame(EIP_1014_ADDRESS, frame.getOriginatorAddress());
        assertEquals(Wei.of(NETWORK_GAS_PRICE), frame.getGasPrice());
        assertEquals(Wei.ONE, frame.getBlobGasPrice());
        assertEquals(Wei.of(VALUE), frame.getValue());
        assertEquals(Wei.of(VALUE), frame.getApparentValue());
        assertSame(blockValues, frame.getBlockValues());
        assertFalse(frame.isStatic());
        assertEquals(asLongZeroAddress(DEFAULT_COINBASE), frame.getMiningBeneficiary());
        final var hashLookup = frame.getBlockHashLookup();
        assertEquals(Hash.EMPTY, hashLookup.apply(SOME_BLOCK_NO));
        assertSame(config, configOf(frame));
        assertDoesNotThrow(frame::notifyCompletion);
        assertEquals(MessageFrame.Type.MESSAGE_CALL, frame.getType());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getRecipientAddress());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getContractAddress());
        assertEquals(transaction.evmPayload(), frame.getInputData());
        assertSame(CONTRACT_CODE, frame.getCode());
        assertNotNull(accessTrackerFor(frame));
        assertSame(tinybarValues, tinybarValuesFor(frame));
        assertSame(recordBuilder, selfDestructBeneficiariesFor(frame));
        assertEquals(0L, FrameUtils.opsDurationCounter(frame).opsDurationUnitsConsumed());
    }

    @Test
    void constructsExpectedFrameForCallToExtantContractNotIncludingAccessTrackerWithSidecarDisabled() {
        final var transaction = wellKnownHapiCall();
        givenContractExists();
        given(worldUpdater.updater()).willReturn(stackedUpdater);
        given(blocks.blockValuesOf(GAS_LIMIT)).willReturn(blockValues);
        given(blocks.blockHashOf(SOME_BLOCK_NO)).willReturn(Hash.EMPTY);
        given(worldUpdater.getHederaAccount(CALLED_CONTRACT_ID)).willReturn(account);
        given(account.getEvmCode(Bytes.wrap(CALL_DATA.toByteArray()))).willReturn(CONTRACT_CODE);
        final var config = HederaTestConfigBuilder.create()
                .withValue("ledger.fundingAccount", DEFAULT_COINBASE)
                .withValue("contracts.sidecars", "CONTRACT_BYTECODE,CONTRACT_ACTION")
                .getOrCreateConfig();

        final var frame = subject.buildInitialFrameWith(
                transaction,
                worldUpdater,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                config,
                OpsDurationCounter.disabled(),
                featureFlags,
                EIP_1014_ADDRESS,
                NON_SYSTEM_LONG_ZERO_ADDRESS,
                INTRINSIC_GAS);

        assertEquals(1024, frame.getMaxStackSize());
        assertSame(stackedUpdater, frame.getWorldUpdater());
        assertEquals(transaction.gasAvailable(INTRINSIC_GAS), frame.getRemainingGas());
        assertSame(EIP_1014_ADDRESS, frame.getOriginatorAddress());
        assertEquals(Wei.of(NETWORK_GAS_PRICE), frame.getGasPrice());
        assertEquals(Wei.ONE, frame.getBlobGasPrice());
        assertEquals(Wei.of(VALUE), frame.getValue());
        assertEquals(Wei.of(VALUE), frame.getApparentValue());
        assertSame(blockValues, frame.getBlockValues());
        assertFalse(frame.isStatic());
        assertEquals(asLongZeroAddress(DEFAULT_COINBASE), frame.getMiningBeneficiary());
        final var hashLookup = frame.getBlockHashLookup();
        assertEquals(Hash.EMPTY, hashLookup.apply(SOME_BLOCK_NO));
        assertSame(config, configOf(frame));
        assertDoesNotThrow(frame::notifyCompletion);
        assertEquals(MessageFrame.Type.MESSAGE_CALL, frame.getType());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getRecipientAddress());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getContractAddress());
        assertEquals(transaction.evmPayload(), frame.getInputData());
        assertSame(CONTRACT_CODE, frame.getCode());
        assertNull(accessTrackerFor(frame));
        assertSame(tinybarValues, tinybarValuesFor(frame));
    }

    @Test
    void callFailsWhenContractNotFound() {
        final var transaction = wellKnownHapiCall();
        givenContractExists();
        given(worldUpdater.updater()).willReturn(stackedUpdater);
        given(blocks.blockValuesOf(GAS_LIMIT)).willReturn(blockValues);
        given(worldUpdater.getHederaAccount(CALLED_CONTRACT_ID)).willReturn(null);
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.evm.allowCallsToNonContractAccounts", "false")
                .getOrCreateConfig();

        assertThrows(
                HandleException.class,
                () -> subject.buildInitialFrameWith(
                        transaction,
                        worldUpdater,
                        wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                        config,
                        OpsDurationCounter.disabled(),
                        featureFlags,
                        EIP_1014_ADDRESS,
                        NON_SYSTEM_LONG_ZERO_ADDRESS,
                        INTRINSIC_GAS));
    }

    @Test
    void callSucceedsWhenContractNotFoundIfPermitted() {
        final var transaction = wellKnownRelayedHapiCall(VALUE);
        givenContractExists();
        given(worldUpdater.updater()).willReturn(stackedUpdater);
        given(blocks.blockValuesOf(GAS_LIMIT)).willReturn(blockValues);
        given(worldUpdater.getHederaAccount(CALLED_CONTRACT_ID)).willReturn(account);
        given(account.getEvmCode(Bytes.wrap(CALL_DATA.toByteArray()))).willReturn(CONTRACT_CODE);
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();

        final var frame = subject.buildInitialFrameWith(
                transaction,
                worldUpdater,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                config,
                OpsDurationCounter.disabled(),
                featureFlags,
                EIP_1014_ADDRESS,
                NON_SYSTEM_LONG_ZERO_ADDRESS,
                INTRINSIC_GAS);

        assertEquals(1024, frame.getMaxStackSize());
        assertSame(stackedUpdater, frame.getWorldUpdater());
        assertEquals(transaction.gasAvailable(INTRINSIC_GAS), frame.getRemainingGas());
        assertSame(EIP_1014_ADDRESS, frame.getOriginatorAddress());
        assertEquals(Wei.of(NETWORK_GAS_PRICE), frame.getGasPrice());
        assertEquals(Wei.ONE, frame.getBlobGasPrice());
        assertEquals(Wei.of(VALUE), frame.getValue());
        assertEquals(Wei.of(VALUE), frame.getApparentValue());
        assertSame(blockValues, frame.getBlockValues());
        assertFalse(frame.isStatic());
        assertEquals(asLongZeroAddress(DEFAULT_COINBASE), frame.getMiningBeneficiary());
        assertSame(config, configOf(frame));
        assertDoesNotThrow(frame::notifyCompletion);
        assertEquals(MessageFrame.Type.MESSAGE_CALL, frame.getType());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getRecipientAddress());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getContractAddress());
        assertEquals(transaction.evmPayload(), frame.getInputData());
        assertSame(CONTRACT_CODE, frame.getCode());
        assertSame(tinybarValues, tinybarValuesFor(frame));
    }

    @Test
    void callSucceedsWhenContractFoundButDeleted() {
        final var transaction = wellKnownRelayedHapiCall(VALUE);
        givenContractExists();
        given(worldUpdater.updater()).willReturn(stackedUpdater);
        given(blocks.blockValuesOf(GAS_LIMIT)).willReturn(blockValues);
        given(contract.deleted()).willReturn(true);
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();

        final var frame = subject.buildInitialFrameWith(
                transaction,
                worldUpdater,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                config,
                OpsDurationCounter.disabled(),
                featureFlags,
                EIP_1014_ADDRESS,
                NON_SYSTEM_LONG_ZERO_ADDRESS,
                INTRINSIC_GAS);

        assertEquals(1024, frame.getMaxStackSize());
        assertSame(stackedUpdater, frame.getWorldUpdater());
        assertEquals(transaction.gasAvailable(INTRINSIC_GAS), frame.getRemainingGas());
        assertSame(EIP_1014_ADDRESS, frame.getOriginatorAddress());
        assertEquals(Wei.of(NETWORK_GAS_PRICE), frame.getGasPrice());
        assertEquals(Wei.ONE, frame.getBlobGasPrice());
        assertEquals(Wei.of(VALUE), frame.getValue());
        assertEquals(Wei.of(VALUE), frame.getApparentValue());
        assertSame(blockValues, frame.getBlockValues());
        assertFalse(frame.isStatic());
        assertEquals(asLongZeroAddress(DEFAULT_COINBASE), frame.getMiningBeneficiary());
        assertSame(config, configOf(frame));
        assertDoesNotThrow(frame::notifyCompletion);
        assertEquals(MessageFrame.Type.MESSAGE_CALL, frame.getType());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getRecipientAddress());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getContractAddress());
        assertEquals(transaction.evmPayload(), frame.getInputData());
        assertSame(CodeV0.EMPTY_CODE, frame.getCode());
        assertSame(tinybarValues, tinybarValuesFor(frame));
    }

    @Test
    void constructsExpectedFrameForCallToMissingContract() {
        final var transaction = wellKnownRelayedHapiCall(VALUE);
        givenContractExists();
        given(worldUpdater.updater()).willReturn(stackedUpdater);
        given(blocks.blockValuesOf(GAS_LIMIT)).willReturn(blockValues);
        given(blocks.blockHashOf(SOME_BLOCK_NO)).willReturn(Hash.EMPTY);
        final var config = HederaTestConfigBuilder.create()
                .withValue("ledger.fundingAccount", DEFAULT_COINBASE)
                .withValue("contracts.sidecarValidationEnabled", true)
                .getOrCreateConfig();

        final var frame = subject.buildInitialFrameWith(
                transaction,
                worldUpdater,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                config,
                OpsDurationCounter.disabled(),
                featureFlags,
                EIP_1014_ADDRESS,
                NON_SYSTEM_LONG_ZERO_ADDRESS,
                INTRINSIC_GAS);

        assertTrue(FrameUtils.hasActionValidationEnabled(frame));
        assertEquals(1024, frame.getMaxStackSize());
        assertSame(stackedUpdater, frame.getWorldUpdater());
        assertEquals(transaction.gasAvailable(INTRINSIC_GAS), frame.getRemainingGas());
        assertSame(EIP_1014_ADDRESS, frame.getOriginatorAddress());
        assertEquals(Wei.of(NETWORK_GAS_PRICE), frame.getGasPrice());
        assertEquals(Wei.ONE, frame.getBlobGasPrice());
        assertEquals(Wei.of(VALUE), frame.getValue());
        assertEquals(Wei.of(VALUE), frame.getApparentValue());
        assertSame(blockValues, frame.getBlockValues());
        assertFalse(frame.isStatic());
        assertEquals(asLongZeroAddress(DEFAULT_COINBASE), frame.getMiningBeneficiary());
        final var hashLookup = frame.getBlockHashLookup();
        assertEquals(Hash.EMPTY, hashLookup.apply(SOME_BLOCK_NO));
        assertSame(config, configOf(frame));
        assertDoesNotThrow(frame::notifyCompletion);
        assertEquals(MessageFrame.Type.MESSAGE_CALL, frame.getType());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getRecipientAddress());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getContractAddress());
        assertEquals(transaction.evmPayload(), frame.getInputData());
        assertSame(CodeV0.EMPTY_CODE, frame.getCode());
        assertSame(tinybarValues, tinybarValuesFor(frame));
    }

    @Test
    void constructsExpectedFrameForCreate() {
        final var transaction = wellKnownHapiCreate();
        given(worldUpdater.updater()).willReturn(stackedUpdater);
        given(blocks.blockValuesOf(GAS_LIMIT)).willReturn(blockValues);
        given(blocks.blockHashOf(SOME_BLOCK_NO)).willReturn(Hash.EMPTY);
        final var config = HederaTestConfigBuilder.create()
                .withValue("ledger.fundingAccount", DEFAULT_COINBASE)
                .getOrCreateConfig();
        final var expectedCode = CodeFactory.createCode(transaction.evmPayload(), 0, false);

        final var frame = subject.buildInitialFrameWith(
                transaction,
                worldUpdater,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                config,
                OpsDurationCounter.disabled(),
                featureFlags,
                EIP_1014_ADDRESS,
                NON_SYSTEM_LONG_ZERO_ADDRESS,
                INTRINSIC_GAS);

        assertEquals(1024, frame.getMaxStackSize());
        assertSame(stackedUpdater, frame.getWorldUpdater());
        assertEquals(transaction.gasAvailable(INTRINSIC_GAS), frame.getRemainingGas());
        assertSame(EIP_1014_ADDRESS, frame.getOriginatorAddress());
        assertEquals(Wei.of(NETWORK_GAS_PRICE), frame.getGasPrice());
        assertEquals(Wei.ONE, frame.getBlobGasPrice());
        assertEquals(Wei.of(VALUE), frame.getValue());
        assertEquals(Wei.of(VALUE), frame.getApparentValue());
        assertSame(blockValues, frame.getBlockValues());
        assertFalse(frame.isStatic());
        assertEquals(asLongZeroAddress(DEFAULT_COINBASE), frame.getMiningBeneficiary());
        final var hashLookup = frame.getBlockHashLookup();
        assertEquals(Hash.EMPTY, hashLookup.apply(SOME_BLOCK_NO));
        assertSame(config, configOf(frame));
        assertDoesNotThrow(frame::notifyCompletion);
        assertEquals(MessageFrame.Type.CONTRACT_CREATION, frame.getType());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getRecipientAddress());
        assertEquals(NON_SYSTEM_LONG_ZERO_ADDRESS, frame.getContractAddress());
        assertEquals(Bytes.EMPTY, frame.getInputData());
        assertEquals(expectedCode, frame.getCode());
        assertSame(tinybarValues, tinybarValuesFor(frame));
    }

    private void givenContractExists() {
        given(worldUpdater.enhancement()).willReturn(enhancement);
        given(enhancement.nativeOperations()).willReturn(hederaNativeOperations);
        given(hederaNativeOperations.readableAccountStore()).willReturn(readableAccountStore);
        given(readableAccountStore.getContractById(any())).willReturn(contract);
    }
}
