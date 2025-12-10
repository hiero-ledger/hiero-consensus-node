// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CODE_FACTORY;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.contract.impl.state.DispatchingEvmFrameState;
import com.hedera.node.app.service.contract.impl.state.ProxyEvmAccount;
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

    @Mock
    private DispatchingEvmFrameState state;

    @Mock
    private ProxyEvmAccount subject;

    @BeforeEach
    void setUp() {
        subject = new ProxyEvmAccount(ACCOUNT_ID, state, CODE_FACTORY);
    }

    @Test
    void notTokenFacade() {
        assertFalse(subject.isTokenFacade());
    }

    @Test
    void notScheduleTxnFacade() {
        assertFalse(subject.isScheduleTxnFacade());
    }
}
