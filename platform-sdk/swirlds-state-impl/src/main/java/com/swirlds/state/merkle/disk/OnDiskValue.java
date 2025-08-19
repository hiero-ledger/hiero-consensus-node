// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.readFromStream;
import static com.swirlds.state.merkle.StateUtils.writeToStream;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.virtualmap.VirtualValue;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A {@link VirtualValue} used for storing the actual value. In our system, a state might have
 * business objects as values, such as {@code Account} or {@code Token}. However, the {@link
 * com.swirlds.virtualmap.VirtualMap} requires each value in the map to be of the type {@link
 * VirtualValue}. Rather than exposing each service to the merkle APIs, we allow them to work in
 * terms of business objects, and this one implementation of {@link VirtualValue} is used for all
 * types of values.
 *
 * @param <V> The type of the value (business object) held in this merkel data structure
 */
public class OnDiskValue<V> implements VirtualValue {

    @Deprecated(forRemoval = true)
    private static final long CLASS_ID = 0x8837746626372L;

    static final int VERSION = 1;

    private final long classId;
    private final Codec<V> codec;
    private V value;
    private boolean immutable = false;

    // Default constructor is for deserialization
    public OnDiskValue() {
        this.codec = null;
        this.classId = CLASS_ID;
    }

    public OnDiskValue(final long classId, @NonNull final Codec<V> codec) {
        this.codec = requireNonNull(codec);
        this.classId = classId;
    }

    public OnDiskValue(final long classId, @NonNull final Codec<V> codec, @NonNull final V value) {
        this(classId, codec);
        this.value = requireNonNull(value);
    }

    /** {@inheritDoc} */
    @Override
    public VirtualValue copy() {
        final var copy = new OnDiskValue<>(classId, requireNonNull(codec), value);
        this.immutable = true;
        return copy;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isImmutable() {
        return immutable;
    }

    /** {@inheritDoc} */
    @Override
    public VirtualValue asReadOnly() {
        if (isImmutable()) {
            return this;
        } else {
            final var copy = new OnDiskValue<>(classId, requireNonNull(codec), value);
            copy.immutable = true;
            return copy;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        if (codec == null) {
            throw new IllegalStateException("Cannot serialize on-disk value, null metadata / codec");
        }
        writeToStream(out, codec, value);
    }

    /** {@inheritDoc} */
    @Override
    public void deserialize(@NonNull final SerializableDataInputStream in, int ignored) throws IOException {
        if (codec == null) {
            throw new IllegalStateException("Cannot deserialize on-disk value, null metadata / codec");
        }
        value = readFromStream(in, codec);
    }

    /** {@inheritDoc} */
    @Override
    public long getClassId() {
        return classId;
    }

    /** {@inheritDoc} */
    @Override
    public int getVersion() {
        return 1;
    }

    /**
     * Gets the value.
     *
     * @return The value (business object)
     */
    @Nullable
    public V getValue() {
        return value;
    }

    /**
     * Sets the value
     *
     * @param value the business object value
     */
    public void setValue(@Nullable final V value) {
        throwIfImmutable();
        this.value = requireNonNull(value);
    }
}
