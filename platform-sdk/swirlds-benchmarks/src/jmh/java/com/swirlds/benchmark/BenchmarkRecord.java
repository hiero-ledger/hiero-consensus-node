// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;

public class BenchmarkRecord extends BenchmarkValue {

    private long path;

    public BenchmarkRecord() {
        // default constructor for deserialize
    }

    public BenchmarkRecord(long path, long seed) {
        super(seed);
        this.path = path;
    }

    public BenchmarkRecord(BenchmarkRecord other) {
        super(other);
        this.path = other.path;
    }

    public void serialize(final WritableSequentialData out) {
        out.writeLong(path);
        super.serialize(out);
    }

    public void deserialize(final ReadableSequentialData in) {
        path = in.readLong();
        super.deserialize(in);
    }

    @Override
    public int getSizeInBytes() {
        return Long.BYTES + super.getSizeInBytes();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BenchmarkRecord that)) return false;
        if (this.path != that.path) return false;
        return super.equals(that);
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + Long.hashCode(path);
    }
}
