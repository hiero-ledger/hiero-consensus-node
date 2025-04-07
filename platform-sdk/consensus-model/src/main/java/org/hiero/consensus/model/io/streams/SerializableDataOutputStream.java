// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.io.streams;

import com.hedera.pbj.runtime.Codec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import org.hiero.consensus.model.io.SelfSerializable;

/**
 * A drop-in replacement for {@link DataOutputStream}, which handles SerializableDet classes specially.
 * It is designed for use with the SerializableDet interface, and its use is described there.
 */
public abstract class SerializableDataOutputStream extends DataOutputStream {

    protected SerializableDataOutputStream(OutputStream out) {
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
    public abstract void writeByteArray(byte[] data, boolean writeChecksum) throws IOException;

    /**
     * Writes a byte array to the stream. Can be null.
     * Same as {@link #writeByteArray(byte[], boolean)} with {@code writeChecksum} set to false
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeByteArray(byte[] data) throws IOException;

    /**
     * Writes a {@link SelfSerializable} object to a stream. If the class is known at the time of deserialization, the
     * the {@code writeClassId} param can be set to false. If the class might be unknown when deserializing, then the
     * {@code writeClassId} must be written.
     *
     * @param serializable
     * 		the object to serialize
     * @param writeClassId
     * 		whether to write the class ID or not
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeSerializable(SelfSerializable serializable, boolean writeClassId) throws IOException;

    /**
     * Writes a list of objects returned by an {@link Iterator} when the size in known ahead of time. If the class is
     * known at the time of deserialization, the {@code writeClassId} param can be set to false. If the class might be
     * unknown when deserializing, then the {@code writeClassId} must be written.
     *
     * @param iterator
     * 		the iterator that returns the data
     * @param size
     * 		the size of the dataset
     * @param writeClassId
     * 		whether to write the class ID or not
     * @param allSameClass
     * 		should be set to true if all the objects in the list are the same class
     * @param <T>
     * 		the type returned by the iterator
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract <T extends SelfSerializable> void writeSerializableIterableWithSize(
            Iterator<T> iterator, int size, boolean writeClassId, boolean allSameClass) throws IOException;

    /**
     * Writes a list of {@link SelfSerializable} objects to the stream
     *
     * @param list
     * 		the list to write, can be null
     * @param writeClassId
     * 		set to true if the classID should be written. This can be false if the class is known when
     * 		de-serializing
     * @param allSameClass
     * 		should be set to true if all the objects in the list are the same class
     * @param <T>
     * 		the class stored in the list
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract <T extends SelfSerializable> void writeSerializableList(
            List<T> list, boolean writeClassId, boolean allSameClass) throws IOException;

    /**
     * Writes an int array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeIntArray(int[] data) throws IOException;

    /**
     * Writes an int list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeIntList(List<Integer> data) throws IOException;

    /**
     * Writes a long array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeLongArray(long[] data) throws IOException;

    /**
     * Writes a long list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeLongList(List<Long> data) throws IOException;

    /**
     * Writes a boolean list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeBooleanList(List<Boolean> data) throws IOException;

    /**
     * Writes a float array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeFloatArray(float[] data) throws IOException;

    /**
     * Writes a float list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeFloatList(List<Float> data) throws IOException;

    /**
     * Writes a double array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeDoubleArray(double[] data) throws IOException;

    /**
     * Writes a double list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeDoubleList(List<Double> data) throws IOException;

    /**
     * Writes a String array to the stream. Can be null.
     *
     * @param data
     * 		the array to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeStringArray(String[] data) throws IOException;

    /**
     * Writes a string list to the stream. Can be null.
     *
     * @param data
     * 		the list to write
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract void writeStringList(List<String> data) throws IOException;

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
    public abstract void writeNormalisedString(String s) throws IOException;

    /**
     * Write an Instant to the stream
     *
     * @param instant
     * 		the instant to write
     * @throws IOException
     * 		thrown if there are any problems during the operation
     */
    public abstract void writeInstant(Instant instant) throws IOException;

    /**
     * Write a PBJ record to the stream
     *
     * @param record
     * 		the record to write
     * @param codec
     * 		the codec to use to write the record
     * @param <T>
     * 		the type of the record
     * @throws IOException
     * 		thrown if any IO problems occur
     */
    public abstract <T> void writePbjRecord(@NonNull final T record, @NonNull final Codec<T> codec) throws IOException;
}
