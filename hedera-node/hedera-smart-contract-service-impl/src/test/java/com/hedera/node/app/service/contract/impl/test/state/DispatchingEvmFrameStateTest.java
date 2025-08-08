// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.CONTRACT_IS_TREASURY;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.CONTRACT_STILL_OWNS_NFTS;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.FAILURE_DURING_LAZY_ACCOUNT_CREATION;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_ALIAS_KEY;
import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CODE_FACTORY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_HEDERA_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniUInt256;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.scope.ActiveContractVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.DispatchingEvmFrameState;
import com.hedera.node.app.service.contract.impl.state.ProxyEvmAccount;
import com.hedera.node.app.service.contract.impl.state.ProxyEvmContract;
import com.hedera.node.app.service.contract.impl.state.RentFactors;
import com.hedera.node.app.service.contract.impl.state.ScheduleEvmAccount;
import com.hedera.node.app.service.contract.impl.state.StorageAccess;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.service.contract.impl.state.TokenEvmAccount;
import com.hedera.node.app.service.contract.impl.state.TxStorageUsage;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DispatchingEvmFrameStateTest {
    private static final int REDIRECT_CODE_FIXED_PREFIX_LEN =
            "6080604052348015600f57600080fd5b506000610167905077618dc65e".length();
    private static final int NUM_KV_SLOTS = 42;
    private static final long EXPIRY = 1_234_567L;
    private static final long ACCOUNT_NUM = 0x9abcdefabcdefbbbL;
    private static final long BENEFICIARY_NUM = 0xdefdefL;
    private static final long SCHEDULE_NUM = 0xdedededededeL;
    private static final Bytes SOME_OTHER_ALIAS = Bytes.wrap("<PRETEND>");
    private static final Address EVM_ADDRESS = Address.fromHexString("abcabcabcabcabcabeeeeeee9abcdefabcdefbbb");
    private static final Address LONG_ZERO_ADDRESS = Address.fromHexString("0000000000000000000000009abcdefabcdefbbb");
    private static final Address BENEFICIARY_ADDRESS =
            Address.fromHexString("0000000000000000000000000000000000defdef");
    private static final Address TOKEN_ADDRESS = Address.fromHexString("0000000000000000000000000000ffffffffffff");
    private static final Address SCHEDULE_ADDRESS = Address.fromHexString("0000000000000000000000000000dededededede");
    private static final Bytes SOME_PRETEND_CODE = Bytes.wrap("<NOT-REALLY-CODE>");
    private static final Bytecode SOME_PRETEND_BYTECODE =
            Bytecode.newBuilder().code(SOME_PRETEND_CODE).build();
    private static final Hash SOME_PRETEND_CODE_HASH =
            CODE_FACTORY.createCode(pbjToTuweniBytes(SOME_PRETEND_CODE), false).getCodeHash();

    private static final Bytes A_STORAGE_KEY = Bytes.wrap(Bytes32.random().toArrayUnsafe());
    private static final Bytes B_STORAGE_KEY = Bytes.wrap(Bytes32.random().toArrayUnsafe());
    private static final Bytes C_STORAGE_KEY = Bytes.wrap(Bytes32.random().toArrayUnsafe());
    private static final Bytes A_STORAGE_VALUE = Bytes.wrap(Bytes32.random().toArrayUnsafe());
    private static final SlotKey A_SLOT_KEY = SlotKey.newBuilder()
            .contractID(ContractID.newBuilder().contractNum(ACCOUNT_NUM).build())
            .key(A_STORAGE_KEY)
            .build();
    private static final SlotValue A_SLOT_VALUE = SlotValue.newBuilder()
            .previousKey(B_STORAGE_KEY)
            .value(A_STORAGE_VALUE)
            .nextKey(C_STORAGE_KEY)
            .build();
    private static final ContractID A_CONTRACT_ID =
            ContractID.newBuilder().contractNum(ACCOUNT_NUM).build();
    private static final ContractID B_CONTRACT_ID =
            ContractID.newBuilder().contractNum(1L).build();
    private static final ContractID C_CONTRACT_ID =
            ContractID.newBuilder().contractNum(2L).build();
    private static final AccountID A_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(ACCOUNT_NUM).build();
    private static final AccountID B_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(BENEFICIARY_NUM).build();
    private static final Configuration configuration = HederaTestConfigBuilder.createConfig();

    @Mock
    private HederaNativeOperations nativeOperations;

    @Mock
    private ContractStateStore contractStateStore;

    @Mock
    private MessageFrame frame;

    private DispatchingEvmFrameState subject;

    @BeforeEach
    void setUp() {
        subject = new DispatchingEvmFrameState(nativeOperations, contractStateStore, CODE_FACTORY);
    }

    @Test
    void dispatchesToSetNonce() {
        subject.setNonce(ACCOUNT_NUM, 1234);

        verify(nativeOperations).setNonce(ACCOUNT_NUM, 1234);
    }

    @Test
    void dispatchesToNumBytecodes() {
        given(contractStateStore.getNumBytecodes()).willReturn(1234L);

        assertEquals(1234L, subject.numBytecodesInState());
    }

    @Test
    void extFrameScopeesToFinalizeHollowAccount() {
        subject.finalizeHollowAccount(EVM_ADDRESS);

        verify(nativeOperations).finalizeHollowAccountAsContract(tuweniToPbjBytes(EVM_ADDRESS));
    }

    @Test
    void extFrameScopeesToSetCode() {
        final var expectedCode = Bytecode.newBuilder().code(SOME_PRETEND_CODE).build();

        subject.setCode(A_CONTRACT_ID, pbjToTuweniBytes(SOME_PRETEND_CODE));

        verify(contractStateStore).putBytecode(A_CONTRACT_ID, expectedCode);
    }

    @Test
    void getsExtantStorageValues() {
        given(contractStateStore.getSlotValue(A_SLOT_KEY)).willReturn(A_SLOT_VALUE);

        final var expectedWord = pbjToTuweniUInt256(A_STORAGE_VALUE);
        final var actualWord = subject.getStorageValue(A_CONTRACT_ID, pbjToTuweniUInt256(A_STORAGE_KEY));

        assertEquals(expectedWord, actualWord);
    }

    @Test
    void getsOriginalStorageValues() {
        given(contractStateStore.getOriginalSlotValue(A_SLOT_KEY)).willReturn(A_SLOT_VALUE);

        final var expectedWord = pbjToTuweniUInt256(A_STORAGE_VALUE);
        final var actualWord = subject.getOriginalStorageValue(A_CONTRACT_ID, pbjToTuweniUInt256(A_STORAGE_KEY));

        assertEquals(expectedWord, actualWord);
    }

    @Test
    void summarizesModificationsAsExpected() {
        final var modifiedKeys = List.of(
                new SlotKey(B_CONTRACT_ID, tuweniToPbjBytes(UInt256.ONE)),
                new SlotKey(B_CONTRACT_ID, tuweniToPbjBytes(UInt256.MAX_VALUE)),
                new SlotKey(C_CONTRACT_ID, tuweniToPbjBytes(UInt256.MAX_VALUE)),
                new SlotKey(C_CONTRACT_ID, tuweniToPbjBytes(UInt256.ONE)));
        final var expected = new TxStorageUsage(
                List.of(
                        new StorageAccesses(
                                B_CONTRACT_ID,
                                List.of(
                                        StorageAccess.newWrite(UInt256.ONE, UInt256.ONE, UInt256.MAX_VALUE),
                                        StorageAccess.newWrite(UInt256.MAX_VALUE, UInt256.MIN_VALUE, UInt256.ONE))),
                        new StorageAccesses(
                                C_CONTRACT_ID,
                                List.of(
                                        StorageAccess.newWrite(UInt256.MAX_VALUE, UInt256.MIN_VALUE, UInt256.ONE),
                                        StorageAccess.newWrite(UInt256.ONE, UInt256.ONE, UInt256.MAX_VALUE)))),
                new LinkedHashSet<>(modifiedKeys));

        given(contractStateStore.getModifiedSlotKeys()).willReturn(new LinkedHashSet<>(modifiedKeys));
        final var iter = modifiedKeys.iterator();
        givenOrigAndNewValues(iter.next(), UInt256.ONE, UInt256.MAX_VALUE);
        givenOrigAndNewValues(iter.next(), UInt256.MIN_VALUE, UInt256.ONE);
        givenOrigAndNewValues(iter.next(), UInt256.MIN_VALUE, UInt256.ONE);
        givenOrigAndNewValues(iter.next(), UInt256.ONE, UInt256.MAX_VALUE);

        final var actual = subject.getTxStorageUsage(true);

        assertEquals(expected, actual);
    }

    private void givenOrigAndNewValues(
            @NonNull final SlotKey key, @NonNull final UInt256 origValue, @NonNull final UInt256 newValue) {
        given(contractStateStore.getOriginalSlotValue(key))
                .willReturn(SlotValue.newBuilder()
                        .value(tuweniToPbjBytes(origValue))
                        .build());
        given(contractStateStore.getSlotValue(key))
                .willReturn(
                        SlotValue.newBuilder().value(tuweniToPbjBytes(newValue)).build());
    }

    @Test
    void overwritesNewStorageSlotAsExpected() {
        final var newSlotValue = A_SLOT_VALUE
                .copyBuilder()
                .previousKey(Bytes.EMPTY)
                .nextKey(Bytes.EMPTY)
                .build();

        subject.setStorageValue(A_CONTRACT_ID, pbjToTuweniUInt256(A_STORAGE_KEY), pbjToTuweniUInt256(A_STORAGE_VALUE));

        verify(contractStateStore).putSlot(A_SLOT_KEY, newSlotValue);
    }

    @Test
    void preservesPrevNextPointersForStorageSlotUpdate() {
        final var oldSlotValue = A_SLOT_VALUE
                .copyBuilder()
                .previousKey(Bytes.fromHex("1234"))
                .nextKey(Bytes.fromHex("5678"))
                .build();
        final var newSlotValue = A_SLOT_VALUE
                .copyBuilder()
                .previousKey(Bytes.fromHex("1234"))
                .nextKey(Bytes.fromHex("5678"))
                .build();

        given(contractStateStore.getSlotValue(A_SLOT_KEY)).willReturn(oldSlotValue);
        subject.setStorageValue(A_CONTRACT_ID, pbjToTuweniUInt256(A_STORAGE_KEY), pbjToTuweniUInt256(A_STORAGE_VALUE));

        verify(contractStateStore).putSlot(A_SLOT_KEY, newSlotValue);
    }

    @Test
    void skipsSettingZeroInAnUnusedSLot() {
        subject.setStorageValue(A_CONTRACT_ID, pbjToTuweniUInt256(A_STORAGE_KEY), UInt256.ZERO);

        verify(contractStateStore).getSlotValue(A_SLOT_KEY);
        verifyNoMoreInteractions(contractStateStore);
    }

    @Test
    void getsZeroWordForMissingSlotKey() {
        final var actualWord = subject.getStorageValue(A_CONTRACT_ID, pbjToTuweniUInt256(A_STORAGE_KEY));

        assertSame(UInt256.ZERO, actualWord);
    }

    @Test
    void getsZeroWordForEmptySlotValue() {
        given(contractStateStore.getSlotValue(A_SLOT_KEY)).willReturn(SlotValue.DEFAULT);

        final var actualWord = subject.getStorageValue(A_CONTRACT_ID, pbjToTuweniUInt256(A_STORAGE_KEY));

        assertSame(UInt256.ZERO, actualWord);
    }

    @Test
    void getsExtantCode() {
        givenWellKnownBytecode();

        final var actualCode = subject.getCode(A_CONTRACT_ID);

        assertEquals(pbjToTuweniBytes(SOME_PRETEND_CODE), actualCode);
    }

    @Test
    void interpolatesTokenCodeByAddress() {
        final var actualCode = subject.getTokenRedirectCode(TOKEN_ADDRESS);

        assertEquals(
                TOKEN_ADDRESS.toUnprefixedHexString(),
                actualCode
                        .toUnprefixedHexString()
                        // EVM 20-byte address is 40 hex chars
                        .substring(REDIRECT_CODE_FIXED_PREFIX_LEN, REDIRECT_CODE_FIXED_PREFIX_LEN + 40));
    }

    @Test
    void hashesInterpolatesTokenCode() {
        final var code = subject.getTokenRedirectCode(TOKEN_ADDRESS);
        final var expectedHash = Hash.hash(code);

        assertEquals(expectedHash, subject.getTokenRedirectCodeHash(TOKEN_ADDRESS));
    }

    @Test
    void interpolatesAccountCodeByAddress() {
        final var actualCode = subject.getAccountRedirectCode(LONG_ZERO_ADDRESS);

        assertEquals(
                LONG_ZERO_ADDRESS.toUnprefixedHexString(),
                actualCode
                        .toUnprefixedHexString()
                        // EVM 20-byte address is 40 hex chars
                        .substring(REDIRECT_CODE_FIXED_PREFIX_LEN, REDIRECT_CODE_FIXED_PREFIX_LEN + 40));
    }

    @Test
    void hashesInterpolatesAccountCode() {
        final var code = subject.getAccountRedirectCode(LONG_ZERO_ADDRESS);
        final var expectedHash = Hash.hash(code);

        assertEquals(expectedHash, subject.getAccountRedirectCodeHash(LONG_ZERO_ADDRESS));
    }

    @Test
    void interpolatesAccountCodeWhenAddressNull() {
        final var actualCode = subject.getAccountRedirectCode(null);

        assertEquals(org.apache.tuweni.bytes.Bytes.EMPTY, actualCode);
    }

    @Test
    void hashesInterpolatesAccountCodeWhenNull() {
        final var expectedHash = Hash.hash(org.apache.tuweni.bytes.Bytes.EMPTY);

        assertEquals(expectedHash, subject.getAccountRedirectCodeHash(null));
    }

    @Test
    void interpolatesScheduleCodeByAddress() {
        final var actualCode = subject.getScheduleRedirectCode(LONG_ZERO_ADDRESS);

        assertEquals(
                LONG_ZERO_ADDRESS.toUnprefixedHexString(),
                actualCode
                        .toUnprefixedHexString()
                        // EVM 20-byte address is 40 hex chars
                        .substring(REDIRECT_CODE_FIXED_PREFIX_LEN, REDIRECT_CODE_FIXED_PREFIX_LEN + 40));
    }

    @Test
    void hashesInterpolatesScheduleCode() {
        final var code = subject.getScheduleRedirectCode(LONG_ZERO_ADDRESS);
        final var expectedHash = Hash.hash(code);

        assertEquals(expectedHash, subject.getScheduleRedirectCodeHash(LONG_ZERO_ADDRESS));
    }

    @Test
    void interpolatesScheduleCodeWhenAddressNull() {
        final var actualCode = subject.getScheduleRedirectCode(null);

        assertEquals(org.apache.tuweni.bytes.Bytes.EMPTY, actualCode);
    }

    @Test
    void hashesInterpolatesScheduleCodeWhenNull() {
        final var expectedHash = Hash.hash(org.apache.tuweni.bytes.Bytes.EMPTY);

        assertEquals(expectedHash, subject.getScheduleRedirectCodeHash(null));
    }

    @Test
    void getsEmptyCodeForMissing() {
        final var actualCode = subject.getCode(A_CONTRACT_ID);

        assertSame(org.apache.tuweni.bytes.Bytes.EMPTY, actualCode);
    }

    @Test
    void getsEmptyCodeForNull() {
        given(contractStateStore.getBytecode(A_CONTRACT_ID)).willReturn(new Bytecode(null));

        final var actualCode = subject.getCode(A_CONTRACT_ID);

        assertSame(org.apache.tuweni.bytes.Bytes.EMPTY, actualCode);
    }

    @Test
    void getsExtantCodeHash() {
        givenWellKnownBytecode();

        final var actualCodeHash = subject.getCodeHash(A_CONTRACT_ID, CODE_FACTORY);

        assertEquals(SOME_PRETEND_CODE_HASH, actualCodeHash);
    }

    @Test
    void getsEmptyCodeHashForMissing() {
        final var actualCodeHash = subject.getCodeHash(A_CONTRACT_ID, CODE_FACTORY);

        assertSame(Hash.EMPTY, actualCodeHash);
    }

    @Test
    void throwsOnMissingAddressWhenGettingHederaIdNumber() {
        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(), DEFAULT_HEDERA_CONFIG.realm(), tuweniToPbjBytes(EVM_ADDRESS)))
                .willReturn(MISSING_ENTITY_NUMBER);
        given(nativeOperations.configuration()).willReturn(configuration);
        assertThrows(IllegalArgumentException.class, () -> subject.getIdNumber(EVM_ADDRESS));
    }

    @Test
    void returnsResolvedNumberForEvmAddress() {
        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(), DEFAULT_HEDERA_CONFIG.realm(), tuweniToPbjBytes(EVM_ADDRESS)))
                .willReturn(ACCOUNT_NUM);
        given(nativeOperations.configuration()).willReturn(configuration);
        assertEquals(ACCOUNT_NUM, subject.getIdNumber(EVM_ADDRESS));
    }

    @Test
    void returnsLongZeroAddressWithoutAnAlias() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertEquals(LONG_ZERO_ADDRESS, subject.getAddress(ACCOUNT_NUM));
    }

    @Test
    void returnsExpectedRentFactors() {
        givenWellKnownContract(A_CONTRACT_ID, contractWith(A_ACCOUNT_ID));

        final var expected = new RentFactors(NUM_KV_SLOTS, EXPIRY);
        assertEquals(expected, subject.getRentFactorsFor(A_CONTRACT_ID));
    }

    @Test
    void returnsNullWithDeletedAccount() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).deleted(true));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertNull(subject.getAddress(ACCOUNT_NUM));
    }

    @Test
    void returnsLongZeroAddressWithNonAddressAlias() {
        givenWellKnownAccount(accountWith(A_ACCOUNT_ID, SOME_OTHER_ALIAS));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertEquals(LONG_ZERO_ADDRESS, subject.getAddress(ACCOUNT_NUM));
    }

    @Test
    void returnsAliasIfPresent() {
        givenWellKnownAccount(accountWith(A_ACCOUNT_ID, Bytes.wrap(EVM_ADDRESS.toArrayUnsafe())));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertEquals(EVM_ADDRESS, subject.getAddress(ACCOUNT_NUM));
    }

    @Test
    void throwsIfAccountMissing() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertThrows(IllegalArgumentException.class, () -> subject.getAddress(ACCOUNT_NUM));
    }

    @Test
    void returnsNonceIfPresent() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).ethereumNonce(1234));
        assertEquals(1234, subject.getNonce(A_ACCOUNT_ID));
    }

    @Test
    void returnsNumTreasuryTitlesIfPresent() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).numberTreasuryTitles(1234));
        assertEquals(1234, subject.getNumTreasuryTitles(A_ACCOUNT_ID));
    }

    @Test
    void returnsNumNonZeroBalancesIfPresent() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).numberPositiveBalances(1234));
        assertEquals(1234, subject.getNumPositiveTokenBalances(A_ACCOUNT_ID));
    }

    @Test
    void returnsWhetherAnAccountIsContract() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).smartContract(true));
        assertTrue(subject.isContract(A_ACCOUNT_ID));
    }

    @Test
    void returnsBalanceIfPresent() {
        final var value = Wei.of(1234);
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).tinybarBalance(1234));
        assertEquals(value, subject.getBalance(A_ACCOUNT_ID));
    }

    @Test
    void returnsNullForMissing() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertNull(subject.getAccount(LONG_ZERO_ADDRESS));
    }

    @Test
    void returnsNullForDeleted() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).deleted(true));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertNull(subject.getAccount(LONG_ZERO_ADDRESS));
    }

    @Test
    void returnsNullForExpired() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).expiredAndPendingRemoval(true));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertNull(subject.getAccount(LONG_ZERO_ADDRESS));
    }

    @Test
    void missingAccountsCannotBeBeneficiaries() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).expiredAndPendingRemoval(true));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var reasonToHaltDeletion =
                subject.tryTrackingSelfDestructBeneficiary(EVM_ADDRESS, LONG_ZERO_ADDRESS, frame);

        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(INVALID_SOLIDITY_ADDRESS, reasonToHaltDeletion.get());
    }

    @Test
    void missingAccountsCannotTransferFunds() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(configuration);
        final var reasonToHaltDeletion = subject.tryTransfer(EVM_ADDRESS, LONG_ZERO_ADDRESS, 123L, true);
        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(INVALID_SOLIDITY_ADDRESS, reasonToHaltDeletion.get());
    }

    @Test
    void cannotTransferToMissingAccount() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).smartContract(true));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(configuration);
        final var reasonToHaltDeletion = subject.tryTransfer(LONG_ZERO_ADDRESS, EVM_ADDRESS, 123L, true);
        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(INVALID_SOLIDITY_ADDRESS, reasonToHaltDeletion.get());
    }

    @Test
    void cannotTransferToTokenAccount() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).smartContract(true));
        givenWellKnownToken();
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var reasonToHaltDeletion = subject.tryTransfer(LONG_ZERO_ADDRESS, TOKEN_ADDRESS, 123L, true);
        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(ILLEGAL_STATE_CHANGE, reasonToHaltDeletion.get());
    }

    @Test
    void cannotTransferToScheduleAccount() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).smartContract(true));
        givenWellKnownSchedule();
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var reasonToHaltDeletion = subject.tryTransfer(LONG_ZERO_ADDRESS, SCHEDULE_ADDRESS, 123L, true);
        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(ILLEGAL_STATE_CHANGE, reasonToHaltDeletion.get());
    }

    @Test
    void cannotLazyCreateOverExpiredAccount() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).expiredAndPendingRemoval(true));
        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(),
                        DEFAULT_HEDERA_CONFIG.realm(),
                        Bytes.wrap(EVM_ADDRESS.toArrayUnsafe())))
                .willReturn(ACCOUNT_NUM);
        given(nativeOperations.configuration()).willReturn(configuration);

        final var reasonLazyCreationFailed = subject.tryLazyCreation(EVM_ADDRESS);

        assertTrue(reasonLazyCreationFailed.isPresent());
        assertEquals(FAILURE_DURING_LAZY_ACCOUNT_CREATION, reasonLazyCreationFailed.get());
    }

    @Test
    void noHaltIfLazyCreationOk() {
        given(nativeOperations.createHollowAccount(tuweniToPbjBytes(EVM_ADDRESS)))
                .willReturn(ResponseCodeEnum.SUCCESS);
        given(nativeOperations.configuration()).willReturn(configuration);
        final var reasonLazyCreationFailed = subject.tryLazyCreation(EVM_ADDRESS);

        assertTrue(reasonLazyCreationFailed.isEmpty());
    }

    @Test
    void translatesMaxAccountsCreated() {
        given(nativeOperations.createHollowAccount(tuweniToPbjBytes(EVM_ADDRESS)))
                .willReturn(ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);
        given(nativeOperations.configuration()).willReturn(configuration);
        final var reasonLazyCreationFailed = subject.tryLazyCreation(EVM_ADDRESS);

        assertTrue(reasonLazyCreationFailed.isPresent());
        assertEquals(FAILURE_DURING_LAZY_ACCOUNT_CREATION, reasonLazyCreationFailed.get());
    }

    @Test
    void throwsOnLazyCreateOfLongZeroAddress() {
        final var reasonLazyCreationFailed = subject.tryLazyCreation(LONG_ZERO_ADDRESS);
        assertTrue(reasonLazyCreationFailed.isPresent());
        assertEquals(INVALID_ALIAS_KEY, reasonLazyCreationFailed.get());
    }

    @Test
    void throwsOnLazyCreateOfNonExpiredAccount() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID));
        given(nativeOperations.configuration()).willReturn(configuration);
        given(nativeOperations.resolveAlias(anyLong(), anyLong(), eq(Bytes.wrap(EVM_ADDRESS.toArrayUnsafe()))))
                .willReturn(ACCOUNT_NUM);

        assertThrows(IllegalArgumentException.class, () -> subject.tryLazyCreation(EVM_ADDRESS));
    }

    @Test
    void transferDelegationUsesExpectedVerifierForNonDelegate() {
        final var captor = ArgumentCaptor.forClass(VerificationStrategy.class);
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).smartContract(true));
        givenWellKnownAccount(B_ACCOUNT_ID, contractWith(B_ACCOUNT_ID));
        given(nativeOperations.transferWithReceiverSigCheck(
                        eq(123L), eq(A_ACCOUNT_ID), eq(B_ACCOUNT_ID), captor.capture()))
                .willReturn(OK);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var reasonToHaltDeletion = subject.tryTransfer(LONG_ZERO_ADDRESS, BENEFICIARY_ADDRESS, 123L, false);
        assertTrue(reasonToHaltDeletion.isEmpty());
        final var strategy = assertInstanceOf(ActiveContractVerificationStrategy.class, captor.getValue());
        assertEquals(A_ACCOUNT_ID.accountNum(), strategy.getActiveContractID().contractNum());
        assertEquals(tuweniToPbjBytes(LONG_ZERO_ADDRESS), strategy.getActiveAddress());
        assertFalse(strategy.requiresDelegatePermission());
    }

    @Test
    void transferDelegationReportsInvalidSignature() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).smartContract(true));
        givenWellKnownAccount(B_ACCOUNT_ID, contractWith(B_ACCOUNT_ID));
        given(nativeOperations.transferWithReceiverSigCheck(eq(123L), eq(A_ACCOUNT_ID), eq(B_ACCOUNT_ID), any()))
                .willReturn(INVALID_SIGNATURE);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var reasonToHaltDeletion = subject.tryTransfer(LONG_ZERO_ADDRESS, BENEFICIARY_ADDRESS, 123L, false);
        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(CustomExceptionalHaltReason.INVALID_SIGNATURE, reasonToHaltDeletion.get());
    }

    @Test
    void transferDelegationThrowsOnApparentlyImpossibleFailureMode() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).smartContract(true));
        givenWellKnownAccount(B_ACCOUNT_ID, contractWith(B_ACCOUNT_ID));
        given(nativeOperations.transferWithReceiverSigCheck(eq(123L), eq(A_ACCOUNT_ID), eq(B_ACCOUNT_ID), any()))
                .willReturn(INSUFFICIENT_ACCOUNT_BALANCE);
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertThrows(
                IllegalStateException.class,
                () -> subject.tryTransfer(LONG_ZERO_ADDRESS, BENEFICIARY_ADDRESS, 123L, false));
    }

    @Test
    void deletedAccountCannotBeTokenTreasury() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).numberTreasuryTitles(1));
        givenWellKnownAccount(B_ACCOUNT_ID, contractWith(B_ACCOUNT_ID));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var reasonToHaltDeletion =
                subject.tryTrackingSelfDestructBeneficiary(LONG_ZERO_ADDRESS, BENEFICIARY_ADDRESS, frame);

        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(CONTRACT_IS_TREASURY, reasonToHaltDeletion.get());
    }

    @Test
    void deletedAccountCannotHaveTokenBalances() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).numberPositiveBalances(1));
        givenWellKnownAccount(B_ACCOUNT_ID, contractWith(B_ACCOUNT_ID));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var reasonToHaltDeletion =
                subject.tryTrackingSelfDestructBeneficiary(LONG_ZERO_ADDRESS, BENEFICIARY_ADDRESS, frame);

        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(CONTRACT_STILL_OWNS_NFTS, reasonToHaltDeletion.get());
    }

    @Test
    void deletionsAreTracked() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID));
        givenWellKnownAccount(B_ACCOUNT_ID, contractWith(B_ACCOUNT_ID));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var reasonToHaltDeletion =
                subject.tryTrackingSelfDestructBeneficiary(LONG_ZERO_ADDRESS, BENEFICIARY_ADDRESS, frame);

        assertTrue(reasonToHaltDeletion.isEmpty());
        verify(nativeOperations).trackSelfDestructBeneficiary(A_ACCOUNT_ID, B_ACCOUNT_ID, frame);
    }

    @Test
    void beneficiaryCannotBeSelf() {
        final var reasonToHaltDeletion = subject.tryTrackingSelfDestructBeneficiary(EVM_ADDRESS, EVM_ADDRESS, frame);

        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(CustomExceptionalHaltReason.SELF_DESTRUCT_TO_SELF, reasonToHaltDeletion.get());
    }

    @Test
    void tokenAccountsCannotBeBeneficiaries() {
        givenWellKnownToken();
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var reasonToHaltDeletion = subject.tryTrackingSelfDestructBeneficiary(EVM_ADDRESS, TOKEN_ADDRESS, frame);

        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(INVALID_SOLIDITY_ADDRESS, reasonToHaltDeletion.get());
    }

    @Test
    void scheduleAccountsCannotBeBeneficiaries() {
        givenWellKnownSchedule();
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        final var reasonToHaltDeletion =
                subject.tryTrackingSelfDestructBeneficiary(EVM_ADDRESS, SCHEDULE_ADDRESS, frame);

        assertTrue(reasonToHaltDeletion.isPresent());
        assertEquals(INVALID_SOLIDITY_ADDRESS, reasonToHaltDeletion.get());
    }

    @Test
    void returnsProxyContractForNormal() {
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        assertInstanceOf(ProxyEvmContract.class, subject.getAccount(LONG_ZERO_ADDRESS));
    }

    @Test
    void returnsProxyAccountForNormal() {
        givenWellKnownAccount(accountWith(A_ACCOUNT_ID));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);

        assertInstanceOf(ProxyEvmAccount.class, subject.getAccount(LONG_ZERO_ADDRESS));
    }

    @Test
    void returnsNullForMissingAlias() {
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        given(nativeOperations.configuration()).willReturn(configuration);
        assertNull(subject.getAccount(EVM_ADDRESS));
    }

    @Test
    void missingAliasIsNotHollow() {
        given(nativeOperations.configuration()).willReturn(configuration);
        assertFalse(subject.isHollowAccount(EVM_ADDRESS));
    }

    @Test
    void missingAccountIsNotHollow() {
        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(),
                        DEFAULT_HEDERA_CONFIG.realm(),
                        Bytes.wrap(EVM_ADDRESS.toArrayUnsafe())))
                .willReturn(ACCOUNT_NUM);
        given(nativeOperations.configuration()).willReturn(configuration);
        assertFalse(subject.isHollowAccount(EVM_ADDRESS));
    }

    @Test
    void extantAccountIsHollowOnlyIfHasAnEmptyKey() {
        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(),
                        DEFAULT_HEDERA_CONFIG.realm(),
                        Bytes.wrap(EVM_ADDRESS.toArrayUnsafe())))
                .willReturn(ACCOUNT_NUM);
        given(nativeOperations.configuration()).willReturn(configuration);
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID)
                .key(Key.newBuilder().keyList(KeyList.DEFAULT).build()));
        assertTrue(subject.isHollowAccount(EVM_ADDRESS));
    }

    @Test
    void usesResolvedNumberFromDispatch() {
        given(nativeOperations.resolveAlias(
                        DEFAULT_HEDERA_CONFIG.shard(),
                        DEFAULT_HEDERA_CONFIG.realm(),
                        Bytes.wrap(EVM_ADDRESS.toArrayUnsafe())))
                .willReturn(ACCOUNT_NUM);
        given(nativeOperations.configuration()).willReturn(configuration);
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertInstanceOf(ProxyEvmContract.class, subject.getAccount(EVM_ADDRESS));
    }

    @Test
    void returnsNullForAliasedReferencedByLongZero() {
        final var alias = Bytes.wrap(EVM_ADDRESS.toArrayUnsafe());
        givenWellKnownAccount(contractWith(A_ACCOUNT_ID).alias(alias));
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        assertNull(subject.getAccount(LONG_ZERO_ADDRESS));
    }

    @Test
    void choosesTokenAccountIfApplicable() {
        givenWellKnownToken();
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var account = subject.getAccount(TOKEN_ADDRESS);
        assertInstanceOf(TokenEvmAccount.class, account);
    }

    @Test
    void choosesScheduleAccountIfApplicable() {
        givenWellKnownSchedule();
        given(nativeOperations.entityIdFactory()).willReturn(entityIdFactory);
        final var account = subject.getAccount(SCHEDULE_ADDRESS);
        assertInstanceOf(ScheduleEvmAccount.class, account);
    }

    @Test
    void getAccountDelegatesToGetMutableAccount() {
        final var mockSubject = mock(DispatchingEvmFrameState.class);
        final var mockAccount = mock(TokenEvmAccount.class);

        given(mockSubject.getMutableAccount(TOKEN_ADDRESS)).willReturn(mockAccount);
        doCallRealMethod().when(mockSubject).getAccount(TOKEN_ADDRESS);

        final var account = mockSubject.getAccount(TOKEN_ADDRESS);

        assertSame(mockAccount, account);
    }

    @Test
    void delegatesSizeOfKvState() {
        given(contractStateStore.getNumSlots()).willReturn(123L);
        assertEquals(123L, subject.getKvStateSize());
    }

    private void givenWellKnownBytecode() {
        given(contractStateStore.getBytecode(A_CONTRACT_ID)).willReturn(SOME_PRETEND_BYTECODE);
    }

    private void givenWellKnownAccount(final Account.Builder builder) {
        givenWellKnownAccount(A_ACCOUNT_ID, builder);
    }

    private void givenWellKnownAccount(final AccountID accountID, final Account.Builder builder) {
        given(nativeOperations.getAccount(accountID)).willReturn(builder.build());
    }

    private void givenWellKnownContract(final ContractID contractID, final Account.Builder builder) {
        given(nativeOperations.getAccount(contractID)).willReturn(builder.build());
    }

    private void givenWellKnownToken() {
        given(nativeOperations.getToken(any(TokenID.class)))
                .willReturn(Token.newBuilder().build());
    }

    private void givenWellKnownSchedule() {
        given(nativeOperations.getSchedule(entityIdFactory.newScheduleId(SCHEDULE_NUM)))
                .willReturn(Schedule.newBuilder().build());
    }

    private Account.Builder accountWith(final AccountID accountID, final Bytes alias) {
        return contractWith(accountID).alias(alias);
    }

    private Account.Builder contractWith(final AccountID accountID) {
        return Account.newBuilder()
                .accountId(accountID)
                .expirationSecond(EXPIRY)
                .contractKvPairsNumber(NUM_KV_SLOTS)
                .smartContract(true);
    }

    private Account.Builder accountWith(final AccountID accountID) {
        return Account.newBuilder()
                .accountId(accountID)
                .expirationSecond(EXPIRY)
                .contractKvPairsNumber(NUM_KV_SLOTS)
                .smartContract(false);
    }
}
