// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.VirtualValue;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class BenchmarkRecord extends BenchmarkValue {

    static final long CLASS_ID = 0x1828f37a141L;
    static final int VERSION = 1;

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

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public VirtualValue copy() {
        return new BenchmarkRecord(this);
    }

    @Override
    public void serialize(SerializableDataOutputStream outputStream) throws IOException {
        outputStream.writeLong(path);
        super.serialize(outputStream);
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
    public void deserialize(SerializableDataInputStream inputStream, int dataVersion) throws IOException {
        assert dataVersion == getVersion() : "dataVersion=" + dataVersion + " != getVersion()=" + getVersion();
        path = inputStream.readLong();
        super.deserialize(inputStream, dataVersion);
    }

    public static int getSerializedSize() {
        return Long.BYTES + BenchmarkValue.getSerializedSize();
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
