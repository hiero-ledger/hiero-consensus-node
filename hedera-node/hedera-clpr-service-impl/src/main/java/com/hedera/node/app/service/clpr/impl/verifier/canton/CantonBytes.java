// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import com.hedera.node.app.hapi.utils.CommonUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Byte-level helpers shared by the Canton verifier classes: SHA-256, concatenation, and big-endian integers. */
final class CantonBytes {

    private CantonBytes() {}

    /** The SHA-256 digest of {@code message}. */
    @NonNull
    static byte[] sha256(@NonNull final byte[] message) {
        return CommonUtils.sha256DigestOrThrow().digest(message);
    }

    /** The concatenation of {@code parts}, in order. */
    @NonNull
    static byte[] concat(@NonNull final byte[]... parts) {
        int n = 0;
        for (final byte[] p : parts) {
            n += p.length;
        }
        final byte[] out = new byte[n];
        int at = 0;
        for (final byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    /** {@code v} as 4 big-endian bytes. */
    @NonNull
    static byte[] be32(final int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** The unsigned 32-bit big-endian value at {@code off} (the reference's {@code _u32}). */
    static long u32(@NonNull final byte[] b, final int off) {
        return ((long) Byte.toUnsignedInt(b[off]) << 24)
                | ((long) Byte.toUnsignedInt(b[off + 1]) << 16)
                | ((long) Byte.toUnsignedInt(b[off + 2]) << 8)
                | Byte.toUnsignedInt(b[off + 3]);
    }

    /** The 32 bytes at {@code off} (the reference's {@code _word}). */
    @NonNull
    static byte[] word(@NonNull final byte[] b, final int off) {
        return Arrays.copyOfRange(b, off, off + 32);
    }

    static byte[] sha256(final byte[]... parts) throws Exception {
        final var md = MessageDigest.getInstance("SHA-256");
        for (final byte[] p : parts) {
            md.update(p);
        }
        return md.digest();
    }

    static byte[] repeat(final byte b, final int n) {
        final byte[] out = new byte[n];
        Arrays.fill(out, b);
        return out;
    }

    static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] to32(final BigInteger v) {
        final byte[] raw = v.toByteArray();
        final byte[] out = new byte[32];
        if (raw.length <= 32) {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        } else {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        }
        return out;
    }

    static byte[] filled(final int b) {
        final byte[] out = new byte[32];
        Arrays.fill(out, (byte) b);
        return out;
    }

    /** A minimal growable big-endian byte writer, matching {@code abi.encodePacked} widths. */
    static final class Writer {
        private byte[] buf = new byte[64];
        private int len = 0;

        private void ensure(final int extra) {
            if (len + extra > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
            }
        }

        void u8(final int v) {
            ensure(1);
            buf[len++] = (byte) v;
        }

        void u32(final long v) {
            ensure(4);
            buf[len++] = (byte) (v >>> 24);
            buf[len++] = (byte) (v >>> 16);
            buf[len++] = (byte) (v >>> 8);
            buf[len++] = (byte) v;
        }

        void bytes(final byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, buf, len, b.length);
            len += b.length;
        }

        byte[] toBytes() {
            return Arrays.copyOf(buf, len);
        }
    }
}
