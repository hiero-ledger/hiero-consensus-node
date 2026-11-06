// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RlpTest {

    /**
     * A buffer of nested single-item RLP lists (each byte {@code 0xc1} opens a "list of payload
     * length 1") drives one recursion level per byte. Before the depth cap this overflowed the JVM
     * thread stack and threw {@link StackOverflowError} — an {@link Error} that escaped the
     * {@code catch (RuntimeException)} guards at the decode sites. It must now be rejected cleanly as
     * a caught {@link IllegalArgumentException} so a malformed bundle fails closed instead of taking
     * down the handling thread.
     */
    @Test
    void deeplyNestedListsAreRejectedWithoutStackOverflow() {
        final byte[] bomb = new byte[8192];
        for (int i = 0; i < bomb.length - 1; i++) {
            bomb[i] = (byte) 0xc1;
        }
        bomb[bomb.length - 1] = 0x00;

        assertThatThrownBy(() -> Rlp.decodeOne(bomb))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum depth");
    }

    /** Nesting exactly at the limit still decodes successfully. */
    @Test
    void nestingAtTheDepthLimitStillDecodes() {
        byte[] item = Rlp.encodeBytes(new byte[0]);
        for (int i = 0; i < Rlp.MAX_RECURSION_DEPTH; i++) {
            item = Rlp.encodeList(List.of(item));
        }
        final byte[] encoded = item;
        Rlp.Item decoded = Rlp.decodeOne(encoded);
        assertThat(decoded.isList()).isTrue();
    }

    /** Nesting one level beyond the limit fails closed with a caught exception. */
    @Test
    void nestingBeyondTheDepthLimitIsRejected() {
        byte[] item = Rlp.encodeBytes(new byte[0]);
        for (int i = 0; i < Rlp.MAX_RECURSION_DEPTH + 5; i++) {
            item = Rlp.encodeList(List.of(item));
        }
        final byte[] encoded = item;
        assertThatThrownBy(() -> Rlp.decodeOne(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum depth");
    }

    /** A normal shallow structure is unaffected by the depth cap. */
    @Test
    void shallowStructureDecodesNormally() {
        byte[] encoded = Rlp.encodeList(
                List.of(Rlp.encodeUint(1L), Rlp.encodeUint(2L), Rlp.encodeBytes(new byte[] {(byte) 0xab})));
        Rlp.Item decoded = Rlp.decodeOne(encoded);
        assertThat(decoded.isList()).isTrue();
        assertThat(decoded.children()).hasSize(3);
    }
}
