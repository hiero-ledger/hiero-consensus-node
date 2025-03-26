// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams;

import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_CLASS_ID;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_LIST_ARRAY_LENGTH;
import static com.swirlds.common.io.streams.SerializableStreamConstants.NULL_VERSION;
import static com.swirlds.common.io.streams.SerializableStreamConstants.SERIALIZATION_PROTOCOL_VERSION;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.base.function.CheckedFunction;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.io.exceptions.ClassNotFoundException;
import com.swirlds.common.io.exceptions.InvalidVersionException;
import com.swirlds.common.utility.ValueReference;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.SerializableDet;

/**
 * A drop-in replacement for {@link DataInputStream}, which handles SerializableDet classes specially. It is designed
 * for use with the SerializableDet interface, and its use is described there.
 */
public class SerializableDataInputStreamImpl extends AugmentedDataInputStream {

    private static final Set<Integer> SUPPORTED_PROTOCOL_VERSIONS = Set.of(SERIALIZATION_PROTOCOL_VERSION);

    /** A stream used to read PBJ objects */
    private final ReadableSequentialData readableSequentialData;

    /**
     * Creates a stream capable of deserializing serializable objects.
     *
     * @param in the specified input stream
     */
    public SerializableDataInputStreamImpl(final InputStream in) {
        super(in);
        readableSequentialData = new ReadableStreamingData(in);
    }

    /**
     * Reads the protocol version written by {@link SerializableDataOutputStreamImpl#writeProtocolVersion()}
     * From this point on, it will use this version number to deserialize.
     *
     * @throws IOException thrown if any IO problems occur
     */
    public int readProtocolVersion() throws IOException {
        final int protocolVersion = readInt();
        if (!SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersion)) {
            throw new IOException("Unsupported protocol version " + protocolVersion);
        }
        return protocolVersion;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> T readSerializable(@Nullable final Set<Long> permissibleClassIds)
            throws IOException {
        return readSerializable(true, SerializableDataInputStreamImpl::registryConstructor, permissibleClassIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> T readSerializable() throws IOException {
        return readSerializable(null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> T readSerializable(
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        Objects.requireNonNull(serializableConstructor, "serializableConstructor must not be null");
        return readSerializable(readClassId, id -> serializableConstructor.get(), permissibleClassIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> T readSerializable(
            final boolean readClassId, @NonNull final Supplier<T> serializableConstructor) throws IOException {
        return readSerializable(readClassId, serializableConstructor, null);
    }

    /**
     * Throws an exception if the version is not supported.
     */
    protected void validateVersion(final SerializableDet object, final int version) throws InvalidVersionException {
        if (version < object.getMinimumSupportedVersion() || version > object.getVersion()) {
            throw new InvalidVersionException(version, object);
        }
    }

    /**
     * Called when the class ID of an object becomes known. This method is a hook for the debug stream.
     *
     * @param classId the class ID of the current object being deserialized
     */
    protected void recordClassId(final long classId) {
        // debug framework can override
    }

    /**
     * Called when the class ID of an object becomes known. This method is a hook for the debug stream.
     *
     * @param o the object that is being deserialized
     */
    protected void recordClass(final Object o) {
        // debug framework can override
    }

    /**
     * Same as {@link #readSerializable(boolean, Supplier)} except that the constructor takes a class ID
     */
    private <T extends SelfSerializable> T readSerializable(
            final boolean readClassId,
            @NonNull final CheckedFunction<Long, T, IOException> serializableConstructor,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        final Long classId;
        if (readClassId) {
            classId = readLong();
            if (permissibleClassIds != null && !permissibleClassIds.contains(classId)) {
                throw new IOException(
                        "Class ID " + classId + " is not in the set of permissible class IDs: " + permissibleClassIds);
            }

            recordClassId(classId);
            if (classId == NULL_CLASS_ID) {
                return null;
            }
        } else {
            classId = null;
        }

        final int version = readInt();
        if (version == NULL_VERSION) {
            return null;
        }

        final T serializable = serializableConstructor.apply(classId);
        recordClass(serializable);

        validateVersion(serializable, version);
        serializable.deserialize(this, version);
        return serializable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> void readSerializableIterableWithSize(
            final int maxSize, @NonNull final Consumer<T> callback, @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        final int size = readInt();
        checkLengthLimit(size, maxSize);
        readSerializableIterableWithSizeInternal(
                size, true, SerializableDataInputStreamImpl::registryConstructor, callback, permissibleClassIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> void readSerializableIterableWithSize(
            final int maxSize, @NonNull final Consumer<T> callback) throws IOException {

        readSerializableIterableWithSize(maxSize, callback, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> void readSerializableIterableWithSize(
            final int maxSize,
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @NonNull final Consumer<T> callback,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {
        final int size = readInt();
        checkLengthLimit(size, maxSize);
        readSerializableIterableWithSizeInternal(
                size, readClassId, id -> serializableConstructor.get(), callback, permissibleClassIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> void readSerializableIterableWithSize(
            final int maxSize,
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @NonNull final Consumer<T> callback)
            throws IOException {
        readSerializableIterableWithSize(maxSize, readClassId, serializableConstructor, callback, null);
    }

    /**
     * Read a sequence of serializable objects and pass them to a callback method.
     *
     * @param size                    the number of objects to read
     * @param readClassId             if true then the class ID needs to be read
     * @param serializableConstructor a method that takes a class ID and provides a constructor
     * @param callback                the callback method where each object is passed when it is deserialized
     * @param permissibleClassIds     a set of class IDs that are allowed to be read, will throw an IOException if asked
     *                                to deserialize a class not in this set, all class IDs are permitted if null.
     *                                Ignored if readClassId is false.
     * @param <T>                     the type of the objects being deserialized
     */
    private <T extends SelfSerializable> void readSerializableIterableWithSizeInternal(
            final int size,
            final boolean readClassId,
            @NonNull final CheckedFunction<Long, T, IOException> serializableConstructor,
            @NonNull final Consumer<T> callback,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        if (serializableConstructor == null) {
            throw new IllegalArgumentException("serializableConstructor is null");
        }

        // return if size is zero while deserializing similar to serializing
        if (size == 0) {
            return;
        }

        final boolean allSameClass = readBoolean();

        final ValueReference<Long> classId = new ValueReference<>();
        final ValueReference<Integer> version = new ValueReference<>();

        for (int i = 0; i < size; i++) {
            final T next = readNextSerializableIteration(
                    allSameClass, readClassId, classId, version, serializableConstructor, permissibleClassIds);
            callback.accept(next);
        }
    }

    /**
     * Helper method for {@link #readSerializableIterableWithSizeInternal(int, boolean, CheckedFunction, Consumer, Set)}
     * . Protected instead of private to allow debug framework to intercept this method.
     *
     * @param allSameClass            true if the elements all have the same class
     * @param readClassId             if true then the class ID needs to be read, ignored if allSameClass is true
     * @param classId                 the class ID if known, otherwise null
     * @param version                 the version if known, otherwise ignored
     * @param serializableConstructor given a class ID, returns a constructor for that class
     * @param permissibleClassIds     a set of class IDs that are allowed to be read, will throw an IOException if asked
     *                                to deserialize a class not in this set, all class IDs are permitted if null.
     *                                Ignored if readClassId is false.
     * @param <T>                     the type of the elements in the sequence
     * @return true if the class ID has already been read
     */
    protected <T extends SelfSerializable> T readNextSerializableIteration(
            final boolean allSameClass,
            final boolean readClassId,
            @NonNull final ValueReference<Long> classId,
            @NonNull final ValueReference<Integer> version,
            @NonNull final CheckedFunction<Long, T, IOException> serializableConstructor,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        if (!allSameClass) {
            // if classes are different, we just read each object one by one
            return readSerializable(readClassId, serializableConstructor, permissibleClassIds);
        }

        final boolean isNull = readBoolean();
        if (isNull) {
            return null;
        }

        if (version.getValue() == null) {
            // this is the first non-null member, so we read the ID and version
            if (readClassId) {
                classId.setValue(readLong());
                if (permissibleClassIds != null && !permissibleClassIds.contains(classId.getValue())) {
                    throw new IOException("Class ID " + classId + " is not in the set of permissible class IDs: "
                            + permissibleClassIds);
                }
            }
            version.setValue(readInt());
        }

        final T serializable = serializableConstructor.apply(classId.getValue());
        recordClassId(serializable.getClassId());
        recordClass(serializable);
        serializable.deserialize(this, version.getValue());
        return serializable;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> List<T> readSerializableList(
            final int maxListSize, @Nullable final Set<Long> permissibleClassIds) throws IOException {
        return readSerializableList(
                maxListSize, true, SerializableDataInputStreamImpl::registryConstructor, permissibleClassIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> List<T> readSerializableList(final int maxListSize) throws IOException {
        return readSerializableList(maxListSize, null);
    }

    /**
     * Read a list of serializable objects from the stream
     *
     * @param maxListSize             maximal number of object to read
     * @param readClassId             set to true if the class ID was written to the stream
     * @param serializableConstructor the constructor to use when instantiating list elements
     * @param permissibleClassIds     a set of class IDs that are allowed to be read, will throw an IOException if asked
     *                                to deserialize a class not in this set, all class IDs are permitted if null.
     *                                Ignored if readClassId is false.
     * @param <T>                     the implementation of {@link SelfSerializable} used
     * @return A list of the instances of the class previously written
     * @throws IOException thrown if any IO problems occur
     */
    public <T extends SelfSerializable> List<T> readSerializableList(
            final int maxListSize,
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {
        Objects.requireNonNull(serializableConstructor, "serializableConstructor must not be null");
        return readSerializableList(maxListSize, readClassId, id -> serializableConstructor.get(), permissibleClassIds);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> List<T> readSerializableList(
            final int maxListSize, final boolean readClassId, @NonNull final Supplier<T> serializableConstructor)
            throws IOException {
        return readSerializableList(maxListSize, readClassId, serializableConstructor, null);
    }

    /**
     * Read a list of serializable objects from the stream
     *
     * @param maxListSize             maximal number of object to read
     * @param readClassId             set to true if the class ID was written to the stream
     * @param serializableConstructor a method that takes a class ID and returns a constructor
     * @param permissibleClassIds     a set of class IDs that are allowed to be read, will throw an IOException if asked
     *                                to deserialize a class not in this set, all class IDs are permitted if null.
     *                                Ignored if readClassId is false.
     * @param <T>                     the implementation of {@link SelfSerializable} used
     * @return A list of the instances of the class previously written
     * @throws IOException thrown if any IO problems occur
     */
    private <T extends SelfSerializable> List<T> readSerializableList(
            final int maxListSize,
            final boolean readClassId,
            @NonNull final CheckedFunction<Long, T, IOException> serializableConstructor,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        final int length = readInt();
        if (length == NULL_LIST_ARRAY_LENGTH) {
            return null;
        }
        checkLengthLimit(length, maxListSize);

        // ArrayList is used by default, we can add support for different list types in the future
        final List<T> list = new ArrayList<>(length);
        if (length == 0) {
            return list;
        }
        readSerializableIterableWithSizeInternal(
                length, readClassId, serializableConstructor, list::add, permissibleClassIds);
        return list;
    }

    /**
     * Read an array of serializable objects from the stream.
     *
     * @param arrayConstructor    a method that returns an array of the requested size
     * @param maxListSize         maximal number of object should read
     * @param readClassId         set to true if the class ID was written to the stream
     * @param permissibleClassIds a set of class IDs that are allowed to be read, will throw an IOException if asked to
     *                            deserialize a class not in this set, all class IDs are permitted if null. Ignored if
     *                            readClassId is false.
     * @param <T>                 the implementation of {@link SelfSerializable} used
     * @return An array of the instances of the class previously written
     * @throws IOException thrown if any IO problems occur
     */
    public <T extends SelfSerializable> T[] readSerializableArray(
            @NonNull final IntFunction<T[]> arrayConstructor,
            final int maxListSize,
            final boolean readClassId,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        final List<T> list = readSerializableList(
                maxListSize, readClassId, SerializableDataInputStreamImpl::registryConstructor, permissibleClassIds);
        if (list == null) {
            return null;
        }

        return list.toArray(arrayConstructor.apply(list.size()));
    }

    /**
     * Read an array of serializable objects from the stream.
     *
     * @param arrayConstructor        a method that returns an array of the requested size
     * @param maxListSize             maximal number of object should read
     * @param readClassId             set to true if the class ID was written to the stream
     * @param permissibleClassIds     a set of class IDs that are allowed to be read, will throw an IOException if asked
     *                                to deserialize a class not in this, all class IDs are permitted if null. Ignored
     *                                if readClassId is false.
     * @param serializableConstructor an object that returns new instances of the class
     * @param <T>                     the implementation of {@link SelfSerializable} used
     * @return An array of the instances of the class previously written
     * @throws IOException thrown if any IO problems occur
     */
    public <T extends SelfSerializable> T[] readSerializableArray(
            @NonNull final IntFunction<T[]> arrayConstructor,
            final int maxListSize,
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        final List<T> list = readSerializableList(
                maxListSize, readClassId, id -> serializableConstructor.get(), permissibleClassIds);
        if (list == null) {
            return null;
        }
        return list.toArray(arrayConstructor.apply(list.size()));
    }

    /**
     * Read an array of serializable objects from the stream.
     *
     * @param arrayConstructor        a method that returns an array of the requested size
     * @param maxListSize             maximal number of object we are willing to read
     * @param readClassId             set to true if the class ID was written to the stream
     * @param serializableConstructor an object that returns new instances of the class
     * @param <T>                     the implementation of {@link SelfSerializable} used
     * @return An array of the instances of the class previously written
     * @throws IOException thrown if any IO problems occur
     */
    public <T extends SelfSerializable> T[] readSerializableArray(
            @NonNull final IntFunction<T[]> arrayConstructor,
            final int maxListSize,
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor)
            throws IOException {

        return readSerializableArray(arrayConstructor, maxListSize, readClassId, serializableConstructor, null);
    }

    /**
     * Looks up a constructor given a class ID.
     *
     * @param classId a requested class ID
     * @param <T>     the type of the class
     * @return a constructor for the class
     * @throws ClassNotFoundException if the class ID is not registered
     */
    private static <T extends SelfSerializable> T registryConstructor(final long classId) throws IOException {
        final T rc = ConstructableRegistry.getInstance().createObject(classId);
        if (rc == null) {
            throw new ClassNotFoundException(classId);
        }
        return rc;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull <T> T readPbjRecord(@NonNull final Codec<T> codec) throws IOException {
        final int size = readInt();
        readableSequentialData.limit(readableSequentialData.position() + size);
        try {
            final T parsed = codec.parse(readableSequentialData);
            if (readableSequentialData.position() != readableSequentialData.limit()) {
                throw new EOFException("PBJ record was not fully read");
            }
            return parsed;
        } catch (final ParseException e) {
            if (e.getCause() instanceof BufferOverflowException || e.getCause() instanceof BufferUnderflowException) {
                // PBJ Codec can throw these exceptions if it does not read enough bytes
                final EOFException eofException = new EOFException("Buffer underflow while reading PBJ record");
                eofException.addSuppressed(e);
                throw eofException;
            }
            throw new IOException(e);
        }
    }
}
