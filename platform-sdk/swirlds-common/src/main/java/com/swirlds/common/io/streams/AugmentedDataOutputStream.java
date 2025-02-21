// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams;

import static com.swirlds.common.io.streams.SerializableStreamConstants.DEFAULT_CHECKSUM;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_INSTANT_EPOCH_SECOND;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_LIST_ARRAY_LENGTH;

import com.swirlds.common.utility.CommonUtils;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;

/**
 * This data output stream provides additional functionality for serializing various basic data structures.
 */
public class AugmentedDataOutputStream extends DataOutputStream {

    public AugmentedDataOutputStream(OutputStream out) {
        super(out);
    }

    /**
     * Writes a byte array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @param writeChecksum
     * 		whether to read the checksum or not
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes a byte array to the stream. Can be null.
     * Same as {@link #writeByteArray(byte[], boolean)} with {@code writeChecksum} set to false
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public void writeByteArray(byte[] data) throws IOException {
        writeByteArray(data, DEFAULT_CHECKSUM);
    }

    /**
     * Writes an int array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes an int list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes a long array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes a long list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes a boolean list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes a float array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes a float list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes a double array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes a double list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes a String array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Writes a string list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
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
     * Normalizes the string in accordance with the Swirlds default normalization method (NFD) and writes it
     * to the output stream encoded in the Swirlds default charset (UTF8). This is important for having a
     * consistent method of converting Strings to bytes that will guarantee that two identical strings will
     * have an identical byte representation
     *
     * @param s
     * 		the String to be converted and written
     * @throws IOException
     * 		thrown if there are any problems during the operation
     */
    public void writeNormalisedString(String s) throws IOException {
        byte[] data = CommonUtils.getNormalisedStringBytes(s);
        this.writeByteArray(data);
    }

    /**
     * Write an Instant to the stream
     *
     * @param instant
     * 		the instant to write
     * @throws IOException
     * 		thrown if there are any problems during the operation
     */
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
        int totalByteLength = Integer.BYTES; // add the the size of array length field
        if (writeChecksum) {
            totalByteLength += Integer.BYTES; // add the length of checksum
        }
        totalByteLength += (data == null) ? 0 : data.length;
        return totalByteLength;
    }
}
