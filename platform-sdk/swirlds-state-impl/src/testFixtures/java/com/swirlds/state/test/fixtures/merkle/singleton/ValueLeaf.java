// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle.singleton;

import static com.swirlds.state.merkle.StateUtils.readFromStream;
import static com.swirlds.state.merkle.StateUtils.writeToStream;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;

/**
 * A Merkle leaf that stores an arbitrary value with delegated serialization based on the {@link
 * #classId}.
 * @deprecated This class should be removed together with {@link MerkleStateRoot}.
 */
@Deprecated
public class ValueLeaf<T> extends PartialMerkleLeaf implements MerkleLeaf {

    /**
     * {@deprecated} Needed for ConstructableRegistry, TO BE REMOVED ASAP
     */
    @Deprecated(forRemoval = true)
    public static final long CLASS_ID = 0x65A48B28C563D72EL;

    private final long classId;
    private final Codec<T> codec;
    /** The actual value. For example, it could be an Account or SmartContract. */
    @Nullable
    private T val;

    /**
     * {@deprecated} Default constructor provided for ConstructableRegistry, TO BE REMOVED ASAP
     */
    @Deprecated(forRemoval = true)
    public ValueLeaf() {
        codec = null;
        classId = CLASS_ID;
    }

    /**
     * Used by the deserialization system to create an {@link ValueLeaf} that does not yet have a
     * value. Normally this should not be used.
     *
     * @param singletonClassId The class ID of the object
     * @param codec   The codec to use for serialization
     */
    public ValueLeaf(final long singletonClassId, @NonNull Codec<T> codec) {
        this.codec = requireNonNull(codec);
        this.classId = singletonClassId;
    }

    /**
     * Create a new instance with the given value.
     *
     * @param singletonClassId The class ID of the object
     * @param codec   The codec to use for serialization
     * @param value The value.
     */
    public ValueLeaf(final long singletonClassId, @NonNull Codec<T> codec, @Nullable final T value) {
        this(singletonClassId, codec);
        this.val = value;
    }

    /** {@inheritDoc} */
    @Override
    public ValueLeaf<T> copy() {
        throwIfImmutable();
        throwIfDestroyed();

        final var cp = new ValueLeaf<>(classId, codec, val);
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
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        if (codec == null) {
            throw new IllegalStateException("Metadata is null, meaning this is not a proper object");
        }

        writeToStream(out, codec, val);
    }

    /** {@inheritDoc} */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        if (codec == null) {
            throw new IllegalStateException("Metadata is null, meaning this is not a proper object");
        }

        this.val = readFromStream(in, codec);
    }

    /**
     * Gets the value.
     *
     * @return The value.
     */
    @Nullable
    public T getValue() {
        return val;
    }

    /**
     * Sets the value. Cannot be called if the leaf is immutable.
     *
     * @param value the new value
     */
    public void setValue(@Nullable final T value) {
        throwIfImmutable();
        this.val = value;
    }

    /**
     * Gets the codec used for serialization and deserialization.
     *
     * @return The codec
     */
    public Codec<T> getCodec() {
        return codec;
    }
}
