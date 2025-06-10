// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.FAILURE_DURING_LAZY_ACCOUNT_CREATION;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CALLED_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OUTPUT_DATA;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.PERMITTED_ADDRESS_CALLER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.RELAYER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.SENDER_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.aliasFrom;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static org.hyperledger.besu.datatypes.Address.ALTBN128_ADD;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INSUFFICIENT_GAS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.SystemContractOperations;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.EvmFrameState;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStateFactory;
import com.hedera.node.app.service.contract.impl.state.PendingCreation;
import com.hedera.node.app.service.contract.impl.state.ProxyEvmContract;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.StorageAccess;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProxyWorldUpdaterTest {
    private static final long NUMBER = 123L;
    static final long NEXT_NUMBER = 124L;
    private static final long NUMBER_OF_DELETED = 125L;
    private static final Address LONG_ZERO_ADDRESS = asLongZeroAddress(NUMBER);
    private static final Address NEXT_LONG_ZERO_ADDRESS = asLongZeroAddress(NEXT_NUMBER);
    static final Address SOME_EVM_ADDRESS = Address.fromHexString("0x1234123412341234123412341234123412341234");
    private static final Address OTHER_EVM_ADDRESS =
            Address.fromHexString("0x1239123912391239123912391239123912391239");
    private static final Address ADDRESS_6 = Address.fromHexString("0x6");

    @Mock
    private Account anImmutableAccount;

    @Mock
    private Account anotherImmutableAccount;

    @Mock
    private MutableAccount mutableAccount;

    @Mock
    private ProxyEvmContract proxyEvmContract;

    @Mock
    private MessageFrame frame;

    @Mock
    private HederaOperations hederaOperations;

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private SystemContractOperations systemContractOperations;

    @Mock
    private WorldUpdater parent;

    @Mock
    private EvmFrameStateFactory evmFrameStateFactory;

    @Mock
    private EvmFrameState evmFrameState;

    private ProxyWorldUpdater subject;

    @BeforeEach
    void setUp() {
        final var enhancement =
                new HederaWorldUpdater.Enhancement(hederaOperations, nativeOperations, systemContractOperations);
        subject = new ProxyWorldUpdater(enhancement, () -> evmFrameState, null);
    }

    @Test
    void collectingAndRefundingFeesDelegate() {
        subject.collectGasFee(RELAYER_ID, 1L, false);
        subject.refundGasFee(SENDER_ID, 1L);
        verify(hederaOperations).collectGasFee(RELAYER_ID, 1L, false);
        verify(hederaOperations).refundGasFee(SENDER_ID, 1L);
    }

    @Test
    void getsImmutableAccount() {
        given(evmFrameState.getAccount(ADDRESS_6)).willReturn(anImmutableAccount);

        assertSame(anImmutableAccount, subject.get(ADDRESS_6));
    }

    @Test
    void getsHederaAccountByNumber() {
        final var num = ADDRESS_6.toBigInteger().longValueExact();
        final var numericId = AccountID.newBuilder().accountNum(num).build();
        given(evmFrameState.getAddress(numericId)).willReturn(ADDRESS_6);
        given(evmFrameState.getAccount(ADDRESS_6)).willReturn(proxyEvmContract);
        assertSame(proxyEvmContract, subject.getHederaAccount(numericId));
    }

    @Test
    void getsHederaContractByNumber() {
        final var num = ADDRESS_6.toBigInteger().longValueExact();
        final var numericId = ContractID.newBuilder().contractNum(num).build();
        given(hederaOperations.shardAndRealmValidated(numericId)).willReturn(numericId);
        given(evmFrameState.getAddress(num)).willReturn(ADDRESS_6);
        given(evmFrameState.getAccount(ADDRESS_6)).willReturn(proxyEvmContract);
        assertSame(proxyEvmContract, subject.getHederaAccount(numericId));
    }

    @Test
    void returnsNullHederaAccountIfMissing() {
        final var num = ADDRESS_6.toBigInteger().longValueExact();
        final var numericId = AccountID.newBuilder().accountNum(num).build();
        doThrow(IllegalArgumentException.class).when(evmFrameState).getAddress(numericId);
        assertNull(subject.getHederaAccount(numericId));
    }

    @Test
    void returnsNullHederaContractIfMissing() {
        final var num = ADDRESS_6.toBigInteger().longValueExact();
        final var numericId = ContractID.newBuilder().contractNum(num).build();
        given(hederaOperations.shardAndRealmValidated(numericId)).willReturn(numericId);
        doThrow(IllegalArgumentException.class).when(evmFrameState).getAddress(num);
        assertNull(subject.getHederaAccount(numericId));
    }

    @Test
    void getsHederaAccountByAlias() {
        final var aliasId = AccountID.newBuilder()
                .alias(tuweniToPbjBytes(
                        asLongZeroAddress(ADDRESS_6.toBigInteger().longValueExact())))
                .build();
        given(evmFrameState.getAccount(ADDRESS_6)).willReturn(proxyEvmContract);
        assertSame(proxyEvmContract, subject.getHederaAccount(aliasId));
    }

    @Test
    void getsHederaContractByAlias() {
        final var aliasId = ContractID.newBuilder()
                .evmAddress(tuweniToPbjBytes(
                        asLongZeroAddress(ADDRESS_6.toBigInteger().longValueExact())))
                .build();
        given(hederaOperations.shardAndRealmValidated(aliasId)).willReturn(aliasId);
        given(evmFrameState.getAccount(ADDRESS_6)).willReturn(proxyEvmContract);
        assertSame(proxyEvmContract, subject.getHederaAccount(aliasId));
    }

    @Test
    void delegatesHollowCheck() {
        given(evmFrameState.isHollowAccount(ADDRESS_6)).willReturn(true);

        assertTrue(subject.isHollowAccount(ADDRESS_6));
    }

    @Test
    void delegatesFeeCharging() {
        given(evmFrameState.isHollowAccount(ADDRESS_6)).willReturn(true);

        assertTrue(subject.isHollowAccount(ADDRESS_6));
    }

    @Test
    void delegatesHollowFinalization() {
        given(evmFrameState.getAccount(EIP_1014_ADDRESS)).willReturn(proxyEvmContract);
        given(proxyEvmContract.hederaContractId())
                .willReturn(ContractID.newBuilder().contractNum(999L).build());
        subject.setupTopLevelLazyCreate(EIP_1014_ADDRESS);
        subject.finalizeHollowAccount(EIP_1014_ADDRESS, PERMITTED_ADDRESS_CALLER);
        verify(evmFrameState).finalizeHollowAccount(EIP_1014_ADDRESS);
    }

    @Test
    void getsMutableAccount() {
        given(evmFrameState.getMutableAccount(ADDRESS_6)).willReturn(mutableAccount);

        assertSame(mutableAccount, subject.getAccount(ADDRESS_6));
    }

    @Test
    void cannotCreateAccountWithoutPendingCreation() {
        assertThrows(IllegalStateException.class, () -> subject.createAccount(ADDRESS_6, 1, Wei.ZERO));
    }

    @Test
    void providesAccessToPendingStorageChanges() {
        final var someChanges = new StorageAccesses(
                ContractID.newBuilder().contractNum(123L).build(),
                List.of(new StorageAccess(UInt256.ONE, UInt256.MIN_VALUE, UInt256.MAX_VALUE)));
        final var expected = List.of(someChanges);

        given(evmFrameState.getStorageChanges()).willReturn(expected);

        assertSame(expected, subject.pendingStorageUpdates());
    }

    @Test
    void cannotCreateUnlessPendingCreationHasExpectedAddress() {
        given(hederaOperations.peekNextEntityNumber()).willReturn(NEXT_NUMBER);
        given(hederaOperations.contractCreationLimit()).willReturn(1234L);
        given(hederaOperations.accountCreationLimit()).willReturn(4321L);

        subject.setupInternalCreate(ADDRESS_6);

        assertThrows(IllegalStateException.class, () -> subject.createAccount(LONG_ZERO_ADDRESS, 1, Wei.ZERO));
    }

    @Test
    void cannotCreateUnlessLimitIsHighEnough() {
        given(hederaOperations.peekNextEntityNumber()).willReturn(NEXT_NUMBER);

        subject.setupInternalCreate(ALTBN128_ADD);

        assertThrows(ResourceExhaustedException.class, () -> subject.createAccount(LONG_ZERO_ADDRESS, 1, Wei.ZERO));
    }

    @Test
    void cannotCreateWithNonZeroBalance() {
        final var balance = Wei.of(123);
        assertThrows(IllegalStateException.class, () -> subject.createAccount(NEXT_LONG_ZERO_ADDRESS, 1, balance));
    }

    @Test
    void cannotCreateUnlessPendingCreationHasExpectedNumber() {
        given(hederaOperations.peekNextEntityNumber()).willReturn(NEXT_NUMBER).willReturn(NEXT_NUMBER + 1);
        given(hederaOperations.contractCreationLimit()).willReturn(1234L);
        given(hederaOperations.accountCreationLimit()).willReturn(1234L);

        subject.setupInternalCreate(ADDRESS_6);

        assertThrows(IllegalStateException.class, () -> subject.createAccount(NEXT_LONG_ZERO_ADDRESS, 1, Wei.ZERO));
    }

    @Test
    void revertDelegatesToScope() {
        subject.revert();
        verify(hederaOperations).revert();
    }

    @Test
    void commitIsNoopAfterRevert() {
        subject.revert();
        subject.commit();
        verify(hederaOperations).revert();
        verify(hederaOperations, never()).commit();
    }

    @Test
    void commitDelegatesToScope() {
        subject.commit();
        verify(hederaOperations).commit();
    }

    @Test
    void usesAliasIfCreate2IsSetupRecipient() {
        given(hederaOperations.peekNextEntityNumber()).willReturn(NEXT_NUMBER);
        given(evmFrameState.getMutableAccount(SOME_EVM_ADDRESS)).willReturn(mutableAccount);
        given(evmFrameState.getIdNumber(ADDRESS_6))
                .willReturn(ADDRESS_6.toBigInteger().longValueExact());
        given(hederaOperations.contractCreationLimit()).willReturn(1234L);
        given(hederaOperations.accountCreationLimit()).willReturn(1234L);

        subject.setupInternalAliasedCreate(ADDRESS_6, SOME_EVM_ADDRESS);
        subject.createAccount(SOME_EVM_ADDRESS, 1, Wei.ZERO);

        verify(hederaOperations)
                .createContract(NEXT_NUMBER, ADDRESS_6.toBigInteger().longValueExact(), aliasFrom(SOME_EVM_ADDRESS));
    }

    @Test
    void usesAliasIfBodyCreatedWithAlias() {
        given(hederaOperations.peekNextEntityNumber()).willReturn(NEXT_NUMBER);
        given(evmFrameState.getMutableAccount(SOME_EVM_ADDRESS)).willReturn(mutableAccount);
        given(hederaOperations.contractCreationLimit()).willReturn(1234L);
        given(hederaOperations.accountCreationLimit()).willReturn(1234L);

        subject.setupAliasedTopLevelCreate(ContractCreateTransactionBody.DEFAULT, SOME_EVM_ADDRESS);
        subject.createAccount(SOME_EVM_ADDRESS, 1, Wei.ZERO);

        verify(hederaOperations)
                .createContract(NEXT_NUMBER, ContractCreateTransactionBody.DEFAULT, aliasFrom(SOME_EVM_ADDRESS));
    }

    @Test
    void usesJustAliasForTopLevelLazyCreate() {
        given(hederaOperations.peekNextEntityNumber()).willReturn(NEXT_NUMBER);

        subject.setupTopLevelLazyCreate(SOME_EVM_ADDRESS);

        final var expectedPending = new PendingCreation(SOME_EVM_ADDRESS, NEXT_NUMBER, MISSING_ENTITY_NUMBER, null);
        assertEquals(expectedPending, subject.getPendingCreation());
    }

    @Test
    void doesNotUseAliasIfBodyCreatedWithoutAlias() {
        given(hederaOperations.peekNextEntityNumber()).willReturn(NEXT_NUMBER);
        given(hederaOperations.contractCreationLimit()).willReturn(1234L);
        given(hederaOperations.accountCreationLimit()).willReturn(1234L);

        assertEquals(NEXT_LONG_ZERO_ADDRESS, subject.setupTopLevelCreate(ContractCreateTransactionBody.DEFAULT));
        subject.createAccount(NEXT_LONG_ZERO_ADDRESS, 1, Wei.ZERO);

        verify(hederaOperations).createContract(NEXT_NUMBER, ContractCreateTransactionBody.DEFAULT, null);
    }

    @Test
    void canResolvePendingCreationHederaId() {
        given(hederaOperations.peekNextEntityNumber()).willReturn(NEXT_NUMBER);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        subject.setupInternalAliasedCreate(ADDRESS_6, SOME_EVM_ADDRESS);

        final var contractId = subject.getHederaContractId(SOME_EVM_ADDRESS);
        assertEquals(ContractID.newBuilder().contractNum(NEXT_NUMBER).build(), contractId);
    }

    @Test
    void throwsIseWithoutCorrespondingAccount() {
        given(hederaOperations.peekNextEntityNumber()).willReturn(NEXT_NUMBER);

        subject.setupInternalAliasedCreate(ADDRESS_6, SOME_EVM_ADDRESS);

        assertThrows(IllegalArgumentException.class, () -> subject.getHederaContractId(OTHER_EVM_ADDRESS));
    }

    @Test
    void getsAvailableContractIdByAddress() {
        given(evmFrameState.getAccount(SOME_EVM_ADDRESS)).willReturn(proxyEvmContract);
        given(proxyEvmContract.hederaContractId()).willReturn(CALLED_CONTRACT_ID);
        final var actual = subject.getHederaContractId(SOME_EVM_ADDRESS);
        assertEquals(CALLED_CONTRACT_ID, actual);
    }

    @Test
    void dispatchesDeletingLongZeroAddressByNumber() {
        subject.deleteAccount(ADDRESS_6);

        verify(hederaOperations)
                .deleteUnaliasedContract(ADDRESS_6.toBigInteger().longValueExact());
    }

    @Test
    void dispatchesDeletingEvmAddressByAddress() {
        subject.deleteAccount(SOME_EVM_ADDRESS);

        verify(hederaOperations).deleteAliasedContract(aliasFrom(SOME_EVM_ADDRESS));
    }

    @Test
    void hasEmptyParentIfNull() {
        assertTrue(subject.parentUpdater().isEmpty());
    }

    @Test
    void hasGivenParentIfNonNull() {
        final var enhancement =
                new HederaWorldUpdater.Enhancement(hederaOperations, nativeOperations, systemContractOperations);
        subject = new ProxyWorldUpdater(enhancement, evmFrameStateFactory, parent);
        assertTrue(subject.parentUpdater().isPresent());
        assertSame(parent, subject.parentUpdater().get());
    }

    @Test
    void updaterHasExpectedProperties() {
        given(hederaOperations.begin()).willReturn(hederaOperations);
        final var updater = subject.updater();
        assertInstanceOf(ProxyWorldUpdater.class, updater);
        assertTrue(updater.parentUpdater().isPresent());
        assertSame(subject, updater.parentUpdater().get());
    }

    @Test
    void updaterPreservesPendingCreation() {
        given(hederaOperations.begin()).willReturn(hederaOperations);
        subject.setupTopLevelLazyCreate(SOME_EVM_ADDRESS);
        final var updater = subject.updater();
        assertInstanceOf(ProxyWorldUpdater.class, updater);
        assertTrue(updater.parentUpdater().isPresent());
        assertSame(subject, updater.parentUpdater().get());
        assertSame(subject.getPendingCreation(), updater.getPendingCreation());
    }

    @Test
    void delegatesTransfer() {
        given(evmFrameState.tryTransfer(ADDRESS_6, SOME_EVM_ADDRESS, 123L, true))
                .willReturn(Optional.of(CustomExceptionalHaltReason.INVALID_SIGNATURE));
        final var maybeHaltReason = subject.tryTransfer(ADDRESS_6, SOME_EVM_ADDRESS, 123L, true);
        assertTrue(maybeHaltReason.isPresent());
        assertEquals(CustomExceptionalHaltReason.INVALID_SIGNATURE, maybeHaltReason.get());
    }

    @Test
    void abortsLazyCreationIfRemainingGasInsufficient() {
        final var pretendCost = 1_234L;
        given(hederaOperations.lazyCreationCostInGas(SOME_EVM_ADDRESS)).willReturn(pretendCost);
        given(frame.getRemainingGas()).willReturn(pretendCost - 1);
        final var maybeHaltReason = subject.tryLazyCreation(SOME_EVM_ADDRESS, frame);
        assertTrue(maybeHaltReason.isPresent());
        assertEquals(INSUFFICIENT_GAS, maybeHaltReason.get());
    }

    @Test
    void delegatesLazyCreationAndDecrementsGasCostOnSuccess() {
        final var pretendCost = 1_234L;
        given(hederaOperations.lazyCreationCostInGas(SOME_EVM_ADDRESS)).willReturn(pretendCost);
        given(frame.getRemainingGas()).willReturn(pretendCost * 2);
        given(evmFrameState.tryLazyCreation(SOME_EVM_ADDRESS)).willReturn(Optional.empty());
        final var maybeHaltReason = subject.tryLazyCreation(SOME_EVM_ADDRESS, frame);
        assertTrue(maybeHaltReason.isEmpty());
        verify(frame).decrementRemainingGas(pretendCost);
    }

    @Test
    void doesntBothDecrementingGasOnLazyCreationFailureSinceAboutToHalt() {
        final var pretendCost = 1_234L;
        final var haltReason = Optional.<ExceptionalHaltReason>of(FAILURE_DURING_LAZY_ACCOUNT_CREATION);
        given(hederaOperations.lazyCreationCostInGas(SOME_EVM_ADDRESS)).willReturn(pretendCost);
        given(frame.getRemainingGas()).willReturn(pretendCost * 2);
        given(evmFrameState.tryLazyCreation(SOME_EVM_ADDRESS)).willReturn(haltReason);
        final var maybeHaltReason = subject.tryLazyCreation(SOME_EVM_ADDRESS, frame);
        assertEquals(haltReason, maybeHaltReason);
        verify(frame, never()).decrementRemainingGas(pretendCost);
    }

    @Test
    void onlyReturnsNonDeletedAccountsAsTouched() {
        given(hederaOperations.getModifiedAccountNumbers()).willReturn(List.of(NUMBER, NEXT_NUMBER, NUMBER_OF_DELETED));
        given(evmFrameState.getAddress(NUMBER)).willReturn(asLongZeroAddress(NUMBER));
        given(evmFrameState.getAddress(NEXT_NUMBER)).willReturn(SOME_EVM_ADDRESS);
        given(evmFrameState.getAddress(NUMBER_OF_DELETED)).willReturn(null);
        given(evmFrameState.getAccount(asLongZeroAddress(NUMBER))).willReturn(anImmutableAccount);
        given(evmFrameState.getAccount(SOME_EVM_ADDRESS)).willReturn(anotherImmutableAccount);

        final var touched = subject.getTouchedAccounts();

        assertEquals(List.of(anImmutableAccount, anotherImmutableAccount), touched);
    }

    @Test
    void doesntSupportDeletedAccountAddresses() {
        assertThrows(UnsupportedOperationException.class, subject::getDeletedAccountAddresses);
    }

    @Test
    void delegatesDeletionTrackingAttempt() {
        final var haltReason = Optional.<ExceptionalHaltReason>of(CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF);
        given(evmFrameState.tryTrackingSelfDestructBeneficiary(SOME_EVM_ADDRESS, OTHER_EVM_ADDRESS, frame))
                .willReturn(haltReason);
        assertSame(haltReason, subject.tryTrackingSelfDestructBeneficiary(SOME_EVM_ADDRESS, OTHER_EVM_ADDRESS, frame));
    }

    @Test
    void delegatesEntropy() {
        given(hederaOperations.entropy()).willReturn(OUTPUT_DATA);
        assertEquals(pbjToTuweniBytes(OUTPUT_DATA), subject.entropy());
    }

    @Test
    void currentExchangeRateTest() {
        subject.currentExchangeRate();
        verify(systemContractOperations).currentExchangeRate();
    }
}
