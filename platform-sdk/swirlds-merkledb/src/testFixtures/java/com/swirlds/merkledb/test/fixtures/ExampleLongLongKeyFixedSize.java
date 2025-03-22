// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import static com.swirlds.common.utility.NonCryptographicHashing.hash32;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.common.FastCopyable;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.serialize.KeySerializer;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

@SuppressWarnings("unused")
public class ExampleLongLongKeyFixedSize implements VirtualKey, FastCopyable {

    /** random so that for testing we are sure we are getting same version */
    private static final int CURRENT_SERIALIZATION_VERSION = 4685;

    private long value1;
    private long value2;

    public ExampleLongLongKeyFixedSize() {}

    public ExampleLongLongKeyFixedSize(final long value) {
        this.value1 = value;
        this.value2 = Long.MAX_VALUE - value;
    }

    public ExampleLongLongKeyFixedSize(final long value1, final long value2) {
        this.value1 = value1;
        this.value2 = value2;
    }

    public long getValue() {
        return value1;
    }

    public int hashCode() {
        return hash32(value1, value2);
    }

    @SuppressWarnings("unchecked")
    public ExampleLongLongKeyFixedSize copy() {
        return new ExampleLongLongKeyFixedSize(this.value1, this.value2);
    }

    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(this.value1);
        out.writeLong(this.value2);
    }

    public void deserialize(final SerializableDataInputStream in, final int dataVersion) throws IOException {
        assert dataVersion == getVersion() : "dataVersion=" + dataVersion + " != getVersion()=" + getVersion();
        this.value1 = in.readLong();
        this.value2 = in.readLong();
    }

    public long getClassId() {
        return 654838434445546360L;
    }

    public int getVersion() {
        return CURRENT_SERIALIZATION_VERSION;
    }

    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof ExampleLongLongKeyFixedSize)) {
            return false;
        } else {
            final ExampleLongLongKeyFixedSize that = (ExampleLongLongKeyFixedSize) o;
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
        return "ExampleLongLongKeyFixedSize{" + "value1=" + value1 + ", value2=" + value2 + '}';
    }

    public static class Serializer implements KeySerializer<ExampleLongLongKeyFixedSize> {

        private static final long CLASS_ID = 0xee6fa2534c50d634L;

        private static final class ClassVersion {
            public static final int ORIGINAL = 1;
        }

        public Serializer() {}

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
         * Get the number of bytes a data item takes when serialized
         *
         * @return Either a number of bytes or DataFileCommon.VARIABLE_DATA_SIZE if size is variable
         */
        @Override
        public int getSerializedSize() {
            return Long.BYTES + Long.BYTES;
        }

        /** Get the current data item serialization version */
        @Override
        public long getCurrentDataVersion() {
            return CURRENT_SERIALIZATION_VERSION;
        }

        @Override
        public ExampleLongLongKeyFixedSize deserialize(ReadableSequentialData in) {
            final long value1 = in.readLong();
            final long value2 = in.readLong();
            return new ExampleLongLongKeyFixedSize(value1, value2);
        }

        @Override
        public void serialize(ExampleLongLongKeyFixedSize data, WritableSequentialData out) {
            out.writeLong(data.getValue1());
            out.writeLong(data.getValue2());
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
        public boolean equals(final BufferedData buffer, final ExampleLongLongKeyFixedSize keyToCompare) {
            final long readValue1 = buffer.readLong();
            final long readValue2 = buffer.readLong();
            return readValue1 == keyToCompare.getValue1() && readValue2 == keyToCompare.getValue2();
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
