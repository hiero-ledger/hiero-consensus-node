// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import com.swirlds.virtualmap.VirtualValue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

@SuppressWarnings("ResultOfMethodCallIgnored")
public final class ExampleVariableSizeVirtualValue extends ExampleByteArrayVirtualValue {

    private static final int RANDOM_BYTES = 1024;
    private static final byte[] RANDOM_DATA = new byte[RANDOM_BYTES];
    private static final Random RANDOM = new Random(12234);
    public static final int SERIALIZATION_VERSION = 865;

    static {
        RANDOM.nextBytes(RANDOM_DATA);
    }

    private int id;
    private byte[] data;

    public ExampleVariableSizeVirtualValue() {}

    public ExampleVariableSizeVirtualValue(final int id) {
        this.id = id;
        data = new byte[256 + (id % 768)];
        System.arraycopy(RANDOM_DATA, 0, data, 0, data.length);
    }

    public ExampleVariableSizeVirtualValue(final int id, final byte[] data) {
        this.id = id;
        this.data = new byte[data.length];
        System.arraycopy(data, 0, this.data, 0, data.length);
    }

    public int getId() {
        return id;
    }

    public byte[] getData() {
        return data;
    }

    public int getDataLength() {
        return data.length;
    }

    @Override
    public void deserialize(final SerializableDataInputStream inputStream, final int dataVersion) throws IOException {
        assert dataVersion == getVersion() : "dataVersion=" + dataVersion + " != getVersion()=" + getVersion();
        id = inputStream.readInt();
        final int dataLength = inputStream.readInt();
        data = new byte[dataLength];
        inputStream.read(data);
    }

    @Override
    public void serialize(final SerializableDataOutputStream outputStream) throws IOException {
        outputStream.writeInt(id);
        outputStream.writeInt(data.length);
        outputStream.write(data);
    }

    void serialize(final ByteBuffer buffer) {
        buffer.putInt(id);
        buffer.putInt(data.length);
        buffer.put(data);
    }

    void deserialize(final ByteBuffer buffer, final int dataVersion) {
        assert dataVersion == getVersion() : "dataVersion=" + dataVersion + " != getVersion()=" + getVersion();
        id = buffer.getInt();
        final int dataLength = buffer.getInt();
        assert dataLength > 0 : "DataLength[" + dataLength + "] should never be 0 or less";
        data = new byte[dataLength];
        buffer.get(data);
    }

    @Override
    public VirtualValue copy() {
        return this;
    }

    @Override
    public VirtualValue asReadOnly() {
        return this;
    }

    @Override
    public long getClassId() {
        return 846821551352L;
    }

    @Override
    public int getVersion() {
        return SERIALIZATION_VERSION;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ExampleVariableSizeVirtualValue that = (ExampleVariableSizeVirtualValue) o;
        return id == that.id && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "ExampleVariableSizeVirtualValue{" + "id=" + id + ", data=" + Arrays.toString(data) + '}';
    }
}
