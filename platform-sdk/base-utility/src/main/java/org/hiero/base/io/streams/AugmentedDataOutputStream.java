// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.io.streams;

import static org.hiero.base.io.streams.SerializableStreamConstants.DEFAULT_CHECKSUM;
import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_INSTANT_EPOCH_SECOND;
import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_LIST_ARRAY_LENGTH;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import org.hiero.base.utility.CommonUtils;

/**
 * This data output stream provides additional functionality for serializing various basic data structures.
 */
public class AugmentedDataOutputStream extends DataOutputStream {

    public AugmentedDataOutputStream(@NonNull final OutputStream out) {
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
    public void writeByteArray(@Nullable final byte[] data, final boolean writeChecksum) throws IOException {
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
    public void writeByteArray(@Nullable final byte[] data) throws IOException {
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
    public void writeIntArray(@Nullable final int[] data) throws IOException {
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
    public void writeIntList(@Nullable final List<Integer> data) throws IOException {
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
    public void writeLongArray(@Nullable final long[] data) throws IOException {
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
    public void writeLongList(@Nullable final List<Long> data) throws IOException {
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
    public void writeBooleanList(@Nullable final List<Boolean> data) throws IOException {
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
    public void writeFloatArray(@Nullable final float[] data) throws IOException {
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
    public void writeFloatList(@Nullable final List<Float> data) throws IOException {
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
    public void writeDoubleArray(@Nullable final double[] data) throws IOException {
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
    public void writeDoubleList(@Nullable final List<Double> data) throws IOException {
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
    public void writeStringArray(@Nullable final String[] data) throws IOException {
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
    public void writeStringList(@Nullable final List<String> data) throws IOException {
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
    public void writeNormalisedString(@Nullable final String s) throws IOException {
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
    public void writeInstant(@Nullable final Instant instant) throws IOException {
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
    public static int getArraySerializedLength(@Nullable final long[] data) {
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
    public static int getArraySerializedLength(@Nullable final int[] data) {
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
    public static int getArraySerializedLength(@Nullable final byte[] data) {
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
    public static int getArraySerializedLength(@Nullable final byte[] data, final boolean writeChecksum) {
        int totalByteLength = Integer.BYTES; // add the the size of array length field
        if (writeChecksum) {
            totalByteLength += Integer.BYTES; // add the length of checksum
        }
        totalByteLength += (data == null) ? 0 : data.length;
        return totalByteLength;
    }
}
