// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import static org.hiero.base.utility.NonCryptographicHashing.hash32;
import static org.hiero.base.utility.NonCryptographicHashing.hash64;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Non-Cryptographic Hash Test")
class NonCryptographicHashTest {

    /**
     * This test does not attempt to verify statistical properties of the hash functions.
     * Its purpose is to ensure that none of the methods cause a crash.
     */
    @DisplayName("Test hash32")
    @Test
    void testHash32() {
        final Random random = getRandomPrintSeed();

        assertDoesNotThrow(() -> {
            hash32(random.nextInt());
            hash32(random.nextInt(), random.nextInt());
            hash32(random.nextInt(), random.nextInt(), random.nextInt());
            hash32(random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt());
            hash32(random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt());
            hash32(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash32(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash32(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash32(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash32(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash32(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash32(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());

            hash32(random.nextLong());
            hash32(random.nextLong(), random.nextLong());
            hash32(random.nextLong(), random.nextLong(), random.nextLong());
            hash32(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong());
            hash32(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong());
            hash32(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash32(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash32(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash32(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash32(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash32(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash32(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());

            for (int i = 0; i < 100; i++) {
                final byte[] bytes = new byte[i];
                hash32(bytes);

                final String string = RandomUtils.randomString(random, i);
                hash32(string);
            }
        });
    }

    /**
     * This test does not attempt to verify statistical properties of the hash functions.
     * Its purpose is to ensure that none of the methods cause a crash.
     */
    @DisplayName("Test hash64")
    @Test
    void testHash64() {
        final Random random = getRandomPrintSeed();

        assertDoesNotThrow(() -> {
            hash64(random.nextInt());
            hash64(random.nextInt(), random.nextInt());
            hash64(random.nextInt(), random.nextInt(), random.nextInt());
            hash64(random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt());
            hash64(random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt(), random.nextInt());
            hash64(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash64(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash64(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash64(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash64(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash64(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());
            hash64(
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt(),
                    random.nextInt());

            hash64(random.nextLong());
            hash64(random.nextLong(), random.nextLong());
            hash64(random.nextLong(), random.nextLong(), random.nextLong());
            hash64(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong());
            hash64(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong());
            hash64(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash64(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash64(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash64(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash64(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash64(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());
            hash64(
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong(),
                    random.nextLong());

            for (int i = 0; i < 100; i++) {
                final byte[] bytes = new byte[i];
                hash64(bytes);

                final String string = RandomUtils.randomString(random, i);
                hash64(string);
            }
        });
    }

    @DisplayName("Hashes Are Not Degenerate 32")
    @Test
    void hashesAreNonDegenerate32() {
        final Random random = getRandomPrintSeed();

        assertNotEquals(0, hash32(0));
        assertNotEquals(0, hash32(0, 0));
        assertNotEquals(0, hash32(0, 0, 0));
        assertNotEquals(0, hash32(0, 0, 0));
        assertNotEquals(0, hash32(0, 0, 0, 0));
        assertNotEquals(0, hash32(0, 0, 0, 0, 0));
        assertNotEquals(0, hash32(0, 0, 0, 0, 0, 0));
        assertNotEquals(0, hash32(0, 0, 0, 0, 0, 0, 0));
        assertNotEquals(0, hash32(0, 0, 0, 0, 0, 0, 0, 0));
        assertNotEquals(0, hash32(0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertNotEquals(0, hash32(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertNotEquals(0, hash32(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

        assertNotEquals(0, hash32(random.nextLong()));
        assertNotEquals(0, hash32(random.nextLong(), random.nextLong()));
        assertNotEquals(0, hash32(random.nextLong(), random.nextLong(), random.nextLong()));
        assertNotEquals(0, hash32(random.nextLong(), random.nextLong(), random.nextLong()));
        assertNotEquals(0, hash32(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong()));
        assertNotEquals(
                0,
                hash32(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong()));
        assertNotEquals(
                0,
                hash32(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));
        assertNotEquals(
                0,
                hash32(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));
        assertNotEquals(
                0,
                hash32(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));
        assertNotEquals(
                0,
                hash32(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));
        assertNotEquals(
                0,
                hash32(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));
        assertNotEquals(
                0,
                hash32(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));

        for (int i = 0; i < 100; i++) {
            final byte[] bytes = new byte[i];
            assertNotEquals(0, hash32(bytes), "Hashes should be non-degenerate");

            final String string = RandomUtils.randomString(random, i);
            assertNotEquals(0, hash32(string), "Hashes should be non-degenerate");
        }
    }

    @DisplayName("Hashes Are Not Degenerate 64")
    @Test
    void hashesAreNonDegenerate64() {
        final Random random = getRandomPrintSeed();

        assertNotEquals(0, hash64(0));
        assertNotEquals(0, hash64(0, 0));
        assertNotEquals(0, hash64(0, 0, 0));
        assertNotEquals(0, hash64(0, 0, 0));
        assertNotEquals(0, hash64(0, 0, 0, 0));
        assertNotEquals(0, hash64(0, 0, 0, 0, 0));
        assertNotEquals(0, hash64(0, 0, 0, 0, 0, 0));
        assertNotEquals(0, hash64(0, 0, 0, 0, 0, 0, 0));
        assertNotEquals(0, hash64(0, 0, 0, 0, 0, 0, 0, 0));
        assertNotEquals(0, hash64(0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertNotEquals(0, hash64(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertNotEquals(0, hash64(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));

        assertNotEquals(0, hash64(random.nextLong()));
        assertNotEquals(0, hash64(random.nextLong(), random.nextLong()));
        assertNotEquals(0, hash64(random.nextLong(), random.nextLong(), random.nextLong()));
        assertNotEquals(0, hash64(random.nextLong(), random.nextLong(), random.nextLong()));
        assertNotEquals(0, hash64(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong()));
        assertNotEquals(
                0,
                hash64(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong()));
        assertNotEquals(
                0,
                hash64(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));
        assertNotEquals(
                0,
                hash64(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));
        assertNotEquals(
                0,
                hash64(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));
        assertNotEquals(
                0,
                hash64(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));
        assertNotEquals(
                0,
                hash64(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));
        assertNotEquals(
                0,
                hash64(
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong(),
                        random.nextLong()));

        for (int i = 0; i < 100; i++) {
            final byte[] bytes = new byte[i];
            assertNotEquals(0, hash64(bytes), "Hashes should be non-degenerate");

            final String string = RandomUtils.randomString(random, i);
            assertNotEquals(0, hash64(string), "Hashes should be non-degenerate");
        }
    }
}
