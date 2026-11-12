// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * <b>Provenance of vectors.</b> The PR has no isolated anchor blob (anchors are built inside Forge flows
 * via {@code abi.encode}). The two hex vectors below were produced by Foundry's Solidity ABI encoder:
 * {@code cast abi-encode "f(string,uint64,uint32,bytes32,bytes32,bytes32,string,uint64,uint32,string)" ...}
 * (cast 1.7.1), whose top-level head/tail layout is identical to {@code abi.encode(...)} of the same ten values.
 * They are reference-derived, NOT captured from a Canton flow. Values: {@code FULL} uses uint64/uint32 values
 * with the high bit set (max and 2^63 / 2^31) and non-empty strings; {@code MINIMAL} has empty successor/app
 * (the no-pending-LSU shape built by {@code CantonProofBuilder._anchorFor}).
 */
class CantonAnchorTest {
    private static final HexFormat HEX = HexFormat.of();

    private static final String FULL_HEX =
            "0000000000000000000000000000000000000000000000000000000000000140000000000000000000000000000000000000000000000000ffffffffffffffff00000000000000000000000000000000000000000000000000000000ffffffff11111111111111111111111111111111111111111111111111111111111111112222222222222222222222222222222222222222222222222222222222222222333333333333333333333333333333333333333333333333333333333333333300000000000000000000000000000000000000000000000000000000000001800000000000000000000000000000000000000000000000008000000000000000000000000000000000000000000000000000000000000000000000008000000000000000000000000000000000000000000000000000000000000000000001c00000000000000000000000000000000000000000000000000000000000000017676c6f62616c2d646f6d61696e3a3a3132323061626364000000000000000000000000000000000000000000000000000000000000000000000000000000000c737563633a3a313232306566000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000096170703a3a313232300000000000000000000000000000000000000000000000";
    private static final String MINIMAL_HEX =
            "000000000000000000000000000000000000000000000000000000000000014000000000000000000000000000000000000000000000000000000000000001f4000000000000000000000000000000000000000000000000000000000000000011111111111111111111111111111111111111111111111111111111111111112222222222222222222222222222222222222222222222222222222222222222333333333333333333333333333333333333333333333333333333333333333300000000000000000000000000000000000000000000000000000000000001800000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001a0000000000000000000000000000000000000000000000000000000000000000873796e633a3a313200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000";

    private static byte[] filled(final int b) {
        final byte[] out = new byte[32];
        Arrays.fill(out, (byte) b);
        return out;
    }

    private static CantonAnchor.Anchor full() {
        return new CantonAnchor.Anchor(
                "global-domain::1220abcd",
                -1L,
                -1,
                filled(0x11),
                filled(0x22),
                filled(0x33),
                "succ::1220ef",
                Long.MIN_VALUE,
                Integer.MIN_VALUE,
                "app::1220");
    }

    private static CantonAnchor.Anchor minimal() {
        return new CantonAnchor.Anchor("sync::12", 500L, 0, filled(0x11), filled(0x22), filled(0x33), "", 0L, 0, "");
    }

    private static void assertAnchorEquals(final CantonAnchor.Anchor e, final CantonAnchor.Anchor a) {
        assertEquals(e.synchronizerId(), a.synchronizerId());
        assertEquals(e.lastRequestId(), a.lastRequestId());
        assertEquals(e.mediatorGroup(), a.mediatorGroup());
        assertArrayEquals(e.synchronizerNamespace(), a.synchronizerNamespace());
        assertArrayEquals(e.ownersHash(), a.ownersHash());
        assertArrayEquals(e.mediatorsHash(), a.mediatorsHash());
        assertEquals(e.successorSynchronizerId(), a.successorSynchronizerId());
        assertEquals(e.upgradeTime(), a.upgradeTime());
        assertEquals(e.lsuSerial(), a.lsuSerial());
        assertEquals(e.app(), a.app());
    }

    @Test
    void decodesSolidityEncodedFull() {
        final var a = CantonAnchor.decode(HEX.parseHex(FULL_HEX));
        assertAnchorEquals(full(), a);
        // unsigned semantics
        assertEquals("18446744073709551615", Long.toUnsignedString(a.lastRequestId()));
        assertEquals("4294967295", Integer.toUnsignedString(a.mediatorGroup()));
        assertEquals("9223372036854775808", Long.toUnsignedString(a.upgradeTime()));
        assertEquals("2147483648", Integer.toUnsignedString(a.lsuSerial()));
    }

    @Test
    void decodesSolidityEncodedMinimal() {
        assertAnchorEquals(minimal(), CantonAnchor.decode(HEX.parseHex(MINIMAL_HEX)));
    }

    @Test
    void encodeMatchesSolidityBytes() {
        assertEquals(FULL_HEX, HEX.formatHex(CantonAnchor.encode(full())));
        assertEquals(MINIMAL_HEX, HEX.formatHex(CantonAnchor.encode(minimal())));
    }

    @Test
    void roundTrips() {
        final var longString = new Anchor32().s; // multi-word string tail (> 32 bytes)
        final var a = new CantonAnchor.Anchor(
                longString, 7L, 3, filled(1), filled(2), filled(3), longString + "x", 99L, 5, longString + "yy");
        assertAnchorEquals(a, CantonAnchor.decode(CantonAnchor.encode(a)));
    }

    @Test
    void writeWordRejectsNegative() {
        final var out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> CantonAnchor.writeWord(out, -1L));
        assertEquals(0, out.size());
    }

    @Test
    void writeWordEncodesNonNegativeAsBigEndianWord() {
        final var out = new ByteArrayOutputStream();
        CantonAnchor.writeWord(out, 32L);
        final byte[] word = out.toByteArray();
        assertEquals(32, word.length);
        assertArrayEquals(new byte[31], Arrays.copyOfRange(word, 0, 31));
        assertEquals(0x20, word[31]);
    }

    @Test
    void uint64WithHighBitSetStillRoundTrips() {
        final var a = full(); // lastRequestId == -1L, i.e. 0xFFFFFFFFFFFFFFFF unsigned
        final byte[] encoded = CantonAnchor.encode(a);
        final byte[] word = Arrays.copyOfRange(encoded, 32, 64);
        final byte[] expected = new byte[32];
        Arrays.fill(expected, 24, 32, (byte) 0xFF);
        assertArrayEquals(expected, word);
        final var decoded = CantonAnchor.decode(encoded);
        assertEquals(-1L, decoded.lastRequestId());
        assertAnchorEquals(a, decoded);
    }

    @Test
    void tooShortThrowsInvalidTrustAnchor() {
        final byte[] ok = HEX.parseHex(MINIMAL_HEX);
        for (final int len : new int[] {0, 1, 31, 13 * 32 - 1}) {
            final byte[] shortBytes = Arrays.copyOf(ok, len);
            final var ex = assertThrows(CantonProofException.class, () -> CantonAnchor.decode(shortBytes));
            assertEquals("InvalidTrustAnchor", ex.errorName());
        }
    }

    @Test
    void truncatedTailThrowsInvalidTrustAnchor() {
        final byte[] full = HEX.parseHex(FULL_HEX);
        final byte[] cut = Arrays.copyOf(full, full.length - 32);
        final var ex = assertThrows(CantonProofException.class, () -> CantonAnchor.decode(cut));
        assertEquals("InvalidTrustAnchor", ex.errorName());
    }

    @Test
    void dirtyUintHighBitsRejected() {
        final byte[] bad = HEX.parseHex(MINIMAL_HEX);
        bad[32] = 1; // lastRequestId word, top byte nonzero (exceeds uint64)
        assertThrows(CantonProofException.class, () -> CantonAnchor.decode(bad));
        final byte[] bad2 = HEX.parseHex(MINIMAL_HEX);
        bad2[64 + 27] = 1; // mediatorGroup word, byte above uint32
        assertThrows(CantonProofException.class, () -> CantonAnchor.decode(bad2));
    }

    @Test
    void offsetOutOfBoundsRejected() {
        final byte[] bad = HEX.parseHex(MINIMAL_HEX);
        bad[31] = (byte) 0xFF;
        bad[30] = (byte) 0xFF; // synchronizerId offset far beyond the input
        assertThrows(CantonProofException.class, () -> CantonAnchor.decode(bad));
    }

    private static final class Anchor32 {
        final String s = "0123456789abcdef0123456789abcdef0123456789abcdef"; // 48 bytes
    }
}
