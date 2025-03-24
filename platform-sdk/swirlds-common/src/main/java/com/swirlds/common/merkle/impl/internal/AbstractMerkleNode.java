// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.impl.internal;

import static com.swirlds.common.merkle.route.MerkleRouteFactory.getEmptyRoute;

import com.swirlds.base.state.Mutable;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.exceptions.MerkleRouteException;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.common.merkle.interfaces.HasMerkleRoute;
import com.swirlds.common.merkle.interfaces.MerkleType;
import com.swirlds.common.merkle.route.MerkleRoute;
import com.swirlds.common.utility.AbstractReservable;
import java.beans.Transient;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.Hashable;

/**
 * This class implements boilerplate functionality for a {@link MerkleNode}.
 */
public abstract sealed class AbstractMerkleNode extends AbstractReservable
        implements Hashable, HasMerkleRoute, Mutable, MerkleType permits PartialMerkleLeaf, AbstractMerkleInternal {

    private boolean immutable;

    private MerkleRoute route;

    private Hash hash = null;

    protected AbstractMerkleNode() {
        immutable = false;
        route = getEmptyRoute();
    }

    protected AbstractMerkleNode(final AbstractMerkleNode that) {
        this.route = that.getRoute();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getHash() {
        return hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHash(final Hash hash) {
        this.hash = hash;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isImmutable() {
        return immutable;
    }

    /**
     * Specify the immutability status of the node.
     *
     * @param immutable
     * 		if this node should be immutable
     */
    @Transient
    public void setImmutable(final boolean immutable) {
        this.immutable = immutable;
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
        if (!(getRoute() == route || getRoute().equals(route)) && getReservationCount() > 1) {
            // If you see this exception, the most likely culprit is that you are attempting to "move" a merkle
            // node from one position to another but the merkle node has multiple parents. If this operation was
            // allowed to proceed, a node in multiple trees would have the incorrect route in at least one of those
            // trees. Instead of moving the node, make a copy and move the copy.
            throw new MerkleRouteException("Routes can not be set unless the reservation count is 0 or 1. (type = "
                    + this.getClass().getName() + ", reservation count = " + getReservationCount() + ", route = "
                    + getRoute() + ", new route = " + route + ")");
        }
        this.route = route;
    }

    /**
     * Perform any required cleanup for this node, if necessary.
     */
    protected void destroyNode() {
        // override if needed
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onDestroy() {
        destroyNode();
    }
}
