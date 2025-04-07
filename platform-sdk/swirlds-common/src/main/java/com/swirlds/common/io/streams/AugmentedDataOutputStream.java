// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams;

import static com.swirlds.common.io.streams.SerializableStreamConstants.DEFAULT_CHECKSUM;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_INSTANT_EPOCH_SECOND;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_LIST_ARRAY_LENGTH;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.utility.CommonUtils;

/**
 * This data output stream provides additional functionality for serializing various basic data structures.
 */
public abstract class AugmentedDataOutputStream extends SerializableDataOutputStream {

    protected AugmentedDataOutputStream(OutputStream out) {
        super(out);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeByteArray(byte[] data, boolean writeChecksum) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
            return;
        }
        this.writeInt(data.length);
        if (writeChecksum) {
            // write a simple checksum to detect if at wrong place in the stream
            this.writeInt(101 - data.length);
        }
        this.write(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeByteArray(byte[] data) throws IOException {
        writeByteArray(data, DEFAULT_CHECKSUM);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeIntArray(int[] data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.length);
            for (int datum : data) {
                writeInt(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeIntList(List<Integer> data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.size());
            for (int datum : data) {
                writeInt(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeLongArray(long[] data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.length);
            for (long datum : data) {
                writeLong(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeLongList(List<Long> data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.size());
            for (long datum : data) {
                writeLong(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeBooleanList(List<Boolean> data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.size());
            for (boolean datum : data) {
                writeBoolean(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeFloatArray(float[] data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.length);
            for (float datum : data) {
                writeFloat(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeFloatList(List<Float> data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.size());
            for (Float datum : data) {
                writeFloat(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeDoubleArray(double[] data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.length);
            for (double datum : data) {
                writeDouble(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeDoubleList(List<Double> data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.size());
            for (Double datum : data) {
                writeDouble(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeStringArray(String[] data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.length);
            for (String datum : data) {
                writeNormalisedString(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeStringList(List<String> data) throws IOException {
        if (data == null) {
            this.writeInt(NULL_LIST_ARRAY_LENGTH);
        } else {
            this.writeInt(data.size());
            for (String datum : data) {
                writeNormalisedString(datum);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeNormalisedString(String s) throws IOException {
        byte[] data = CommonUtils.getNormalisedStringBytes(s);
        this.writeByteArray(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeInstant(Instant instant) throws IOException {
        if (instant == null) {
            this.writeLong(NULL_INSTANT_EPOCH_SECOND);
            return;
        }
        this.writeLong(instant.getEpochSecond());
        this.writeLong(instant.getNano());
    }

    /**
     * Get serialized length of a long array
     *
     * @param data
     * 		the array to write
     */
    public static int getArraySerializedLength(final long[] data) {
        int totalByteLength = Integer.BYTES;
        totalByteLength += (data == null) ? 0 : (data.length * Long.BYTES);
        return totalByteLength;
    }

    /**
     * Get serialized length of an integer array
     *
     * @param data
     * 		the array to write
     */
    public static int getArraySerializedLength(final int[] data) {
        int totalByteLength = Integer.BYTES;
        totalByteLength += (data == null) ? 0 : (data.length * Integer.BYTES);
        return totalByteLength;
    }

    /**
     * Get serialized length of a byte array
     *
     * @param data
     * 		the array to write
     */
    public static int getArraySerializedLength(final byte[] data) {
        return getArraySerializedLength(data, DEFAULT_CHECKSUM);
    }

    /**
     * Get serialized length of a byte array
     *
     * @param data
     * 		the array to write
     * @param writeChecksum
     * 		whether to read the checksum or not
     */
    public static int getArraySerializedLength(final byte[] data, final boolean writeChecksum) {
        int totalByteLength = Integer.BYTES; // add the size of array length field
        if (writeChecksum) {
            totalByteLength += Integer.BYTES; // add the length of checksum
        }
        totalByteLength += (data == null) ? 0 : data.length;
        return totalByteLength;
    }
}
