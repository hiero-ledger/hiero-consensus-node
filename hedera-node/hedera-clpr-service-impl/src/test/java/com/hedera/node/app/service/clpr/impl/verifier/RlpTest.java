// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RlpTest {

    /** {@code depth} lists nested inside each other, the innermost holding one byte string. */
    private static byte[] nestedLists(final int depth) {
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
    void decodedItemsDoNotChangeWhenTheInputChanges() {
        final byte[] encoded = Rlp.encodeList(List.of(Rlp.encodeBytes(new byte[] {0x11, 0x22, 0x33})));
        final Rlp.Item list = Rlp.decodeOne(encoded);
        encoded[encoded.length - 1] = 0x00;

        assertThat(list.children().getFirst().asBytes()).containsExactly(0x11, 0x22, 0x33);
    }

    @Test
    void accessorsReturnCopies() {
        final Rlp.Item string = Rlp.decodeOne(Rlp.encodeBytes(new byte[] {0x11, 0x22}));
        string.asBytes()[0] = 0x00;
        string.rawBytes()[0] = 0x00;

        assertThat(string.asBytes()).containsExactly(0x11, 0x22);
        assertThat(string.rawBytes()).containsExactly(0x82, 0x11, 0x22);
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
