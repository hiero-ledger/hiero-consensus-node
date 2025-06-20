// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.serialize;

import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

public interface BaseSerializer<T> {

    /** Data size constant used when the data size is variable */
    int VARIABLE_DATA_SIZE = -1;

    /** Get the current data item serialization version */
    long getCurrentDataVersion();

    /**
     * Get if the number of bytes a data item takes when serialized is variable or fixed.
     *
     * @return true if getSerializedSize() == DataFileCommon.VARIABLE_DATA_SIZE
     */
    default boolean isVariableSize() {
        return getSerializedSize() == VARIABLE_DATA_SIZE;
    }

    /**
     * Get the number of bytes an arbitrary data item of type {@code D} takes when serialized. If
     * serialized data items may be of different sizes, this method should return {@link
     * #VARIABLE_DATA_SIZE}, and methods {@link #getTypicalSerializedSize()} and {@link
     * #getSerializedSize(Object)} are mandatory to implement.
     *
     * <p>For fixed-sized data items, this is the only method to implement. For variable-sized
     * data items, two more methods are needed: {@link #getTypicalSerializedSize()} and
     * {@link #getSerializedSize(Object)}.
     *
     * @return Either a number of bytes or DataFileCommon.VARIABLE_DATA_SIZE if size is variable
     */
    int getSerializedSize();

    @Deprecated
    default int getSerializedSizeForVersion(long version) {
        return getSerializedSize();
    }

    /**
     * For variable sized data items get the typical number of bytes an item takes when serialized.
     * If data items are all of fixed size, there is no need to implement this method.
     *
     * @return Either for fixed size same as getSerializedSize() or an estimated typical size
     */
    default int getTypicalSerializedSize() {
        if (isVariableSize()) {
            throw new IllegalStateException("Variable sized implementations have to override this method");
        }
        return getSerializedSize();
    }

    /**
     * Returns the number of bytes a given data item takes when serialized. If data items are all
     * of fixed size, there is no need to implement this method.
     *
     * @param data Data item to estimate
     * @return Number of bytes the data item will take when serialized
     */
    default int getSerializedSize(@NonNull final T data) {
        Objects.requireNonNull(data);
        final int size = getSerializedSize();
        if (size != VARIABLE_DATA_SIZE) {
            return size;
        }
        throw new RuntimeException("TO IMPLEMENT: " + getClass().getSimpleName() + ".getSerializedSize()");
    }

    /**
     * Serialize a data item buffer in protobuf format. Serialization format must be identical to
     * {@link #deserialize(ReadableSequentialData)}.
     *
     * @param data The data item to serialize
     * @param out Output buffer to write to
     */
    void serialize(@NonNull final T data, @NonNull final WritableSequentialData out);

    default Bytes toBytes(@NonNull final T data) {
        try (final ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
            final WritableSequentialData out = new WritableStreamingData(bout);
            serialize(data, out);
            final byte[] bytes = bout.toByteArray();
            assert bytes.length == getSerializedSize(data);
            return Bytes.wrap(bytes);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Deserialize a data item from a buffer where it was previously written using {@link
     * #serialize(Object, WritableSequentialData)} method.
     *
     * @param in The buffer to read from containing the data item in protobuf format
     * @return Deserialized data item
     */
    T deserialize(@NonNull final ReadableSequentialData in);

    default T fromBytes(@NonNull final Bytes b) {
        return deserialize(b.toReadableSequentialData());
    }
}
