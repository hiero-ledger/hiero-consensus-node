// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import com.swirlds.base.state.Mutable;
import com.swirlds.common.utility.AbstractReservable;
import org.hiero.base.crypto.Hash;

/**
 * This class implements boilerplate functionality for a VirtualRoot.
 */
public abstract class AbstractVirtualRoot extends AbstractReservable implements Mutable, VirtualRoot {

    private boolean immutable;

    private Hash hash = null;

    protected AbstractVirtualRoot() {
        immutable = false;
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
    public void setImmutable(final boolean immutable) {
        this.immutable = immutable;
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
