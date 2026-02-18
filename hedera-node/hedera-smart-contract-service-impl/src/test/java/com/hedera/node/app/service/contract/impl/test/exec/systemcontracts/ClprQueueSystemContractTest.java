// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.ClprQueueSystemContract.CLPR_QUEUE_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSamePrecompileResult;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ClprQueueSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprQueueCallFactory;
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
class ClprQueueSystemContractTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private ClprQueueCallFactory attemptFactory;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private ContractMetrics contractMetrics;

    private MockedStatic<FrameUtils> frameUtils;

    private ClprQueueSystemContract subject;
    private final Bytes validInput = Bytes.fromHexString("8cfaaa60");

    @BeforeEach
    void setUp() {
        frameUtils = Mockito.mockStatic(FrameUtils.class);
        subject = new ClprQueueSystemContract(gasCalculator, attemptFactory, contractMetrics);
    }

    @AfterEach
    void clear() {
        frameUtils.close();
    }

    @Test
    void revertsWithTypedReasonIfConfigIsOff() {
        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(contractsConfig);
        when(contractsConfig.systemContractClprQueueEnabled()).thenReturn(false);
        final var expected = revertResult(Bytes.wrap("CLPR_QUEUE_DISABLED".getBytes(UTF_8)), frame.getRemainingGas());
        final var result = subject.computeFully(CLPR_QUEUE_CONTRACT_ID, validInput, frame);
        assertSamePrecompileResult(expected, result);
    }
}
