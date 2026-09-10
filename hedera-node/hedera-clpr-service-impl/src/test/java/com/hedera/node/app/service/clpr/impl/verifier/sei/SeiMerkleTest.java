// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeiMerkleTest {
    @Test
    void emptyRootIsSha256OfEmptyInput() {
        assertThat(SeiMerkle.root(List.of())).isEqualTo(SeiMerkle.sha256());
    }

    @Test
    void splitPointUsesLargestPowerOfTwoStrictlyLessThanCount() {
        assertThat(SeiMerkle.splitPoint(2)).isEqualTo(1);
        assertThat(SeiMerkle.splitPoint(3)).isEqualTo(2);
        assertThat(SeiMerkle.splitPoint(4)).isEqualTo(2);
        assertThat(SeiMerkle.splitPoint(5)).isEqualTo(4);
    }

    @Test
    void rootUsesLeafAndInnerDomainSeparation() {
        final byte[] leftLeaf = SeiMerkle.sha256(new byte[] {0}, new byte[] {1});
        final byte[] rightLeaf = SeiMerkle.sha256(new byte[] {0}, new byte[] {2});
        final byte[] expected = SeiMerkle.sha256(new byte[] {1}, leftLeaf, rightLeaf);

        assertThat(SeiMerkle.root(List.of(new byte[] {1}, new byte[] {2}))).isEqualTo(expected);
    }

    @Test
    void rejectsNullInputs() {
        assertThatNullPointerException().isThrownBy(() -> SeiMerkle.root(null));
    }

    @Test
    void wrapsMissingSha256AsIllegalState() {
        try (final var digest = mockStatic(MessageDigest.class)) {
            digest.when(() -> MessageDigest.getInstance(eq("SHA-256"))).thenThrow(new NoSuchAlgorithmException("nope"));

            assertThatIllegalStateException()
                    .isThrownBy(SeiMerkle::sha256)
                    .withMessage("SHA-256 unavailable")
                    .withCauseInstanceOf(NoSuchAlgorithmException.class);
        }
    }
}
