// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.test.fixtures.Randotron;
import java.net.InetAddress;
import java.util.Arrays;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Randotron class
 */
class RandotronTests {
    private Randotron random1;
    private Randotron random1Duplicate;
    private Randotron random2;

    @BeforeEach
    void setUp() {
        random1 = Randotron.create();
        random1Duplicate = Randotron.create(random1.getSeed());
        random2 = Randotron.create();
    }

    @Test
    @DisplayName("Test nextString with different seeds")
    void nextStringUnique() {
        final String nextString1 = random1.nextString(10);
        final String nextString2 = random2.nextString(10);

        assertNotEquals(nextString1, nextString2);
        assertEquals(10, nextString1.length());
        assertEquals(10, nextString2.length());
    }

    @Test
    @DisplayName("Test nextString with same seed")
    void nextStringSameSeed() {
        final String nextString1 = random1.nextString(10);
        final String nextString2 = random1Duplicate.nextString(10);

        assertEquals(nextString1, nextString2);
        assertEquals(10, nextString1.length());
        assertEquals(10, nextString2.length());
    }

    @Test
    @DisplayName("Test nextString edge cases")
    void nextStringEdgeCases() {
        assertEquals("", random1.nextString(0));
        assertThrows(IllegalArgumentException.class, () -> random1.nextString(-1));
    }

    @Test
    @DisplayName("Test nextIp with different seeds")
    void nextIpUnique() {
        final String ip1 = random1.nextIp();
        final String ip2 = random2.nextIp();

        assertNotEquals(ip1, ip2);
        assertDoesNotThrow(() -> InetAddress.getByName(ip1));
        assertDoesNotThrow(() -> InetAddress.getByName(ip2));
    }

    @Test
    @DisplayName("Test nextIp with same seed")
    void nextIpSameSeed() {
        final String ip1 = random1.nextIp();
        final String ip2 = random1Duplicate.nextIp();

        assertEquals(ip1, ip2);
        assertDoesNotThrow(() -> InetAddress.getByName(ip1));
        assertDoesNotThrow(() -> InetAddress.getByName(ip2));
    }

    @Test
    @DisplayName("Test bounded nextPositiveLong with different seeds")
    void randomPositiveBoundedLongUnique() {
        final long randomLong1 = random1.nextPositiveLong(10000000);
        final long randomLong2 = random2.nextPositiveLong(10000000);

        assertNotEquals(randomLong1, randomLong2);
        assertTrue(randomLong1 < 10000000);
        assertTrue(randomLong2 < 10000000);
    }

    @Test
    @DisplayName("Test bounded nextPositiveLong with same seed")
    void randomPositiveBoundedLongSameSeed() {
        final long randomLong1 = random1.nextPositiveLong(10000000);
        final long randomLong2 = random1Duplicate.nextPositiveLong(10000000);

        assertEquals(randomLong1, randomLong2);
        assertTrue(randomLong1 < 10000000);
    }

    @Test
    @DisplayName("Test bounded nextPositiveLong edge cases")
    void randomPositiveBoundedLongEdgeCases() {
        assertThrows(IllegalArgumentException.class, () -> random1.nextPositiveLong(1));
        assertThrows(IllegalArgumentException.class, () -> random1.nextPositiveLong(0));
        assertThrows(IllegalArgumentException.class, () -> random1.nextPositiveLong(-1));
        assertEquals(1, random1.nextPositiveLong(2));
    }

    @Test
    @DisplayName("Test nextPositiveLong with different seeds")
    void nextPositiveLongUnique() {
        assertNotEquals(random1.nextPositiveLong(), random2.nextPositiveLong());
    }

    @Test
    @DisplayName("Test nextPositiveLong with same seed")
    void nextPositiveLongSameSeed() {
        assertEquals(random1.nextPositiveLong(), random1Duplicate.nextPositiveLong());
    }

    @Test
    @DisplayName("Test bounded nextPositiveInt with same seed")
    void randomPositiveBoundedIntSameSeed() {
        final long randomLong1 = random1.nextPositiveInt(10000000);
        final long randomLong2 = random1Duplicate.nextPositiveInt(10000000);

        assertEquals(randomLong1, randomLong2);
        assertTrue(randomLong1 < 10000000);
    }

    @Test
    @DisplayName("Test bounded nextPositiveInt edge cases")
    void randomPositiveBoundedIntEdgeCases() {
        assertThrows(IllegalArgumentException.class, () -> random1.nextPositiveInt(1));
        assertThrows(IllegalArgumentException.class, () -> random1.nextPositiveInt(0));
        assertThrows(IllegalArgumentException.class, () -> random1.nextPositiveInt(-1));
        assertEquals(1, random1.nextPositiveInt(2));
    }

    @Test
    @DisplayName("Test nextPositiveInt with same seed")
    void randomPositiveIntSameSeed() {
        assertEquals(random1.nextPositiveInt(), random1Duplicate.nextPositiveInt());
    }

    @Test
    @DisplayName("Test nextHash with different seeds")
    void nextHashUnique() {
        final Hash hash1 = random1.nextHash();
        final Hash hash2 = random2.nextHash();
        assertNotEquals(hash1, hash2);

        assertEquals(48, hash1.getBytes().length());
        assertEquals(48, hash2.getBytes().length());
        assertEquals(DigestType.SHA_384, hash1.getDigestType());
        assertEquals(DigestType.SHA_384, hash2.getDigestType());
    }

    @Test
    @DisplayName("Test nextHash with same seed")
    void nextHashSameSeed() {
        final Hash hash1 = random1.nextHash();
        final Hash hash2 = random1Duplicate.nextHash();

        assertEquals(hash1, hash2);

        assertEquals(48, hash1.getBytes().length());
        assertEquals(48, hash2.getBytes().length());
        assertEquals(DigestType.SHA_384, hash1.getDigestType());
        assertEquals(DigestType.SHA_384, hash2.getDigestType());
    }

    @Test
    @DisplayName("Test nextHashBytes with different seeds")
    void nextHashBytesUnique() {
        final Bytes hashBytes1 = random1.nextHashBytes();
        final Bytes hashBytes2 = random2.nextHashBytes();

        assertNotEquals(hashBytes1, hashBytes2);
        assertEquals(48, hashBytes1.length());
        assertEquals(48, hashBytes2.length());
    }

    @Test
    @DisplayName("Test nextHashBytes with same seed")
    void nextHashBytesSameSeed() {
        final Bytes hashBytes1 = random1.nextHashBytes();
        final Bytes hashBytes2 = random1Duplicate.nextHashBytes();

        assertEquals(hashBytes1, hashBytes2);
        assertEquals(48, hashBytes1.length());
        assertEquals(48, hashBytes2.length());
    }

    @Test
    @DisplayName("Test nextSignature with different seeds")
    void nextSignatureUnique() {
        final Signature signature1 = random1.nextSignature();
        final Signature signature2 = random2.nextSignature();

        assertNotEquals(signature1, signature2);
        assertEquals(384, signature1.getBytes().length());
        assertEquals(384, signature2.getBytes().length());
    }

    @Test
    @DisplayName("Test nextSignature with same seed")
    void nextSignatureSameSeed() {
        final Signature signature1 = random1.nextSignature();
        final Signature signature2 = random1Duplicate.nextSignature();

        assertEquals(signature1, signature2);
        assertEquals(384, signature1.getBytes().length());
        assertEquals(384, signature2.getBytes().length());
    }

    @Test
    @DisplayName("Test nextSignatureBytes with different seeds")
    void nextSignatureBytesUnique() {
        final Bytes signatureBytes1 = random1.nextSignatureBytes();
        final Bytes signatureBytes2 = random2.nextSignatureBytes();

        assertNotEquals(signatureBytes1, signatureBytes2);
        assertEquals(384, signatureBytes1.length());
        assertEquals(384, signatureBytes2.length());
    }

    @Test
    @DisplayName("Test nextSignatureBytes with same seed")
    void nextSignatureBytesSameSeed() {
        final Bytes signatureBytes1 = random1.nextSignatureBytes();
        final Bytes signatureBytes2 = random1Duplicate.nextSignatureBytes();

        assertEquals(signatureBytes1, signatureBytes2);
        assertEquals(384, signatureBytes1.length());
        assertEquals(384, signatureBytes2.length());
    }

    @Test
    @DisplayName("Test nextByteArray with different seeds")
    void nextByteArray() {
        final byte[] randomBytes1 = random1.nextByteArray(10);
        final byte[] randomBytes2 = random2.nextByteArray(10);

        assertFalse(Arrays.equals(randomBytes1, randomBytes2));

        assertEquals(10, randomBytes1.length);
        assertEquals(10, randomBytes2.length);
    }

    @Test
    @DisplayName("Test nextByteArray with same seed")
    void nextByteArraySameSeed() {
        final byte[] randomBytes1 = random1.nextByteArray(10);
        final byte[] randomBytes2 = random1Duplicate.nextByteArray(10);

        assertArrayEquals(random1.nextByteArray(10), random1Duplicate.nextByteArray(10));
        assertEquals(10, randomBytes1.length);
        assertEquals(10, randomBytes2.length);
    }

    @Test
    @DisplayName("Test nextInstant with different seeds")
    void nextInstantUnique() {
        assertNotEquals(random1.nextInstant(), random2.nextInstant());
    }

    @Test
    @DisplayName("Test nextInstant with same seed")
    void nextInstantSameSeed() {
        assertEquals(random1.nextInstant(), random1Duplicate.nextInstant());
    }

    @Test
    @DisplayName("Test nextBooleanWithProbability with different seeds")
    void nextBooleanWithProbabilityUnique() {
        boolean isDifferent = false;
        for (int i = 0; i < 100; i++) {
            if (random1.nextBoolean(0.5) != random2.nextBoolean(0.5)) {
                isDifferent = true;
                break;
            }
        }

        assertTrue(isDifferent, "You should buy a lottery ticket if this test fails");
    }

    @Test
    @DisplayName("Test nextBooleanWithProbability with same seed")
    void nextBooleanWithProbabilitySameSeed() {
        for (int i = 0; i < 100; i++) {
            assertEquals(random1.nextBoolean(0.5), random1Duplicate.nextBoolean(0.5));
        }
    }

    @Test
    @DisplayName("Test nextBooleanWithProbability edge cases")
    void nextBooleanWithProbabilityEdgeCases() {
        assertThrows(IllegalArgumentException.class, () -> random1.nextBoolean(1.1));
        assertThrows(IllegalArgumentException.class, () -> random1.nextBoolean(-0.1));
        assertDoesNotThrow(() -> random1.nextBoolean(0));
        assertDoesNotThrow(() -> random1.nextBoolean(1));
    }
}
