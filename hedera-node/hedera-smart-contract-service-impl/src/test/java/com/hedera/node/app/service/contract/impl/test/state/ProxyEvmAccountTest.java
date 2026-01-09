// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.pbjToTuweniBytes;
import static org.hyperledger.besu.crypto.Hash.keccak256;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.state.DispatchingEvmFrameState;
import com.hedera.node.app.service.contract.impl.state.ProxyEvmAccount;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hyperledger.besu.evm.code.CodeV0;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProxyEvmAccountTest {
    private static final long ACCOUNT_NUM = 0x9abcdefabcdefbbbL;
    private static final Account ACCOUNT = mock(Account.class);

    @Mock
    private DispatchingEvmFrameState state;

    @Mock
    private ProxyEvmAccount subject;

    @BeforeEach
    void setUp() {
        subject = new ProxyEvmAccount(ACCOUNT, state);
        when(ACCOUNT.accountId())
                .thenReturn(AccountID.newBuilder().accountNum(ACCOUNT_NUM).build());
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
    void getCodeShouldReturnDelegationIndicatorIfSet() {
        final var delegationAddress = Bytes.fromHex("0000000000000000000000000000000000000001");
        when(ACCOUNT.delegationAddress()).thenReturn(delegationAddress);
        assertEquals(
                pbjToTuweniBytes(Bytes.fromHex("ef01000000000000000000000000000000000000000001")), subject.getCode());
    }

    @Test
    void getCodeShouldReturnEmptyIfNoDelegationIndicator() {
        when(ACCOUNT.delegationAddress()).thenReturn(Bytes.EMPTY);
        assertEquals(pbjToTuweniBytes(Bytes.EMPTY), subject.getCode());
    }

    @Test
    void getCodeHashShouldReturnDelegationIndicatorHash() {
        final var delegationAddress = Bytes.fromHex("0000000000000000000000000000000000000001");
        when(ACCOUNT.delegationAddress()).thenReturn(delegationAddress);
        assertEquals(
                keccak256(pbjToTuweniBytes(Bytes.fromHex("ef01000000000000000000000000000000000000000001"))),
                subject.getCodeHash());
    }

    @Test
    void getCodeHashShouldReturnCorrectHashWhenNoDelegation() {
        when(ACCOUNT.delegationAddress()).thenReturn(Bytes.EMPTY);
        assertEquals(CodeV0.EMPTY_CODE.getCodeHash(), subject.getCodeHash());
    }
}
