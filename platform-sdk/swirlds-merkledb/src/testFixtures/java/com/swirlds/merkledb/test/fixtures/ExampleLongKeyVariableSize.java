// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.serialize.KeySerializer;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class ExampleLongKeyVariableSize implements VirtualKey {

    /** random so that for testing we are sure we are getting same version */
    private static final int CURRENT_SERIALIZATION_VERSION = 8483;

    private long value;
    private int hashCode;

    public ExampleLongKeyVariableSize() {}

    public ExampleLongKeyVariableSize(final long value) {
        setValue(value);
    }

    public long getValue() {
        return value;
    }

    public void setValue(final long value) {
        this.value = value;
        this.hashCode = Long.hashCode(value);
    }

    public int hashCode() {
        return this.hashCode;
    }

    public void serialize(final WritableSequentialData out) {
        final int numOfBytes = computeNonZeroBytes(value);
        out.writeByte((byte) numOfBytes);
        for (int b = numOfBytes - 1; b >= 0; b--) {
            out.writeByte((byte) (value >> (b * 8)));
        }
    }

    public void deserialize(final ReadableSequentialData in) {
        byte numOfBytes = in.readByte();
        long value = 0;
        if (numOfBytes >= 8) value |= ((long) in.readByte() & 255) << 56;
        if (numOfBytes >= 7) value |= ((long) in.readByte() & 255) << 48;
        if (numOfBytes >= 6) value |= ((long) in.readByte() & 255) << 40;
        if (numOfBytes >= 5) value |= ((long) in.readByte() & 255) << 32;
        if (numOfBytes >= 4) value |= ((long) in.readByte() & 255) << 24;
        if (numOfBytes >= 3) value |= ((long) in.readByte() & 255) << 16;
        if (numOfBytes >= 2) value |= ((long) in.readByte() & 255) << 8;
        if (numOfBytes >= 1) value |= ((long) in.readByte() & 255);
        setValue(value);
    }

    public ExampleLongKeyFixedSize copy() {
        return new ExampleLongKeyFixedSize(this.value);
    }

    public void serialize(final SerializableDataOutputStream outputStream) throws IOException {
        final int numOfBytes = computeNonZeroBytes(value);
        outputStream.write(numOfBytes);
        for (int b = numOfBytes - 1; b >= 0; b--) {
            outputStream.write((byte) (value >> (b * 8)));
        }
    }

    public void deserialize(final SerializableDataInputStream in, final int dataVersion) throws IOException {
        assert dataVersion == getVersion() : "dataVersion=" + dataVersion + " != getVersion()=" + getVersion();
        final byte numOfBytes = in.readByte();
        long value = 0;
        if (numOfBytes >= 8) value |= ((long) in.readByte() & 255) << 56;
        if (numOfBytes >= 7) value |= ((long) in.readByte() & 255) << 48;
        if (numOfBytes >= 6) value |= ((long) in.readByte() & 255) << 40;
        if (numOfBytes >= 5) value |= ((long) in.readByte() & 255) << 32;
        if (numOfBytes >= 4) value |= ((long) in.readByte() & 255) << 24;
        if (numOfBytes >= 3) value |= ((long) in.readByte() & 255) << 16;
        if (numOfBytes >= 2) value |= ((long) in.readByte() & 255) << 8;
        if (numOfBytes >= 1) value |= ((long) in.readByte() & 255);
        setValue(value);
    }

    public long getClassId() {
        return 5614541343884544469L;
    }

    public int getVersion() {
        return CURRENT_SERIALIZATION_VERSION;
    }

    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof final ExampleLongKeyVariableSize that)) {
            return false;
        } else {
            return this.value == that.value;
        }
    }

    @Override
    public String toString() {
        return "ExampleVariableSizeLongKey{" + "value=" + value + ", hashCode=" + hashCode + '}';
    }

    /**
     * Compute number of bytes of non-zero data are there from the least significant side of a long.
     *
     * @param num the long to count non-zero bits for
     * @return the number of non-zero bytes, Minimum 1, we always write at least 1 byte even for
     *     value 0
     */
    static byte computeNonZeroBytes(final long num) {
        if (num == 0) {
            return (byte) 1;
        }
        return (byte) Math.ceil((double) (Long.SIZE - Long.numberOfLeadingZeros(num)) / 8D);
    }

    public static class Serializer implements KeySerializer<ExampleLongKeyVariableSize> {

        private static final long CLASS_ID = 0x8cc51b9601c706e7L;

        private static final class ClassVersion {
            public static final int ORIGINAL = 1;
        }

        /** {@inheritDoc} */
        @Override
        public long getClassId() {
            return CLASS_ID;
        }

        /** {@inheritDoc} */
        @Override
        public int getVersion() {
            return ClassVersion.ORIGINAL;
        }

        /**
         * For variable sized data get the typical number of bytes a data item takes when serialized
         *
         * @return Either for fixed size same as getSerializedSize() or an estimated typical size
         *     for data items
         */
        @Override
        public int getTypicalSerializedSize() {
            return 1 + Long.BYTES;
        }

        /**
         * Get the number of bytes a data item takes when serialized
         *
         * @return Either a number of bytes or DataFileCommon.VARIABLE_DATA_SIZE if size is variable
         */
        @Override
        public int getSerializedSize() {
            return VARIABLE_DATA_SIZE;
        }

        /** Get the current data item serialization version */
        @Override
        public long getCurrentDataVersion() {
            return CURRENT_SERIALIZATION_VERSION; // random so that for testing we are sure we are
            // getting same version
        }

        @Override
        public int getSerializedSize(ExampleLongKeyVariableSize data) {
            return 1 + computeNonZeroBytes(data.getValue());
        }

        @Override
        public ExampleLongKeyVariableSize deserialize(ReadableSequentialData in) {
            final ExampleLongKeyVariableSize key = new ExampleLongKeyVariableSize();
            key.deserialize(in);
            return key;
        }

        @Override
        public void serialize(ExampleLongKeyVariableSize data, WritableSequentialData out) {
            data.serialize(out);
        }

        /**
         * Compare keyToCompare's data to that contained in the given ByteBuffer. The data in the
         * buffer is assumed to be starting at the current buffer position and in the format written
         * by this class's serialize() method. The reason for this rather than just deserializing
         * then doing an object equals is performance. By doing the comparison here you can fail
         * fast on the first byte that does not match. As this is used in a tight loop in searching
         * a hash map bucket for a match performance is critical.
         *
         * @param buffer The buffer to read from and compare to
         * @param keyToCompare The key to compare with the data in the file.
         * @return true if the content of the buffer matches this class's data
         */
        @Override
        public boolean equals(final BufferedData buffer, final ExampleLongKeyVariableSize keyToCompare) {
            byte numOfBytes = buffer.readByte();
            long value = 0;
            if (numOfBytes >= 8) value |= ((long) buffer.readByte() & 255) << 56;
            if (numOfBytes >= 7) value |= ((long) buffer.readByte() & 255) << 48;
            if (numOfBytes >= 6) value |= ((long) buffer.readByte() & 255) << 40;
            if (numOfBytes >= 5) value |= ((long) buffer.readByte() & 255) << 32;
            if (numOfBytes >= 4) value |= ((long) buffer.readByte() & 255) << 24;
            if (numOfBytes >= 3) value |= ((long) buffer.readByte() & 255) << 16;
            if (numOfBytes >= 2) value |= ((long) buffer.readByte() & 255) << 8;
            if (numOfBytes >= 1) value |= ((long) buffer.readByte() & 255);
            return value == keyToCompare.getValue();
        }

        /** {@inheritDoc} */
        @Override
        public int hashCode() {
            return super.hashCode();
        }

        /** {@inheritDoc} */
        @Override
        public boolean equals(final Object obj) {
            // Since there is no class state, objects of the same type are considered to be equal
            return obj instanceof Serializer;
        }
    }
}
