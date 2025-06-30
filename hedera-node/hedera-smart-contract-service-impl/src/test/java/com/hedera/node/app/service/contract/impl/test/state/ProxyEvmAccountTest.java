// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarallowance.HbarAllowanceTranslator.HBAR_ALLOWANCE_PROXY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ACCOUNT_CALL_REDIRECT_CONTRACT_BINARY;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.ADDRESS_BYTECODE_PATTERN;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CODE_FACTORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.state.DispatchingEvmFrameState;
import com.hedera.node.app.service.contract.impl.state.ProxyEvmAccount;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProxyEvmAccountTest {
    private static final long ACCOUNT_NUM = 0x9abcdefabcdefbbbL;
    private static final AccountID ACCOUNT_ID =
            AccountID.newBuilder().accountNum(ACCOUNT_NUM).build();
    private static final Bytes SOME_PRETEND_CODE = Bytes.wrap("<NOT-REALLY-CODE>");

    @Mock
    private DispatchingEvmFrameState state;

    @Mock
    private ProxyEvmAccount subject;

    @BeforeEach
    void setUp() {
        subject = new ProxyEvmAccount(ACCOUNT_ID, state);
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
    void returnsEvmCodeOfProxy() {
        final var accountInHex = String.format("%040X", ACCOUNT_NUM);
        final var expected = org.apache.tuweni.bytes.Bytes.fromHexString(
                ACCOUNT_CALL_REDIRECT_CONTRACT_BINARY.replace(ADDRESS_BYTECODE_PATTERN, accountInHex));
        given(state.getAddress(ACCOUNT_ID)).willReturn(Address.fromHexString(accountInHex));
        given(state.getAccountRedirectCode(Address.fromHexString(accountInHex))).willCallRealMethod();

        assertEquals(
                CODE_FACTORY.createCode(expected, false),
                subject.getEvmCode(org.apache.tuweni.bytes.Bytes.wrap(HBAR_ALLOWANCE_PROXY.selector()), CODE_FACTORY));
    }

    @Test
    void returnsEvmCodeOfEmptyBytes() {
        given(state.getAccountRedirectCode(null)).willCallRealMethod();

        assertEquals(
                CODE_FACTORY.createCode(org.apache.tuweni.bytes.Bytes.EMPTY, false),
                subject.getEvmCode(org.apache.tuweni.bytes.Bytes.wrap(SOME_PRETEND_CODE.toByteArray()), CODE_FACTORY));
    }

    @Test
    void returnsEvmCodeHashOfProxy() {
        final var accountInHex = String.format("%040X", ACCOUNT_NUM);
        final var expected = org.apache.tuweni.bytes.Bytes.fromHexString(
                ACCOUNT_CALL_REDIRECT_CONTRACT_BINARY.replace(ADDRESS_BYTECODE_PATTERN, accountInHex));
        given(state.getAddress(ACCOUNT_ID)).willReturn(Address.fromHexString(accountInHex));
        given(state.getAccountRedirectCode(Address.fromHexString(accountInHex))).willCallRealMethod();

        final var expectedHash = CODE_FACTORY.createCode(expected, false).getCodeHash();

        when(state.getAccountRedirectCodeHash(Address.fromHexString(accountInHex)))
                .thenReturn(expectedHash);

        subject.getEvmCode(org.apache.tuweni.bytes.Bytes.wrap(HBAR_ALLOWANCE_PROXY.selector()), CODE_FACTORY);
        final var hash = subject.getCodeHash();

        assertEquals(expectedHash, hash);
    }

    @Test
    void returnsEvmCodeHashOfEmptyBytes() {
        given(state.getAccountRedirectCode(null)).willCallRealMethod();

        final var expectedHash = CODE_FACTORY
                .createCode(org.apache.tuweni.bytes.Bytes.EMPTY, false)
                .getCodeHash();

        when(state.getAccountRedirectCodeHash(any())).thenReturn(expectedHash);

        subject.getEvmCode(org.apache.tuweni.bytes.Bytes.wrap(SOME_PRETEND_CODE.toByteArray()), CODE_FACTORY);
        final var hash = subject.getCodeHash();

        assertEquals(expectedHash, hash);
    }
}
