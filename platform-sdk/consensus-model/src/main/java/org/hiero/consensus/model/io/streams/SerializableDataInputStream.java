// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.io.streams;

import com.hedera.pbj.runtime.Codec;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.SerializableDet;

/**
 * A drop-in replacement for {@link DataInputStream}, which handles SerializableDet classes specially. It is designed
 * for use with the SerializableDet interface, and its use is described there.
 */
public abstract class SerializableDataInputStream extends InputStream implements DataInput {

    /**
     * Reads a byte array from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the array
     * @param readChecksum
     * 		whether to read the checksum or not
     * @return the byte[] read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract byte[] readByteArray(final int maxLength, final boolean readChecksum) throws IOException;

    /**
     * Reads a byte array from the stream.
     * Same as {@link #readByteArray(int, boolean)} with {@code readChecksum} set to false
     *
     * @param maxLength
     * 		the maximum expected length of the array
     * @return the byte[] read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract byte[] readByteArray(final int maxLength) throws IOException;

    /**
     * Reads an int array from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the array
     * @return the int[] read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract int[] readIntArray(final int maxLength) throws IOException;

    /**
     * Reads an int list from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the list
     * @return the list read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract List<Integer> readIntList(final int maxLength) throws IOException;

    /**
     * Reads a long array from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the array
     * @return the long[] read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract long[] readLongArray(final int maxLength) throws IOException;

    /**
     * Reads an long list from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the list
     * @return the list read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract List<Long> readLongList(final int maxLength) throws IOException;

    /**
     * Reads an boolean list from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the list
     * @return the list read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract List<Boolean> readBooleanList(final int maxLength) throws IOException;

    /**
     * Reads a float array from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the array
     * @return the float[] read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract float[] readFloatArray(final int maxLength) throws IOException;

    /**
     * Reads an float list from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the list
     * @return the list read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract List<Float> readFloatList(final int maxLength) throws IOException;

    /**
     * Reads a double array from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the array
     * @return the double[] read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract double[] readDoubleArray(final int maxLength) throws IOException;

    /**
     * Reads an double list from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the list
     * @return the list read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract List<Double> readDoubleList(final int maxLength) throws IOException;

    /**
     * Reads a String array from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the array
     * @param maxStringLength
     * 		The maximum expected length of a string in the array.
     * @return the String[] read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract String[] readStringArray(final int maxLength, final int maxStringLength) throws IOException;

    /**
     * Reads an String list from the stream.
     *
     * @param maxLength
     * 		the maximum expected length of the list
     * @param maxStringLength
     * 		The maximum expected length of a string in the array.
     * @return the list read or null if null was written
     * @throws IOException
     * 		thrown if any problems occur
     */
    @Nullable
    public abstract List<String> readStringList(final int maxLength, final int maxStringLength) throws IOException;

    /**
     * Read an Instant from the stream
     *
     * @return the Instant that was read
     * @throws IOException
     * 		thrown if there are any problems during the operation
     */
    @Nullable
    public abstract Instant readInstant() throws IOException;

    /**
     * Reads a String encoded in the Swirlds default charset (UTF8) from the input stream
     *
     * @param maxLength
     * 		the maximum length of the String in bytes
     * @return the String read
     * @throws IOException
     * 		thrown if there are any problems during the operation
     */
    @Nullable
    public abstract String readNormalisedString(final int maxLength) throws IOException;

    /**
     * Reads a {@link SerializableDet} from a stream and returns it. The instance will be created using the
     * {@code com.swirlds.common.constructable.ConstructableRegistry}. The instance must have previously been written using
     * {@link SerializableDataOutputStream#writeSerializable(SelfSerializable, boolean)} (SerializableDet, boolean)}
     * with {@code writeClassId} set to true, otherwise we cannot know what the class written is.
     *
     * @param permissibleClassIds a set of class IDs that are allowed to be read, will throw an IOException if asked to
     *                            deserialize a class not in this set, all class IDs are permitted if null
     * @param <T>                 the implementation of {@link SelfSerializable} used
     * @return An instance of the class previously written
     * @throws IOException thrown if any IO problems occur
     */
    public abstract <T extends SelfSerializable> T readSerializable(@Nullable final Set<Long> permissibleClassIds)
            throws IOException;

    /**
     * Reads a {@link SerializableDet} from a stream and returns it. The instance will be created using the
     * {@code com.swirlds.common.constructable.ConstructableRegistry}. The instance must have previously been written using
     * {@link SerializableDataOutputStream#writeSerializable(SelfSerializable, boolean)} (SerializableDet, boolean)}
     * with {@code writeClassId} set to true, otherwise we cannot know what the class written is.
     *
     * @param <T> the implementation of {@link SelfSerializable} used
     * @return An instance of the class previously written
     * @throws IOException thrown if any IO problems occur
     */
    public abstract <T extends SelfSerializable> T readSerializable() throws IOException;

    /**
     * Uses the provided {@code serializable} to read its data from the stream.
     *
     * @param serializableConstructor a constructor for the instance written in the stream
     * @param readClassId             set to true if the class ID was written to the stream
     * @param permissibleClassIds     a set of class IDs that are allowed to be read, will throw an IOException if asked
     *                                to deserialize a class not in this set, all class IDs are permitted if null.
     *                                Ignored if readClassId is false.
     * @param <T>                     the implementation of {@link SelfSerializable} used
     * @return the same object that was passed in, returned for convenience
     * @throws IOException thrown if any IO problems occur
     */
    public abstract <T extends SelfSerializable> T readSerializable(
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException;

    /**
     * Uses the provided {@code serializable} to read its data from the stream.
     *
     * @param serializableConstructor a constructor for the instance written in the stream
     * @param readClassId             set to true if the class ID was written to the stream
     * @param <T>                     the implementation of {@link SelfSerializable} used
     * @return the same object that was passed in, returned for convenience
     * @throws IOException thrown if any IO problems occur
     */
    public abstract <T extends SelfSerializable> T readSerializable(
            final boolean readClassId, @NonNull final Supplier<T> serializableConstructor) throws IOException;

    /**
     * Read a sequence of serializable objects and pass them to a callback method.
     *
     * @param maxSize             the maximum allowed size
     * @param callback            this method is passed each object in the sequence
     * @param permissibleClassIds a set of class IDs that are allowed to be read, will throw an IOException if asked to
     *                            deserialize a class not in this set, all class IDs are permitted if null
     * @param <T>                 the type of the objects in the sequence
     */
    public abstract <T extends SelfSerializable> void readSerializableIterableWithSize(
            final int maxSize, @NonNull final Consumer<T> callback, @Nullable final Set<Long> permissibleClassIds)
            throws IOException;

    /**
     * Read a sequence of serializable objects and pass them to a callback method.
     *
     * @param maxSize  the maximum allowed size
     * @param callback this method is passed each object in the sequence
     * @param <T>      the type of the objects in the sequence
     */
    public abstract <T extends SelfSerializable> void readSerializableIterableWithSize(
            final int maxSize, @NonNull final Consumer<T> callback) throws IOException;
    /**
     * Read a sequence of serializable objects and pass them to a callback method.
     *
     * @param maxSize                 the maximum number of objects to read
     * @param readClassId             if true then the class ID needs to be read
     * @param serializableConstructor a method that takes a class ID and provides a constructor
     * @param callback                the callback method where each object is passed when it is deserialized
     * @param permissibleClassIds     a set of class IDs that are allowed to be read, will throw an IOException if asked
     *                                to deserialize a class not in this set, all class IDs are permitted if null.
     *                                Ignored if readClassId is false.
     * @param <T>                     the type of the objects being deserialized
     */
    public abstract <T extends SelfSerializable> void readSerializableIterableWithSize(
            final int maxSize,
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @NonNull final Consumer<T> callback,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException;

    /**
     * Read a sequence of serializable objects and pass them to a callback method.
     *
     * @param maxSize                 the maximum number of objects to read
     * @param readClassId             if true then the class ID needs to be read
     * @param serializableConstructor a method that takes a class ID and provides a constructor
     * @param callback                the callback method where each object is passed when it is deserialized
     * @param <T>                     the type of the objects being deserialized
     */
    public abstract <T extends SelfSerializable> void readSerializableIterableWithSize(
            final int maxSize,
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @NonNull final Consumer<T> callback)
            throws IOException;

    /**
     * Read a list of serializable objects from the stream
     *
     * @param maxListSize         maximal number of object to read
     * @param permissibleClassIds a set of class IDs that are allowed to be read, will throw an IOException if asked to
     *                            deserialize a class not in this set, all class IDs are permitted if null
     * @param <T>                 the implementation of {@link SelfSerializable} used
     * @return A list of the instances of the class previously written
     * @throws IOException thrown if any IO problems occur
     */
    public abstract <T extends SelfSerializable> List<T> readSerializableList(
            final int maxListSize, @Nullable final Set<Long> permissibleClassIds) throws IOException;

    /**
     * Read a list of serializable objects from the stream
     *
     * @param maxListSize maximal number of object to read
     * @param <T>         the implementation of {@link SelfSerializable} used
     * @return A list of the instances of the class previously written
     * @throws IOException thrown if any IO problems occur
     */
    public abstract <T extends SelfSerializable> List<T> readSerializableList(final int maxListSize) throws IOException;

    /**
     * Read a list of serializable objects from the stream
     *
     * @param maxListSize             maximal number of object to read
     * @param readClassId             set to true if the class ID was written to the stream
     * @param serializableConstructor the constructor to use when instantiating list elements
     * @param <T>                     the implementation of {@link SelfSerializable} used
     * @return A list of the instances of the class previously written
     * @throws IOException thrown if any IO problems occur
     */
    public abstract <T extends SelfSerializable> List<T> readSerializableList(
            final int maxListSize, final boolean readClassId, @NonNull final Supplier<T> serializableConstructor)
            throws IOException;

    /**
     * Reads a PBJ record from the stream.
     *
     * @param codec the codec to use to parse the record
     * @param <T>   the type of the record
     * @return the parsed record
     * @throws IOException if an IO error occurs
     */
    public abstract @NonNull <T> T readPbjRecord(@NonNull final Codec<T> codec) throws IOException;
}
