// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import static com.hedera.node.app.hapi.utils.ethereum.CodeDelegation.MAGIC;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import java.math.BigInteger;
import java.util.Optional;
import org.apache.commons.codec.binary.Hex;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class CodeDelegationTest {

    public static byte[] fillBytes(int len, int start) {
        final byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) (start + i);
        }
        return b;
    }

    @Test
    void computeAuthorityEmptyWhenExtractorReturnsNull() {
        final var chainId = new byte[] {0x01, 0x2a};
        final var address = fillBytes(20, 0x10);
        final var r = fillBytes(32, 0x20);
        final var s = fillBytes(32, 0x40);

        final var cd = new CodeDelegation(chainId, address, 7L, 1, r, s);

        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(cd)).thenReturn(Optional.empty());

            final Optional<EthTxSigs> result = cd.computeAuthority();
            assertTrue(result.isEmpty());
        }
    }

    @Test
    void computeAuthorityPresentWhenExtractorReturnsInstance() {
        final var chainId = new byte[] {0x00, 0x01};
        final var address = fillBytes(20, 0x01);
        final var r = fillBytes(32, 0x11);
        final var s = fillBytes(32, 0x22);

        final var cd = new CodeDelegation(chainId, address, 1L, 27, r, s);

        final Optional<EthTxSigs> mockSigs = Optional.of(mock(EthTxSigs.class));
        try (MockedStatic<EthTxSigs> mocked = mockStatic(EthTxSigs.class)) {
            mocked.when(() -> EthTxSigs.extractAuthoritySignature(cd)).thenReturn(mockSigs);

            final Optional<EthTxSigs> result = cd.computeAuthority();
            assertTrue(result.isPresent());
            assertSame(mockSigs.get(), result.get());
        }
    }

    @Test
    void getChainIdThrowsWhenNull() {
        final var address = fillBytes(20, 0x00);
        final var r = fillBytes(32, 0x33);
        final var s = fillBytes(32, 0x44);

        final var cd =
                assertThrowsExactly(NullPointerException.class, () -> new CodeDelegation(null, address, 0L, 0, r, s));
    }

    @Test
    void getChainIdReturnsUnsignedLongValue() {
        final var chainId = new byte[] {0x01, 0x2a}; // 298
        final var address = fillBytes(20, 0x10);
        final var r = fillBytes(32, 0x20);
        final var s = fillBytes(32, 0x30);

        final var cd = new CodeDelegation(chainId, address, 0L, 0, r, s);
        assertEquals(298L, cd.getChainId());
    }

    @Test
    void calculateSignableMessageIsRlpOfFields() {
        final var chainId = new byte[] {0x01, 0x2a};
        final var address = fillBytes(20, 0x55);
        final long nonce = 123456789L;
        final var r = fillBytes(32, 0x66);
        final var s = fillBytes(32, 0x77);

        final var cd = new CodeDelegation(chainId, address, nonce, 0, r, s);

        final byte[] expected = Bytes.concatenate(
                        MAGIC,
                        Bytes.wrap(RLPEncoder.list(
                                chainId, address, com.google.common.primitives.Longs.toByteArray(nonce))))
                .toArray();
        assertArrayEquals(expected, cd.calculateSignableMessage());
    }

    @Test
    void getSIsUnsigned() {
        final var chainId = new byte[] {0x00};
        final var address = fillBytes(20, 0x01);
        final var r = fillBytes(32, 0x02);
        final var s = new byte[] {(byte) 0x80, 0x00}; // 0x8000 = 32768 unsigned

        final var cd = new CodeDelegation(chainId, address, 0L, 0, r, s);
        assertEquals(new BigInteger("32768"), cd.getS());
    }

    @Test
    void equalsAndHashCodeWorkForSameContent() {
        final var chainId = new byte[] {0x01};
        final var address = fillBytes(20, 0x0A);
        final var r = fillBytes(32, 0x0B);
        final var s = fillBytes(32, 0x0C);

        final var a = new CodeDelegation(chainId, address, 42L, 28, r, s);
        final var b = new CodeDelegation(
                new byte[] {0x01}, fillBytes(20, 0x0A), 42L, 28, fillBytes(32, 0x0B), fillBytes(32, 0x0C));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "not-a-delegation");
    }

    @Test
    void equalsDetectsDifferences() {
        final var base = new CodeDelegation(
                new byte[] {0x01}, fillBytes(20, 0x10), 1L, 27, fillBytes(32, 0x20), fillBytes(32, 0x30));

        assertNotEquals(
                base,
                new CodeDelegation(
                        new byte[] {0x02}, fillBytes(20, 0x10), 1L, 27, fillBytes(32, 0x20), fillBytes(32, 0x30)));
        assertNotEquals(
                base,
                new CodeDelegation(
                        new byte[] {0x01}, fillBytes(20, 0x11), 1L, 27, fillBytes(32, 0x20), fillBytes(32, 0x30)));
        assertNotEquals(
                base,
                new CodeDelegation(
                        new byte[] {0x01}, fillBytes(20, 0x10), 2L, 27, fillBytes(32, 0x20), fillBytes(32, 0x30)));
        assertNotEquals(
                base,
                new CodeDelegation(
                        new byte[] {0x01}, fillBytes(20, 0x10), 1L, 28, fillBytes(32, 0x20), fillBytes(32, 0x30)));
        assertNotEquals(
                base,
                new CodeDelegation(
                        new byte[] {0x01}, fillBytes(20, 0x10), 1L, 27, fillBytes(32, 0x21), fillBytes(32, 0x30)));
        assertNotEquals(
                base,
                new CodeDelegation(
                        new byte[] {0x01}, fillBytes(20, 0x10), 1L, 27, fillBytes(32, 0x20), fillBytes(32, 0x31)));
    }

    @Test
    void toStringContainsHexEncodedFields() {
        final var chainId = new byte[] {0x01, 0x2a};
        final var address = fillBytes(20, 0x0F);
        final var r = fillBytes(32, 0x01);
        final var s = fillBytes(32, 0x02);

        final var cd = new CodeDelegation(chainId, address, 99L, 1, r, s);
        final String str = cd.toString();

        assertTrue(str.contains("chainId=" + Hex.encodeHexString(chainId)));
        assertTrue(str.contains("address=" + Hex.encodeHexString(address)));
        assertTrue(str.contains("nonce=99"));
        assertTrue(str.contains("yParity=1"));
        assertTrue(str.contains("r=" + Hex.encodeHexString(r)));
        assertTrue(str.contains("s=" + Hex.encodeHexString(s)));
    }
}
