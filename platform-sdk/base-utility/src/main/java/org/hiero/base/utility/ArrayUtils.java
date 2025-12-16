// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Arrays;

/**
 * Utility methods for arrays
 */
public class ArrayUtils {

    /**
     * Compare arrays lexicographically, with element 0 having the most influence.
     * A null array is considered less than a non-null array.
     * This is the same as Java.Util.Arrays#compare
     *
     * @param b1
     * 		first array
     * @param b2
     * 		second array
     * @return 1 if first is bigger, -1 if second, 0 otherwise
     */
    public static int arrayCompare(@Nullable final Bytes b1, @Nullable final Bytes b2) {
        if (b1 == null && b2 == null) {
            return 0;
        }
        if (b1 == null && b2 != null) {
            return -1;
        }
        if (b1 != null && b2 == null) {
            return 1;
        }
        for (int i = 0; i < Math.min(b1.length(), b2.length()); i++) {
            if (b1.getByte(i) < b2.getByte(i)) {
                return -1;
            }
            if (b1.getByte(i) > b2.getByte(i)) {
                return 1;
            }
        }
        if (b1.length() < b2.length()) {
            return -1;
        }
        if (b1.length() > b2.length()) {
            return 1;
        }
        return 0;
    }

    /**
     * Compare arrays lexicographically, with element 0 having the most influence, as if each array was
     * XORed with whitening before the comparison. The XOR doesn't actually happen, and the arrays are left
     * unchanged.
     *
     * @param a1
     * 		first array
     * @param a2
     * 		second array
     * @param whitening
     * 		the array virtually XORed with the other two
     * @return 1 if first is bigger, -1 if second, 0 otherwise
     */
    public static int arrayCompare(@Nullable final Bytes a1, @Nullable final Bytes a2, byte[] whitening) {
        if (a1 == null && a2 == null) {
            return 0;
        }
        if (a1 != null && a2 == null) {
            return 1;
        }
        if (a1 == null && a2 != null) {
            return -1;
        }
        final int maxLen = (int) Math.max(a1.length(), a2.length());
        final int minLen = (int) Math.min(a1.length(), a2.length());
        if (whitening.length < maxLen) {
            whitening = Arrays.copyOf(whitening, maxLen);
        }
        for (int i = 0; i < minLen; i++) {
            final int b1 = a1.getByte(i) ^ whitening[i];
            final int b2 = a2.getByte(i) ^ whitening[i];
            if (b1 > b2) {
                return 1;
            }
            if (b1 < b2) {
                return -1;
            }
        }
        if (a1.length() > a2.length()) {
            return 1;
        }
        if (a1.length() < a2.length()) {
            return -1;
        }
        return 0;
    }
}
