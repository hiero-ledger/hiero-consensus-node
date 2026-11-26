// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.operations;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.INVALID_ADDRESS_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSameResult;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSelfDestructOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSelfDestructOperation.UseEIP6780Semantics;
import com.hedera.node.app.service.contract.impl.exec.utils.InvalidAddressContext;
import com.hedera.node.app.service.contract.impl.state.AbstractMutableEvmAccount;
import com.hedera.node.app.service.contract.impl.state.AbstractProxyEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayDeque;
import java.util.Optional;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.UnderflowException;
import org.hyperledger.besu.evm.operation.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomSelfDestructOperationTest {
    private static final long COLD_ACCESS_COST = 1L;
    private static final Wei INHERITANCE = Wei.of(666L);
    private static final Address TBD = Address.fromHexString("0xa234567890abcdefa234567890abcdefa2345678");
    private static final Address BENEFICIARY = Address.fromHexString("0x1234567890abcdef1234567890abcdef12345678");

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private AddressChecks addressChecks;

    @Mock
    private MessageFrame frame;

    @Mock
    private EVM evm;

    @Mock
    private ProxyWorldUpdater proxyWorldUpdater;

    @Mock
    private AbstractMutableEvmAccount account;

    @Mock
    private AbstractMutableEvmAccount mutableAccount;

    @Mock
    private AbstractProxyEvmAccount proxyEvmAccount;

    private CustomSelfDestructOperation subject;

    void createSubject(@NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        subject = new CustomSelfDestructOperation(gasCalculator, addressChecks, useEIP6780Semantics);
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void catchesUnderflowWhenStackIsEmpty(
            @NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        given(frame.popStackItem()).willThrow(UnderflowException.class);
        final var expected = new Operation.OperationResult(0L, ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void rejectsSystemBeneficiaryAsMissing(
            @NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        given(frame.popStackItem()).willReturn(BENEFICIARY.getBytes());
        given(frame.getRecipientAddress()).willReturn(TBD);
        given(frame.getRemainingGas()).willReturn(123L);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(frame.getMessageFrameStack()).willReturn(new ArrayDeque<>());
        given(frame.getContextVariable(INVALID_ADDRESS_CONTEXT_VARIABLE)).willReturn(new InvalidAddressContext());
        given(addressChecks.isSystemAccount(BENEFICIARY)).willReturn(true);
        given(gasCalculator.selfDestructOperationGasCost(null, INHERITANCE)).willReturn(123L);
        given(gasCalculator.selfDestructOperationGasCost(null, Wei.ZERO)).willReturn(123L);
        given(proxyWorldUpdater.get(TBD)).willReturn(account);
        given(account.getBalance()).willReturn(INHERITANCE);
        final var expected = new Operation.OperationResult(123L, INVALID_SOLIDITY_ADDRESS);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void respectsHederaCustomHaltReason(
            @NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        given(frame.popStackItem()).willReturn(BENEFICIARY.getBytes());
        given(frame.getRecipientAddress()).willReturn(TBD);
        given(frame.getRemainingGas()).willReturn(123L);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(proxyWorldUpdater.getAccount(BENEFICIARY)).willReturn(mutableAccount);
        given(addressChecks.isPresent(BENEFICIARY, frame)).willReturn(true);
        given(gasCalculator.selfDestructOperationGasCost(null, null)).willReturn(123L);
        given(gasCalculator.selfDestructOperationGasCost(null, Wei.ZERO)).willReturn(123L);
        given(proxyWorldUpdater.get(TBD)).willReturn(account).willReturn(proxyEvmAccount);
        given(proxyWorldUpdater.tryTrackingSelfDestructBeneficiary(TBD, BENEFICIARY, frame))
                .willReturn(Optional.of(CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF));
        final var expected = new Operation.OperationResult(123L, CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void rejectSelfDestructIfContractBeneficiaryIsTreasury(
            @NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        given(frame.popStackItem()).willReturn(BENEFICIARY.getBytes());
        given(frame.getRecipientAddress()).willReturn(TBD);
        given(frame.getRemainingGas()).willReturn(123L);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(proxyWorldUpdater.getAccount(BENEFICIARY)).willReturn(mutableAccount);
        given(addressChecks.isPresent(BENEFICIARY, frame)).willReturn(true);
        given(gasCalculator.selfDestructOperationGasCost(null, null)).willReturn(123L);
        given(gasCalculator.selfDestructOperationGasCost(null, Wei.ZERO)).willReturn(123L);
        given(proxyWorldUpdater.get(TBD)).willReturn(account).willReturn(proxyEvmAccount);
        given(proxyEvmAccount.numTreasuryTitles()).willReturn(1);
        final var expected = new Operation.OperationResult(123L, CustomExceptionalHaltReason.CONTRACT_IS_TREASURY);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void rejectSelfDestructIfContractBeneficiaryStillOwnsTokens(
            @NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        given(frame.popStackItem()).willReturn(BENEFICIARY.getBytes());
        given(frame.getRecipientAddress()).willReturn(TBD);
        given(frame.getRemainingGas()).willReturn(123L);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(proxyWorldUpdater.getAccount(BENEFICIARY)).willReturn(mutableAccount);
        given(addressChecks.isPresent(BENEFICIARY, frame)).willReturn(true);
        given(gasCalculator.selfDestructOperationGasCost(null, null)).willReturn(123L);
        given(gasCalculator.selfDestructOperationGasCost(null, Wei.ZERO)).willReturn(123L);
        given(proxyWorldUpdater.get(TBD)).willReturn(account).willReturn(proxyEvmAccount);
        given(proxyEvmAccount.numPositiveTokenBalances()).willReturn(1);
        final var expected = new Operation.OperationResult(123L, CustomExceptionalHaltReason.CONTRACT_STILL_OWNS_NFTS);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void rejectsSelfDestructInStaticChanges(
            @NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        givenRunnableSelfDestruct();
        given(frame.isStatic()).willReturn(true);
        given(gasCalculator.getColdAccountAccessCost()).willReturn(COLD_ACCESS_COST);
        given(gasCalculator.selfDestructOperationGasCost(any(), any(Wei.class)))
                // null, Wei.ZERO
                .willReturn(123L)
                // beneficiary, inheritance
                .willReturn(321L);
        final var expected =
                new Operation.OperationResult(321L + COLD_ACCESS_COST, ExceptionalHaltReason.ILLEGAL_STATE_CHANGE);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void haltsSelfDestructWithInsufficientGas(
            @NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        givenRunnableSelfDestruct();
        given(frame.warmUpAddress(BENEFICIARY)).willReturn(true);
        given(gasCalculator.selfDestructOperationGasCost(any(), any(Wei.class)))
                // null, Wei.ZERO
                .willReturn(123L)
                // beneficiary, inheritance
                .willReturn(321L);
        final var expected = new Operation.OperationResult(321L, INSUFFICIENT_GAS);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void haltsSelfDestructOnFailedInheritanceTransfer(
            @NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        givenRunnableSelfDestruct();
        givenWarmBeneficiaryWithSufficientGas();
        given(addressChecks.isPresent(BENEFICIARY, frame)).willReturn(true);
        given(proxyWorldUpdater.tryTransfer(TBD, BENEFICIARY, INHERITANCE.toLong(), true))
                .willReturn(Optional.of(CustomExceptionalHaltReason.INVALID_SIGNATURE));
        final var expected = new Operation.OperationResult(123L, CustomExceptionalHaltReason.INVALID_SIGNATURE);
        assertSameResult(expected, subject.execute(frame, evm));
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void finalizesFrameAsExpected(@NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        givenRunnableSelfDestruct();
        givenWarmBeneficiaryWithSufficientGas();
        given(addressChecks.isPresent(BENEFICIARY, frame)).willReturn(true);
        given(frame.getContractAddress()).willReturn(TBD);
        given(proxyWorldUpdater.tryTransfer(TBD, BENEFICIARY, INHERITANCE.toLong(), false))
                .willReturn(Optional.empty());
        final var expected = new Operation.OperationResult(321L, null);
        assertSameResult(expected, subject.execute(frame, evm));

        switch (useEIP6780Semantics) {
            case NO -> verify(frame).addSelfDestruct(TBD);
            case YES -> verify(frame, never()).addSelfDestruct(TBD); // NOT created in same frame
        }

        verify(frame).addRefund(BENEFICIARY, INHERITANCE);
        verify(frame).setState(MessageFrame.State.CODE_SUCCESS);
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void finalizesFrameAsExpectedIfCreatedInSameTransaction(
            @NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        givenRunnableSelfDestruct();
        givenWarmBeneficiaryWithSufficientGas();
        given(frame.getContractAddress()).willReturn(TBD);
        if (useEIP6780Semantics == UseEIP6780Semantics.YES) {
            given(frame.wasCreatedInTransaction(TBD)).willReturn(true);
        }
        given(proxyWorldUpdater.tryTransfer(TBD, BENEFICIARY, INHERITANCE.toLong(), false))
                .willReturn(Optional.empty());
        final var expected = new Operation.OperationResult(321L, null);
        given(addressChecks.isSystemAccount(BENEFICIARY)).willReturn(false);
        given(addressChecks.isPresent(BENEFICIARY, frame)).willReturn(true);
        assertSameResult(expected, subject.execute(frame, evm));
        verify(frame).addSelfDestruct(TBD);
        verify(frame).addRefund(BENEFICIARY, INHERITANCE);
        verify(frame).setState(MessageFrame.State.CODE_SUCCESS);
    }

    @Test
    void selfBeneficiaryIsNoOpWhenContractIsNotDeleted() {
        createSubject(UseEIP6780Semantics.YES);
        givenRunnableSelfDestructToSelf(account);
        final var expected = new Operation.OperationResult(321L, null);

        assertSameResult(expected, subject.execute(frame, evm));

        verify(frame).setState(MessageFrame.State.CODE_SUCCESS);
        verify(frame, never()).addSelfDestruct(any());
        verify(frame, never()).addRefund(any(), any());
        verify(proxyWorldUpdater, never()).tryTrackingSelfDestructBeneficiary(any(), any(), any());
        verify(proxyWorldUpdater, never()).tryTransfer(any(), any(), anyLong(), anyBoolean());
        verify(proxyWorldUpdater, never()).trackSelfDestructBeneficiary(any(), any(), any());
    }

    @Test
    void selfBeneficiaryNoOpIsNotSubjectToDeletionRestrictions() {
        createSubject(UseEIP6780Semantics.YES);
        givenRunnableSelfDestructToSelf(proxyEvmAccount);
        // Nothing is deleted or swept, so neither treasury titles nor token balances matter
        lenient().when(proxyEvmAccount.numTreasuryTitles()).thenReturn(1);
        lenient().when(proxyEvmAccount.numPositiveTokenBalances()).thenReturn(1);
        final var expected = new Operation.OperationResult(321L, null);

        assertSameResult(expected, subject.execute(frame, evm));

        verify(frame).setState(MessageFrame.State.CODE_SUCCESS);
        verify(frame, never()).addSelfDestruct(any());
    }

    @ParameterizedTest
    @EnumSource(CustomSelfDestructOperation.UseEIP6780Semantics.class)
    void selfBeneficiaryStillHaltsWhenContractIsToBeDeleted(
            @NonNull final CustomSelfDestructOperation.UseEIP6780Semantics useEIP6780Semantics) {
        createSubject(useEIP6780Semantics);
        givenRunnableSelfDestructToSelf(proxyEvmAccount);
        if (useEIP6780Semantics == UseEIP6780Semantics.YES) {
            given(frame.wasCreatedInTransaction(TBD)).willReturn(true);
        }
        given(proxyWorldUpdater.getAccount(TBD)).willReturn(mutableAccount);
        given(addressChecks.isPresent(TBD, frame)).willReturn(true);
        given(proxyWorldUpdater.tryTrackingSelfDestructBeneficiary(TBD, TBD, frame))
                .willReturn(Optional.of(CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF));
        final var expected = new Operation.OperationResult(123L, CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF);

        assertSameResult(expected, subject.execute(frame, evm));

        verify(proxyWorldUpdater, never()).tryTransfer(any(), any(), anyLong(), anyBoolean());
        verify(frame, never()).addSelfDestruct(any());
        verify(frame, never()).setState(any());
    }

    @Test
    void selfBeneficiaryWithNoHbarIsDeletedWhenCreatedInSameTransaction() {
        createSubject(UseEIP6780Semantics.YES);
        givenRunnableSelfDestructToSelf(proxyEvmAccount, Wei.ZERO);
        given(frame.wasCreatedInTransaction(TBD)).willReturn(true);
        final var expected = new Operation.OperationResult(321L, null);

        assertSameResult(expected, subject.execute(frame, evm));

        // There is nothing to burn, so the contract is deleted with itself recorded as beneficiary
        verify(frame).addSelfDestruct(TBD);
        verify(proxyWorldUpdater).trackSelfDestructBeneficiary(TBD, TBD, frame);
        verify(frame).addRefund(TBD, Wei.ZERO);
        verify(frame).setState(MessageFrame.State.CODE_SUCCESS);
        verify(proxyWorldUpdater, never()).tryTrackingSelfDestructBeneficiary(any(), any(), any());
        verify(proxyWorldUpdater, never()).tryTransfer(any(), any(), anyLong(), anyBoolean());
    }

    @Test
    void selfBeneficiaryWithNoHbarCannotBeDeletedIfTreasury() {
        createSubject(UseEIP6780Semantics.YES);
        givenRunnableSelfDestructToSelf(proxyEvmAccount, Wei.ZERO);
        given(frame.wasCreatedInTransaction(TBD)).willReturn(true);
        given(proxyEvmAccount.numTreasuryTitles()).willReturn(1);
        final var expected = new Operation.OperationResult(123L, CustomExceptionalHaltReason.CONTRACT_IS_TREASURY);

        assertSameResult(expected, subject.execute(frame, evm));

        verify(frame, never()).addSelfDestruct(any());
        verify(frame, never()).setState(any());
    }

    @Test
    void selfBeneficiaryWithNoHbarCannotBeDeletedIfItOwnsTokens() {
        createSubject(UseEIP6780Semantics.YES);
        givenRunnableSelfDestructToSelf(proxyEvmAccount, Wei.ZERO);
        given(frame.wasCreatedInTransaction(TBD)).willReturn(true);
        given(proxyEvmAccount.numPositiveTokenBalances()).willReturn(1);
        final var expected = new Operation.OperationResult(123L, CustomExceptionalHaltReason.CONTRACT_STILL_OWNS_NFTS);

        assertSameResult(expected, subject.execute(frame, evm));

        verify(frame, never()).addSelfDestruct(any());
        verify(frame, never()).setState(any());
    }

    @Test
    void selfBeneficiaryWithNoHbarStillHaltsBeforeEip6780() {
        createSubject(UseEIP6780Semantics.NO);
        givenRunnableSelfDestructToSelf(proxyEvmAccount, Wei.ZERO);
        given(proxyWorldUpdater.getAccount(TBD)).willReturn(mutableAccount);
        given(addressChecks.isPresent(TBD, frame)).willReturn(true);
        given(proxyWorldUpdater.tryTrackingSelfDestructBeneficiary(TBD, TBD, frame))
                .willReturn(Optional.of(CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF));
        final var expected = new Operation.OperationResult(123L, CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF);

        assertSameResult(expected, subject.execute(frame, evm));

        verify(frame, never()).addSelfDestruct(any());
        verify(frame, never()).setState(any());
    }

    private void givenRunnableSelfDestructToSelf(@NonNull final AbstractMutableEvmAccount tbdAccount) {
        givenRunnableSelfDestructToSelf(tbdAccount, INHERITANCE);
    }

    private void givenRunnableSelfDestructToSelf(
            @NonNull final AbstractMutableEvmAccount tbdAccount, @NonNull final Wei balance) {
        given(frame.popStackItem()).willReturn(TBD.getBytes());
        given(frame.getRecipientAddress()).willReturn(TBD);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(proxyWorldUpdater.get(TBD)).willReturn(tbdAccount);
        given(tbdAccount.getBalance()).willReturn(balance);
        given(frame.warmUpAddress(TBD)).willReturn(true);
        given(frame.getRemainingGas()).willReturn(666L);
        given(gasCalculator.selfDestructOperationGasCost(any(), any(Wei.class)))
                // null, Wei.ZERO
                .willReturn(123L)
                // beneficiary, inheritance
                .willReturn(321L);
    }

    private void givenWarmBeneficiaryWithSufficientGas() {
        given(frame.warmUpAddress(BENEFICIARY)).willReturn(true);
        given(frame.getRemainingGas()).willReturn(666L);
        given(proxyWorldUpdater.getAccount(BENEFICIARY)).willReturn(mutableAccount);
        given(gasCalculator.selfDestructOperationGasCost(any(), any(Wei.class)))
                // null, Wei.ZERO
                .willReturn(123L)
                // beneficiary, inheritance
                .willReturn(321L);
    }

    private void givenRunnableSelfDestruct() {
        given(frame.popStackItem()).willReturn(BENEFICIARY.getBytes());
        given(frame.getRecipientAddress()).willReturn(TBD);
        given(frame.getWorldUpdater()).willReturn(proxyWorldUpdater);
        given(proxyWorldUpdater.get(TBD)).willReturn(account).willReturn(proxyEvmAccount);
        given(account.getBalance()).willReturn(INHERITANCE);
    }
}
