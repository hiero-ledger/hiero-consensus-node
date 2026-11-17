// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Minimal Ethereum RLP encoder/decoder used by execution header and Merkle Patricia proof code.
 *
 * <p>The implementation intentionally exposes only the operations needed by this repository:
 * byte strings, unsigned integers, lists of already-encoded items, trie storage values, and decoded
 * item accessors.</p>
 *
 * <p>Decoding is bounded for untrusted input: lists may nest at most {@value #MAX_DEPTH} levels deep, and
 * decoded items are views into one copy of the input (an offset and a length each), so decoding costs time
 * and memory linear in the input size whatever its shape.</p>
 */
public final class Rlp {
    /**
     * Maximum list nesting depth accepted by {@link #decodeOne}. The payloads decoded in this repository
     * (block headers, Merkle Patricia proofs, bundle and config payloads) nest only a few levels deep; the cap
     * turns a deeply nested input into an ordinary decoding error instead of a stack overflow.
     */
    public static final int MAX_DEPTH = 8;

    private static final int MIN_LONG_PAYLOAD_LENGTH = 56;

    private Rlp() {}

    public static byte[] encodeUint(long value) {
        if (value < 0) throw new IllegalArgumentException("RLP unsigned integer is negative");
        if (value == 0) return encodeBytes(new byte[0]);
        byte[] tmp = new byte[8];
        long v = value;
        for (int i = 7; i >= 0; i--) {
            tmp[i] = (byte) (v & 0xff);
            v >>>= 8;
        }
        int first = 0;
        while (first < tmp.length && tmp[first] == 0) first++;
        return encodeBytes(Arrays.copyOfRange(tmp, first, tmp.length));
    }

    public static byte[] encodeUint(BigInteger value) {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0) throw new IllegalArgumentException("RLP unsigned integer is negative");
        if (value.signum() == 0) return encodeBytes(new byte[0]);
        byte[] raw = value.toByteArray();
        if (raw.length > 1 && raw[0] == 0) raw = Arrays.copyOfRange(raw, 1, raw.length);
        return encodeBytes(raw);
    }

    public static byte[] encodeBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 1 && (bytes[0] & 0xff) < 0x80) return bytes.clone();
        byte[] prefix = encodeLength(bytes.length, 0x80);
        byte[] out = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(bytes, 0, out, prefix.length, bytes.length);
        return out;
    }

    public static byte[] encodeList(List<byte[]> encodedItems) {
        Objects.requireNonNull(encodedItems, "encodedItems");
        int payloadLen = 0;
        for (byte[] item : encodedItems) payloadLen += Objects.requireNonNull(item, "encoded item").length;
        byte[] prefix = encodeLength(payloadLen, 0xc0);
        byte[] out = new byte[prefix.length + payloadLen];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        int p = prefix.length;
        for (byte[] item : encodedItems) {
            System.arraycopy(item, 0, out, p, item.length);
            p += item.length;
        }
        return out;
    }

    /**
     * Decodes exactly one RLP item spanning all of {@code encoded}.
     *
     * @throws IllegalArgumentException if the input is malformed, has trailing bytes, or nests lists more than
     *     {@link #MAX_DEPTH} levels deep
     */
    public static Item decodeOne(byte[] encoded) {
        // All items share this private snapshot and copy their bytes on access.
        final byte[] input = Objects.requireNonNull(encoded, "encoded").clone();
        final Item item = decode(input, 0, input.length, 0);
        if (item.rawEnd != input.length) {
            final int consumed = item.rawEnd;
            final int trailing = input.length - consumed;
            final int dumpStart = Math.max(0, consumed - 8);
            final int dumpEnd = consumed + Math.min(trailing, 32);
            final StringBuilder window = new StringBuilder();
            for (int i = dumpStart; i < dumpEnd; i++) {
                if (i == consumed) window.append('|');
                window.append(String.format("%02x", input[i] & 0xff));
            }
            final StringBuilder headHex = new StringBuilder();
            for (int i = 0; i < Math.min(16, input.length); i++) {
                headHex.append(String.format("%02x", input[i] & 0xff));
            }
            throw new IllegalArgumentException(String.format(
                    "trailing bytes after RLP item (len=%d, consumed=%d, trailing=%d, head=%s, window-around-boundary=%s)",
                    input.length, consumed, trailing, headHex, window));
        }
        return item;
    }

    public static byte[] decodeTrieStorageValueAsBytes32(byte[] storageValueRlp) {
        Item item = decodeOne(storageValueRlp);
        byte[] rawValue = item.asBytes();
        if (rawValue.length > 32) throw new IllegalArgumentException("storage value exceeds 32 bytes");
        byte[] out = new byte[32];
        System.arraycopy(rawValue, 0, out, 32 - rawValue.length, rawValue.length);
        return out;
    }

    private static byte[] encodeLength(int len, int offset) {
        if (len < MIN_LONG_PAYLOAD_LENGTH) return new byte[] {(byte) (offset + len)};
        int tmp = len;
        int lenOfLen = 0;
        while (tmp > 0) {
            lenOfLen++;
            tmp >>>= 8;
        }
        byte[] out = new byte[1 + lenOfLen];
        out[0] = (byte) (offset + 55 + lenOfLen);
        for (int i = lenOfLen; i > 0; i--) {
            out[i] = (byte) (len & 0xff);
            len >>>= 8;
        }
        return out;
    }

    private static Item decode(byte[] input, int offset, int limit, int depth) {
        if (offset >= limit) throw new IllegalArgumentException("RLP offset out of bounds");
        int prefix = input[offset] & 0xff;
        if (prefix < 0x80) {
            return Item.string(input, offset, offset, offset + 1);
        }
        if (prefix <= 0xb7) {
            int len = prefix - 0x80;
            int start = offset + 1;
            checkRange(start, len, limit, "RLP short string");
            if (len == 1 && (input[start] & 0xff) < 0x80) {
                throw new IllegalArgumentException("non-canonical RLP single byte");
            }
            return Item.string(input, offset, start, start + len);
        }
        if (prefix <= 0xbf) {
            int lenOfLen = prefix - 0xb7;
            int len = readRlpLength(input, offset + 1, lenOfLen, limit);
            int start = offset + 1 + lenOfLen;
            checkRange(start, len, limit, "RLP long string");
            return Item.string(input, offset, start, start + len);
        }
        if (prefix <= 0xf7) {
            int len = prefix - 0xc0;
            return decodeList(input, offset, offset + 1, len, limit, depth);
        }
        int lenOfLen = prefix - 0xf7;
        int len = readRlpLength(input, offset + 1, lenOfLen, limit);
        int start = offset + 1 + lenOfLen;
        return decodeList(input, offset, start, len, limit, depth);
    }

    private static Item decodeList(byte[] input, int rawStart, int payloadStart, int payloadLen, int limit, int depth) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("RLP lists nested more than " + MAX_DEPTH + " levels deep");
        }
        checkRange(payloadStart, payloadLen, limit, "RLP list");
        int end = payloadStart + payloadLen;
        List<Item> children = new ArrayList<>();
        int p = payloadStart;
        while (p < end) {
            Item child = decode(input, p, end, depth + 1);
            children.add(child);
            p = child.rawEnd;
        }
        return Item.list(input, rawStart, end, children);
    }

    private static int readRlpLength(byte[] input, int offset, int lenOfLen, int limit) {
        if (lenOfLen > Integer.BYTES) throw new IllegalArgumentException("unsupported RLP length-of-length");
        checkRange(offset, lenOfLen, limit, "RLP length");
        if (input[offset] == 0) throw new IllegalArgumentException("non-canonical RLP length");
        long len = 0;
        for (int i = 0; i < lenOfLen; i++) len = (len << 8) | (input[offset + i] & 0xff);
        if (len < MIN_LONG_PAYLOAD_LENGTH) throw new IllegalArgumentException("non-canonical RLP length");
        if (len > Integer.MAX_VALUE) throw new IllegalArgumentException("RLP length exceeds integer range");
        return (int) len;
    }

    private static void checkRange(int offset, int len, int limit, String name) {
        if (len > limit - offset) {
            throw new IllegalArgumentException("unexpected end while reading " + name);
        }
    }

    /**
     * A decoded RLP item: a byte string or a list of items. It is a view into the decoded input (which
     * {@link #decodeOne} copies once), so creating it copies nothing; the accessors return fresh copies.
     */
    public static final class Item {
        private final byte[] input;
        private final boolean isList;
        private final int rawStart;
        private final int rawEnd;
        /** Start of a string's content; unused for lists. */
        private final int bytesStart;

        private final List<Item> children;

        private Item(
                final byte[] input,
                final boolean isList,
                final int rawStart,
                final int rawEnd,
                final int bytesStart,
                final List<Item> children) {
            this.input = input;
            this.isList = isList;
            this.rawStart = rawStart;
            this.rawEnd = rawEnd;
            this.bytesStart = bytesStart;
            this.children = children;
        }

        private static Item string(final byte[] input, final int rawStart, final int bytesStart, final int end) {
            return new Item(input, false, rawStart, end, bytesStart, List.of());
        }

        private static Item list(final byte[] input, final int rawStart, final int end, final List<Item> children) {
            return new Item(input, true, rawStart, end, end, List.copyOf(children));
        }

        public boolean isList() {
            return isList;
        }

        /** The item's children; empty for a byte string. */
        public List<Item> children() {
            return children;
        }

        /** The item's full encoding, prefix included. */
        public byte[] raw() {
            return Arrays.copyOfRange(input, rawStart, rawEnd);
        }

        /** A byte string's content, or {@code null} for a list. */
        public byte[] bytes() {
            return isList ? null : Arrays.copyOfRange(input, bytesStart, rawEnd);
        }

        public boolean isEmptyString() {
            return !isList && bytesStart == rawEnd;
        }

        public byte[] asBytes() {
            if (isList) throw new IllegalArgumentException("expected RLP string, got list");
            return Arrays.copyOfRange(input, bytesStart, rawEnd);
        }

        public byte[] asNodeReference() {
            return isList ? raw() : asBytes();
        }

        public byte[] rawBytes() {
            return raw();
        }

        @Override
        public String toString() {
            return "Rlp.Item["
                    + (isList ? "list, children=" + children.size() : "string, length=" + (rawEnd - bytesStart)) + "]";
        }
    }
}
