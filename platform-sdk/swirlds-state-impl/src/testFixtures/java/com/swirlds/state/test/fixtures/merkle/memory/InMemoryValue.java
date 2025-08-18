// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle.memory;

import static com.swirlds.state.merkle.StateUtils.readFromStream;
import static com.swirlds.state.merkle.StateUtils.writeToStream;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.merkle.map.MerkleMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.Objects;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/** The value stored in a {@link MerkleMap} for in memory states */
public final class InMemoryValue<K, V> extends PartialMerkleLeaf
        implements MerkleNode, Keyed<InMemoryKey<K>>, SelfSerializable, MerkleLeaf {

    @Deprecated(forRemoval = true)
    private static final long CLASS_ID = 0x657483284563728L;

    /** The key associated with this value. {@link MerkleMap} requires we do this. */
    private InMemoryKey<K> key;

    private final Codec<K> keyCodec;
    private final Codec<V> valueCodec;

    private final long classId;
    /** The actual value. For example, it could be an Account or SmartContract. */
    private V val;

    // Default constructor provided for ConstructableRegistry, TO BE REMOVED ASAP
    @Deprecated(forRemoval = true)
    public InMemoryValue() {
        classId = CLASS_ID;
        keyCodec = null;
        valueCodec = null;
    }

    /**
     * Used by the deserialization system to create an {@link InMemoryValue} that does not yet have
     * a value. Normally this should not be used.
     *
     * @param classId
     * @param keyCodec
     * @param valueCodec
     */
    public InMemoryValue(final long classId, @Nullable Codec<K> keyCodec, @NonNull Codec<V> valueCodec) {
        this.classId = classId;
        this.keyCodec = keyCodec;
        this.valueCodec = valueCodec;
    }

    /**
     * Create a new instance with the given value.
     *
     * @param classId value class ID
     * @param keyCodec key codec
     * @param valueCodec value codec
     * @param key The associated key.
     * @param value The value.
     */
    public InMemoryValue(
            final long classId,
            @Nullable Codec<K> keyCodec,
            @NonNull Codec<V> valueCodec,
            @NonNull final InMemoryKey<K> key,
            @NonNull final V value) {
        this(classId, keyCodec, valueCodec);
        this.key = Objects.requireNonNull(key);
        this.val = Objects.requireNonNull(value);
    }

    /** {@inheritDoc} */
    @Override
    public InMemoryValue<K, V> copy() {
        throwIfImmutable();
        throwIfDestroyed();

        final var cp = new InMemoryValue<>(classId, keyCodec, valueCodec, key, val);
        setImmutable(true);
        return cp;
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

    /** {@inheritDoc} */
    @Override
    public InMemoryKey<K> getKey() {
        return key;
    }

    /** {@inheritDoc} */
    @Override
    public void setKey(@NonNull final InMemoryKey<K> inMemoryKey) {
        throwIfImmutable();
        this.key = Objects.requireNonNull(inMemoryKey);
    }

    /**
     * Gets the value.
     *
     * @return The value.
     */
    @Nullable
    public V getValue() {
        return val;
    }

    /**
     * Sets the value. Cannot be called if the leaf is immutable.
     *
     * @param value the new value
     */
    public void setValue(@Nullable final V value) {
        throwIfImmutable();
        this.val = value;
    }

    /** {@inheritDoc} */
    @Override
    public void deserialize(@NonNull final SerializableDataInputStream in, final int ignored) throws IOException {
        assert keyCodec != null;
        final var k = readFromStream(in, keyCodec);
        this.key = new InMemoryKey<>(k);
        this.val = readFromStream(in, valueCodec);
    }

    /** {@inheritDoc} */
    @Override
    public void serialize(@NonNull final SerializableDataOutputStream out) throws IOException {
        writeToStream(out, keyCodec, key.key());
        writeToStream(out, valueCodec, val);
    }
}
