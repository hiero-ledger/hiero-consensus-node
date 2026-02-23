// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import java.util.Arrays;
import java.util.function.LongUnaryOperator;

public class BenchmarkValue {

    private static int valueSize = 16;
    private byte[] valueBytes;

    public static void setValueSize(int size) {
        valueSize = size;
    }

    public BenchmarkValue() {
        // default constructor for deserialize
    }

    public BenchmarkValue(long seed) {
        valueBytes = new byte[valueSize];
        Utils.toBytes(seed, valueBytes);
    }

    private BenchmarkValue(byte[] valueBytes) {
        this.valueBytes = Arrays.copyOf(valueBytes, valueBytes.length);
    }

    protected BenchmarkValue(BenchmarkValue other) {
        this(other.valueBytes);
    }

    public BenchmarkValue(final ReadableSequentialData in) {
        final int len = in.readInt();
        valueBytes = new byte[len];
        in.readBytes(valueBytes);
    }

    public long toLong() {
        return Utils.fromBytes(valueBytes);
    }

    public Builder copyBuilder() {
        return new Builder(this);
    }

    public int getSizeInBytes() {
        return Integer.BYTES + valueBytes.length;
    }

    public void writeTo(final WritableSequentialData out) {
        out.writeInt(valueBytes.length);
        out.writeBytes(valueBytes);
    }

    public void serialize(final WritableSequentialData out) {
        out.writeInt(valueBytes.length);
        out.writeBytes(valueBytes);
    }

    public void deserialize(final ReadableSequentialData in) {
        int n = in.readInt();
        valueBytes = new byte[n];
        in.readBytes(valueBytes);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BenchmarkValue that)) return false;
        return Arrays.equals(this.valueBytes, that.valueBytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(valueBytes);
    }

    public static final class Builder {

        private byte[] valueBytes;

        public Builder(final BenchmarkValue value) {
            this.valueBytes = Arrays.copyOf(value.valueBytes, value.valueBytes.length);
        }

        public Builder update(LongUnaryOperator updater) {
            long value = Utils.fromBytes(valueBytes);
            value = updater.applyAsLong(value);
            Utils.toBytes(value, valueBytes);
            return this;
        }

        public BenchmarkValue build() {
            return new BenchmarkValue(valueBytes);
        }
    }
}
