// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams;

import static com.swirlds.common.io.streams.SerializableStreamConstants.DEFAULT_CHECKSUM;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_INSTANT_EPOCH_SECOND;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_LIST_ARRAY_LENGTH;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hiero.consensus.model.io.exceptions.BadIOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.utility.CommonUtils;

/**
 * This data input stream provides additional functionality for deserializing various basic data structures.
 */
public abstract class AugmentedDataInputStream extends SerializableDataInputStream {

    private final DataInputStream baseStream;

    /**
     * Create an input stream capable of deserializing a variety of useful objects.
     *
     * @param in
     * 		the base input stream
     */
    protected AugmentedDataInputStream(final InputStream in) {
        baseStream = new DataInputStream(in);
    }

    /**
     * Corresponds to {@link DataInputStream#available()}.
     */
    @Override
    public int available() throws IOException {
        return baseStream.available();
    }

    /**
     * Closes the stream.
     */
    @Override
    public void close() throws IOException {
        baseStream.close();
    }

    /**
     * Corresponds to {@link DataInputStream#read()}.
     */
    @Override
    public int read() throws IOException {
        return baseStream.read();
    }

    /**
     * Corresponds to {@link DataInputStream#read(byte[], int, int)}.
     */
    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        return baseStream.read(b, off, len);
    }

    /**
     * Corresponds to {@link DataInputStream#skip(long)}.
     */
    @Override
    public long skip(final long n) throws IOException {
        return baseStream.skip(n);
    }

    /**
     * Corresponds to {@link DataInputStream#readAllBytes()}.
     */
    @Override
    public byte[] readAllBytes() throws IOException {
        return baseStream.readAllBytes();
    }

    /**
     * Corresponds to {@link DataInputStream#readNBytes(int)}.
     */
    @Override
    public byte[] readNBytes(final int len) throws IOException {
        return baseStream.readNBytes(len);
    }

    /**
     * Corresponds to {@link DataInputStream#readNBytes(byte[], int, int)}.
     */
    @Override
    public int readNBytes(final byte[] b, final int off, final int len) throws IOException {
        return baseStream.readNBytes(b, off, len);
    }

    /**
     * Corresponds to {@link DataInputStream#skipNBytes(long)}.
     */
    @Override
    public void skipNBytes(final long n) throws IOException {
        baseStream.skipNBytes(n);
    }

    @Override
    public void readFully(final byte[] b) throws IOException {
        baseStream.readFully(b);
    }

    @Override
    public void readFully(final byte[] b, final int off, final int len) throws IOException {
        baseStream.readFully(b, off, len);
    }

    @Override
    public int skipBytes(final int n) throws IOException {
        return baseStream.skipBytes(n);
    }

    @Override
    public boolean readBoolean() throws IOException {
        return baseStream.readBoolean();
    }

    @Override
    public byte readByte() throws IOException {
        return baseStream.readByte();
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return baseStream.readUnsignedByte();
    }

    @Override
    public short readShort() throws IOException {
        return baseStream.readShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return baseStream.readUnsignedShort();
    }

    @Override
    public char readChar() throws IOException {
        return baseStream.readChar();
    }

    @Override
    public int readInt() throws IOException {
        return baseStream.readInt();
    }

    @Override
    public long readLong() throws IOException {
        return baseStream.readLong();
    }

    @Override
    public float readFloat() throws IOException {
        return baseStream.readFloat();
    }

    @Override
    public double readDouble() throws IOException {
        return baseStream.readDouble();
    }

    @Override
    public String readUTF() throws IOException {
        return baseStream.readUTF();
    }

    @Override
    @Deprecated
    public String readLine() throws IOException {
        return baseStream.readLine();
    }

    @Override
    @Nullable
    public byte[] readByteArray(final int maxLength, final boolean readChecksum) throws IOException {
        int len = this.readInt();
        if (len < 0) {
            // if length is negative, it's a null value
            return null;
        }
        if (readChecksum) {
            int checksum = readInt();
            if (checksum != (101 - len)) { // must be at wrong place in the stream
                throw new BadIOException(
                        "SerializableDataInputStream tried to create array of length " + len + " with wrong checksum.");
            }
        }
        byte[] bytes;
        checkLengthLimit(len, maxLength);
        bytes = new byte[len];
        this.readFully(bytes);

        return bytes;
    }

    @Override
    @Nullable
    public byte[] readByteArray(final int maxLength) throws IOException {
        return readByteArray(maxLength, DEFAULT_CHECKSUM);
    }

    @Override
    @Nullable
    public int[] readIntArray(final int maxLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        int[] data = new int[len];
        for (int i = 0; i < len; i++) {
            data[i] = readInt();
        }
        return data;
    }

    @Override
    @Nullable
    public List<Integer> readIntList(final int maxLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        List<Integer> data = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            data.add(readInt());
        }
        return data;
    }

    @Override
    @Nullable
    public long[] readLongArray(final int maxLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        long[] data = new long[len];
        for (int i = 0; i < len; i++) {
            data[i] = readLong();
        }
        return data;
    }

    @Override
    @Nullable
    public List<Long> readLongList(final int maxLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        List<Long> data = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            data.add(readLong());
        }
        return data;
    }

    @Override
    @Nullable
    public List<Boolean> readBooleanList(final int maxLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        List<Boolean> data = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            data.add(readBoolean());
        }
        return data;
    }

    @Override
    @Nullable
    public float[] readFloatArray(final int maxLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        float[] data = new float[len];
        for (int i = 0; i < len; i++) {
            data[i] = readFloat();
        }

        return data;
    }

    @Override
    @Nullable
    public List<Float> readFloatList(final int maxLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        List<Float> data = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            data.add(readFloat());
        }
        return data;
    }

    @Override
    @Nullable
    public double[] readDoubleArray(final int maxLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        double[] data = new double[len];
        for (int i = 0; i < len; i++) {
            data[i] = readDouble();
        }
        return data;
    }

    @Override
    @Nullable
    public List<Double> readDoubleList(final int maxLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        List<Double> data = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            data.add(readDouble());
        }
        return data;
    }

    @Override
    @Nullable
    public String[] readStringArray(final int maxLength, final int maxStringLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        String[] data = new String[len];
        for (int i = 0; i < len; i++) {
            data[i] = readNormalisedString(maxStringLength);
        }
        return data;
    }

    @Override
    @Nullable
    public List<String> readStringList(final int maxLength, final int maxStringLength) throws IOException {
        int len = readInt();
        if (len == NULL_LIST_ARRAY_LENGTH) {
            // if length is negative, it's a null value
            return null;
        }
        checkLengthLimit(len, maxLength);
        List<String> data = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            data.add(readNormalisedString(maxStringLength));
        }
        return data;
    }

    @Override
    @Nullable
    public Instant readInstant() throws IOException {
        long epochSecond = this.readLong(); // from getEpochSecond()
        if (epochSecond == NULL_INSTANT_EPOCH_SECOND) {
            return null;
        }

        long nanos = this.readLong();
        if (nanos < 0 || nanos > 999_999_999) {
            throw new IOException("Instant.nanosecond is not within the allowed range!");
        }
        return Instant.ofEpochSecond(epochSecond, nanos);
    }

    @Override
    @Nullable
    public String readNormalisedString(final int maxLength) throws IOException {
        byte[] data = readByteArray(maxLength);
        if (data == null) {
            return null;
        }

        return CommonUtils.getNormalisedStringFromBytes(data);
    }

    protected void checkLengthLimit(final int length, final int maxLength) throws IOException {
        if (length > maxLength) {
            throw new IOException(String.format(
                    "The input stream provided a length of %d for the list/array "
                            + "which exceeds the maxLength of %d",
                    length, maxLength));
        }
    }
}
