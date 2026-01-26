// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.io.buffer.Bytes;

public class BenchmarkKeyUtils {

    private static int keySize = 8;

    public static void setKeySize(int size) {
        keySize = size;
    }

    public static int getKeySize() {
        return keySize;
    }

    public static Bytes longToKey(long seed) {
        final byte[] keyBytes = new byte[keySize];
        Utils.toBytes(seed, keyBytes);
        return Bytes.wrap(keyBytes);
    }
}
