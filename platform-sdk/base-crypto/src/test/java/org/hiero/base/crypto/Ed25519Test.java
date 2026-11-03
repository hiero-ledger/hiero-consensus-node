// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class Ed25519Test {
    private static final byte[] SEED = new byte[] {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
        30, 31
    };
    private static final byte[] PUBLIC_KEY = new byte[] {
        3, -95, 7, -65, -13, -50, 16, -66, 29, 112, -35, 24, -25, 75, -64, -103, 103, -28, -42, 48, -101, -91, 13, 95,
        29, -36, -122, 100, 18, 85, 49, -72
    };
    private static final byte[] SECRET_KEY = new byte[] {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
        30, 31, 3, -95, 7, -65, -13, -50, 16, -66, 29, 112, -35, 24, -25, 75, -64, -103, 103, -28, -42, 48, -101, -91,
        13, 95, 29, -36, -122, 100, 18, 85, 49, -72
    };
    private static final byte[] SIGNATURE = new byte[] {
        -77, 53, -36, 94, -82, 112, -76, -121, 100, -15, 18, 7, -69, -86, 98, 117, 124, -69, -108, -24, 32, -50, -70,
        55, 106, -21, 98, -91, -127, 93, -6, -66, 120, 66, 23, 117, -120, 54, -54, 40, 80, 74, 54, -94, 22, 10, -69,
        -36, -98, 120, 116, -2, 29, 101, 36, -91, -122, 101, -17, 44, 66, 109, -93, 14
    };

    private void assertArrayEquals(byte[] expected, byte[] actual) {
        Assertions.assertArrayEquals(
                expected,
                actual,
                () -> "Expected:\n" + Arrays.toString(expected) + "\nbut got:\n" + Arrays.toString(actual) + "\n");
    }

    @Test
    public void testNewKeypair() {
        byte[] publicKey = new byte[Ed25519.PUBLIC_KEY_BYTES];
        byte[] secretKey = new byte[Ed25519.SECRET_KEY_BYTES];

        Ed25519.newKeypair(publicKey, secretKey, SEED);

        assertArrayEquals(PUBLIC_KEY, publicKey);
        assertArrayEquals(SECRET_KEY, secretKey);
    }

    @Test
    public void testSignature() {
        byte[] signature = new byte[Ed25519.SIGNATURE_BYTES];

        Ed25519.sign(signature, SEED, SECRET_KEY);

        assertArrayEquals(SIGNATURE, signature);
    }
}
