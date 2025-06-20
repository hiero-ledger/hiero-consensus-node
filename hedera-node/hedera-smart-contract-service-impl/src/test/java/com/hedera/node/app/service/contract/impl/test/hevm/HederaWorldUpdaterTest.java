// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.state.HederaEvmAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HederaWorldUpdaterTest {
    @Mock
    private HederaEvmAccount account;

    @Test
    void delegatesGettingHederaAccount() {
        final var subject = mock(HederaWorldUpdater.class);
        doCallRealMethod().when(subject).getHederaAccount(EIP_1014_ADDRESS);
        given(subject.get(EIP_1014_ADDRESS)).willReturn(account);
        assertSame(account, subject.getHederaAccount(EIP_1014_ADDRESS));
    }
}
