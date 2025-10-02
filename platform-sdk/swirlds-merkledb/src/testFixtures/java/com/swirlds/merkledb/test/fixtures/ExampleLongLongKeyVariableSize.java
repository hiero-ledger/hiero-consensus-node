// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import static org.hiero.base.utility.NonCryptographicHashing.hash32;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.serialize.KeySerializer;
import java.io.IOException;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

@ConstructableIgnored
public class ExampleLongLongKeyVariableSize implements VirtualKey {

    /** random so that for testing we are sure we are getting same version */
    private static final int CURRENT_SERIALIZATION_VERSION = 1235;

    private long value1;
    private long value2;

    public ExampleLongLongKeyVariableSize() {}

    public ExampleLongLongKeyVariableSize(final long value) {
        this.value1 = value;
        this.value2 = Long.MAX_VALUE - value;
    }

    public ExampleLongLongKeyVariableSize(final long value1, final long value2) {
        this.value1 = value1;
        this.value2 = value2;
    }

    public long getValue() {
        return value1;
    }

    public int hashCode() {
        return hash32(value1, value2);
    }

    public void serialize(final WritableSequentialData out) {
        final int numOfBytes1 = computeNonZeroBytes(value1);
        final int numOfBytes2 = computeNonZeroBytes(value2);
        out.writeByte((byte) numOfBytes1);
        out.writeByte((byte) numOfBytes2);
        for (int b = numOfBytes1 - 1; b >= 0; b--) {
            out.writeByte((byte) (value1 >> (b * 8)));
        }
        for (int b = numOfBytes2 - 1; b >= 0; b--) {
            out.writeByte((byte) (value2 >> (b * 8)));
        }
    }

    public void deserialize(final ReadableSequentialData in) {
        byte numOfBytes1 = in.readByte();
        byte numOfBytes2 = in.readByte();
        long value1 = 0;
        if (numOfBytes1 >= 8) value1 |= ((long) in.readByte() & 255) << 56;
        if (numOfBytes1 >= 7) value1 |= ((long) in.readByte() & 255) << 48;
        if (numOfBytes1 >= 6) value1 |= ((long) in.readByte() & 255) << 40;
        if (numOfBytes1 >= 5) value1 |= ((long) in.readByte() & 255) << 32;
        if (numOfBytes1 >= 4) value1 |= ((long) in.readByte() & 255) << 24;
        if (numOfBytes1 >= 3) value1 |= ((long) in.readByte() & 255) << 16;
        if (numOfBytes1 >= 2) value1 |= ((long) in.readByte() & 255) << 8;
        if (numOfBytes1 >= 1) value1 |= ((long) in.readByte() & 255);
        this.value1 = value1;
        long value2 = 0;
        if (numOfBytes2 >= 8) value2 |= ((long) in.readByte() & 255) << 56;
        if (numOfBytes2 >= 7) value2 |= ((long) in.readByte() & 255) << 48;
        if (numOfBytes2 >= 6) value2 |= ((long) in.readByte() & 255) << 40;
        if (numOfBytes2 >= 5) value2 |= ((long) in.readByte() & 255) << 32;
        if (numOfBytes2 >= 4) value2 |= ((long) in.readByte() & 255) << 24;
        if (numOfBytes2 >= 3) value2 |= ((long) in.readByte() & 255) << 16;
        if (numOfBytes2 >= 2) value2 |= ((long) in.readByte() & 255) << 8;
        if (numOfBytes2 >= 1) value2 |= ((long) in.readByte() & 255);
        this.value2 = value2;
    }

    public ExampleLongLongKeyVariableSize copy() {
        return new ExampleLongLongKeyVariableSize(this.value1, value2);
    }

    public void serialize(final SerializableDataOutputStream outputStream) throws IOException {
        final int numOfBytes1 = computeNonZeroBytes(value1);
        final int numOfBytes2 = computeNonZeroBytes(value2);
        outputStream.write(numOfBytes1);
        outputStream.write(numOfBytes2);
        for (int b = numOfBytes1 - 1; b >= 0; b--) {
            outputStream.write((byte) (value1 >> (b * 8)));
        }
        for (int b = numOfBytes2 - 1; b >= 0; b--) {
            outputStream.write((byte) (value2 >> (b * 8)));
        }
    }

    public void deserialize(final SerializableDataInputStream in, final int dataVersion) throws IOException {
        assert dataVersion == getVersion() : "dataVersion=" + dataVersion + " != getVersion()=" + getVersion();
        byte numOfBytes1 = in.readByte();
        byte numOfBytes2 = in.readByte();
        long value1 = 0;
        if (numOfBytes1 >= 8) value1 |= ((long) in.readByte() & 255) << 56;
        if (numOfBytes1 >= 7) value1 |= ((long) in.readByte() & 255) << 48;
        if (numOfBytes1 >= 6) value1 |= ((long) in.readByte() & 255) << 40;
        if (numOfBytes1 >= 5) value1 |= ((long) in.readByte() & 255) << 32;
        if (numOfBytes1 >= 4) value1 |= ((long) in.readByte() & 255) << 24;
        if (numOfBytes1 >= 3) value1 |= ((long) in.readByte() & 255) << 16;
        if (numOfBytes1 >= 2) value1 |= ((long) in.readByte() & 255) << 8;
        if (numOfBytes1 >= 1) value1 |= ((long) in.readByte() & 255);
        this.value1 = value1;
        long value2 = 0;
        if (numOfBytes2 >= 8) value2 |= ((long) in.readByte() & 255) << 56;
        if (numOfBytes2 >= 7) value2 |= ((long) in.readByte() & 255) << 48;
        if (numOfBytes2 >= 6) value2 |= ((long) in.readByte() & 255) << 40;
        if (numOfBytes2 >= 5) value2 |= ((long) in.readByte() & 255) << 32;
        if (numOfBytes2 >= 4) value2 |= ((long) in.readByte() & 255) << 24;
        if (numOfBytes2 >= 3) value2 |= ((long) in.readByte() & 255) << 16;
        if (numOfBytes2 >= 2) value2 |= ((long) in.readByte() & 255) << 8;
        if (numOfBytes2 >= 1) value2 |= ((long) in.readByte() & 255);
        this.value2 = value2;
    }

    public long getClassId() {
        return 6484134134848452L;
    }

    public int getVersion() {
        return CURRENT_SERIALIZATION_VERSION;
    }

    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof ExampleLongLongKeyVariableSize)) {
            return false;
        } else {
            final ExampleLongLongKeyVariableSize that = (ExampleLongLongKeyVariableSize) o;
            return this.value1 == that.value1 && this.value2 == that.value2;
        }
    }

    public long getValue1() {
        return value1;
    }

    public long getValue2() {
        return value2;
    }

    @Override
    public String toString() {
        return "ExampleLongLongKeyVariableSize{" + "value1=" + value1 + ", value2=" + value2 + '}';
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

    public static class Serializer implements KeySerializer<ExampleLongLongKeyVariableSize> {

        private static final long CLASS_ID = 0x6687e6bfe46404e9L;

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
            return 2 + Long.BYTES;
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
            return CURRENT_SERIALIZATION_VERSION;
        }

        @Override
        public int getSerializedSize(ExampleLongLongKeyVariableSize data) {
            return 2 + computeNonZeroBytes(data.value1) + computeNonZeroBytes(data.value2);
        }

        @Override
        public ExampleLongLongKeyVariableSize deserialize(ReadableSequentialData in) {
            final ExampleLongLongKeyVariableSize key = new ExampleLongLongKeyVariableSize();
            key.deserialize(in);
            return key;
        }

        @Override
        public void serialize(ExampleLongLongKeyVariableSize data, WritableSequentialData out) {
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
        public boolean equals(final BufferedData buffer, final ExampleLongLongKeyVariableSize keyToCompare) {
            byte numOfBytes1 = buffer.readByte();
            byte numOfBytes2 = buffer.readByte();
            long value1 = 0;
            if (numOfBytes1 >= 8) value1 |= ((long) buffer.readByte() & 255) << 56;
            if (numOfBytes1 >= 7) value1 |= ((long) buffer.readByte() & 255) << 48;
            if (numOfBytes1 >= 6) value1 |= ((long) buffer.readByte() & 255) << 40;
            if (numOfBytes1 >= 5) value1 |= ((long) buffer.readByte() & 255) << 32;
            if (numOfBytes1 >= 4) value1 |= ((long) buffer.readByte() & 255) << 24;
            if (numOfBytes1 >= 3) value1 |= ((long) buffer.readByte() & 255) << 16;
            if (numOfBytes1 >= 2) value1 |= ((long) buffer.readByte() & 255) << 8;
            if (numOfBytes1 >= 1) value1 |= ((long) buffer.readByte() & 255);
            long value2 = 0;
            if (numOfBytes2 >= 8) value2 |= ((long) buffer.readByte() & 255) << 56;
            if (numOfBytes2 >= 7) value2 |= ((long) buffer.readByte() & 255) << 48;
            if (numOfBytes2 >= 6) value2 |= ((long) buffer.readByte() & 255) << 40;
            if (numOfBytes2 >= 5) value2 |= ((long) buffer.readByte() & 255) << 32;
            if (numOfBytes2 >= 4) value2 |= ((long) buffer.readByte() & 255) << 24;
            if (numOfBytes2 >= 3) value2 |= ((long) buffer.readByte() & 255) << 16;
            if (numOfBytes2 >= 2) value2 |= ((long) buffer.readByte() & 255) << 8;
            if (numOfBytes2 >= 1) value2 |= ((long) buffer.readByte() & 255);
            return value1 == keyToCompare.getValue1() && value2 == keyToCompare.getValue2();
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
