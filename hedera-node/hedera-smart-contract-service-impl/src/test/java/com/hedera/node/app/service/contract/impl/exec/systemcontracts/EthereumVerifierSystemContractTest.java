// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.EthereumVerifierCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EthereumVerifierSystemContractTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private EthereumVerifierCallFactory callFactory;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private ContractMetrics contractMetrics;

    @Test
    void delegatesCallTypeResolutionForRegularAccounts() {
        final var subject = new EthereumVerifierSystemContract(gasCalculator, callFactory, contractMetrics);

        try (final var frameUtils = mockStatic(FrameUtils.class)) {
            frameUtils
                    .when(() -> FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT))
                    .thenReturn(FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT);

            assertThat(subject.callTypeOf(frame)).isEqualTo(FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT);
        }
    }
}
