// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Call-site evidence that the RLP recursion reported in issue #27747 reaches the real
 * {@link BesuQbftVerifier#verifyBundle(byte[], byte[])} decode guard, and that the depth cap added in
 * PR #27748 makes that guard fail closed.
 *
 * <p>The bundle payload is decoded by {@link Rlp#decodeOne(byte[])} inside {@code verifyBundle}, wrapped
 * in {@code catch (RuntimeException e)}. A {@link StackOverflowError} is an {@link Error}, not a
 * {@link RuntimeException}, so without the cap it escaped that guard (and every {@code catch(Exception)}
 * scope above it on the handle path). All tests below drive the production {@link Rlp} decoder.</p>
 */
class BesuQbftVerifierRlpRecursionTest {

    private static final BesuQbftVerifier SUBJECT =
            new BesuQbftVerifier(new BesuQbftVerifier.Config(null, null, 30_000L));

    /** A valid single-validator trust anchor so {@code verifyBundle} gets past the trust-anchor decode. */
    private static final byte[] VALID_TRUST_ANCHOR = BesuQbftVerifier.encodeValidatorSet(List.of(new byte[20]));

    /**
     * Nested-list bomb: {@code depth} bytes of {@code 0xc1} (each the RLP header of a one-byte list)
     * followed by a single {@code 0x00}. Not well-formed RLP beyond the first level, but each header is
     * entered (one decode recursion level per byte) before any length check can reject it.
     */
    private static byte[] nestedListBomb(final int depth) {
        final byte[] out = new byte[depth + 1];
        for (int i = 0; i < depth; i++) {
            out[i] = (byte) 0xc1;
        }
        out[depth] = 0x00;
        return out;
    }

    /** Well-formed RLP: an empty byte string wrapped in {@code depth} single-item lists. */
    private static byte[] validNesting(final int depth) {
        byte[] item = Rlp.encodeBytes(new byte[0]);
        for (int i = 0; i < depth; i++) {
            item = Rlp.encodeList(List.of(item));
        }
        return item;
    }

    @Test
    void fixRejectsDeeplyNestedBundleCleanlyAtRealCallSite() {
        // ~256 KB of nested single-item lists — far past any legitimate bundle nesting.
        final byte[] bomb = nestedListBomb(256 * 1024);

        final Throwable thrown = catchThrowable(() -> SUBJECT.verifyBundle(bomb, VALID_TRUST_ANCHOR));

        assertThat(thrown).isInstanceOf(ProofException.class);
        assertThat(thrown).isNotInstanceOf(Error.class);
        assertThat(thrown.getMessage()).contains("nesting exceeds maximum depth");
    }

    @Test
    void wellFormedNestingBeyondTheCapIsRejectedForDepthAtRealCallSite() {
        final byte[] tooDeep = validNesting(Rlp.MAX_RECURSION_DEPTH + 1);

        final Throwable thrown = catchThrowable(() -> SUBJECT.verifyBundle(tooDeep, VALID_TRUST_ANCHOR));

        assertThat(thrown).isInstanceOf(ProofException.class);
        assertThat(thrown.getMessage()).contains("nesting exceeds maximum depth");
    }

    @Test
    void nestingAtTheCapStillDecodesThroughTheRealCallSite() {
        // Well-formed RLP nested exactly at the cap must decode; verifyBundle then fails later on bundle
        // structure, never on depth or RLP framing.
        final byte[] atCap = validNesting(Rlp.MAX_RECURSION_DEPTH);
        assertThat(Rlp.decodeOne(atCap).isList()).isTrue();

        final Throwable thrown = catchThrowable(() -> SUBJECT.verifyBundle(atCap, VALID_TRUST_ANCHOR));

        assertThat(thrown).isInstanceOf(ProofException.class);
        assertThat(thrown.getMessage())
                .doesNotContain("nesting exceeds maximum depth")
                .doesNotContain("length mismatch");
    }

    @Test
    void productionDecoderFailureIsCaughtByTheRuntimeExceptionDecodeGuard() {
        // The exact catch(RuntimeException) shape used at every Rlp.decodeOne site in BesuQbftVerifier now
        // catches the over-deep input; before the cap the same input produced a StackOverflowError instead.
        final byte[] bomb = nestedListBomb(2_000_000);

        RuntimeException caught = null;
        try {
            Rlp.decodeOne(bomb);
        } catch (final RuntimeException e) {
            caught = e;
        }

        assertThat(caught).isInstanceOf(IllegalArgumentException.class);
        assertThat(caught.getMessage()).contains("nesting exceeds maximum depth");
    }

    @Test
    void stackOverflowErrorIsNotCoveredByRuntimeExceptionOrExceptionGuards() {
        // JVM semantics the fix relies on: StackOverflowError is neither a RuntimeException nor an Exception,
        // so the decode-site and handler-level guards could not have contained an uncapped recursion.
        assertThat(RuntimeException.class.isAssignableFrom(StackOverflowError.class))
                .isFalse();
        assertThat(Exception.class.isAssignableFrom(StackOverflowError.class)).isFalse();
    }
}
