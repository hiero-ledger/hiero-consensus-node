// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.io.buffer.Bytes;

public class BenchmarkUtils {

    public static Bytes longToBytes(long seed, int keySize) {
        final byte[] keyBytes = new byte[keySize];
        Utils.toBytes(seed, keyBytes);
        return Bytes.wrap(keyBytes);
    }
}
