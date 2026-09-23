// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.BesuQBFTVerifierCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class BesuQBFTVerifyConfigTranslatorTest extends CallTestBase {
    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private BesuQBFTVerifierCallAttempt attempt;

    private BesuQBFTVerifyConfigTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new BesuQBFTVerifyConfigTranslator(new SystemContractMethodRegistry(), contractMetrics);
        // identifyMethod/callFrom now probe the V3 selector first; not every test stubs it.
        lenient()
                .when(attempt.isMethod(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V3))
                .thenReturn(Optional.empty());
    }

    @Test
    void identifiesVerifyConfigSelector() {
        given(attempt.isMethod(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V2)).willReturn(Optional.empty());
        given(attempt.isMethod(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG))
                .willReturn(Optional.of(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG));

        assertThat(subject.identifyMethod(attempt)).contains(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG);
    }

    @Test
    void identifiesVerifyConfigV2Selector() {
        given(attempt.isMethod(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V2))
                .willReturn(Optional.of(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V2));

        assertThat(subject.identifyMethod(attempt)).contains(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V2);
    }

    @Test
    void buildsVerifyConfigCallFromAbiInput() {
        final byte[] stateProof = {1, 2, 3};
        given(attempt.inputBytes())
                .willReturn(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG
                        .encodeCall(Tuple.singleton(stateProof))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(BesuQBFTVerifyConfigCall.class);
    }

    @Test
    void buildsVerifyConfigV2CallFromAbiInput() {
        final byte[] stateProof = {1, 2, 3};
        final byte[] channelId32 = new byte[32];
        given(attempt.isMethod(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V2))
                .willReturn(Optional.of(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V2));
        given(attempt.inputBytes())
                .willReturn(BesuQBFTVerifyConfigTranslator.VERIFY_CONFIG_V2
                        .encodeCall(Tuple.of(stateProof, channelId32))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(BesuQBFTVerifyConfigCall.class);
    }

    @Test
    void rethrowsMalformedAbiInput() {
        given(attempt.inputBytes()).willReturn(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> subject.callFrom(attempt)).isInstanceOf(RuntimeException.class);
    }
}
