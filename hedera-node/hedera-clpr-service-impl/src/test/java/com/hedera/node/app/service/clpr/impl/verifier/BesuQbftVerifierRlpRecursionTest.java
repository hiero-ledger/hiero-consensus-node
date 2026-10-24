// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Call-site evidence that the unbounded RLP recursion reported in issue #27747 reaches the real
 * {@link BesuQbftVerifier#verifyBundle(byte[], byte[])} decode guard, and that the depth cap added in
 * PR #27748 makes that guard fail closed.
 *
 * <p>The bundle payload is decoded by {@link Rlp#decodeOne(byte[])} inside {@code verifyBundle}, wrapped
 * in {@code catch (RuntimeException e)}. A {@link StackOverflowError} is an {@link Error}, not a
 * {@link RuntimeException}, so before the fix it escaped that guard (and every {@code catch(Exception)}
 * scope above it on the handle path). These tests pin both facts down.</p>
 */
class BesuQbftVerifierRlpRecursionTest {

    private static final BesuQbftVerifier SUBJECT =
            new BesuQbftVerifier(new BesuQbftVerifier.Config(null, null, 30_000L));

    /** A valid single-validator trust anchor so {@code verifyBundle} gets past the trust-anchor decode. */
    private static final byte[] VALID_TRUST_ANCHOR = BesuQbftVerifier.encodeValidatorSet(List.of(new byte[20]));

    /**
     * Builds the nested-list bomb as a <em>class of input</em>: {@code depth} bytes of {@code 0xc1}
     * (each the RLP header of a one-item list) followed by a single {@code 0x00} terminal item. This
     * drives one decode recursion level per byte with no weaponized bundle content.
     */
    private static byte[] nestedListBomb(final int depth) {
        final byte[] out = new byte[depth + 1];
        for (int i = 0; i < depth; i++) {
            out[i] = (byte) 0xc1;
        }
        out[depth] = 0x00;
        return out;
    }

    @Test
    void fixRejectsDeeplyNestedBundleCleanlyAtRealCallSite() {
        // ~256 KB of nested single-item lists — far past any legitimate bundle nesting.
        final byte[] bomb = nestedListBomb(256 * 1024);

        final Throwable thrown = catchThrowable(() -> SUBJECT.verifyBundle(bomb, VALID_TRUST_ANCHOR));

        // With the depth cap, the real call site fails closed: the IllegalArgumentException thrown by
        // Rlp is a RuntimeException, so verifyBundle's catch(RuntimeException) converts it to a
        // ProofException. Crucially it is NOT a StackOverflowError / Error any more.
        assertThat(thrown).isInstanceOf(ProofException.class);
        assertThat(thrown).isNotInstanceOf(StackOverflowError.class);
        assertThat(thrown).isNotInstanceOf(Error.class);
        assertThat(thrown.getMessage()).contains("nesting exceeds maximum depth");
    }

    @Test
    void nestingAtTheCapStillDecodesThroughTheRealCallSite() {
        // A list nested exactly at the cap is structurally valid RLP; it must not trip the depth guard,
        // so verifyBundle proceeds past the decode and fails later on bundle structure, never on depth.
        final byte[] atCap = nestedListBomb(Rlp.MAX_RECURSION_DEPTH);

        final Throwable thrown = catchThrowable(() -> SUBJECT.verifyBundle(atCap, VALID_TRUST_ANCHOR));

        assertThat(thrown).isInstanceOf(ProofException.class);
        assertThat(thrown.getMessage()).doesNotContain("nesting exceeds maximum depth");
    }

    /**
     * Demonstrates the escape semantics that made this a network-halt bug before the cap: the pre-fix
     * decode recursion (reproduced verbatim below, with no depth guard) throws a StackOverflowError on
     * the same nested-list input, and that Error slips straight through the exact
     * {@code catch (RuntimeException e)} shape used at every RLP decode site in
     * {@link BesuQbftVerifier} (verifyBundle, verifyConfigPayload, decodeValidatorSet, ...).
     */
    @Test
    void stackOverflowErrorEscapesTheRuntimeExceptionDecodeGuard() {
        final byte[] bomb = nestedListBomb(2_000_000);

        final Throwable escaped = catchThrowable(() -> {
            try {
                // Same guard the verifier wraps every Rlp.decodeOne call in.
                preFixUnboundedDecode(bomb, 0);
            } catch (final RuntimeException e) {
                throw new AssertionError("decode-site catch(RuntimeException) should NOT have caught this", e);
            }
        });

        assertThat(escaped).isInstanceOf(StackOverflowError.class);
        assertThat(escaped).isInstanceOf(Error.class);
        assertThat(escaped).isNotInstanceOf(RuntimeException.class);
    }

    @Test
    void stackOverflowErrorIsNotCaughtByHandlerExceptionScopes() {
        // The handler / dispatch / workflow layers guard with catch(Exception). Exception does not cover
        // Error, so the same StackOverflowError escapes those scopes too — which is why one bundle could
        // halt the node once clpr.enabled is set.
        final byte[] bomb = nestedListBomb(2_000_000);

        final Throwable escaped = catchThrowable(() -> {
            try {
                preFixUnboundedDecode(bomb, 0);
            } catch (final Exception e) {
                throw new AssertionError("handler catch(Exception) should NOT have caught this", e);
            }
        });

        assertThat(escaped).isInstanceOf(StackOverflowError.class);
    }

    /**
     * Verbatim shape of the pre-fix {@code Rlp.decode}/{@code decodeList} list recursion: one stack frame
     * per nesting level, no depth cap. Only the branches exercised by the nested-list bomb (single byte +
     * short list) are reproduced; this is exactly the code path PR #27748 adds the {@code MAX_RECURSION_DEPTH}
     * guard to.
     */
    private static int preFixUnboundedDecode(final byte[] in, final int off) {
        final int prefix = in[off] & 0xff;
        if (prefix < 0x80) {
            return off + 1; // single byte item
        }
        if (prefix >= 0xc0 && prefix <= 0xf7) {
            final int len = prefix - 0xc0;
            int p = off + 1;
            final int end = p + len;
            while (p < end) {
                p = preFixUnboundedDecode(in, p); // unbounded recursion per child
            }
            return end;
        }
        throw new IllegalArgumentException("unexpected prefix in bomb fixture: " + prefix);
    }
}
