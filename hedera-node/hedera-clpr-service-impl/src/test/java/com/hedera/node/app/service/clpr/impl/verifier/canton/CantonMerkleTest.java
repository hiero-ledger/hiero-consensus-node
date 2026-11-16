// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.canton;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * <b>Provenance of vectors.</b> The PR this ports has no isolated per-function Merkle vectors, so:
 * <ul>
 *   <li>Real bytes: {@code update-multihash} (a real {@code 0x1220 || digest} multihash) and the real
 *       verdict {@code typedContent} are used as inputs / cross-checks.</li>
 *   <li>Reference-derived: the pinned hex digests below were computed offline (xxd + sha256) directly from
 *       the byte layout in {@code ClprCantonMerkle.sol}; they are NOT captured Canton output.</li>
 * </ul>
 */
class CantonMerkleTest {
    private static final HexFormat HEX = HexFormat.of();

    private static byte[] filled(final int b) {
        final byte[] out = new byte[32];
        Arrays.fill(out, (byte) b);
        return out;
    }

    private static byte[] sha256(final byte[]... parts) throws Exception {
        final var md = MessageDigest.getInstance("SHA-256");
        for (final byte[] p : parts) {
            md.update(p);
        }
        return md.digest();
    }

    private static byte[] be32(final int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static final byte[] MH = {0x12, 0x20};

    @Test
    void constantsMatchReference() {
        assertEquals(6, CantonMerkle.PURPOSE_COMMON_METADATA);
        assertEquals(8, CantonMerkle.PURPOSE_VIEW_COMMON_DATA);
        assertEquals(9, CantonMerkle.PURPOSE_VIEW_PARTICIPANT_DATA);
        assertEquals(3, CantonMerkle.PURPOSE_MERKLE_TREE_INNER_NODE);
        assertEquals(38, CantonMerkle.PURPOSE_RESULT_MESSAGE_SIGNATURE);
    }

    @Test
    void leafPinnedVectors() {
        // BE32(6) || BE32(4) || 01020304 || BE32(3) || aabbcc
        assertEquals(
                "a72fd0b9d6b5d429c19867a79e4fc3b449234429ad72db47989d6eae2ba5eaad",
                HEX.formatHex(CantonMerkle.leaf(6, HEX.parseHex("01020304"), HEX.parseHex("aabbcc"))));
        // empty salt: BE32(8) || BE32(0) || BE32(1) || 00
        assertEquals(
                "5ea13982f1ff8af0a055106738d432fe8cda20d6ebe35e207fae1ec0bad70f21",
                HEX.formatHex(CantonMerkle.leaf(8, new byte[0], new byte[] {0})));
    }

    @Test
    void leafMatchesManualEncoding() throws Exception {
        final byte[] salt = filled(0x11);
        final byte[] data = {1, 2, 3, 4, 5};
        assertArrayEquals(sha256(be32(9), be32(32), salt, be32(5), data), CantonMerkle.leaf(9, salt, data));
    }

    @Test
    void innerPinnedVectors() {
        assertEquals(
                "0e337b799779e966201b7e95a326e564267fa4d6573062e3de6f7835d0623d17",
                HEX.formatHex(CantonMerkle.inner(new byte[0][])));
        assertEquals(
                "793e256894c15f1a64d8bed89e56b82ee5f970434b91290f16ab9fc6d7f16b95",
                HEX.formatHex(CantonMerkle.inner(new byte[][] {filled(0xaa)})));
        assertEquals(
                "289f219c134ed9ad029e27548a916cf2bfcf3527d91d1dc514976ab6dcfe0b56",
                HEX.formatHex(CantonMerkle.inner(new byte[][] {filled(0xaa), filled(0xbb)})));
        assertEquals(
                "e8421508eb192b446076819981c2fddc1280f30be39bc0cbe63652c1b9da5fd3",
                HEX.formatHex(CantonMerkle.inner(new byte[][] {filled(0xaa), filled(0xbb), filled(0xcc)})));
    }

    @Test
    void inner1AndInner2AgreeWithGeneralInner() {
        final byte[] a = filled(0xaa);
        final byte[] b = filled(0xbb);
        assertArrayEquals(CantonMerkle.inner(new byte[][] {a}), CantonMerkle.inner1(a));
        assertArrayEquals(CantonMerkle.inner(new byte[][] {a, b}), CantonMerkle.inner2(a, b));
    }

    @Test
    void innerChildEnteringAsRealMultihash() throws Exception {
        // Real fixture: the verdict update multihash is 0x1220 || rootHash. An inner node must feed each
        // child digest as exactly this 34-byte form.
        final byte[] multihash = CantonFixtures.updateMultihash();
        assertEquals(34, multihash.length);
        assertArrayEquals(MH, Arrays.copyOfRange(multihash, 0, 2));
        final byte[] digest = Arrays.copyOfRange(multihash, 2, 34);

        assertArrayEquals(sha256(be32(3), be32(1), multihash), CantonMerkle.inner1(digest));
        assertArrayEquals(sha256(be32(3), be32(2), multihash, multihash), CantonMerkle.inner2(digest, digest));
    }

    @Test
    void verdictSigningInputOnRealTypedContent() throws Exception {
        final byte[] typed = CantonFixtures.verdictTypedContent();
        final byte[] out = CantonMerkle.verdictSigningInput(typed);
        assertEquals(34, out.length);
        assertArrayEquals(MH, Arrays.copyOfRange(out, 0, 2));
        // Reference-derived (computed offline from BE32(38) || typedContent).
        assertEquals("12208b2fae52150fa9b32d70b39cf068bbf2e634cdd6328cd2abfc50f20eb8fce405", HEX.formatHex(out));
        final var buf = new ByteArrayOutputStream();
        buf.writeBytes(be32(38));
        buf.writeBytes(typed);
        assertArrayEquals(sha256(buf.toByteArray()), Arrays.copyOfRange(out, 2, 34));
    }

    @Test
    void verdictSigningInputSmallPinned() {
        assertEquals(
                "1220b9c43d654a0296e4012499281ce7086535f7ccb4ca667b9371a75dee5a7339b4",
                HEX.formatHex(CantonMerkle.verdictSigningInput(new byte[] {1, 2, 3})));
    }
}
