// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.common.FastCopyable;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.serialize.KeySerializer;
import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public class ExampleLongKeyFixedSize implements VirtualKey, FastCopyable {

    private static final long CLASS_ID = 0x6ec21ff5ab56811fL;

    /** random so that for testing we are sure we are getting same version */
    private static final int CURRENT_SERIALIZATION_VERSION = 3054;

    private long value;
    private int hashCode;

    public ExampleLongKeyFixedSize() {}

    public ExampleLongKeyFixedSize(final long value) {
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

    @SuppressWarnings("unchecked")
    public ExampleLongKeyFixedSize copy() {
        return new ExampleLongKeyFixedSize(this.value);
    }

    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeLong(this.value);
    }

    public void deserialize(final SerializableDataInputStream in, final int dataVersion) throws IOException {
        assert dataVersion == getVersion() : "dataVersion=" + dataVersion + " != getVersion()=" + getVersion();
        setValue(in.readLong());
    }

    public long getClassId() {
        return CLASS_ID;
    }

    public int getVersion() {
        return CURRENT_SERIALIZATION_VERSION;
    }

    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof ExampleLongKeyFixedSize that)) {
            return false;
        } else {
            return this.value == that.value;
        }
    }

    @Override
    public String toString() {
        return "LongVirtualKey{" + "value=" + value + ", hashCode=" + hashCode + '}';
    }

    public static class Serializer implements KeySerializer<ExampleLongKeyFixedSize> {

        private static final long CLASS_ID = 0x58a0db3356d8ec69L;

        private static final class ClassVersion {
            public static final int ORIGINAL = 1;
        }

        /**
         * Get the number of bytes a data item takes when serialized
         *
         * @return Either a number of bytes or DataFileCommon.VARIABLE_DATA_SIZE if size is variable
         */
        @Override
        public int getSerializedSize() {
            return Long.BYTES;
        }

        /** Get the current data item serialization version */
        @Override
        public long getCurrentDataVersion() {
            return CURRENT_SERIALIZATION_VERSION;
        }

        @Override
        public ExampleLongKeyFixedSize deserialize(ReadableSequentialData in) {
            final long value = in.readLong();
            return new ExampleLongKeyFixedSize(value);
        }

        @Override
        public void serialize(ExampleLongKeyFixedSize data, WritableSequentialData out) {
            out.writeLong(data.getValue());
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
        public boolean equals(BufferedData buffer, ExampleLongKeyFixedSize keyToCompare) {
            final long readKey = buffer.readLong();
            return readKey == keyToCompare.getValue();
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
