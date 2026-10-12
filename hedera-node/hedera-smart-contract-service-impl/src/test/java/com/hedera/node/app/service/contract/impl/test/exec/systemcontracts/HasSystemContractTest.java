// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason.NOT_SUPPORTED;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.haltResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractNativeSystemContract.FUNCTION_SELECTOR_LENGTH;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.contractsConfigOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.hederaConfigOf;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.TRANSACTION_MAX_BYTES;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.assertSamePrecompileResult;
import static org.hyperledger.besu.evm.frame.ExceptionalHaltReason.INVALID_OPERATION;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallFactory;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking.StakingTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
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
class HasSystemContractTest {

    @Mock
    private MessageFrame frame;

    @Mock
    private ContractsConfig contractsConfig;

    @Mock
    private HederaConfig hederaConfig;

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

    @ParameterizedTest
    @ValueSource(ints = {0, 3, TRANSACTION_MAX_BYTES + 1})
    @DisplayName("HasSystemContract halts and consumes remaining gas on input size validation")
    void inputSizeValidation(int inputSize) {
        byte[] input = new byte[inputSize];
        TestHelpers.RANDOM.nextBytes(input);

        frameUtils.when(() -> contractsConfigOf(frame)).thenReturn(contractsConfig);
        when(contractsConfig.systemContractAccountServiceEnabled()).thenReturn(true);
        if (inputSize >= FUNCTION_SELECTOR_LENGTH) {
            frameUtils.when(() -> hederaConfigOf(frame)).thenReturn(hederaConfig);
            when(hederaConfig.transactionMaxBytes()).thenReturn(TRANSACTION_MAX_BYTES);
        }
        final var expected = haltResult(INVALID_OPERATION, frame.getRemainingGas());
        final var result = subject.computeFully(HAS_CONTRACT_ID, Bytes.of(input), frame);
        assertSamePrecompileResult(expected, result);
    }

    @Test
    @DisplayName("every IHRC632 staking selector is eligible for the HAS proxy redirect")
    void stakingFacadeSelectorsAreRedirectEligible() {
        // HAS_PROXY_ELIGIBLE_CALL_DATA_PREFIXES is a hand-written list of hex literals that has to agree with
        // the selectors StakingTranslator derives from its ABI signatures. Nothing else ties the two
        // together, and a drifted literal fails silently: the redirect never fires, so an EOA calling
        // IHRC632(self).setDeclineReward(true) is dispatched as a plain call to a codeless account, which
        // succeeds with empty output while changing nothing.
        for (final var method : new SystemContractMethod[] {
            StakingTranslator.STAKE_TO_NODE_PROXY,
            StakingTranslator.STAKE_TO_ACCOUNT_PROXY,
            StakingTranslator.UNSTAKE_PROXY,
            StakingTranslator.SET_DECLINE_REWARD_PROXY,
            StakingTranslator.STAKE_TO_NODE_AND_DECLINE_REWARD_PROXY,
            StakingTranslator.GET_STAKING_INFO_PROXY
        }) {
            assertTrue(
                    HasSystemContract.isPayloadEligibleForHasProxyRedirect(Bytes.of(method.selector())),
                    () -> "%s is declared CallVia.PROXY but its selector is not in the redirect allowlist"
                            .formatted(method.signature()));
        }
    }

    @Test
    @DisplayName("a non-facade staking selector is not redirect eligible")
    void explicitStakingSelectorsAreNotRedirectEligible() {
        // The explicit IHederaAccountService forms name their target, so they are meaningless on a redirect
        // and must not widen the set of calldata diverted away from an account's own code.
        for (final var method : new SystemContractMethod[] {
            StakingTranslator.STAKE_TO_NODE, StakingTranslator.UNSTAKE, StakingTranslator.GET_STAKING_INFO
        }) {
            assertFalse(
                    HasSystemContract.isPayloadEligibleForHasProxyRedirect(Bytes.of(method.selector())),
                    () -> "%s should not be in the redirect allowlist".formatted(method.signature()));
        }
    }
}
