// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.EthereumVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class EthereumVerifyConfigTranslatorTest extends CallTestBase {
    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private EthereumVerifierCallAttempt attempt;

    private EthereumVerifyConfigTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new EthereumVerifyConfigTranslator(new SystemContractMethodRegistry(), contractMetrics);
    }

    @Test
    void rejectsLegacyConfigAbiInput() {
        final var legacyMethod = new Function("verifyConfig(bytes)", "(bytes)");
        given(attempt.inputBytes())
                .willReturn(legacyMethod
                        .encodeCall(Tuple.singleton(new byte[] {1, 2, 3}))
                        .array());

        assertThatThrownBy(() -> subject.callFrom(attempt)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifiesVerifyConfigWithSeedEndpointsSelector() {
        given(attempt.isMethod(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_MANIFEST))
                .willReturn(Optional.empty());
        given(attempt.isMethod(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_SEED_ENDPOINTS))
                .willReturn(Optional.of(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_SEED_ENDPOINTS));

        assertThat(subject.identifyMethod(attempt))
                .contains(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_SEED_ENDPOINTS);
    }

    @Test
    void buildsVerifyConfigWithSeedEndpointsCallFromAbiInput() {
        given(attempt.inputBytes())
                .willReturn(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_SEED_ENDPOINTS
                        .encodeCall(Tuple.of(new byte[] {1, 2, 3}, new byte[32]))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(EthereumVerifyConfigCall.class);
    }

    @Test
    void identifiesVerifyConfigWithManifestSelector() {
        given(attempt.isMethod(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_MANIFEST))
                .willReturn(Optional.of(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_MANIFEST));

        assertThat(subject.identifyMethod(attempt))
                .contains(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_MANIFEST);
    }

    @Test
    void buildsVerifyConfigWithManifestCallFromAbiInput() {
        given(attempt.isMethod(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_MANIFEST))
                .willReturn(Optional.of(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_MANIFEST));
        given(attempt.inputBytes())
                .willReturn(EthereumVerifyConfigTranslator.VERIFY_CONFIG_WITH_MANIFEST
                        .encodeCall(Tuple.of(new byte[] {1, 2, 3}, new byte[32], new byte[] {4, 5, 6}))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(EthereumVerifyConfigCall.class);
    }

    @Test
    void rethrowsMalformedAbiInput() {
        given(attempt.inputBytes()).willReturn(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> subject.callFrom(attempt)).isInstanceOf(RuntimeException.class);
    }
}
