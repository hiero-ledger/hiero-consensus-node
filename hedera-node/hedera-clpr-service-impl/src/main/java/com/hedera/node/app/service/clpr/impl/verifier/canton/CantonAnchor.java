// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * The trust anchor of a Canton channel.
 *
 * <p>Wire format is Solidity's {@code abi.encode(string, uint64, uint32, bytes32, bytes32, bytes32, string,
 * uint64, uint32, string)}: a 10-word head (static values inline, each {@code string} a word holding its
 * offset into the tail) followed by the string tails (32-byte length, then data right-padded to 32 bytes).
 * {@code uint64}/{@code uint32} fields are held in {@code long}/{@code int} with unsigned semantics.
 */
public final class CantonAnchor {
    private static final int WORD = 32;
    private static final int HEAD_WORDS = 10;
    /** Head (10 words) + three string lengths + one word of synchronizerId; mirrors {@code MIN_LENGTH}. */
    static final int MIN_LENGTH = 13 * WORD;

    private CantonAnchor() {}

    /**
     * Decoded anchor. {@code lastRequestId}/{@code upgradeTime} are unsigned 64-bit, {@code mediatorGroup}/
     * {@code lsuSerial} unsigned 32-bit (use {@code Long/Integer.toUnsignedString} / {@code compareUnsigned}).
     */
    public record Anchor(
            @NonNull String synchronizerId,
            long lastRequestId,
            int mediatorGroup,
            @NonNull byte[] synchronizerNamespace,
            @NonNull byte[] ownersHash,
            @NonNull byte[] mediatorsHash,
            @NonNull String successorSynchronizerId,
            long upgradeTime,
            int lsuSerial,
            @NonNull String app) {}

    /** Decodes {@code abi.decode(trustAnchor, (string, uint64, uint32, bytes32, bytes32, bytes32, string, uint64, uint32, string))}. */
    @NonNull
    public static Anchor decode(@NonNull final byte[] trustAnchor) {
        Objects.requireNonNull(trustAnchor, "trustAnchor");
        if (trustAnchor.length < MIN_LENGTH) {
            throw CantonProofException.invalidTrustAnchor();
        }
        return new Anchor(
                string(trustAnchor, 0),
                uint(trustAnchor, 1, 64).longValue(),
                uint(trustAnchor, 2, 32).intValue(),
                word(trustAnchor, 3),
                word(trustAnchor, 4),
                word(trustAnchor, 5),
                string(trustAnchor, 6),
                uint(trustAnchor, 7, 64).longValue(),
                uint(trustAnchor, 8, 32).intValue(),
                string(trustAnchor, 9));
    }

    /** Encodes as Solidity {@code abi.encode} of the ten fields. */
    @NonNull
    public static byte[] encode(@NonNull final Anchor a) {
        Objects.requireNonNull(a, "anchor");
        final byte[][] strings = {
            a.synchronizerId().getBytes(StandardCharsets.UTF_8),
            a.successorSynchronizerId().getBytes(StandardCharsets.UTF_8),
            a.app().getBytes(StandardCharsets.UTF_8)
        };
        final var out = new ByteArrayOutputStream();
        long tailOffset = (long) HEAD_WORDS * WORD;
        final long[] offsets = new long[3];
        for (int i = 0; i < 3; i++) {
            offsets[i] = tailOffset;
            tailOffset += WORD + padded(strings[i].length);
        }
        writeWord(out, offsets[0]);
        writeWord(out, Long.toUnsignedString(a.lastRequestId()));
        writeWord(out, Integer.toUnsignedLong(a.mediatorGroup()));
        out.writeBytes(fixed32(a.synchronizerNamespace()));
        out.writeBytes(fixed32(a.ownersHash()));
        out.writeBytes(fixed32(a.mediatorsHash()));
        writeWord(out, offsets[1]);
        writeWord(out, Long.toUnsignedString(a.upgradeTime()));
        writeWord(out, Integer.toUnsignedLong(a.lsuSerial()));
        writeWord(out, offsets[2]);
        for (final byte[] s : strings) {
            writeWord(out, s.length);
            out.writeBytes(s);
            out.writeBytes(new byte[padded(s.length) - s.length]);
        }
        return out.toByteArray();
    }

    private static int padded(final int len) {
        return (len + WORD - 1) / WORD * WORD;
    }

    private static byte[] fixed32(final byte[] v) {
        Objects.requireNonNull(v, "bytes32");
        if (v.length != WORD) {
            throw new IllegalArgumentException("bytes32 field must be 32 bytes, was " + v.length);
        }
        return v;
    }

    // Package-private (not private) only so the same-package test can reach the guard directly.
    static void writeWord(final ByteArrayOutputStream out, final long value) {
        if (value < 0) {
            throw new IllegalArgumentException("ABI word must be non-negative, was " + value);
        }
        writeWord(out, Long.toString(value));
    }

    private static void writeWord(final ByteArrayOutputStream out, final String decimal) {
        final byte[] raw = new BigInteger(decimal).toByteArray();
        final int start = raw.length > 1 && raw[0] == 0 ? 1 : 0;
        final int n = raw.length - start;
        out.writeBytes(new byte[WORD - n]);
        out.write(raw, start, n);
    }

    private static byte[] word(final byte[] in, final int index) {
        return Arrays.copyOfRange(in, index * WORD, (index + 1) * WORD);
    }

    /** Reads the head word as an unsigned integer of {@code bits} bits; dirty high bits are rejected (as abi.decode does). */
    private static BigInteger uint(final byte[] in, final int index, final int bits) {
        final var v = new BigInteger(1, word(in, index));
        if (v.bitLength() > bits) {
            throw CantonProofException.invalidTrustAnchor("uint" + bits + " out of range");
        }
        return v;
    }

    private static String string(final byte[] in, final int headIndex) {
        final var offset = new BigInteger(1, word(in, headIndex));
        if (offset.bitLength() > 32 || offset.longValue() > in.length - WORD) {
            throw CantonProofException.invalidTrustAnchor("string offset out of bounds");
        }
        final int lenPos = offset.intValue();
        final var len = new BigInteger(1, Arrays.copyOfRange(in, lenPos, lenPos + WORD));
        final long dataPos = (long) lenPos + WORD;
        if (len.bitLength() > 32 || len.longValue() > in.length - dataPos) {
            throw CantonProofException.invalidTrustAnchor("string length out of bounds");
        }
        return new String(in, (int) dataPos, len.intValue(), StandardCharsets.UTF_8);
    }
}
