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
 */
public final class Rlp {
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

    public static Item decodeOne(byte[] encoded) {
        DecodeResult result = decode(Objects.requireNonNull(encoded, "encoded"), 0);
        if (result.nextOffset() != encoded.length) {
            final int consumed = result.nextOffset();
            final int trailing = encoded.length - consumed;
            final int dumpStart = Math.max(0, consumed - 8);
            final int dumpEnd = Math.min(encoded.length, consumed + 32);
            final StringBuilder window = new StringBuilder();
            for (int i = dumpStart; i < dumpEnd; i++) {
                if (i == consumed) window.append('|');
                window.append(String.format("%02x", encoded[i] & 0xff));
            }
            final StringBuilder headHex = new StringBuilder();
            for (int i = 0; i < Math.min(16, encoded.length); i++) {
                headHex.append(String.format("%02x", encoded[i] & 0xff));
            }
            throw new IllegalArgumentException(String.format(
                    "trailing bytes after RLP item (len=%d, consumed=%d, trailing=%d, head=%s, window-around-boundary=%s)",
                    encoded.length, consumed, trailing, headHex, window));
        }
        return result.item();
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
        if (len < 56) return new byte[] {(byte) (offset + len)};
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

    private static DecodeResult decode(byte[] input, int offset) {
        if (offset >= input.length) throw new IllegalArgumentException("RLP offset out of bounds");
        int prefix = input[offset] & 0xff;
        if (prefix < 0x80) {
            byte[] raw = new byte[] {input[offset]};
            return new DecodeResult(new Item(false, raw, raw, List.of()), offset + 1);
        }
        if (prefix <= 0xb7) {
            int len = prefix - 0x80;
            int start = offset + 1;
            checkRange(input, start, len, "RLP short string");
            return new DecodeResult(
                    new Item(
                            false,
                            Arrays.copyOfRange(input, offset, start + len),
                            Arrays.copyOfRange(input, start, start + len),
                            List.of()),
                    start + len);
        }
        if (prefix <= 0xbf) {
            int lenOfLen = prefix - 0xb7;
            int len = readRlpLength(input, offset + 1, lenOfLen);
            int start = offset + 1 + lenOfLen;
            checkRange(input, start, len, "RLP long string");
            return new DecodeResult(
                    new Item(
                            false,
                            Arrays.copyOfRange(input, offset, start + len),
                            Arrays.copyOfRange(input, start, start + len),
                            List.of()),
                    start + len);
        }
        if (prefix <= 0xf7) {
            int len = prefix - 0xc0;
            return decodeList(input, offset, offset + 1, len);
        }
        int lenOfLen = prefix - 0xf7;
        int len = readRlpLength(input, offset + 1, lenOfLen);
        int start = offset + 1 + lenOfLen;
        return decodeList(input, offset, start, len);
    }

    private static DecodeResult decodeList(byte[] input, int rawStart, int payloadStart, int payloadLen) {
        checkRange(input, payloadStart, payloadLen, "RLP list");
        int end = payloadStart + payloadLen;
        List<Item> children = new ArrayList<>();
        int p = payloadStart;
        while (p < end) {
            DecodeResult child = decode(input, p);
            children.add(child.item());
            p = child.nextOffset();
        }
        if (p != end) throw new IllegalArgumentException("RLP list length mismatch");
        return new DecodeResult(
                new Item(true, Arrays.copyOfRange(input, rawStart, end), null, List.copyOf(children)), end);
    }

    private static int readRlpLength(byte[] input, int offset, int lenOfLen) {
        if (lenOfLen <= 0 || lenOfLen > 4) throw new IllegalArgumentException("unsupported RLP length-of-length");
        checkRange(input, offset, lenOfLen, "RLP length");
        if (input[offset] == 0) throw new IllegalArgumentException("non-canonical RLP length");
        int len = 0;
        for (int i = 0; i < lenOfLen; i++) len = (len << 8) | (input[offset + i] & 0xff);
        return len;
    }

    private static void checkRange(byte[] data, int offset, int len, String name) {
        if (len < 0 || offset < 0 || offset > data.length || data.length - offset < len) {
            throw new IllegalArgumentException("unexpected end while reading " + name);
        }
    }

    private record DecodeResult(Item item, int nextOffset) {}

    public record Item(boolean isList, byte[] raw, byte[] bytes, List<Item> children) {
        public Item {
            raw = Objects.requireNonNull(raw, "raw").clone();
            bytes = bytes == null ? null : bytes.clone();
            children = List.copyOf(Objects.requireNonNull(children, "children"));
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }

        public boolean isEmptyString() {
            return !isList && bytes.length == 0;
        }

        public byte[] asBytes() {
            if (isList) throw new IllegalArgumentException("expected RLP string, got list");
            return bytes.clone();
        }

        public byte[] asNodeReference() {
            return isList ? raw.clone() : bytes.clone();
        }

        public byte[] rawBytes() {
            return raw.clone();
        }
    }
}
