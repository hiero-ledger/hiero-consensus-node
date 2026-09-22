// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

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
    void identifiesVerifyBundleSelector() {
        given(attempt.isMethod(EthereumVerifyBundleTranslator.VERIFY_BUNDLE_V2)).willReturn(Optional.empty());
        given(attempt.isMethod(EthereumVerifyBundleTranslator.VERIFY_BUNDLE))
                .willReturn(Optional.of(EthereumVerifyBundleTranslator.VERIFY_BUNDLE));

        assertThat(subject.identifyMethod(attempt)).contains(EthereumVerifyBundleTranslator.VERIFY_BUNDLE);
    }

    @Test
    void buildsVerifyBundleCallFromAbiInput() {
        final byte[] bundlePayload = {1, 2, 3};
        final byte[] trustAnchor = {4, 5, 6};
        given(attempt.inputBytes())
                .willReturn(EthereumVerifyBundleTranslator.VERIFY_BUNDLE
                        .encodeCall(Tuple.of(bundlePayload, trustAnchor))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(EthereumVerifyBundleCall.class);
    }

    @Test
    void identifiesVerifyBundleV2Selector() {
        given(attempt.isMethod(EthereumVerifyBundleTranslator.VERIFY_BUNDLE_V2))
                .willReturn(Optional.of(EthereumVerifyBundleTranslator.VERIFY_BUNDLE_V2));

        assertThat(subject.identifyMethod(attempt)).contains(EthereumVerifyBundleTranslator.VERIFY_BUNDLE_V2);
    }

    @Test
    void buildsVerifyBundleV2CallFromAbiInput() {
        final byte[] bundlePayload = {1, 2, 3};
        final byte[] trustAnchor = {4, 5, 6};
        final byte[] channelContext = {7, 8, 9};
        given(attempt.isMethod(EthereumVerifyBundleTranslator.VERIFY_BUNDLE_V2))
                .willReturn(Optional.of(EthereumVerifyBundleTranslator.VERIFY_BUNDLE_V2));
        given(attempt.inputBytes())
                .willReturn(EthereumVerifyBundleTranslator.VERIFY_BUNDLE_V2
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
