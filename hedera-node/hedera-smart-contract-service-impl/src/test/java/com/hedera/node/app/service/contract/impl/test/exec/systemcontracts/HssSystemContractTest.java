// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.NOT_SUPPORTED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract.HSS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractNativeSystemContract.FUNCTION_SELECTOR_LENGTH;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hederaConfigOf;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.TRANSACTION_MAX_BYTES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSamePrecompileResult;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INVALID_OPERATION;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallFactory;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.HederaConfig;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HssSystemContractTest {
    @Mock
    private MessageFrame frame;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private HederaConfig hederaConfig;

    @Mock
    private HssCallFactory attemptFactory;

    @Mock
    private GasCalculator gasCalculator;

    @Mock
    private ContractMetrics contractMetrics;

    private MockedStatic<FrameUtils> frameUtils;

    private HssSystemContract subject;
    private final Bytes validInput = Bytes.fromHexString("91548228");

    @BeforeEach
    void setUp() {
        frameUtils = Mockito.mockStatic(FrameUtils.class);
        subject = new HssSystemContract(gasCalculator, attemptFactory, contractMetrics);
    }

    @AfterEach
    void clear() {
        frameUtils.close();
    }

    /**
     * The unit tests for HtsSystemContract are also valid for HssSystemContract.
     * Only add tests for unique functionality.
     */
    @Test
    void haltsAndConsumesRemainingGasIfConfigIsOff() {
        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(contractsConfig);
        when(contractsConfig.systemContractScheduleServiceEnabled()).thenReturn(false);
        final var expected = haltResult(NOT_SUPPORTED, frame.getRemainingGas());
        final var result = subject.computeFully(HSS_CONTRACT_ID, validInput, frame);
        assertSamePrecompileResult(expected, result);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, TRANSACTION_MAX_BYTES + 1})
    @DisplayName("HssSystemContract halts and consumes remaining gas on input size validation")
    void inputSizeValidation(int inputSize) {
        byte[] input = new byte[inputSize];
        TestHelpers.RANDOM.nextBytes(input);

        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(contractsConfig);
        when(contractsConfig.systemContractScheduleServiceEnabled()).thenReturn(true);
        if (inputSize >= FUNCTION_SELECTOR_LENGTH) {
            frameUtils.when(() -> hederaConfigOf(frame)).thenReturn(hederaConfig);
            when(hederaConfig.transactionMaxBytes()).thenReturn(TRANSACTION_MAX_BYTES);
        }
        final var expected = haltResult(INVALID_OPERATION, frame.getRemainingGas());
        final var result = subject.computeFully(HAS_CONTRACT_ID, Bytes.of(input), frame);
        assertSamePrecompileResult(expected, result);
    }
}
