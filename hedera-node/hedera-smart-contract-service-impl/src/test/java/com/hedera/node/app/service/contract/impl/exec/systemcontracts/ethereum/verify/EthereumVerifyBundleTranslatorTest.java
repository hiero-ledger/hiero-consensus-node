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

class EthereumVerifyBundleTranslatorTest extends CallTestBase {
    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private EthereumVerifierCallAttempt attempt;

    private EthereumVerifyBundleTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new EthereumVerifyBundleTranslator(new SystemContractMethodRegistry(), contractMetrics);
    }

    @Test
    void registersOnlyCurrentBundleAbi() {
        final var registry = new SystemContractMethodRegistry();
        new EthereumVerifyBundleTranslator(registry, contractMetrics);

        assertThat(registry.allSignatures()).containsExactly("verifyBundle(bytes,bytes,bytes)");
    }

    @Test
    void rejectsLegacyBundleAbiInput() {
        final var legacyMethod = new Function("verifyBundle(bytes,bytes)", "(bytes)");
        given(attempt.inputBytes())
                .willReturn(legacyMethod
                        .encodeCall(Tuple.of(new byte[] {1, 2, 3}, new byte[] {4, 5, 6}))
                        .array());

        assertThatThrownBy(() -> subject.callFrom(attempt)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifiesVerifyBundleSelector() {
        given(attempt.isMethod(EthereumVerifyBundleTranslator.VERIFY_BUNDLE))
                .willReturn(Optional.of(EthereumVerifyBundleTranslator.VERIFY_BUNDLE));

        assertThat(subject.identifyMethod(attempt)).contains(EthereumVerifyBundleTranslator.VERIFY_BUNDLE);
    }

    @Test
    void buildsVerifyBundleCallFromAbiInput() {
        final byte[] bundlePayload = {1, 2, 3};
        final byte[] trustAnchor = {4, 5, 6};
        final byte[] channelContext = {7, 8, 9};
        given(attempt.inputBytes())
                .willReturn(EthereumVerifyBundleTranslator.VERIFY_BUNDLE
                        .encodeCall(Tuple.of(bundlePayload, trustAnchor, channelContext))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(EthereumVerifyBundleCall.class);
    }

    @Test
    void rethrowsMalformedAbiInput() {
        given(attempt.inputBytes()).willReturn(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> subject.callFrom(attempt)).isInstanceOf(RuntimeException.class);
    }
}
