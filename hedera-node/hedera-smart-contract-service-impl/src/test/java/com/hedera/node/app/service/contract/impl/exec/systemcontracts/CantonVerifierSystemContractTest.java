// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.CantonVerifierCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.EntityType;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CantonVerifierSystemContractTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private CantonVerifierCallFactory callFactory;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private ContractMetrics contractMetrics;

    @Test
    void delegatesCallTypeResolutionForRegularAccounts() {
        final var subject = new CantonVerifierSystemContract(gasCalculator, callFactory, contractMetrics);

        try (final var frameUtils = mockStatic(FrameUtils.class)) {
            frameUtils
                    .when(() -> FrameUtils.callTypeOf(frame, EntityType.REGULAR_ACCOUNT))
                    .thenReturn(FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT);

            assertThat(subject.callTypeOf(frame)).isEqualTo(FrameUtils.CallType.DIRECT_OR_PROXY_REDIRECT);
        }
    }

    @Test
    void isNamedCantonVerifierAtAddress0x173() {
        final var subject = new CantonVerifierSystemContract(gasCalculator, callFactory, contractMetrics);

        assertThat(subject.getName()).isEqualTo(CantonVerifierSystemContract.CANTON_VERIFIER_SYSTEM_CONTRACT_NAME);
        assertThat(CantonVerifierSystemContract.CANTON_VERIFIER_SYSTEM_CONTRACT_NAME)
                .isEqualTo("CantonVerifier");
        assertThat(Address.fromHexString(CantonVerifierSystemContract.CANTON_VERIFIER_EVM_ADDRESS))
                .isEqualTo(Address.fromHexString("0x0000000000000000000000000000000000000173"));
    }
}
