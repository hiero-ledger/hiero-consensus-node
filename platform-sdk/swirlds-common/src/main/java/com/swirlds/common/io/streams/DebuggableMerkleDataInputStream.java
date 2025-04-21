// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.streams;

import com.swirlds.base.function.CheckedFunction;
import com.swirlds.common.io.streams.internal.SerializationOperation;
import com.swirlds.common.io.streams.internal.SerializationStack;
import com.swirlds.common.merkle.MerkleNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.hiero.base.ValueReference;
import org.hiero.base.io.SelfSerializable;

/**
 * A stream that performs the same role as a {@link MerkleDataInputStream} but with extra debug functionality. This
 * debuggability adds overhead, so use of this stream should be limited to test environments or production environments
 * where there is a known serialization problem (heaven forbid).
 */
public class DebuggableMerkleDataInputStream extends MerkleDataInputStream {

    /**
     * The stack trace contains the following elements:
     *
     * <ul>
     * <li>
     * index 0: a frame from inside the java thread API
     * </li>
     * <li>
     * index 1: a frame inside {@link #startOperation(SerializationOperation)}
     * </li>
     * <li>
     * index 2: the frame where {@link #startOperation(SerializationOperation)} is called
     * </li>
     * <li>
     * index 3: the original caller (this is the one we are interested in)
     * </li>
     * </ul>
     */
    private static final int STACK_TRACE_OFFSET = 3;

    private final SerializationStack stack;

    /**
     * Create a new {@link MerkleDataInputStream} that has extra debug capability.
     *
     * @param in the base stream
     */
    public DebuggableMerkleDataInputStream(final InputStream in) {
        super(in);

        final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        final StackTraceElement callLocation = stackTrace[STACK_TRACE_OFFSET];
        stack = new SerializationStack(callLocation);
    }

    /**
     * Create a formatted serialization stack trace string.
     *
     * @return a formatted stack trace, or an empty string if debug is disabled
     */
    public String getFormattedStackTrace() {
        return stack.generateSerializationStackTrace();
    }

    /**
     * Get the serialization stack trace.
     *
     * @return the stack
     */
    public SerializationStack getStack() {
        return stack;
    }

    /**
     * Record the start of an operation.
     *
     * @param operation the operation that is starting
     */
    private void startOperation(final SerializationOperation operation) {
        final StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        final StackTraceElement callLocation = stackTrace[STACK_TRACE_OFFSET];

        stack.startOperation(operation, callLocation);
    }

    /**
     * Record when an operation finishes.
     */
    private void finishOperation() {
        stack.finishOperation();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void recordClassId(final long classId) {
        stack.setClassId(classId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void recordClass(final Object o) {
        if (o != null) {
            stack.setClass(o.getClass());
        }
    }

    /**
     * Record a short string that represents the object.
     *
     * @param value an object to be converted into a string
     * @return the input object
     */
    private <T> T recordStringRepresentation(final T value) {
        stack.setStringRepresentation(Objects.toString(value));
        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException {
        startOperation(SerializationOperation.READ);
        try {
            return super.read();
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long skip(final long n) throws IOException {
        startOperation(SerializationOperation.SKIP);
        recordStringRepresentation(n);
        try {
            return super.skip(n);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public byte[] readAllBytes() throws IOException {
        startOperation(SerializationOperation.READ_ALL_BYTES);
        try {
            return super.readAllBytes();
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public byte[] readNBytes(final int len) throws IOException {
        startOperation(SerializationOperation.READ_N_BYTES);
        try {
            return super.readNBytes(len);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readNBytes(@NonNull final byte[] b, final int off, final int len) throws IOException {
        startOperation(SerializationOperation.READ_N_BYTES_ARRAY);
        try {
            return super.readNBytes(b, off, len);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int skipBytes(final int n) throws IOException {
        startOperation(SerializationOperation.SKIP_BYTES);
        try {
            return super.skipBytes(n);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void skipNBytes(final long n) throws IOException {
        startOperation(SerializationOperation.SKIP_N_BYTES);
        try {
            super.skipNBytes(n);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readFully(@NonNull final byte[] b) throws IOException {
        startOperation(SerializationOperation.READ_FULLY);
        try {
            super.readFully(b);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void readFully(@NonNull final byte[] b, final int off, final int len) throws IOException {
        startOperation(SerializationOperation.READ_FULLY_OFFSET);
        try {
            super.readFully(b, off, len);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean readBoolean() throws IOException {
        startOperation(SerializationOperation.READ_BOOLEAN);
        try {
            return recordStringRepresentation(super.readBoolean());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte readByte() throws IOException {
        startOperation(SerializationOperation.READ_BYTE);
        try {
            return recordStringRepresentation(super.readByte());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readUnsignedByte() throws IOException {
        startOperation(SerializationOperation.READ_UNSIGNED_BYTE);
        try {
            return recordStringRepresentation(super.readUnsignedByte());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public short readShort() throws IOException {
        startOperation(SerializationOperation.READ_SHORT);
        try {
            return recordStringRepresentation(super.readShort());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readUnsignedShort() throws IOException {
        startOperation(SerializationOperation.READ_UNSIGNED_SHORT);
        try {
            return recordStringRepresentation(super.readUnsignedShort());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public char readChar() throws IOException {
        startOperation(SerializationOperation.READ_CHAR);
        try {
            return recordStringRepresentation(super.readChar());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int readInt() throws IOException {
        startOperation(SerializationOperation.READ_INT);
        try {
            return recordStringRepresentation(super.readInt());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long readLong() throws IOException {
        startOperation(SerializationOperation.READ_LONG);
        try {
            return recordStringRepresentation(super.readLong());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float readFloat() throws IOException {
        startOperation(SerializationOperation.READ_FLOAT);
        try {
            return recordStringRepresentation(super.readFloat());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double readDouble() throws IOException {
        startOperation(SerializationOperation.READ_DOUBLE);
        try {
            return recordStringRepresentation(super.readDouble());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String readUTF() throws IOException {
        startOperation(SerializationOperation.READ_UTF);
        try {
            return recordStringRepresentation(super.readUTF());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] readByteArray(final int maxLength, final boolean readChecksum) throws IOException {
        startOperation(SerializationOperation.READ_BYTE_ARRAY);
        try {
            return super.readByteArray(maxLength, readChecksum);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] readByteArray(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_BYTE_ARRAY);
        try {
            return super.readByteArray(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int[] readIntArray(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_INT_LIST);
        try {
            return super.readIntArray(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Integer> readIntList(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_INT_LIST);
        try {
            return super.readIntList(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long[] readLongArray(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_LONG_LIST);
        try {
            return super.readLongArray(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Long> readLongList(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_LONG_LIST);
        try {
            return super.readLongList(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Boolean> readBooleanList(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_BOOLEAN_LIST);
        try {
            return super.readBooleanList(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float[] readFloatArray(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_FLOAT_LIST);
        try {
            return super.readFloatArray(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Float> readFloatList(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_FLOAT_LIST);
        try {
            return super.readFloatList(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double[] readDoubleArray(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_DOUBLE_LIST);
        try {
            return super.readDoubleArray(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Double> readDoubleList(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_DOUBLE_LIST);
        try {
            return super.readDoubleList(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String[] readStringArray(final int maxLength, final int maxStringLength) throws IOException {
        startOperation(SerializationOperation.READ_STRING_LIST);
        try {
            return super.readStringArray(maxLength, maxStringLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> readStringList(final int maxLength, final int maxStringLength) throws IOException {
        startOperation(SerializationOperation.READ_STRING_LIST);
        try {
            return super.readStringList(maxLength, maxStringLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Instant readInstant() throws IOException {
        startOperation(SerializationOperation.READ_INSTANT);
        try {
            return recordStringRepresentation(super.readInstant());
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String readNormalisedString(final int maxLength) throws IOException {
        startOperation(SerializationOperation.READ_NORMALISED_STRING);
        try {
            return super.readNormalisedString(maxLength);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends MerkleNode> T readMerkleTree(final Path directory, final int maxNumberOfNodes)
            throws IOException {

        startOperation(SerializationOperation.READ_MERKLE_TREE);
        try {
            return super.readMerkleTree(directory, maxNumberOfNodes);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void readNextNode(
            final Path directory, final Map<Long /* class ID */, Integer /* version */> deserializedVersions)
            throws IOException {
        startOperation(SerializationOperation.READ_MERKLE_NODE);
        try {
            super.readNextNode(directory, deserializedVersions);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> T readSerializable() throws IOException {
        startOperation(SerializationOperation.READ_SERIALIZABLE);
        try {
            return super.readSerializable();
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> T readSerializable(
            final boolean readClassId, @NonNull final Supplier<T> serializableConstructor) throws IOException {
        startOperation(SerializationOperation.READ_SERIALIZABLE);
        try {
            return super.readSerializable(readClassId, serializableConstructor);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> void readSerializableIterableWithSize(
            final int maxSize, @NonNull final Consumer<T> callback, @Nullable final Set<Long> permissibleClassIds)
            throws IOException {
        startOperation(SerializationOperation.READ_SERIALIZABLE_LIST);
        try {
            super.readSerializableIterableWithSize(maxSize, callback, permissibleClassIds);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> void readSerializableIterableWithSize(
            final int size,
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @NonNull final Consumer<T> callback,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {
        startOperation(SerializationOperation.READ_SERIALIZABLE_LIST);
        try {
            super.readSerializableIterableWithSize(
                    size, readClassId, serializableConstructor, callback, permissibleClassIds);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected <T extends SelfSerializable> T readNextSerializableIteration(
            final boolean allSameClass,
            final boolean readClassId,
            @NonNull final ValueReference<Long> classId,
            @NonNull final ValueReference<Integer> version,
            @NonNull final CheckedFunction<Long, T, IOException> serializableConstructor,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {
        startOperation(SerializationOperation.READ_SERIALIZABLE);
        try {
            return super.readNextSerializableIteration(
                    allSameClass, readClassId, classId, version, serializableConstructor, permissibleClassIds);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> List<T> readSerializableList(
            final int maxListSize, @Nullable final Set<Long> permissibleClassIds) throws IOException {
        startOperation(SerializationOperation.READ_SERIALIZABLE_LIST);
        try {
            return super.readSerializableList(maxListSize, permissibleClassIds);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> List<T> readSerializableList(
            final int maxListSize,
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        startOperation(SerializationOperation.READ_SERIALIZABLE_LIST);
        try {
            return super.readSerializableList(maxListSize, readClassId, serializableConstructor, permissibleClassIds);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> T[] readSerializableArray(
            @NonNull final IntFunction<T[]> arrayConstructor,
            final int maxListSize,
            final boolean readClassId,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        startOperation(SerializationOperation.READ_SERIALIZABLE_LIST);
        try {
            return super.readSerializableArray(arrayConstructor, maxListSize, readClassId, permissibleClassIds);
        } finally {
            finishOperation();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> T[] readSerializableArray(
            @NonNull final IntFunction<T[]> arrayConstructor,
            final int maxListSize,
            final boolean readClassId,
            @NonNull final Supplier<T> serializableConstructor,
            @Nullable final Set<Long> permissibleClassIds)
            throws IOException {

        startOperation(SerializationOperation.READ_SERIALIZABLE_LIST);
        try {
            return super.readSerializableArray(
                    arrayConstructor, maxListSize, readClassId, serializableConstructor, permissibleClassIds);
        } finally {
            finishOperation();
        }
    }
}
