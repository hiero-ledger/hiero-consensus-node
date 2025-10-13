// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.scratchpad;

import com.swirlds.config.api.Configuration;
import com.swirlds.platform.scratchpad.internal.StandardScratchpad;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.hiero.base.io.SelfSerializable;
import org.hiero.consensus.model.node.NodeId;

/**
 * A utility for "taking notes" that are preserved across restart boundaries.
 * <p>
 * A scratchpad instance is thread safe. All read operations and write operations against a scratchpad are atomic. Any
 * write that has completed is guaranteed to be visible to all subsequent reads, regardless of crashes/restarts.
 *
 * @param <K> the enum type that defines the scratchpad fields
 */
public interface Scratchpad<K extends Enum<K> & ScratchpadType> {

    /**
     * Create a new scratchpad.
     *
     * @param configuration the configuration to use
     * @param selfId the ID of this node
     * @param clazz the enum class that defines the scratchpad fields
     * @param id the unique ID of this scratchpad (creating multiple scratchpad instances on the same node with the same
     * unique ID has undefined (and possibly undesirable) behavior. Must not contain any non-alphanumeric characters,
     * with the exception of the following characters: "_", "-", and ".". Must not be empty.
     */
    static <K extends Enum<K> & ScratchpadType> Scratchpad<K> create(
            @NonNull final Configuration configuration,
            @NonNull final NodeId selfId,
            @NonNull final Class<K> clazz,
            @NonNull final String id) {
        return new StandardScratchpad<>(configuration, selfId, clazz, id);
    }

    /**
     * Log the contents of the scratchpad.
     */
    void logContents();

    /**
     * Get a value from the scratchpad.
     * <p>
     * The object returned by this method should be treated as if it is immutable. Modifying this object in any way may
     * cause the scratchpad to become corrupted.
     *
     * @param key the field to get
     * @param <V> the type of the value
     * @return the value, or null if the field is not present
     */
    @Nullable
    <V extends SelfSerializable> V get(@NonNull K key);

    /**
     * Set a field in the scratchpad. The scratchpad file is updated atomically. When this method returns, the data
     * written to the scratchpad will be present the next time the scratchpad is checked, even if that is after a
     * restart boundary.
     * <p>
     * The object set via this method should be treated as if it is immutable after this function is called. Modifying
     * this object in any way may cause the scratchpad to become corrupted.
     *
     * @param key the field to set
     * @param value the value to set, may be null
     * @param <V> the type of the value
     * @return the previous value
     */
    @Nullable
    <V extends SelfSerializable> V set(@NonNull final K key, @Nullable final V value);

    /**
     * Perform an arbitrary atomic operation on the scratchpad. This operation is atomic with respect to reads, writes,
     * and other calls to this method.
     * <p>
     * The map provided to the operation should not be accessed after the operation returns. Doing so is not thread safe
     * and may result in undefined behavior.
     * <p>
     * It is safe to keep references to the objects in the map after the operation returns as long as these objects are
     * treated as if they are immutable. Modifying these objects in any way may cause the scratchpad to become
     * corrupted.
     *
     * @param operation the operation to perform, is provided a map of all scratchpad fields to their current values. If
     * a field is not present in the map, then it should be considered to have the value null.
     */
    void atomicOperation(@NonNull final Consumer<Map<K, SelfSerializable>> operation);

    /**
     * Perform an arbitrary atomic operation on the scratchpad. This operation is atomic with respect to reads, writes,
     * and other calls to this method.
     * <p>
     * The map provided to the operation should not be accessed after the operation returns. Doing so is not thread safe
     * and may result in undefined behavior.
     * <p>
     * It is safe to keep references to the objects in the map after the operation returns as long as these objects are
     * treated as if they are immutable. Modifying these objects in any way may cause the scratchpad to become
     * corrupted.
     *
     * @param operation the operation to perform, is provided a map of all scratchpad fields to their current values. If
     * a field is not present in the map, then it should be considered to have the value null. The return value of this
     * operation indicates if the scratchpad was modified. This method should return true if the scratchpad was
     * modified, and false otherwise. If this method modifies data and returns false then data may be lost after a
     * restart.
     */
    void atomicOperation(@NonNull final Function<Map<K, SelfSerializable>, Boolean> operation);

    /**
     * Clear the scratchpad. Deletes all files on disk. Useful if a scratchpad type is being removed and the intention
     * is to delete all data associated with it.
     */
    void clear();
}
