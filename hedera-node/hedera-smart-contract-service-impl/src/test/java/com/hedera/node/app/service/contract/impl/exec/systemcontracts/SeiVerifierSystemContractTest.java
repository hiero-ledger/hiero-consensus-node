// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSamePrecompileResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.failure.HandleExceptionHaltReason;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.SeiVerifierCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import com.hedera.node.config.data.ClprConfig;
import com.swirlds.config.api.Configuration;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeiVerifierSystemContractTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private SeiVerifierCallFactory callFactory;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private Configuration configuration;

    @Mock
    private ClprConfig clprConfig;

    @Test
    void delegatesCallTypeResolutionForRegularAccounts() {
        final var subject = new SeiVerifierSystemContract(gasCalculator, callFactory, contractMetrics);

        try (final var frameUtils = mockStatic(FrameUtils.class)) {
            frameUtils
                    .when(() -> FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT))
                    .thenReturn(FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT);

            assertThat(subject.callTypeOf(frame)).isEqualTo(FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT);
        }
    }

    @Test
    void haltsWithClprNotEnabledWhenClprIsDisabled() {
        final var subject = new SeiVerifierSystemContract(gasCalculator, callFactory, contractMetrics);
        given(configuration.getConfigData(ClprConfig.class)).willReturn(clprConfig);
        given(clprConfig.enabled()).willReturn(false);

        try (final var frameUtils = mockStatic(FrameUtils.class)) {
            frameUtils.when(() -> FrameUtils.configOf(frame)).thenReturn(configuration);

            final var expected = haltResult(new HandleExceptionHaltReason(CLPR_NOT_ENABLED), frame.getRemainingGas());
            final var result = subject.computeFully(ContractID.DEFAULT, Bytes.fromHexString("00000000"), frame);

            assertSamePrecompileResult(expected, result);
        }
    }
}
