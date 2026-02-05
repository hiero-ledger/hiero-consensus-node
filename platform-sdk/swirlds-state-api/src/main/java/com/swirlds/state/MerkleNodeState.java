// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state;

import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.Reservable;
import com.swirlds.state.lifecycle.StateMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.base.crypto.Hash;

/**
 * Represent a state backed up by the Merkle tree. It's a {@link State} implementation that is backed by a Merkle tree.
 * It provides methods to manage the service states in the merkle tree.
 * This interface supports two level of state abstractions:
 * <ul>
 *     <li> codec-based State API, as used by execution </li>
 *     <li> protobuf binary states API supporting notions of singletons, queues, and key-value pairs</li>
 * </ul>
 *
 * @param <T> The type of the root node of the Merkle tree.
 */
public interface MerkleNodeState<T extends Reservable> extends State {

    /**
     * @return an instance representing a root of the Merkle tree. For most of the implementations
     * this default implementation will be sufficient.
     */
    T getRoot();

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    MerkleNodeState<T> copy();

    //
    // The following block of methods is for the high-level codec-based State API operating named service states.
    //

    /**
     * Commit all singleton states for every registered service.
     */
    void commitSingletons();

    /**
     * Initializes the defined service state.
     *
     * @param md The metadata associated with the state.
     */
    void initializeState(@NonNull StateMetadata<?, ?> md);

    /**
     * Removes the node and metadata from the state merkle tree.
     *
     * @param serviceName The service name. Cannot be null.
     * @param stateId The state ID
     */
    void removeServiceState(@NonNull String serviceName, int stateId);

    /**
     * Get the merkle path of the queue element
     * @param stateId The state ID of the queue state.
     * @param expectedValue The expected value of the queue element to retrieve the path for
     * @return The merkle path of the queue element or {@code com.swirlds.virtualmap.internal.Path#INVALID_PATH} if stateId is unknown or element is not found.
     * @param <V> The type of the value of the queue element
     */
    default <V> long getQueueElementPath(
            final int stateId, @NonNull final V expectedValue, @NonNull final Codec<V> valueCodec) {
        return getQueueElementPath(stateId, valueCodec.toBytes(expectedValue));
    }

    /**
     * Get the merkle path of the key-value pair in the state by its state ID and key.
     * @param stateId The state ID of the key-value pair.
     * @param key The key of the key-value pair.
     * @return The merkle path of the key-value pair or {@code com.swirlds.virtualmap.internal.Path#INVALID_PATH}
     * if the key is not found or the stateId is unknown.
     * @param <V> The type of the value of the queue element
     */
    default <V> long getKvPath(final int stateId, @NonNull final V key, @NonNull final Codec<V> keyCodec) {
        return getKvPath(stateId, keyCodec.toBytes(key));
    }

    /**
     * Get the hash of the merkle node at the given path.
     * @param path merkle path
     * @return hash of the merkle node at the given path or null if the path is non-existent
     */
    Hash getHashForPath(long path);

    /**
     * Prepares a Merkle proof for the given path.
     * @param path merkle path
     * @return Merkle proof for the given path or null if the path is non-existent
     */
    MerkleProof getMerkleProof(long path);

    //
    // The following block of methods is for the mid-level API working with protobuf binary states: singletons, queues,
    // and key-value pairs.
    //

    /**
     * Get the merkle path of the singleton state by its ID.
     * @param stateId The state ID of the singleton state.
     * @return The merkle path of the singleton state or {@code com.swirlds.virtualmap.internal.Path#INVALID_PATH} if the stateId is unknown.
     */
    long getSingletonPath(int stateId);

    /**
     * Get the merkle path of the queue element by its state ID and value.
     * @param stateId The state ID of the queue state.
     * @param expectedValue The expected value of the queue element to retrieve the path for
     * @return The merkle path of the queue element by its state ID and value or {@code com.swirlds.virtualmap.internal.Path#INVALID_PATH}
     * if the stateId is unknown.
     */
    long getQueueElementPath(int stateId, @NonNull Bytes expectedValue);

    /**
     * Get the merkle path of the key-value pair in the state by its state ID and key.
     * @param stateId The state ID of the key-value pair.
     * @param key The key of the key-value pair.
     * @return The merkle path of the key-value pair or {@code com.swirlds.virtualmap.internal.Path#INVALID_PATH}
     * if the key is not found or the stateId is unknown.
     */
    long getKvPath(int stateId, @NonNull Bytes key);

    /**
     * Get a map value from the state
     *
     * @param stateId the state ID
     * @param key the binary protobuf encoded key
     * @return the binary protobuf encoded value, null if not found
     * @throws IllegalArgumentException if the stateId is not valid
     */
    @Nullable
    Bytes getKv(final int stateId, @NonNull final Bytes key);

    /**
     * Get a singleton value from the latest state version
     *
     * @param singletonId the singleton ID, from SingletonType enum, eg. SingletonType.ENTITYIDSERVICE_I_ENTITY_ID
     * @return the binary protobuf encoded value
     * @throws IllegalArgumentException if the singletonId is not valid
     */
    @Nullable
    Bytes getSingleton(final int singletonId);

    /**
     * Get a queue state from the latest state version
     *
     * @param stateId the state ID of the queue state
     * @return the queue state, which has the indexes of head and tail
     * @throws IllegalArgumentException if the stateId is not valid or not a queue type
     */
    QueueState getQueueState(final int stateId);

    /**
     * Peek at head element in a queue from the latest state version
     *
     * @param stateId the state ID of the queue state
     * @return the binary protobuf encoded value at head
     * @throws IllegalArgumentException if the stateId is not valid or not a queue type
     */
    Bytes peekQueueHead(final int stateId);

    /**
     * Peek at tail element in a queue from the latest state version
     *
     * @param stateId the state ID of the queue state
     * @return the binary protobuf encoded value at tail
     * @throws IllegalArgumentException if the stateID is not valid or not a queue type
     */
    Bytes peekQueueTail(final int stateId);

    /**
     * Peek at element at index in a queue from the latest state version. Index has to be between the head and the tail
     * inclusive. To find the head and tail indexes use {@link #getQueueState(int)}
     *
     * @param stateId the state ID of the queue state
     * @param index the index to peek at
     * @return the binary protobuf encoded value at index
     * @throws IllegalArgumentException if the stateID is not valid or not a queue type
     */
    Bytes peekQueue(final int stateId, final int index);

    /**
     * Get all elements in a queue from the latest state version as a list. The list will be ordered from head to tail.
     * <br/>
     * <b>WARNING</b>: This method may be expensive and slow for large queues.
     *
     * @param stateId the state ID of the queue state
     * @return the list of binary protobuf encoded values in the queue
     * @throws IllegalArgumentException if the stateID is not valid or not a queue type
     */
    List<Bytes> getQueueAsList(final int stateId);

    /**
     * Add/update a singleton value for the given state ID using raw protobuf bytes of the value.
     * Null values are not allowed.
     *
     * @param stateId the singleton state ID
     * @param value the raw protobuf-encoded bytes of the singleton value (not wrapped)
     */
    void updateSingleton(int stateId, @NonNull Bytes value);

    /**
     * Remove a singleton value for the given state ID.
     * Does nothing if the singleton with such a state ID does not exist.
     *
     * @param stateId the singleton state ID
     */
    void removeSingleton(int stateId);

    /**
     * Add/update a key/value entry for the given map state ID using raw protobuf bytes of the key and value.
     * The key must not be null. The value may be null, in which case the mapping is removed (same as {@link #removeKv(int, Bytes)}).
     *
     * @param stateId the map state ID
     * @param key the raw protobuf-encoded key bytes (not wrapped), must not be null
     * @param value the raw protobuf-encoded value bytes (not wrapped), may be null to indicate removal
     */
    void updateKv(int stateId, @NonNull Bytes key, @Nullable Bytes value);

    /**
     * Remove a key/value entry for the given map state ID.
     *
     * @param stateId the map state ID
     * @param key the raw protobuf-encoded key bytes (not wrapped), must not be null
     */
    void removeKv(int stateId, @NonNull Bytes key);

    /**
     * Push an element to the queue for the given state ID using raw protobuf bytes of the element value.
     *
     * @param stateId the queue state ID
     * @param value the raw protobuf-encoded element bytes (not wrapped), must not be null
     */
    void pushQueue(int stateId, @NonNull Bytes value);

    /**
     * Pop an element from the queue for the given state ID.
     * Returns null if the queue is empty.
     *
     * @param stateId the queue state ID
     * @return the raw protobuf-encoded element bytes (not wrapped), or null if empty
     */
    @Nullable
    Bytes popQueue(int stateId);

    /**
     * Remove all the elements of the queue for the given state ID and the queue metadata.
     * Does nothing if the queue with such a state ID does not exist.
     * <b>WARNING:</b> This operation may be expensive and slow for large queues.
     * @param stateId the queue state ID
     */
    void removeQueue(int stateId);
}
