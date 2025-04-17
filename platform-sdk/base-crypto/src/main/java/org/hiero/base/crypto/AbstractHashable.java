// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

/**
 * Boilerplate implementation for the Hashable interface.
 */
public abstract class AbstractHashable implements Hashable {

    private Hash hash = null;

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
    public void setHash(Hash hash) {
        this.hash = hash;
    }
}
