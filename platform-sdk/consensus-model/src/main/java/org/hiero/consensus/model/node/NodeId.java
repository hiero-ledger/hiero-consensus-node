// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.node;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Objects;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A class that is used to uniquely identify a Swirlds Node.
 */
public class NodeId implements Comparable<NodeId>, SelfSerializable {

    /** The class identifier for this class. */
    private static final long CLASS_ID = 0xea520dcf050bcaadL;

    /** The class version for this class. */
    public static final class ClassVersion {
        /**
         * The original version of the class.
         *
         * @since 0.39.0
         */
        public static final int ORIGINAL = 1;
    }

    /** The undefined NodeId. */
    public static final NodeId UNDEFINED_NODE_ID = null;

    /** The first allowed Node ID. */
    public static final long LOWEST_NODE_NUMBER = 0L;

    /** The first NodeId. */
    public static final NodeId FIRST_NODE_ID = NodeId.of(LOWEST_NODE_NUMBER);

    /** The ID number. */
    private long id;

    /**
     * Return a potentially cached NodeId instance for a given node id value.
     * The caller MUST NOT mutate the returned object even though the NodeId class is technically mutable.
     * If the caller needs to mutate the instance, then it must use the regular NodeId(long) constructor instead.
     *
     * @param id a node id value
     * @return a NodeId instance
     */
    public static NodeId of(final long id) {
        return NodeIdCache.getOrCreate(id);
    }

    /**
     * Constructs an empty NodeId objects, used in deserialization only.
     */
    public NodeId() {}

    /**
     * Constructs a NodeId object with the given ID number.  The ID number must be non-negative.
     *
     * @param id the ID number
     * @throws IllegalArgumentException if the ID number is negative
     */
    protected NodeId(final long id) {
        if (id < LOWEST_NODE_NUMBER) {
            throw new IllegalArgumentException("id must be non-negative");
        }
        this.id = id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.ORIGINAL;
    }

    /**
     * Gets the long value of the NodeId
     *
     * @return the long value of the NodeId
     */
    public long id() {
        return id;
    }

    /**
     * Get a NodeId that is offset from this NodeId by the given amount.
     *
     * @param offset the amount to offset by
     * @return the NodeId offset by the given amount
     */
    public NodeId getOffset(final long offset) {
        final long newValue = id + offset;
        if (newValue < LOWEST_NODE_NUMBER) {
            throw new IllegalArgumentException("the new NodeId, %d, must not be below the minimum value of %d."
                    .formatted(newValue, LOWEST_NODE_NUMBER));
        }
        return NodeId.of(newValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(@NonNull final NodeId other) {
        Objects.requireNonNull(other, "NodeId cannot be null");
        return Long.compare(this.id, other.id);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String toString() {
        return Long.toString(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(SerializableDataOutputStream out) throws IOException {
        out.writeLong(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(SerializableDataInputStream in, int version) throws IOException {
        id = in.readLong();
    }

    /**
     * Deserialize a NodeId from a {@link SerializableDataInputStream}.
     *
     * @param in the {@link SerializableDataInputStream} to read from
     * @return the deserialized NodeId
     * @throws IOException thrown if an exception occurs while reading from the stream or the long value is negative,
     */
    public static NodeId deserializeLong(SerializableDataInputStream in, boolean allowNull) throws IOException {
        final long longValue = in.readLong();
        if (longValue < LOWEST_NODE_NUMBER) {
            if (allowNull) {
                return null;
            }
            throw new IOException("id must be non-negative");
        }
        return NodeId.of(longValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || NodeId.class != o.getClass()) {
            return false;
        }
        final NodeId nodeId = (NodeId) o;
        return id == nodeId.id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}
