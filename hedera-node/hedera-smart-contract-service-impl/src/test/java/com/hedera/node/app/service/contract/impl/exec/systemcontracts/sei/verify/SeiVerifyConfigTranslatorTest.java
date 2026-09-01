// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei.SeiVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class SeiVerifyConfigTranslatorTest extends CallTestBase {
    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private SeiVerifierCallAttempt attempt;

    private SeiVerifyConfigTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new SeiVerifyConfigTranslator(new SystemContractMethodRegistry(), contractMetrics);
    }

    @Test
    void identifiesVerifyConfigSelector() {
        given(attempt.isMethod(SeiVerifyConfigTranslator.VERIFY_CONFIG_V3)).willReturn(Optional.empty());
        given(attempt.isMethod(SeiVerifyConfigTranslator.VERIFY_CONFIG_V2)).willReturn(Optional.empty());
        given(attempt.isMethod(SeiVerifyConfigTranslator.VERIFY_CONFIG))
                .willReturn(Optional.of(SeiVerifyConfigTranslator.VERIFY_CONFIG));

        assertThat(subject.identifyMethod(attempt)).contains(SeiVerifyConfigTranslator.VERIFY_CONFIG);
    }

    @Test
    void buildsVerifyConfigCallFromAbiInput() {
        final byte[] stateProof = {1, 2, 3};
        given(attempt.inputBytes())
                .willReturn(SeiVerifyConfigTranslator.VERIFY_CONFIG
                        .encodeCall(Tuple.singleton(stateProof))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(SeiVerifyConfigCall.class);
    }

    @Test
    void identifiesVerifyConfigV2Selector() {
        given(attempt.isMethod(SeiVerifyConfigTranslator.VERIFY_CONFIG_V3)).willReturn(Optional.empty());
        given(attempt.isMethod(SeiVerifyConfigTranslator.VERIFY_CONFIG_V2))
                .willReturn(Optional.of(SeiVerifyConfigTranslator.VERIFY_CONFIG_V2));

        assertThat(subject.identifyMethod(attempt)).contains(SeiVerifyConfigTranslator.VERIFY_CONFIG_V2);
    }

    @Test
    void identifiesVerifyConfigV3Selector() {
        given(attempt.isMethod(SeiVerifyConfigTranslator.VERIFY_CONFIG_V3))
                .willReturn(Optional.of(SeiVerifyConfigTranslator.VERIFY_CONFIG_V3));

        assertThat(subject.identifyMethod(attempt)).contains(SeiVerifyConfigTranslator.VERIFY_CONFIG_V3);
    }

    @Test
    void buildsVerifyConfigV2CallFromAbiInput() {
        final byte[] configPayload = {1, 2, 3};
        final byte[] channelId32 = new byte[32];
        given(attempt.isMethod(SeiVerifyConfigTranslator.VERIFY_CONFIG_V3)).willReturn(Optional.empty());
        given(attempt.isMethod(SeiVerifyConfigTranslator.VERIFY_CONFIG_V2))
                .willReturn(Optional.of(SeiVerifyConfigTranslator.VERIFY_CONFIG_V2));
        given(attempt.inputBytes())
                .willReturn(SeiVerifyConfigTranslator.VERIFY_CONFIG_V2
                        .encodeCall(Tuple.of(configPayload, channelId32))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(SeiVerifyConfigCall.class);
    }

    @Test
    void buildsVerifyConfigV3CallFromAbiInput() {
        final byte[] configPayload = {1, 2, 3};
        final byte[] channelId32 = new byte[32];
        final byte[] manifestProof = {4, 5, 6};
        given(attempt.isMethod(SeiVerifyConfigTranslator.VERIFY_CONFIG_V3))
                .willReturn(Optional.of(SeiVerifyConfigTranslator.VERIFY_CONFIG_V3));
        given(attempt.inputBytes())
                .willReturn(SeiVerifyConfigTranslator.VERIFY_CONFIG_V3
                        .encodeCall(Tuple.of(configPayload, channelId32, manifestProof))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(SeiVerifyConfigCall.class);
    }

    @Test
    void rethrowsMalformedAbiInput() {
        given(attempt.inputBytes()).willReturn(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> subject.callFrom(attempt)).isInstanceOf(RuntimeException.class);
    }
}
