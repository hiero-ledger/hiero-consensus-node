// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.v030;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONTRACTS_CONFIG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.v030.Version030FeatureFlags;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.Deque;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Version030FeatureFlagsTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private Deque<MessageFrame> stack;

    private Version030FeatureFlags subject = new Version030FeatureFlags();

    @Test
    void everythingIsDisabled() {
        assertFalse(subject.isImplicitCreationEnabled());
        assertFalse(subject.isAllowCallsToNonContractAccountsEnabled(DEFAULT_CONTRACTS_CONFIG, null));
    }

    @Test
    void create2FeatureFlagWorks() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        final var config = HederaTestConfigBuilder.create()
                .withValue("contracts.allowCreate2", false)
                .getOrCreateConfig();
        given(frame.getContextVariable(CONFIG_CONTEXT_VARIABLE)).willReturn(config);
        assertFalse(subject.isCreate2Enabled(frame));
    }
}
