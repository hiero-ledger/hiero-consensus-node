// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_COINBASE;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.streams.SidecarType;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.v046.Version046FeatureFlags;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import java.util.Deque;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeatureFlagsTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private Deque<MessageFrame> stack;

    @Test
    void sidecarsEnabledBasedOnConfig() {
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(stack.isEmpty()).willReturn(true);
        final var subject = mock(FeatureFlags.class);
        doCallRealMethod().when(subject).isSidecarEnabled(any(), any());

        final var config = HederaTestConfigBuilder.create()
                .withValue("ledger.fundingAccount", DEFAULT_COINBASE)
                .withValue("contracts.sidecars", "CONTRACT_BYTECODE,CONTRACT_ACTION")
                .getOrCreateConfig();
        given(frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE)).willReturn(config);

        assertTrue(subject.isSidecarEnabled(frame, SidecarType.CONTRACT_BYTECODE));
        assertTrue(subject.isSidecarEnabled(frame, SidecarType.CONTRACT_ACTION));
        assertFalse(subject.isSidecarEnabled(frame, SidecarType.CONTRACT_STATE_CHANGE));
    }

    @Test
    void isAllowCallsToNonContractAccountsEnabledGrandfatherTest() {
        final var subject = new Version046FeatureFlags();
        final var config2 = HederaTestConfigBuilder.create()
                .withValue(
                        "contracts.evm.nonExtantContractsFail",
                        ConversionUtils.numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS))
                .getOrCreateConfig();

        final var contractsConfig = config2.getConfigData(ContractsConfig.class);
        assertTrue(subject.isAllowCallsToNonContractAccountsEnabled(contractsConfig, 1L));
        assertFalse(subject.isAllowCallsToNonContractAccountsEnabled(
                contractsConfig, ConversionUtils.numberOfLongZero(NON_SYSTEM_LONG_ZERO_ADDRESS)));
    }
}
