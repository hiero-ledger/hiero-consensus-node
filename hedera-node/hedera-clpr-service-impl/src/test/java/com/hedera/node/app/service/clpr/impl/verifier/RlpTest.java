// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RlpTest {
    private static final int MAX_SUPPORTED_DEPTH = 64;

    @Test
    void acceptsMaximumNestingDepth() {
        final var encoded = nestedLists(MAX_SUPPORTED_DEPTH);
        var item = Rlp.decodeOne(encoded);

        assertThat(item.rawBytes()).isEqualTo(encoded);
        for (int depth = 0; depth < MAX_SUPPORTED_DEPTH; depth++) {
            assertThat(item.isList()).isTrue();
            assertThat(item.children()).hasSize(1);
            item = item.children().getFirst();
        }
        assertThat(item.asBytes()).containsExactly((byte) 0x01);
    }

    @Test
    void rejectsWellFormedListsPastMaximumNestingDepth() {
        final var encoded = nestedLists(MAX_SUPPORTED_DEPTH + 1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Rlp.decodeOne(encoded))
                .withMessageContaining("nesting");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 55, 56, 256})
    void decodesChildrenEndingAtTheirEnclosingBoundary(final int length) {
        final var payload = new byte[length];
        Arrays.fill(payload, (byte) 0x80);
        final var child = Rlp.encodeList(List.of(Rlp.encodeBytes(payload)));
        final var encoded = Rlp.encodeList(List.of(child, new byte[] {0x01}));

        final var items = Rlp.decodeOne(encoded).children();

        assertThat(items).hasSize(2);
        assertThat(items.getFirst().rawBytes()).isEqualTo(child);
        assertThat(items.getFirst().children())
                .singleElement()
                .satisfies(item -> assertThat(item.asBytes()).isEqualTo(payload));
        assertThat(items.getLast().asBytes()).containsExactly((byte) 0x01);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "c182", // short string payload
                "c1b838", // long string length field
                "c2b838", // long string payload
                "c1c1", // short list payload
                "c1f838", // long list length field
                "c2f838", // long list payload
            })
    void rejectsChildrenThatExtendPastTheirEnclosingList(final String prefix) {
        // The child has enough bytes in the input, but they fall outside its enclosing list.
        final var encoded = HexFormat.of().parseHex(prefix + "01".repeat(56));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Rlp.decodeOne(encoded))
                .withMessageStartingWith("unexpected end while reading RLP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bb7fffffff", "bb80000000", "fb7fffffff", "fb80000000"})
    void rejectsDeclaredLengthsBeyondAvailableInput(final String hex) {
        final var encoded = HexFormat.of().parseHex(hex);

        assertThatIllegalArgumentException().isThrownBy(() -> Rlp.decodeOne(encoded));
    }

    private static byte[] nestedLists(final int depth) {
        byte[] encoded = {0x01};
        for (int i = 0; i < depth; i++) {
            encoded = Rlp.encodeList(List.of(encoded));
        }
        return encoded;
    }
}
