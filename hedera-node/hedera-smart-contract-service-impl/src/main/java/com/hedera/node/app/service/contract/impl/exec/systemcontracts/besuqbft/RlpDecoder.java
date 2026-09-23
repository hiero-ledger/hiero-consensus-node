// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RLP decoder for the Besu QBFT verifier.
 *
 * <p>Matches the encoder used on the sending side
 * ({@code org.hiero.clpr.relay.evm.QbftBundleConstructor.PayloadRlpEncoder} in the
 * {@code clpr-evm-endpoint} repo, which roughly mirrors
 * {@code org.hiero.clpr.relay.evm.AbiCodec#rlpBytes}/{@code rlpList}). The format follows the
 * canonical Ethereum RLP spec: a "string" carries raw bytes (single-byte short form, or
 * length-prefixed); a "list" carries a length-prefixed sequence of nested items.
 */
final class RlpDecoder {

    private RlpDecoder() {}

    /** Sealed view of a decoded RLP item: either bytes ("string") or a list of items. */
    sealed interface RlpItem permits RlpBytes, RlpList {
        default byte[] bytes() {
            if (this instanceof RlpBytes b) {
                return b.value();
            }
            throw new IllegalStateException("expected RLP bytes, got list");
        }

        default List<RlpItem> list() {
            if (this instanceof RlpList l) {
                return l.items();
            }
            throw new IllegalStateException("expected RLP list, got bytes");
        }
    }

    record RlpBytes(@NonNull byte[] value) implements RlpItem {}

    record RlpList(@NonNull List<RlpItem> items) implements RlpItem {}

    /**
     * Decode a single top-level RLP item from {@code data}. Throws if {@code data} contains
     * trailing bytes after the item.
     */
    @NonNull
    static RlpItem decode(@NonNull final byte[] data) {
        final Decoded d = readItem(data, 0);
        if (d.nextOffset != data.length) {
            throw new IllegalArgumentException("RLP: trailing bytes after top-level item (consumed=" + d.nextOffset
                    + ", total=" + data.length + ")");
        }
        return d.item;
    }

    private record Decoded(RlpItem item, int nextOffset) {}

    private static Decoded readItem(final byte[] data, final int offset) {
        if (offset >= data.length) {
            throw new IllegalArgumentException("RLP: out of bounds at offset " + offset);
        }
        final int prefix = data[offset] & 0xff;

        // Single byte in [0x00, 0x7f] — its own encoding.
        if (prefix <= 0x7f) {
            return new Decoded(new RlpBytes(new byte[] {data[offset]}), offset + 1);
        }
        // Short string: 0–55 bytes of payload.
        if (prefix <= 0xb7) {
            final int len = prefix - 0x80;
            final int start = offset + 1;
            checkRange(data, start, len);
            return new Decoded(new RlpBytes(slice(data, start, len)), start + len);
        }
        // Long string: payload length is itself encoded as 1–8 bytes.
        if (prefix <= 0xbf) {
            final int lenOfLen = prefix - 0xb7;
            final int lenStart = offset + 1;
            checkRange(data, lenStart, lenOfLen);
            final int len = readLength(data, lenStart, lenOfLen);
            final int start = lenStart + lenOfLen;
            checkRange(data, start, len);
            return new Decoded(new RlpBytes(slice(data, start, len)), start + len);
        }
        // Short list: 0–55 bytes of payload.
        if (prefix <= 0xf7) {
            final int len = prefix - 0xc0;
            final int start = offset + 1;
            checkRange(data, start, len);
            return new Decoded(new RlpList(readListItems(data, start, len)), start + len);
        }
        // Long list: payload length encoded as 1–8 bytes.
        final int lenOfLen = prefix - 0xf7;
        final int lenStart = offset + 1;
        checkRange(data, lenStart, lenOfLen);
        final int len = readLength(data, lenStart, lenOfLen);
        final int start = lenStart + lenOfLen;
        checkRange(data, start, len);
        return new Decoded(new RlpList(readListItems(data, start, len)), start + len);
    }

    private static List<RlpItem> readListItems(final byte[] data, final int start, final int payloadLen) {
        final List<RlpItem> items = new ArrayList<>();
        int cursor = start;
        final int end = start + payloadLen;
        while (cursor < end) {
            final Decoded child = readItem(data, cursor);
            items.add(child.item);
            cursor = child.nextOffset;
        }
        if (cursor != end) {
            throw new IllegalArgumentException("RLP: list payload length mismatch");
        }
        return items;
    }

    private static int readLength(final byte[] data, final int offset, final int lenOfLen) {
        if (lenOfLen <= 0 || lenOfLen > 4) {
            throw new IllegalArgumentException("RLP: unsupported length-of-length " + lenOfLen);
        }
        int len = 0;
        for (int i = 0; i < lenOfLen; i++) {
            len = (len << 8) | (data[offset + i] & 0xff);
            if (len < 0) {
                throw new IllegalArgumentException("RLP: payload length exceeds 2^31-1");
            }
        }
        return len;
    }

    private static byte[] slice(final byte[] data, final int start, final int len) {
        final byte[] out = new byte[len];
        System.arraycopy(data, start, out, 0, len);
        return out;
    }

    private static void checkRange(final byte[] data, final int offset, final int len) {
        if (offset < 0 || len < 0 || offset + len > data.length || offset + len < 0) {
            throw new IllegalArgumentException(
                    "RLP: range [" + offset + ", " + (offset + len) + ") out of bounds for length " + data.length);
        }
    }
}
