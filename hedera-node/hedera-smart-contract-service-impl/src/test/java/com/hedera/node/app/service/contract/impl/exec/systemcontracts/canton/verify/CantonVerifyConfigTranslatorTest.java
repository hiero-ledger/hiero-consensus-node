// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.CantonVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify.EthereumVerifyConfigTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class CantonVerifyConfigTranslatorTest extends CallTestBase {
    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private CantonVerifierCallAttempt attempt;

    private CantonVerifyConfigTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new CantonVerifyConfigTranslator(new SystemContractMethodRegistry(), contractMetrics);
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
    void identifiesVerifyConfigWithManifestSelector() {
        given(attempt.isMethod(CantonVerifyConfigTranslator.VERIFY_CONFIG))
                .willReturn(Optional.of(CantonVerifyConfigTranslator.VERIFY_CONFIG));

        assertThat(subject.identifyMethod(attempt)).contains(CantonVerifyConfigTranslator.VERIFY_CONFIG);
    }

    @Test
    void buildsVerifyConfigWithManifestCallFromAbiInput() {
        given(attempt.inputBytes())
                .willReturn(CantonVerifyConfigTranslator.VERIFY_CONFIG
                        .encodeCall(Tuple.of(new byte[] {1, 2, 3}, new byte[32], new byte[] {4, 5, 6}))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(CantonVerifyConfigCall.class);
    }

    @Test
    void rethrowsMalformedAbiInput() {
        given(attempt.inputBytes()).willReturn(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> subject.callFrom(attempt)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void sharesTheVerifierAbiOfTheOtherBuiltIns() {
        // EvmClprVerifier encodes one ABI for every verifier contract, so 0x173 must expose the same selector
        assertThat(CantonVerifyConfigTranslator.VERIFY_CONFIG.signature())
                .isEqualTo(EthereumVerifyConfigTranslator.VERIFY_CONFIG.signature());
        assertThat(CantonVerifyConfigTranslator.VERIFY_CONFIG.selector())
                .isEqualTo(EthereumVerifyConfigTranslator.VERIFY_CONFIG.selector());
    }

    @Test
    void registersTheMethodForTheCantonVerifierInTheCantonCategory() {
        final var registry = new SystemContractMethodRegistry();
        new CantonVerifyConfigTranslator(registry, contractMetrics);

        assertThat(registry.allSignatures()).containsExactly(CantonVerifyConfigTranslator.VERIFY_CONFIG.signature());
        assertThat(CantonVerifyConfigTranslator.VERIFY_CONFIG.hasCategory(Category.CANTON))
                .isTrue();
        assertThat(registry.allMethods())
                .singleElement()
                .satisfies(method -> assertThat(method.systemContract()).contains(SystemContract.CANTON_VERIFIER));
    }
}
