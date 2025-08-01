// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.test.fixtures;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.swirlds.virtualmap.serialize.BaseSerializer;
import org.hiero.base.constructable.ConstructableIgnored;

/**
 * Very simple DataItem that is fixed size and has a long key and long value. Designed for testing
 */
@ConstructableIgnored
public class ExampleFixedSizeDataSerializer implements BaseSerializer<long[]> {

    /**
     * Get the number of bytes a data item takes when serialized
     *
     * @return Either a number of bytes or DataFileCommon.VARIABLE_DATA_SIZE if size is variable
     */
    @Override
    public int getSerializedSize() {
        return Long.BYTES * 2;
    }

    /** Get the current data item serialization version */
    @Override
    public long getCurrentDataVersion() {
        return 1;
    }

    @Override
    public void serialize(final long[] data, final WritableSequentialData out) {
        out.writeLong(data[0]);
        out.writeLong(data[1]);
    }

    @Override
    public long[] deserialize(final ReadableSequentialData in) {
        return new long[] {in.readLong(), in.readLong()};
    }
}
