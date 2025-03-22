// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.crypto;

import edu.umd.cs.findbugs.annotations.Nullable;

public interface Hashable {

    /**
     * If this object is not self hashing (i.e. {@link #isSelfHashing()} returns false) then this method returns
     * an up to date hash of the contents of the object if it has been computed, or null if the hash
     * has not yet been computed. The hash returned should be the last hash passed to {@link #setHash(Hash)},
     * or null if that method has never been called.
     * <p>
     * If this object is self hashing (i.e. {@link #isSelfHashing()} returns true) then this method must always
     * return a non-null hash that is up to date with respect to the data in this object. It is ok
     * if computation of the hash of such an object is delayed until this method is actually called.
     * <p>
     * This method must never return a non-null hash that does not match the contents of this object.
     *
     * @return a valid hash or null
     */
    @Nullable
    Hash getHash();

    /**
     * If this object is not self hashing (i.e. {@link #isSelfHashing()} returns false) then this
     * method is used to set the hash of this object.
     * <p>
     * If this object is self hashing (i.e. {@link #isSelfHashing()} returns true) then this
     * method should throw an {@link UnsupportedOperationException}.
     *
     * @param hash
     * 		the hash of the object
     * @throws UnsupportedOperationException
     * 		if this object is self hashing
     */
    void setHash(Hash hash);

    /**
     * This method must be called when an object is mutated in a way that makes the current hash no longer valid.
     * <p>
     * If this object is not self hashing (i.e. {@link #isSelfHashing()} returns false) then this method should
     * cause {@link #getHash()} to return null if it is called afterwards.
     * <p>
     * If this object is self hashing (i.e. {@link #isSelfHashing()} returns true) then it should either be a
     * no-op, or it can be used to force the object to recompute its hash (at the implementer's discretion).
     */
    default void invalidateHash() {
        if (!isSelfHashing()) {
            setHash(null);
        }
    }

    /**
     * A method by which the object can indicate whether it calculates its own hash or it is hashed externally.
     *
     * @return true if this object calculates its own hash, false otherwise
     */
    default boolean isSelfHashing() {
        return false;
    }
}
