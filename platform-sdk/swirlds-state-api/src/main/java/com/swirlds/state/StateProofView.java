// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Implementation of this interface provides a set of method required to create state proofs.
 */
public interface StateProofView {

    /**
     * Get the merkle path of the singleton state by its ID.
     * @param stateId The state ID of the singleton state.
     * @return The merkle path of the singleton state
     */
    long singletonPathByKey(int stateId);

    /**
     * Get the merkle path of the queue element
     * @param stateId The state ID of the queue state.
     * @param expectedValue The expected value of the queue element to retrieve the path for
     * @param valueCodec The codec for the value type of the queue element
     * @return The merkle path of the queue element
     * @param <V> The type of the value of the queue element
     */
    <V> long queueElementPathByValue(int stateId, V expectedValue, Codec<V> valueCodec);

    /**
     * Get the merkle path of the key-value pair in the state by its ID.
     * @param stateId The state ID of the key-value pair.
     * @param key The key of the key-value pair.
     * @return The merkle path of the key-value pair
     */
    long kvPathByKey(int stateId, Bytes key);
}
