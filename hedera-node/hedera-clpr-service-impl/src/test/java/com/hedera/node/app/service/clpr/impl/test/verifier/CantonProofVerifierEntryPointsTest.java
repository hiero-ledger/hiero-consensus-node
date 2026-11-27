// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofException;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Canton verifier's entry points as the Canton verifier system contract ({@code 0x173}) uses them: static
 * methods reached from outside the {@code verifier.canton} package. The proof checks themselves are covered by the
 * tests in that package.
 */
class CantonProofVerifierEntryPointsTest {
    /** An RLP empty list: well-formed RLP, but not a proof. */
    private static final byte[] EMPTY_RLP_LIST = {(byte) 0xc0};

    private static final byte[] CHANNEL_ID = new byte[32];

    @Test
    @DisplayName("the Canton verifier is not a ClprVerifier: it is reached through EvmClprVerifier and 0x173")
    void isNotAClprVerifier() {
        assertThat(ClprVerifier.class.isAssignableFrom(CantonProofVerifier.class))
                .isFalse();
    }

    @Test
    @DisplayName("verifyConfig is callable from another package and rejects a malformed proof")
    void verifyConfigIsAPublicEntryPoint() {
        assertThatThrownBy(() -> CantonProofVerifier.verifyConfig(
                        EMPTY_RLP_LIST, CHANNEL_ID, new byte[0], "eip155:298".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo("InvalidPayloadShape"));
    }

    @Test
    @DisplayName("verifyBundle is callable from another package and rejects a malformed proof")
    void verifyBundleIsAPublicEntryPoint() {
        assertThatThrownBy(() -> CantonProofVerifier.verifyBundle(
                        EMPTY_RLP_LIST, new byte[0], CHANNEL_ID, (channelId, messageId) -> null))
                .isInstanceOf(CantonProofException.class)
                .satisfies(
                        e -> assertThat(((CantonProofException) e).errorName()).isEqualTo("InvalidPayloadShape"));
    }
}
