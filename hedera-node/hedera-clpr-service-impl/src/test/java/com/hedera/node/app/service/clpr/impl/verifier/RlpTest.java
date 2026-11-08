// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static com.hedera.node.app.service.clpr.impl.verifier.Rlp.MAX_NESTING_DEPTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RlpTest {
    @Test
    void acceptsMaximumNestingDepth() {
        final var encoded = nestedLists(new byte[] {0x01}, MAX_NESTING_DEPTH);
        var item = Rlp.decodeOne(encoded);

        assertThat(item.rawBytes()).isEqualTo(encoded);
        for (int depth = 0; depth < MAX_NESTING_DEPTH; depth++) {
            assertThat(item.isList()).isTrue();
            assertThat(item.children()).hasSize(1);
            item = item.children().getFirst();
        }
        assertThat(item.asBytes()).containsExactly((byte) 0x01);
    }

    @Test
    void rejectsWellFormedListsPastMaximumNestingDepth() {
        final var encoded = nestedLists(new byte[] {0x01}, MAX_NESTING_DEPTH + 1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Rlp.decodeOne(encoded))
                .withMessageContaining("nesting");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 54, 55, 56, 256})
    void decodesChildrenEndingAtTheirEnclosingBoundary(final int length) {
        final var payload = new byte[length];
        Arrays.fill(payload, (byte) 0x80);
        final var child = Rlp.encodeList(List.of(Rlp.encodeBytes(payload)));
        final var encoded = Rlp.encodeList(List.of(child, new byte[] {0x7f}));

        final var items = Rlp.decodeOne(encoded).children();

        assertThat(items).hasSize(2);
        assertThat(items.getFirst().rawBytes()).isEqualTo(child);
        assertThat(items.getFirst().children())
                .singleElement()
                .satisfies(item -> assertThat(item.asBytes()).isEqualTo(payload));
        assertThat(items.getLast().asBytes()).containsExactly((byte) 0x7f);
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

    @ParameterizedTest
    @CsvSource({
        "8100, 0",
        "817f, 0",
        "b837, 55", // long string form for a short payload
        "f837, 55", // long list form for a short payload
        "b90038, 56", // leading zero in the string length
        "f90038, 56" // leading zero in the list length
    })
    void rejectsNonCanonicalEncodings(final String prefix, final int payloadLength) {
        final var encoded = HexFormat.of().parseHex(prefix + "80".repeat(payloadLength));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Rlp.decodeOne(encoded))
                .withMessageContaining("non-canonical");
    }

    @Test
    void decodedItemsOwnTheirBytes() {
        final var encoded = HexFormat.of().parseHex("c28180");
        final var item = Rlp.decodeOne(encoded);
        Arrays.fill(encoded, (byte) 0);
        final var child = item.children().getFirst();

        Arrays.fill(item.raw(), (byte) 0);
        Arrays.fill(item.rawBytes(), (byte) 0);
        Arrays.fill(item.asNodeReference(), (byte) 0);
        Arrays.fill(child.raw(), (byte) 0);
        Arrays.fill(child.rawBytes(), (byte) 0);
        Arrays.fill(child.bytes(), (byte) 0);
        Arrays.fill(child.asBytes(), (byte) 0);
        Arrays.fill(child.asNodeReference(), (byte) 0);

        assertThat(item.raw()).containsExactly(0xc2, 0x81, 0x80);
        assertThat(item.rawBytes()).isEqualTo(item.raw());
        assertThat(item.asNodeReference()).isEqualTo(item.raw());
        assertThat(item.bytes()).isNull();
        assertThat(child.raw()).containsExactly(0x81, 0x80);
        assertThat(child.rawBytes()).isEqualTo(child.raw());
        assertThat(child.bytes()).containsExactly(0x80);
        assertThat(child.asBytes()).isEqualTo(child.bytes());
        assertThat(child.asNodeReference()).isEqualTo(child.bytes());
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> item.children().clear());
    }

    @Test
    void constructedItemsCopyTheirInputs() {
        final var raw = HexFormat.of().parseHex("8180");
        final byte[] bytes = {(byte) 0x80};
        final var child = new Rlp.Item(false, raw, bytes, List.of());
        final var children = new ArrayList<>(List.of(child));
        final var item = new Rlp.Item(true, HexFormat.of().parseHex("c28180"), null, children);

        Arrays.fill(raw, (byte) 0);
        Arrays.fill(bytes, (byte) 0);
        children.clear();

        assertThat(child.raw()).containsExactly(0x81, 0x80);
        assertThat(child.bytes()).containsExactly(0x80);
        assertThat(item.children()).containsExactly(child);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> item.children().clear());
    }

    static byte[] nestedLists(final byte[] leaf, final int depth) {
        byte[] encoded = leaf;
        for (int i = 0; i < depth; i++) {
            encoded = Rlp.encodeList(List.of(encoded));
        }
        return encoded;
    }
}
