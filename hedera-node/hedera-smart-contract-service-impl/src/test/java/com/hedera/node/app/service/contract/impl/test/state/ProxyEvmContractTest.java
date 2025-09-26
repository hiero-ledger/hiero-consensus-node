// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CODE_FACTORY;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToBesuHash;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.state.EvmFrameState;
import com.hedera.node.app.service.contract.impl.state.ProxyEvmContract;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProxyEvmContractTest {
    private static final long ACCOUNT_NUM = 0x9abcdefabcdefbbbL;
    private static final AccountID ACCOUNT_ID =
            AccountID.newBuilder().accountNum(ACCOUNT_NUM).build();
    private static final ContractID CONTRACT_ID =
            ContractID.newBuilder().contractNum(ACCOUNT_NUM).build();
    private static final Address EVM_ADDRESS = Address.fromHexString("abcabcabcabcabcabeeeeeee9abcdefabcdefbbb");
    private static final Bytes SOME_PRETEND_CODE = Bytes.wrap("<NOT-REALLY-CODE>");
    private static final Bytes SOME_PRETEND_CODE_HASH = Bytes.wrap("<NOT-REALLY-BYTECODE-HASH-12345>");
    private static final UInt256 SOME_KEY =
            UInt256.fromHexString("0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
    private static final UInt256 SOME_VALUE =
            UInt256.fromHexString("0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");

    @Mock
    private EvmFrameState hederaState;

    private ProxyEvmContract subject;

    @BeforeEach
    void setUp() {
        subject = new ProxyEvmContract(ACCOUNT_ID, hederaState, CODE_FACTORY);
    }

    @Test
    void notTokenFacade() {
        assertFalse(subject.isTokenFacade());
    }

    @Test
    void notScheduleTxnFacade() {
        assertFalse(subject.isScheduleTxnFacade());
    }

    @Test
    void hasExpectedId() {
        assertEquals(AccountID.newBuilder().accountNum(ACCOUNT_NUM).build(), subject.hederaId());
    }

    @Test
    void hasExpectedContractId() {
        assertEquals(ContractID.newBuilder().contractNum(ACCOUNT_NUM).build(), subject.hederaContractId());
    }

    @Test
    void accountHashNotSupported() {
        assertThrows(UnsupportedOperationException.class, subject::getAddressHash);
    }

    @Test
    void storageEntriesNotSupported() {
        assertThrows(UnsupportedOperationException.class, () -> subject.storageEntriesFrom(Bytes32.ZERO, 1));
    }

    @Test
    void returnsLongZeroAddressWithoutAnAlias() {
        given(hederaState.getAddress(ACCOUNT_ID)).willReturn(EVM_ADDRESS);
        assertEquals(EVM_ADDRESS, subject.getAddress());
    }

    @Test
    void returnsNonce() {
        given(hederaState.getNonce(ACCOUNT_ID)).willReturn(123L);
        assertEquals(123L, subject.getNonce());
    }

    @Test
    void returnsBalance() {
        final var value = Wei.of(123L);
        given(hederaState.getBalance(ACCOUNT_ID)).willReturn(value);
        assertEquals(value, subject.getBalance());
    }

    @Test
    void returnsCode() {
        final var code = pbjToTuweniBytes(SOME_PRETEND_CODE);
        given(hederaState.getCode(CONTRACT_ID)).willReturn(code);
        assertEquals(code, subject.getCode());
    }

    @Test
    void returnsEvmCode() {
        final var code = pbjToTuweniBytes(SOME_PRETEND_CODE);
        given(hederaState.getCode(CONTRACT_ID)).willReturn(code);
        assertEquals(
                CODE_FACTORY.createCode(code, false),
                subject.getEvmCode(org.apache.tuweni.bytes.Bytes.EMPTY, CODE_FACTORY));
    }

    @Test
    void returnsEvmCodeButSetsState() {
        final var code = pbjToTuweniBytes(SOME_PRETEND_CODE);
        given(hederaState.getCode(CONTRACT_ID)).willReturn(code);
        assertEquals(
                CODE_FACTORY.createCode(code, false),
                subject.getEvmCode(org.apache.tuweni.bytes.Bytes.wrap(HBAR_ALLOWANCE_PROXY.selector()), CODE_FACTORY));
    }

    @Test
    void returnsCodeHash() {
        final var hash = pbjToBesuHash(SOME_PRETEND_CODE_HASH);
        given(hederaState.getCodeHash(CONTRACT_ID, CODE_FACTORY)).willReturn(hash);
        assertEquals(hash, subject.getCodeHash());
    }

    @Test
    void getsStorageValue() {
        given(hederaState.getStorageValue(CONTRACT_ID, SOME_KEY)).willReturn(SOME_VALUE);
        assertEquals(SOME_VALUE, subject.getStorageValue(SOME_KEY));
    }

    @Test
    void getsOriginalStorageValue() {
        given(hederaState.getOriginalStorageValue(CONTRACT_ID, SOME_KEY)).willReturn(SOME_VALUE);
        assertEquals(SOME_VALUE, subject.getOriginalStorageValue(SOME_KEY));
    }

    @Test
    void delegatesSettingNonce() {
        subject.setNonce(123);

        verify(hederaState).setNonce(ACCOUNT_NUM, 123);
    }

    @Test
    void delegatesSettingCode() {
        final var code = ConversionUtils.pbjToTuweniBytes(SOME_PRETEND_CODE);

        subject.setCode(code);

        verify(hederaState).setCode(CONTRACT_ID, code);
    }

    @Test
    void delegatesSettingStorage() {
        subject.setStorageValue(SOME_KEY, SOME_VALUE);

        verify(hederaState).setStorageValue(CONTRACT_ID, SOME_KEY, SOME_VALUE);
    }

    @Test
    void doesNotSupportDirectBalanceMutation() {
        final var balance = Wei.of(123);
        assertThrows(UnsupportedOperationException.class, () -> subject.setBalance(balance));
    }

    @Test
    void delegatesCheckingContract() {
        given(hederaState.isContract(ACCOUNT_ID)).willReturn(true);
        assertTrue(subject.isContract());
    }

    @Test
    void testRegularAccount() {
        given(hederaState.isContract(ACCOUNT_ID)).willReturn(true);
        assertFalse(subject.isRegularAccount());

        given(hederaState.isContract(ACCOUNT_ID)).willReturn(false);
        assertTrue(subject.isRegularAccount());
    }
}
