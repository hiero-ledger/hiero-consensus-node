// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RlpTest {

    /** {@code depth} lists nested inside each other, the innermost holding one byte string. */
    static byte[] nestedLists(final int depth) {
        byte[] item = Rlp.encodeBytes(new byte[] {0x01, 0x02});
        for (int i = 0; i < depth; i++) {
            item = Rlp.encodeList(List.of(item));
        }
        return item;
    }

    @Test
    void decodesListsNestedUpToTheMaximumDepth() {
        Rlp.Item item = Rlp.decodeOne(nestedLists(Rlp.MAX_DEPTH));
        for (int i = 0; i < Rlp.MAX_DEPTH; i++) {
            assertThat(item.isList()).isTrue();
            item = item.children().getFirst();
        }
        assertThat(item.asBytes()).containsExactly(0x01, 0x02);
    }

    @Test
    void rejectsListsNestedDeeperThanTheMaximumDepth() {
        assertThatThrownBy(() -> Rlp.decodeOne(nestedLists(Rlp.MAX_DEPTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nested more than " + Rlp.MAX_DEPTH + " levels deep");
    }

    @Test
    void rejectsAVeryDeeplyNestedInputWithAnOrdinaryException() {
        // Each level is a long-form list prefix (0xf9 + 2 length bytes) around the next one. Without a depth
        // cap this would overflow the stack; with it, decoding stops after MAX_DEPTH levels.
        final int levels = 50_000;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < levels; i++) {
            final int remaining = (levels - i - 1) * 3 + 1;
            out.write(0xf9);
            out.write((remaining >>> 8) & 0xff);
            out.write(remaining & 0xff);
        }
        out.write(0x80);
        final byte[] deep = out.toByteArray();

        assertThatThrownBy(() -> Rlp.decodeOne(deep))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("levels deep");
    }

    @Test
    void decodesALargeFlatListLinearly() {
        final List<byte[]> items = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            items.add(Rlp.encodeBytes(new byte[32]));
        }
        final Rlp.Item list = Rlp.decodeOne(Rlp.encodeList(items));
        assertThat(list.children()).hasSize(20_000);
        assertThat(list.children().getLast().asBytes()).hasSize(32);
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
        assertThatThrownBy(() -> item.children().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exposesRawEncodingContentAndNodeReferences() {
        final byte[] encodedString = Rlp.encodeBytes(new byte[] {0x7f});
        final byte[] encodedList = Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[] {0x11, 0x22}), encodedString));
        final Rlp.Item list = Rlp.decodeOne(encodedList);

        assertThat(list.rawBytes()).isEqualTo(encodedList);
        assertThat(list.bytes()).isNull();
        assertThat(list.asNodeReference()).isEqualTo(encodedList);
        assertThat(list.children().get(0).asNodeReference()).containsExactly(0x11, 0x22);
        assertThat(list.children().get(1).rawBytes()).containsExactly(0x7f);
        assertThat(list.children().get(1).asBytes()).containsExactly(0x7f);
        assertThatThrownBy(list::asBytes).isInstanceOf(IllegalArgumentException.class);
        assertThat(Rlp.decodeOne(Rlp.encodeBytes(new byte[0])).isEmptyString()).isTrue();
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

        assertThatThrownBy(() -> Rlp.decodeOne(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("unexpected end while reading RLP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bb7fffffff", "bb80000000", "fb7fffffff", "fb80000000"})
    void rejectsDeclaredLengthsBeyondAvailableInput(final String hex) {
        final var encoded = HexFormat.of().parseHex(hex);

        assertThatThrownBy(() -> Rlp.decodeOne(encoded)).isInstanceOf(IllegalArgumentException.class);
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

        assertThatThrownBy(() -> Rlp.decodeOne(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-canonical");
    }

    @Test
    void stillRejectsTruncatedAndTrailingInput() {
        assertThatThrownBy(() -> Rlp.decodeOne(new byte[] {(byte) 0x83, 0x01}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected end");
        assertThatThrownBy(() -> Rlp.decodeOne(new byte[] {0x01, 0x02}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing bytes");
    }
}
