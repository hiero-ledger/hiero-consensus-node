// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.disk;

import static com.swirlds.state.merkle.StateUtils.readFromStream;
import static com.swirlds.state.merkle.StateUtils.writeToStream;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.Objects;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * An implementation of {@link VirtualKey} for Hedera applications.
 *
 * <p>The {@link OnDiskKey} is actually a wrapper for the "real" key, which is some business logic
 * object of type {@code K}. For example, the "real" key may be {@code AccountID}, but it must be
 * wrapped by an {@link OnDiskKey} to adapt it for use by the {@link VirtualMap}.
 *
 * <p>The {@code AccountID} itself is not directly serializable, and therefore a {@link Codec} is
 * provided to handle all serialization needs for the "real" key. The {@link Codec} is used to
 * convert the "real" key into bytes for hashing, saving to disk via the {@link VirtualMap}, reading
 * from disk, reconnect, and for state saving.
 *
 * @param <K> The type of key
 */
public final class OnDiskKey<K> implements VirtualKey {

    @Deprecated(forRemoval = true)
    private static final long CLASS_ID = 0x2929238293892373L;

    static final int VERSION = 1;

    /** Key codec. */
    private final Codec<K> codec;

    /** Key class id. */
    private final Long classId;

    /** The "real" key, such as AccountID. */
    private K key;

    // Default constructor provided for ConstructableRegistry, TO BE REMOVED ASAP
    @Deprecated(forRemoval = true)
    public OnDiskKey() {
        this.classId = CLASS_ID;
        this.codec = null;
    }

    /**
     * Creates a new OnDiskKey. Used by {@link OnDiskKeySerializer}.
     *
     * @param classId The class ID for the key
     * @param codec   The codec for the key
     */
    public OnDiskKey(final long classId, @Nullable final Codec<K> codec) {
        this.classId = classId;
        this.codec = codec;
    }

    /**
     * Creates a new OnDiskKey.
     *
     * @param classId The class ID for the key
     * @param codec   The codec for the key
     * @param key     The "real" key
     */
    public OnDiskKey(final long classId, @Nullable final Codec<K> codec, @NonNull final K key) {
        this(classId, codec);
        this.key = requireNonNull(key);
    }

    @NonNull
    public K getKey() {
        return key;
    }

    @Override
    public long getClassId() {
        return classId;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    /** Writes the "real" key to the given stream. {@inheritDoc} */
    @Override
    public void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        if (codec == null) {
            throw new IllegalStateException("Cannot serialize on-disk key, null metadata / codec");
        }
        writeToStream(out, codec, key);
    }

    @Override
    public void deserialize(@NonNull final SerializableDataInputStream in, int ignored) throws IOException {
        if (codec == null) {
            throw new IllegalStateException("Cannot deserialize on-disk key, null metadata / codec");
        }
        key = readFromStream(in, codec);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OnDiskKey<?> onDiskKey)) {
            return false;
        }
        return Objects.equals(key, onDiskKey.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }

    @Override
    public String toString() {
        return "OnDiskKey{" + "key=" + key + '}';
    }
}
