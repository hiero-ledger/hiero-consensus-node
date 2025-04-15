// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.iterators.internal;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.route.MerkleRoute;
import java.io.IOException;
import org.hiero.base.constructable.ConstructableIgnored;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.model.crypto.Hash;

/**
 * This object is used by {@link com.swirlds.common.merkle.iterators.MerkleIterator MerkleIterator}
 * as a placeholder for null values. This object's sole purpose is to store a route associated with
 * the null leaf, and so most other methods intentionally throw unsupported operation exceptions.
 */
@ConstructableIgnored
public class NullNode implements MerkleLeaf {

    public static final long CLASS_ID = 0x654cf5401e13e7e1L;

    private final MerkleRoute route;

    /**
     * Create a placeholder for a null node.
     */
    public NullNode(final MerkleRoute route) {
        this.route = route;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reserve() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean tryReserve() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean release() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDestroyed() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getReservationCount() {
        throw new UnsupportedOperationException();
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
    public Hash getHash() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHash(final Hash hash) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleRoute getRoute() {
        return route;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRoute(final MerkleRoute route) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MerkleLeaf copy() {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLeaf() {
        return true;
    }
}
