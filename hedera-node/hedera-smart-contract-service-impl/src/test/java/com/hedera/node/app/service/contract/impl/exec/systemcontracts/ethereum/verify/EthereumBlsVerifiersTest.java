// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.ethereum.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EthereumBlsVerifiersTest {
    private static final List<byte[]> PUBLIC_KEYS = List.of(filled(48, 0x11), filled(48, 0x22));
    private static final byte[] MESSAGE = filled(32, 0x33);
    private static final byte[] JUNK_SIGNATURE = filled(96, 0x61);

    @Mock
    private MessageFrame frame;

    @Test
    void failsClosedByDefault() {
        givenConfig(HederaTestConfigBuilder.create().getOrCreateConfig());

        final var verifier = EthereumBlsVerifiers.forFrame(frame);

        assertThat(verifier).isSameAs(FailClosedBlsSignatureVerifier.INSTANCE);
        assertThat(verifier.fastAggregateVerify(PUBLIC_KEYS, MESSAGE, JUNK_SIGNATURE))
                .isFalse();
    }

    @Test
    void failsClosedEvenWhenClprEnabled() {
        givenConfig(HederaTestConfigBuilder.create()
                .withValue("clpr.enabled", "true")
                .getOrCreateConfig());

        final var verifier = EthereumBlsVerifiers.forFrame(frame);

        assertThat(verifier.fastAggregateVerify(PUBLIC_KEYS, MESSAGE, JUNK_SIGNATURE))
                .isFalse();
        assertThat(verifier.fastAggregateVerify(PUBLIC_KEYS, MESSAGE, new byte[96]))
                .isFalse();
    }

    @Test
    void failsClosedWhenConfigUnavailable() {
        final Deque<MessageFrame> stack = new ArrayDeque<>();
        given(frame.getMessageFrameStack()).willReturn(stack);

        assertThat(EthereumBlsVerifiers.forFrame(frame)).isSameAs(FailClosedBlsSignatureVerifier.INSTANCE);
    }

    @Test
    void insecureStubOnlyWithExplicitOptIn() {
        givenConfig(HederaTestConfigBuilder.create()
                .withValue("clpr.allowInsecureBlsVerifier", "true")
                .getOrCreateConfig());

        assertThat(EthereumBlsVerifiers.forFrame(frame)).isSameAs(FakeBlsSignatureVerifier.INSTANCE);
    }

    private void givenConfig(final Configuration config) {
        final Deque<MessageFrame> stack = new ArrayDeque<>();
        given(frame.getMessageFrameStack()).willReturn(stack);
        given(frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE)).willReturn(config);
    }

    private static byte[] filled(final int length, final int value) {
        final var bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
