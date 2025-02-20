// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.v034;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.v034.Version034FeatureFlags;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
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
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        assertTrue(subject.isImplicitCreationEnabled(frame));
    }

    @Test
    void implicitCreationNotEnabledIfLazyCreationNotEnabled() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        final var config = HederaTestConfigBuilder.create()
                .withValue("lazyCreation.enabled", false)
                .getOrCreateConfig();
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        assertFalse(subject.isImplicitCreationEnabled(frame));
    }

    @Test
    void implicitCreationNotEnabledIfAutoCreationNotEnabled() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        final var config = HederaTestConfigBuilder.create()
                .withValue("autoCreation.enabled", false)
                .getOrCreateConfig();
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        assertFalse(subject.isImplicitCreationEnabled(frame));
    }
}
