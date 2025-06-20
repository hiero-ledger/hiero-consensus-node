// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.v034;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.service.contract.impl.exec.v034.Version034FeatureFlags;
import java.util.Deque;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Version034FeatureFlagsTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private Deque<MessageFrame> stack;

    private Version034FeatureFlags subject = new Version034FeatureFlags();

    @Test
    void implicitCreationEnabledIfLazyAndAutoCreationBothEnabled() {
        assertTrue(subject.isImplicitCreationEnabled());
    }
}
