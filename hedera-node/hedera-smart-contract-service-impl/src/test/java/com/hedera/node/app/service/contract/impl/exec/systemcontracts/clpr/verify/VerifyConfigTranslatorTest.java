// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.hapi.utils.blocks.TssVerifier;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class VerifyConfigTranslatorTest extends CallTestBase {
    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private ClprCallAttempt attempt;

    @Mock
    private TssVerifier tssVerifier;

    private VerifyConfigTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new VerifyConfigTranslator(new SystemContractMethodRegistry(), contractMetrics, tssVerifier);
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
        given(attempt.isMethod(VerifyConfigTranslator.VERIFY_CONFIG))
                .willReturn(Optional.of(VerifyConfigTranslator.VERIFY_CONFIG));

        assertThat(subject.identifyMethod(attempt)).contains(VerifyConfigTranslator.VERIFY_CONFIG);
    }

    @Test
    void buildsVerifyConfigWithManifestCallFromAbiInput() {
        given(attempt.inputBytes())
                .willReturn(VerifyConfigTranslator.VERIFY_CONFIG
                        .encodeCall(Tuple.of(new byte[] {1, 2, 3}, new byte[32], new byte[] {4, 5, 6}))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(VerifyConfigCall.class);
    }

    @Test
    void rethrowsMalformedAbiInput() {
        given(attempt.inputBytes()).willReturn(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> subject.callFrom(attempt)).isInstanceOf(RuntimeException.class);
    }
}
