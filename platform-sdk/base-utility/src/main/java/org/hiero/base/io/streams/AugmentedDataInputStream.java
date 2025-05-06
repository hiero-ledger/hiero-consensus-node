// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.io.streams;

import static org.hiero.base.io.streams.SerializableStreamConstants.DEFAULT_CHECKSUM;
import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_INSTANT_EPOCH_SECOND;
import static org.hiero.base.io.streams.SerializableStreamConstants.NULL_LIST_ARRAY_LENGTH;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hiero.base.io.exceptions.BadIOException;
import org.hiero.base.utility.CommonUtils;

/**
 * This data input stream provides additional functionality for deserializing various basic data structures.
 */
public class AugmentedDataInputStream extends InputStream implements DataInput {

    private final DataInputStream baseStream;

    /**
     * Create an input stream capable of deserializing a variety of useful objects.
     *
     * @param in the base input stream
     */
    public AugmentedDataInputStream(@NonNull final InputStream in) {
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
    public int read(@NonNull final byte[] b, final int off, final int len) throws IOException {
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
    @NonNull
    public byte[] readAllBytes() throws IOException {
        return baseStream.readAllBytes();
    }

    /**
     * Corresponds to {@link DataInputStream#readNBytes(int)}.
     */
    @Override
    @NonNull
    public byte[] readNBytes(final int len) throws IOException {
        return baseStream.readNBytes(len);
    }

    /**
     * Corresponds to {@link DataInputStream#readNBytes(byte[], int, int)}.
     */
    @Override
    public int readNBytes(@NonNull final byte[] b, final int off, final int len) throws IOException {
        return baseStream.readNBytes(b, off, len);
    }

    /**
     * Corresponds to {@link DataInputStream#skipNBytes(long)}.
     */
    @Override
    public void skipNBytes(final long n) throws IOException {
        baseStream.skipNBytes(n);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public void readFully(@NonNull final byte[] b) throws IOException {
        baseStream.readFully(b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readFully(@NonNull final byte[] b, final int off, final int len) throws IOException {
        baseStream.readFully(b, off, len);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int skipBytes(final int n) throws IOException {
        return baseStream.skipBytes(n);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean readBoolean() throws IOException {
        return baseStream.readBoolean();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public byte readByte() throws IOException {
        return baseStream.readByte();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public int readUnsignedByte() throws IOException {
        return baseStream.readUnsignedByte();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public short readShort() throws IOException {
        return baseStream.readShort();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public int readUnsignedShort() throws IOException {
        return baseStream.readUnsignedShort();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public char readChar() throws IOException {
        return baseStream.readChar();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public int readInt() throws IOException {
        return baseStream.readInt();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public long readLong() throws IOException {
        return baseStream.readLong();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public float readFloat() throws IOException {
        return baseStream.readFloat();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public double readDouble() throws IOException {
        return baseStream.readDouble();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String readUTF() throws IOException {
        return baseStream.readUTF();
    }
    /**
     * {@inheritDoc}
     */
    @Override
    @Deprecated
    @Nullable
    public String readLine() throws IOException {
        return baseStream.readLine();
    }

    /**
     * Reads a byte array from the stream.
     *
     * @param maxLength the maximum expected length of the array
     * @param readChecksum whether to read the checksum or not
     * @return the byte[] read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads a byte array from the stream. Same as {@link #readByteArray(int, boolean)} with {@code readChecksum} set to
     * false
     *
     * @param maxLength the maximum expected length of the array
     * @return the byte[] read or null if null was written
     * @throws IOException thrown if any problems occur
     */
    @Nullable
    public byte[] readByteArray(final int maxLength) throws IOException {
        return readByteArray(maxLength, DEFAULT_CHECKSUM);
    }

    /**
     * Reads an int array from the stream.
     *
     * @param maxLength the maximum expected length of the array
     * @return the int[] read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads an int list from the stream.
     *
     * @param maxLength the maximum expected length of the list
     * @return the list read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads a long array from the stream.
     *
     * @param maxLength the maximum expected length of the array
     * @return the long[] read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads an long list from the stream.
     *
     * @param maxLength the maximum expected length of the list
     * @return the list read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads an boolean list from the stream.
     *
     * @param maxLength the maximum expected length of the list
     * @return the list read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads a float array from the stream.
     *
     * @param maxLength the maximum expected length of the array
     * @return the float[] read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads an float list from the stream.
     *
     * @param maxLength the maximum expected length of the list
     * @return the list read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads a double array from the stream.
     *
     * @param maxLength the maximum expected length of the array
     * @return the double[] read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads an double list from the stream.
     *
     * @param maxLength the maximum expected length of the list
     * @return the list read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads a String array from the stream.
     *
     * @param maxLength the maximum expected length of the array
     * @param maxStringLength The maximum expected length of a string in the array.
     * @return the String[] read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Reads an String list from the stream.
     *
     * @param maxLength the maximum expected length of the list
     * @param maxStringLength The maximum expected length of a string in the array.
     * @return the list read or null if null was written
     * @throws IOException thrown if any problems occur
     */
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

    /**
     * Read an Instant from the stream
     *
     * @return the Instant that was read
     * @throws IOException thrown if there are any problems during the operation
     */
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

    /**
     * Reads a String encoded in the Swirlds default charset (UTF8) from the input stream
     *
     * @param maxLength the maximum length of the String in bytes
     * @return the String read
     * @throws IOException thrown if there are any problems during the operation
     */
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
