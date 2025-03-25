// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.utility;

import org.hiero.consensus.model.crypto.Hashable;

/**
 * <p>
 * Describes an object that contains a key.
 * </p>
 *
 * <p>
 * If a Keyed object implements {@link com.swirlds.common.FastCopyable FastCopyable}, a
 * {@link com.swirlds.common.FastCopyable#copy() copy()} operation is expected to copy the key.
 * </p>
 *
 * <p>
 * If a Keyed object implements {@link Hashable Hashable}
 * then the key is expected to be hashed.
 * </p>
 *
 * <p>
 * If a Keyed object is capable of being serialized and deserialized
 * then the key is expected to be serialized and deserialized as well.
 * </p>
 *
 * @param <K>
 * 		the type of the key, each instance of this type must be effectively immutable
 * 		(that is, no operation after initial construction should be capable of changing the
 * 		behavior of {@link Object#hashCode()} or {@link Object#equals(Object)}.
 */
public interface Keyed<K> {

    /**
     * Get the key that corresponds to this object. Must always return the last object supplied by
     * {@link #setKey(Object)}, or null if {@link #setKey(Object)} has never been called.
     *
     * @return the key that corresponds to this object
     */
    K getKey();

    /**
     * Set the key that corresponds to this object.
     *
     * @param key
     * 		the new key for this object
     */
    void setKey(K key);
}
