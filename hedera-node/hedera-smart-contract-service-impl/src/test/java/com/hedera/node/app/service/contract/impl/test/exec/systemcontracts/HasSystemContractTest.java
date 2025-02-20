// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.NOT_SUPPORTED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSamePrecompileResult;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.config.data.ContractsConfig;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HasSystemContractTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private HasCallFactory attemptFactory;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private ContractMetrics contractMetrics;

    private MockedStatic<FrameUtils> frameUtils;

    private HasSystemContract subject;
    private final Bytes validInput = Bytes.fromHexString("91548228");

    @BeforeEach
    void setUp() {
        frameUtils = Mockito.mockStatic(FrameUtils.class);
        subject = new HasSystemContract(gasCalculator, attemptFactory, contractMetrics);
    }

    @AfterEach
    void clear() {
        frameUtils.close();
    }

    /**
     * The unit tests for HtsSystemContract are also valid for HasSystemContract.
     * Only add tests for unique functionality.
     */
    @Test
    void haltsAndConsumesRemainingGasIfConfigIsOff() {
        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(contractsConfig);
        when(contractsConfig.systemContractAccountServiceEnabled()).thenReturn(false);
        final var expected = haltResult(NOT_SUPPORTED, frame.getRemainingGas());
        final var result = subject.computeFully(HAS_CONTRACT_ID, validInput, frame);
        assertSamePrecompileResult(expected, result);
    }
}
