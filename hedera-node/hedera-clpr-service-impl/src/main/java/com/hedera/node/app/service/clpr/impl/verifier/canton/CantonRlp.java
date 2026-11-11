// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Strict RLP reader that mirrors the reference's OpenZeppelin {@code RLP} library ({@code _decodeLength},
 * {@code readList}, {@code readBytes}, {@code readBytes32}): canonical lengths, no non-minimal single-byte
 * strings, exact list consumption. The general-purpose {@code Rlp} decoder in this module is more lenient, and
 * consensus verification must accept exactly what the reference accepts. Malformed input throws
 * {@code InvalidPayloadShape}.
 *
 * <p>The same algorithm is embedded privately in {@link CantonVerdictVerifier} (Task 6); this class is the
 * shared form used by the opening code.
 */
final class CantonRlp {

    /** A view of one RLP item inside a buffer (header included). */
    record Slice(byte[] buf, int off, int len) {}

    private record Header(int offset, int length, boolean isList) {}

    private CantonRlp() {}

    /** The whole of {@code b} as a single item. */
    static Slice of(@NonNull final byte[] b) {
        return new Slice(b, 0, b.length);
    }

    private static CantonProofException bad(final String why) {
        return CantonProofException.invalidPayloadShape("invalid RLP: " + why);
    }

    private static Header header(final Slice s) {
        if (s.len() == 0) {
            throw bad("empty item");
        }
        final int prefix = s.buf()[s.off()] & 0xff;
        if (prefix < 0x80) {
            return new Header(0, 1, false);
        }
        if (prefix <= 0xb7) {
            final int n = prefix - 0x80;
            if (s.len() <= n || (n == 1 && (s.buf()[s.off() + 1] & 0xff) < 0x80)) {
                throw bad("short string");
            }
            return new Header(1, n, false);
        }
        if (prefix <= 0xbf) {
            return longForm(s, prefix - 0xb7, false);
        }
        if (prefix <= 0xf7) {
            final int n = prefix - 0xc0;
            if (s.len() <= n) {
                throw bad("short list");
            }
            return new Header(1, n, true);
        }
        return longForm(s, prefix - 0xf7, true);
    }

    private static Header longForm(final Slice s, final int lengthLength, final boolean list) {
        if (s.len() <= lengthLength || s.buf()[s.off() + 1] == 0) {
            throw bad("long length");
        }
        long len = 0;
        for (int i = 0; i < lengthLength; ++i) {
            len = (len << 8) | (s.buf()[s.off() + 1 + i] & 0xff);
        }
        if (len <= 55 || len < 0 || len >= s.len() || s.len() <= lengthLength + len) {
            throw bad("long length value");
        }
        return new Header(lengthLength + 1, (int) len, list);
    }

    /** {@code RLP.readList}: the items of a list that spans exactly {@code item}; every header is validated. */
    static List<Slice> readList(final Slice item) {
        final Header h = header(item);
        if (!h.isList() || item.len() != h.offset() + h.length()) {
            throw bad("not an exact list");
        }
        final List<Slice> out = new ArrayList<>();
        int cur = h.offset();
        while (cur < item.len()) {
            final Slice rest = new Slice(item.buf(), item.off() + cur, item.len() - cur);
            final Header eh = header(rest);
            final int total = eh.offset() + eh.length();
            if (total > rest.len()) {
                throw bad("element overruns list");
            }
            out.add(new Slice(item.buf(), item.off() + cur, total));
            cur += total;
        }
        return out;
    }

    /** {@code RLP.readBytes}: the payload of a string item. */
    static byte[] readBytes(final Slice item) {
        final Header h = header(item);
        if (h.isList()) {
            throw bad("expected string");
        }
        return Arrays.copyOfRange(item.buf(), item.off() + h.offset(), item.off() + h.offset() + h.length());
    }

    /** {@code RLP.readBytes32}: a string of at most 32 bytes, left-padded with zeros (big-endian uint256). */
    static byte[] readBytes32(final Slice item) {
        if (item.len() > 33) {
            throw bad("bytes32 item too long");
        }
        final byte[] value = readBytes(item);
        final byte[] out = new byte[32];
        System.arraycopy(value, 0, out, 32 - value.length, value.length);
        return out;
    }
}
