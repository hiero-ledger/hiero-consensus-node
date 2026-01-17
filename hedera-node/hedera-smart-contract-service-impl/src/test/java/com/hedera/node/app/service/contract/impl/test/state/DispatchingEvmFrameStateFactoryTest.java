// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.state;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.CODE_FACTORY;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.DispatchingEvmFrameState;
import com.hedera.node.app.service.contract.impl.state.ScopedEvmFrameStateFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DispatchingEvmFrameStateFactoryTest {
    @Mock
    private HederaOperations scope;

    @Mock
    private HederaNativeOperations extFrameScope;

    @Mock
    private ContractStateStore store;

    private ScopedEvmFrameStateFactory subject;

    @BeforeEach
    void setUp() {
        subject = new ScopedEvmFrameStateFactory(scope, extFrameScope, CODE_FACTORY);
    }

    @Test
    void createsScopedEvmFrameStates() {
        given(scope.getStore()).willReturn(store);

        final var nextFrame = subject.get();

        assertInstanceOf(DispatchingEvmFrameState.class, nextFrame);
    }
}
