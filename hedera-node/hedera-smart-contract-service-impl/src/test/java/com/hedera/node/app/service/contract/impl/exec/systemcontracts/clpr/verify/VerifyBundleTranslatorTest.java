// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

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

class VerifyBundleTranslatorTest extends CallTestBase {
    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private TssVerifier tssVerifier;

    @Mock
    private ClprCallAttempt attempt;

    private VerifyBundleTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new VerifyBundleTranslator(new SystemContractMethodRegistry(), contractMetrics, tssVerifier);
    }

    @Test
    void identifiesVerifyBundleV1Selector() {
        given(attempt.isMethod(VerifyBundleTranslator.VERIFY_BUNDLE_V2)).willReturn(Optional.empty());
        given(attempt.isMethod(VerifyBundleTranslator.VERIFY_BUNDLE))
                .willReturn(Optional.of(VerifyBundleTranslator.VERIFY_BUNDLE));

        assertThat(subject.identifyMethod(attempt)).contains(VerifyBundleTranslator.VERIFY_BUNDLE);
    }

    @Test
    void identifiesVerifyBundleV2Selector() {
        given(attempt.isMethod(VerifyBundleTranslator.VERIFY_BUNDLE_V2))
                .willReturn(Optional.of(VerifyBundleTranslator.VERIFY_BUNDLE_V2));

        assertThat(subject.identifyMethod(attempt)).contains(VerifyBundleTranslator.VERIFY_BUNDLE_V2);
    }

    @Test
    void buildsVerifyBundleCallFromV1AbiInput() {
        final byte[] bundlePayload = {1, 2, 3};
        final byte[] trustAnchor = {4, 5, 6};
        given(attempt.isMethod(VerifyBundleTranslator.VERIFY_BUNDLE_V2)).willReturn(Optional.empty());
        given(attempt.inputBytes())
                .willReturn(VerifyBundleTranslator.VERIFY_BUNDLE
                        .encodeCall(Tuple.of(bundlePayload, trustAnchor))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(VerifyBundleCall.class);
    }

    @Test
    void buildsVerifyBundleCallFromV2AbiInput() {
        final byte[] bundlePayload = {1, 2, 3};
        final byte[] trustAnchor = {4, 5, 6};
        final byte[] channelContext = {7, 8, 9};
        given(attempt.isMethod(VerifyBundleTranslator.VERIFY_BUNDLE_V2))
                .willReturn(Optional.of(VerifyBundleTranslator.VERIFY_BUNDLE_V2));
        given(attempt.inputBytes())
                .willReturn(VerifyBundleTranslator.VERIFY_BUNDLE_V2
                        .encodeCall(Tuple.of(bundlePayload, trustAnchor, channelContext))
                        .array());
        given(attempt.enhancement()).willReturn(mockEnhancement());
        given(attempt.systemContractGasCalculator()).willReturn(gasCalculator);

        assertThat(subject.callFrom(attempt)).isInstanceOf(VerifyBundleCall.class);
    }

    @Test
    void rethrowsMalformedAbiInput() {
        given(attempt.isMethod(VerifyBundleTranslator.VERIFY_BUNDLE_V2)).willReturn(Optional.empty());
        given(attempt.inputBytes()).willReturn(new byte[] {1, 2, 3});

        assertThatThrownBy(() -> subject.callFrom(attempt)).isInstanceOf(RuntimeException.class);
    }
}
