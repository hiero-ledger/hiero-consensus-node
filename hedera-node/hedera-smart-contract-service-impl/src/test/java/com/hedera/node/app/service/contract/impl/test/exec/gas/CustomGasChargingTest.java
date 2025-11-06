// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.gas;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging.ONE_HBAR_IN_TINYBARS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.GAS_LIMIT;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.MAX_GAS_ALLOWANCE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NETWORK_GAS_PRICE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RELAYER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertFailsWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownContextWith;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownHapiCall;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownRelayedHapiCall;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownRelayedHapiCallWithGasLimit;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.wellKnownRelayedHapiCallWithUserGasPriceAndMaxAllowance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.TinybarValues;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmBlocks;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomGasChargingTest {
    @Mock
    private HederaEvmAccount sender;

    @Mock
    private HederaEvmAccount relayer;

    @Mock
    private HederaEvmBlocks blocks;

    @Mock
    private TinybarValues tinybarValues;

    @Mock
    private SystemContractGasCalculator systemContractGasCalculator;

    @Mock
    private HederaWorldUpdater worldUpdater;

    @Mock
    private GasCalculator gasCalculator;

    private CustomGasCharging subject;

    @Mock
    private ContractOperationStreamBuilder recordBuilder;

    @BeforeEach
    void setUp() {
        subject = new CustomGasCharging(gasCalculator);
    }

    @Test
    void staticCallsDoNotChargeGas() {
        givenWellKnownIntrinsicGasCost();
        final var chargingResult = subject.chargeForGas(
                sender,
                relayer,
                wellKnownContextWith(blocks, true, tinybarValues, systemContractGasCalculator),
                worldUpdater,
                wellKnownHapiCall());
        assertEquals(0, chargingResult.relayerAllowanceUsed());
        verifyNoInteractions(worldUpdater);
    }

    @Test
    void zeroPriceGasDoesNoChargingWorkButDoesReturnIntrinsicGas() {
        final var context =
                new HederaEvmContext(0L, false, blocks, tinybarValues, systemContractGasCalculator, null, null);
        givenWellKnownIntrinsicGasCost();
        final var chargingResult = subject.chargeForGas(sender, relayer, context, worldUpdater, wellKnownHapiCall());
        assertEquals(0, chargingResult.relayerAllowanceUsed());
        assertEquals(TestHelpers.INTRINSIC_GAS, chargingResult.intrinsicGas());
    }

    @Test
    void staticCallsDoNotRefundGas() {
        subject.maybeRefundGiven(
                GAS_LIMIT / 2,
                MAX_GAS_ALLOWANCE / 2,
                sender,
                relayer,
                wellKnownContextWith(blocks, true, tinybarValues, systemContractGasCalculator),
                worldUpdater);
        verifyNoInteractions(worldUpdater);
    }

    @Test
    void consumedGasDoesNotRefundAnything() {
        subject.maybeRefundGiven(
                0,
                MAX_GAS_ALLOWANCE / 2,
                sender,
                relayer,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                worldUpdater);
        verifyNoInteractions(worldUpdater);
    }

    @Test
    void withNoRelayerSenderGetsFullRefund() {
        given(sender.hederaId()).willReturn(SENDER_ID);
        final var unusedGas = GAS_LIMIT / 2;
        subject.maybeRefundGiven(
                unusedGas,
                0,
                sender,
                null,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                worldUpdater);
        verify(worldUpdater).refundGasFee(SENDER_ID, unusedGas * NETWORK_GAS_PRICE);
    }

    @Test
    void relayerGetsPriorityAndSenderGetsRemainder() {
        given(sender.hederaId()).willReturn(SENDER_ID);
        given(relayer.hederaId()).willReturn(RELAYER_ID);
        final var unusedGas = GAS_LIMIT / 2;
        final var refund = unusedGas * NETWORK_GAS_PRICE;
        final var allowanceUsed = 2 * refund / 3;
        subject.maybeRefundGiven(
                unusedGas,
                allowanceUsed,
                sender,
                relayer,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                worldUpdater);
        verify(worldUpdater).refundGasFee(RELAYER_ID, allowanceUsed);
        verify(worldUpdater).refundGasFee(SENDER_ID, refund - allowanceUsed);
    }

    @Test
    void senderGetsNoRefundIfNoneRemaining() {
        given(relayer.hederaId()).willReturn(RELAYER_ID);
        final var unusedGas = GAS_LIMIT / 2;
        final var refund = unusedGas * NETWORK_GAS_PRICE;
        final var allowanceUsed = 3 * refund / 2;
        subject.maybeRefundGiven(
                unusedGas,
                allowanceUsed,
                sender,
                relayer,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                worldUpdater);
        verify(worldUpdater).refundGasFee(RELAYER_ID, refund);
        verifyNoMoreInteractions(worldUpdater);
    }

    @Test
    void failsImmediatelyIfGasLimitBelowIntrinsicGas() {
        givenWellKnownIntrinsicGasCost();
        assertFailsWith(
                INSUFFICIENT_GAS,
                () -> subject.chargeForGas(
                        sender,
                        relayer,
                        wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                        worldUpdater,
                        wellKnownRelayedHapiCallWithGasLimit(TestHelpers.INTRINSIC_GAS - 1)));
    }

    @Test
    void failsImmediatelyIfPayerBalanceBelowUpfrontCost() {
        givenWellKnownIntrinsicGasCost();
        final var transaction = wellKnownHapiCall();
        given(sender.getBalance()).willReturn(Wei.of(transaction.upfrontCostGiven(NETWORK_GAS_PRICE) - 1));
        assertFailsWith(
                INSUFFICIENT_PAYER_BALANCE,
                () -> subject.chargeForGas(
                        sender,
                        relayer,
                        wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                        worldUpdater,
                        transaction));
    }

    @Test
    void deductsGasCostIfUpfrontCostIsAfforded() {
        givenWellKnownIntrinsicGasCost();
        final var transaction = wellKnownHapiCall();
        given(sender.hederaId()).willReturn(SENDER_ID);
        given(sender.getBalance()).willReturn(Wei.of(transaction.upfrontCostGiven(NETWORK_GAS_PRICE)));
        final var chargingResult = subject.chargeForGas(
                sender,
                relayer,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                worldUpdater,
                transaction);
        assertEquals(0, chargingResult.relayerAllowanceUsed());
        verify(worldUpdater).collectGasFee(SENDER_ID, transaction.gasCostGiven(NETWORK_GAS_PRICE), false);
    }

    @Test
    void requiresSufficientGasAllowanceIfUserOfferedPriceIsZero() {
        givenWellKnownIntrinsicGasCost();
        final var insufficientMaxAllowance = 123L;
        final var transaction = wellKnownRelayedHapiCallWithUserGasPriceAndMaxAllowance(0, insufficientMaxAllowance);
        assertFailsWith(
                INSUFFICIENT_TX_FEE,
                () -> subject.chargeForGas(
                        sender,
                        relayer,
                        wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                        worldUpdater,
                        transaction));
    }

    @Test
    void requiresRelayerToHaveSufficientBalanceIfUserOfferedPriceIsZero() {
        givenWellKnownIntrinsicGasCost();
        final var transaction = wellKnownRelayedHapiCallWithUserGasPriceAndMaxAllowance(0, Long.MAX_VALUE);
        given(relayer.getBalance()).willReturn(Wei.of(transaction.gasCostGiven(NETWORK_GAS_PRICE) - 1));
        assertFailsWith(
                INSUFFICIENT_PAYER_BALANCE,
                () -> subject.chargeForGas(
                        sender,
                        relayer,
                        wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                        worldUpdater,
                        transaction));
    }

    @Test
    void chargesRelayerOnlyIfUserOfferedPriceIsZero() {
        givenWellKnownIntrinsicGasCost();
        final var transaction = wellKnownRelayedHapiCallWithUserGasPriceAndMaxAllowance(0, Long.MAX_VALUE);
        final var gasCost = transaction.gasCostGiven(NETWORK_GAS_PRICE);
        given(relayer.getBalance()).willReturn(Wei.of(gasCost));
        given(relayer.hederaId()).willReturn(RELAYER_ID);
        given(sender.getBalance()).willReturn(Wei.of(transaction.value()));
        given(sender.hederaId()).willReturn(SENDER_ID);
        final var chargingResult = subject.chargeForGas(
                sender,
                relayer,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator, recordBuilder),
                worldUpdater,
                transaction);
        assertEquals(gasCost, chargingResult.relayerAllowanceUsed());
        verify(worldUpdater).collectGasFee(RELAYER_ID, gasCost, false);
    }

    @Test
    void chargesSenderOnlyIfUserOfferedPriceIsAtLeastNetworkPrice() {
        givenWellKnownIntrinsicGasCost();
        final var transaction = wellKnownRelayedHapiCallWithUserGasPriceAndMaxAllowance(NETWORK_GAS_PRICE, 0);
        final var gasCost = transaction.gasCostGiven(NETWORK_GAS_PRICE);
        given(sender.getBalance()).willReturn(Wei.of(gasCost + transaction.value()));
        given(sender.hederaId()).willReturn(SENDER_ID);
        given(relayer.hederaId()).willReturn(RELAYER_ID);
        given(relayer.getBalance()).willReturn(Wei.ZERO);
        final var chargingResult = subject.chargeForGas(
                sender,
                relayer,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator, recordBuilder),
                worldUpdater,
                transaction);
        assertEquals(0, chargingResult.relayerAllowanceUsed());
        verify(worldUpdater).collectGasFee(SENDER_ID, gasCost, true);
    }

    @Test
    void requiresSenderToCoverGasCost() {
        givenWellKnownIntrinsicGasCost();
        final var transaction = wellKnownRelayedHapiCallWithUserGasPriceAndMaxAllowance(NETWORK_GAS_PRICE, 0);
        final var gasCost = transaction.gasCostGiven(NETWORK_GAS_PRICE);
        given(sender.getBalance()).willReturn(Wei.of(gasCost + transaction.value() - 1));
        given(relayer.getBalance()).willReturn(Wei.ZERO);
        assertFailsWith(
                INSUFFICIENT_PAYER_BALANCE,
                () -> subject.chargeForGas(
                        sender,
                        relayer,
                        wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                        worldUpdater,
                        transaction));
    }

    @Test
    void rejectsIfSenderCannotCoverOfferedGasCost() {
        givenWellKnownIntrinsicGasCost();
        final var transaction =
                wellKnownRelayedHapiCallWithUserGasPriceAndMaxAllowance(NETWORK_GAS_PRICE / 2, Long.MAX_VALUE);
        given(sender.getBalance()).willReturn(Wei.of(transaction.offeredGasCost() - 1));
        given(relayer.getBalance()).willReturn(Wei.of(Long.MAX_VALUE));
        assertFailsWith(
                INSUFFICIENT_PAYER_BALANCE,
                () -> subject.chargeForGas(
                        sender,
                        relayer,
                        wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                        worldUpdater,
                        transaction));
    }

    @Test
    void rejectsIfRelayerCannotCoverRemainingGasCost() {
        givenWellKnownIntrinsicGasCost();
        final var transaction =
                wellKnownRelayedHapiCallWithUserGasPriceAndMaxAllowance(NETWORK_GAS_PRICE / 2, Long.MAX_VALUE);
        given(relayer.getBalance()).willReturn(Wei.ZERO);
        assertFailsWith(
                INSUFFICIENT_PAYER_BALANCE,
                () -> subject.chargeForGas(
                        sender,
                        relayer,
                        wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                        worldUpdater,
                        transaction));
    }

    @Test
    void failsIfGasAllowanceLessThanRemainingGasCost() {
        givenWellKnownIntrinsicGasCost();
        final var transaction = wellKnownRelayedHapiCallWithUserGasPriceAndMaxAllowance(NETWORK_GAS_PRICE / 2, 0);
        assertFailsWith(
                INSUFFICIENT_TX_FEE,
                () -> subject.chargeForGas(
                        sender,
                        relayer,
                        wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator),
                        worldUpdater,
                        transaction));
    }

    @Test
    void chargesSenderAndRelayerIfBothSolventAndWilling() {
        givenWellKnownIntrinsicGasCost();
        final var transaction =
                wellKnownRelayedHapiCallWithUserGasPriceAndMaxAllowance(NETWORK_GAS_PRICE / 2, Long.MAX_VALUE);
        final var gasCost = transaction.gasCostGiven(NETWORK_GAS_PRICE);
        final var relayerGasCost = gasCost - transaction.offeredGasCost();
        given(sender.getBalance()).willReturn(Wei.of(gasCost));
        given(sender.hederaId()).willReturn(SENDER_ID);
        given(relayer.getBalance()).willReturn(Wei.of(gasCost));
        given(relayer.hederaId()).willReturn(RELAYER_ID);
        final var chargingResult = subject.chargeForGas(
                sender,
                relayer,
                wellKnownContextWith(blocks, tinybarValues, systemContractGasCalculator, recordBuilder),
                worldUpdater,
                transaction);
        assertEquals(relayerGasCost, chargingResult.relayerAllowanceUsed());
        verify(worldUpdater).collectGasFee(SENDER_ID, transaction.offeredGasCost(), true);
        verify(worldUpdater).collectGasFee(RELAYER_ID, relayerGasCost, false);
    }

    @Test
    void chargeGasForAbortedTransaction() {
        givenWellKnownIntrinsicGasCost();
        given(worldUpdater.getHederaAccount(SENDER_ID)).willReturn(sender);
        given(sender.getBalance()).willReturn(Wei.of(100_000_000));
        subject.chargeGasForAbortedTransaction(
                SENDER_ID,
                wellKnownContextWith(blocks, false, tinybarValues, systemContractGasCalculator),
                worldUpdater,
                wellKnownHapiCall());
        verify(worldUpdater)
                .collectGasFee(SENDER_ID, Math.multiplyExact(NETWORK_GAS_PRICE, TestHelpers.INTRINSIC_GAS), false);
    }

    @Test
    void chargeGasForAbortedRelayedTransaction() {
        givenWellKnownIntrinsicGasCost();
        given(worldUpdater.getHederaAccount(RELAYER_ID)).willReturn(relayer);

        given(relayer.getBalance()).willReturn(Wei.of(100_000_000));
        subject.chargeGasForAbortedTransaction(
                RELAYER_ID,
                wellKnownContextWith(blocks, false, tinybarValues, systemContractGasCalculator),
                worldUpdater,
                wellKnownRelayedHapiCall(0L));
        verify(worldUpdater)
                .collectGasFee(RELAYER_ID, Math.multiplyExact(NETWORK_GAS_PRICE, TestHelpers.INTRINSIC_GAS), false);
    }

    @Test
    void chargeGasForAbortedTransactionChargesMax() {
        givenExcessiveIntrinsicGasCost(false);
        given(worldUpdater.getHederaAccount(SENDER_ID)).willReturn(sender);
        given(sender.getBalance()).willReturn(Wei.of(100_000_000));
        subject.chargeGasForAbortedTransaction(
                SENDER_ID,
                wellKnownContextWith(blocks, false, tinybarValues, systemContractGasCalculator),
                worldUpdater,
                wellKnownHapiCall());
        verify(worldUpdater).collectGasFee(SENDER_ID, ONE_HBAR_IN_TINYBARS, false);
    }

    private void givenWellKnownIntrinsicGasCost() {
        givenWellKnownIntrinsicGasCost(false);
    }

    private void givenWellKnownIntrinsicGasCost(boolean isCreation) {
        given(gasCalculator.transactionIntrinsicGasCost(any(), eq(isCreation), anyLong()))
                .willReturn(TestHelpers.INTRINSIC_GAS);
    }

    private void givenExcessiveIntrinsicGasCost(boolean isCreation) {
        given(gasCalculator.transactionIntrinsicGasCost(any(), eq(isCreation), anyLong()))
                .willReturn(100_000_000L);
    }
}
