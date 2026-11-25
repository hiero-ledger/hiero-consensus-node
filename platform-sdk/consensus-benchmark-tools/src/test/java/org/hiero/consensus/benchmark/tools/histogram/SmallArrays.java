// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.histogram;

/**
 * Allocates memory in a test without starting a garbage collection by doing so. A single large array would be a
 * humongous allocation for G1, and right after a {@link System#gc()} has shrunk the heap, a humongous allocation
 * starts a garbage collection, which discards the pass it falls into.
 */
final class SmallArrays {

    /** The size of each array, far below the size from which G1 allocates an array as humongous. */
    private static final int ARRAY_BYTES = 1_024;

    /** Keeps the arrays reachable, so that their allocation is not optimized away. */
    @SuppressWarnings("unused")
    private static byte[][] sink;

    private SmallArrays() {}

    /**
     * Allocates at least the given number of bytes, in arrays of {@value #ARRAY_BYTES} bytes.
     *
     * @param bytes the number of bytes to allocate
     */
    static void allocate(final int bytes) {
        final byte[][] arrays = new byte[bytes / ARRAY_BYTES][];
        for (int i = 0; i < arrays.length; i++) {
            arrays[i] = new byte[ARRAY_BYTES];
        }
        sink = arrays;
    }
}
