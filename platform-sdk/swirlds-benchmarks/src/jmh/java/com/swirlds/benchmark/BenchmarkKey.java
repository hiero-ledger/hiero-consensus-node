// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.VirtualKey;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class BenchmarkKey implements VirtualKey {

    private static int keySize = 8;
    private byte[] keyBytes;

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

    public BenchmarkKey() {
        // default constructor for deserialize
    }

    public BenchmarkKey(long seed) {
        keyBytes = new byte[keySize];
        Utils.toBytes(seed, keyBytes);
    }

    void serialize(final WritableSequentialData out) {
        out.writeBytes(keyBytes);
    }

    void deserialize(final ReadableSequentialData in) {
        keyBytes = new byte[keySize];
        in.readBytes(keyBytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(keyBytes);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BenchmarkKey that)) return false;
        return Arrays.equals(this.keyBytes, that.keyBytes);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < keyBytes.length; i++) {
            sb.append(keyBytes[i] & 0xFF);
            if (i < keyBytes.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    boolean equals(BufferedData buffer) {
        for (int i = 0; i < keySize; ++i) {
            if (buffer.readByte() != keyBytes[i]) {
                return false;
            }
        }
        return true;
    }

    @Deprecated
    boolean equals(ByteBuffer buffer) {
        for (int i = 0; i < keySize; ++i) {
            if (buffer.get() != keyBytes[i]) return false;
        }
        return true;
    }
}
