// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.crypto;

import org.hiero.consensus.model.io.SelfSerializable;

public interface SerializableHashable extends Hashable, SelfSerializable {
    /**
     * Should return a hash of the object if it has been calculated. If the method returns null, an external utility
     * will serialize the object, including its class ID and version, and set its hash by calling
     * {@link #setHash(Hash)}.
     * <p>
     * If the class wants to implement its own hashing, this method should never return null. The class should
     * calculate its own hash, which should include the class ID and version, and return that hash by this method.
     *
     * @return the hash of the object.
     */
    @Override
    Hash getHash();

    /**
     * {@inheritDoc}
     */
    @Override
    void setHash(Hash hash);
}
