// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.impl;

import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.route.MerkleRoute;
import org.hiero.consensus.model.crypto.Hash;

/**
 * Implementing this interface allows for a class to implement the {@link MerkleLeaf} interface using inheritance
 * by composition with minimal boilerplate.
 */
public interface ComposedMerkleLeaf extends MerkleLeaf {

    /**
     * Get an object that implements the {@link PartialMerkleLeaf} interface.
     * This object will be inherited by composition.
     *
     * @return an object which implements the {@link PartialMerkleLeaf} interface.
     */
    PartialMerkleLeaf getMerkleImplementation();

    /**
     * {@inheritDoc}
     */
    @Override
    default Hash getHash() {
        return getMerkleImplementation().getHash();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default void setHash(final Hash hash) {
        getMerkleImplementation().setHash(hash);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default MerkleRoute getRoute() {
        return getMerkleImplementation().getRoute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default void setRoute(final MerkleRoute route) {
        getMerkleImplementation().setRoute(route);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default boolean isLeaf() {
        return getMerkleImplementation().isLeaf();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default boolean isImmutable() {
        return getMerkleImplementation().isImmutable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default void reserve() {
        getMerkleImplementation().reserve();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default boolean tryReserve() {
        return getMerkleImplementation().tryReserve();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default boolean release() {
        return getMerkleImplementation().release();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default boolean isDestroyed() {
        return getMerkleImplementation().isDestroyed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default int getReservationCount() {
        return getMerkleImplementation().getReservationCount();
    }
}
