// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.fixtures.util;

import java.util.Random;
import java.util.UUID;

/**
 * Utility class to generate some primitive data for testing purposes.
 */
public final class DataUtils {

    private DataUtils() {
        // Prevent instantiation
    }

    public static int[] shuffle(Random random, final int[] array) {
        if (random == null) {
            random = new Random();
        }
        final int count = array.length;
        for (int i = count; i > 1; i--) {
            swap(array, i - 1, random.nextInt(i));
        }
        return array;
    }

    public static void swap(final int[] array, final int i, final int j) {
        final int temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }

    public static byte[] randomUtf8Bytes(final int n) {
        final byte[] data = new byte[n];
        int i = 0;
        while (i < n) {
            final byte[] rnd = UUID.randomUUID().toString().getBytes();
            System.arraycopy(rnd, 0, data, i, Math.min(rnd.length, n - 1 - i));
            i += rnd.length;
        }
        return data;
    }
}
