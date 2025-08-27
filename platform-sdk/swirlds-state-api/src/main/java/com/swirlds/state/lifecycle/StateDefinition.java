// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * @param stateKey The "state key" that uniquely identifies this {@link ReadableKVState} within the
 *     {@link Schema} which are scoped to the service implementation. The key is therefore not
 *     globally unique, only unique within the service implementation itself.
 * @param keyCodec The {@link Codec} to use for parsing and writing keys in the registered state
 * @param valueCodec The {@link Codec} to use for parsing and writing values in the registered
 *     state
 * @param maxKeysHint A hint as to the maximum number of keys to be stored in this state. This value
 *     CANNOT CHANGE from one schema version to another. If it is changed, you will need to do a
 *     long-form migration to a new state.
 * @param onDisk Whether to store this state on disk
 * @param singleton Whether this state is a singleton, meaning it only has one key/value pair
 *                  associated with it. It cannot be a singleton and a queue at the same time.
 * @param queue Whether this state is a queue, meaning it is a FIFO queue of values. It cannot be a singleton and  queue
 *              at the same time.
 * @param <K> The type of key
 * @param <V> The type of value
 */
public record StateDefinition<K, V>(
        @NonNull String stateKey,
        @Nullable Codec<K> keyCodec,
        @NonNull Codec<V> valueCodec,
        long maxKeysHint,
        boolean onDisk,
        boolean singleton,
        boolean queue) {

    private static final int NO_MAX = -1;

    public StateDefinition {
        if (singleton && queue) {
            throw new IllegalArgumentException("A state cannot both be 'singleton' and 'queue'");
        }

        if (singleton && onDisk) {
            throw new IllegalArgumentException("A state cannot both be 'singleton' and 'onDisk'");
        }

        if (onDisk && maxKeysHint <= 0) {
            throw new IllegalArgumentException("You must specify the maxKeysHint when onDisk. Please see docs.");
        }

        if (queue && onDisk) {
            throw new IllegalArgumentException("A state cannot both be 'queue' and 'onDisk'");
        }

        if (!singleton && !queue) {
            Objects.requireNonNull(keyCodec, "keyCodec must be specified unless using singleton or queue types");
        }

        Objects.requireNonNull(valueCodec, "valueCodec must be specified");
    }

    /**
     * Convenience method for creating a {@link StateDefinition} for in-memory k/v states.
     *
     * @param stateKey The state key
     * @param keyCodec The codec for the key
     * @param valueCodec The codec for the value
     * @return An instance of {@link StateDefinition}
     * @param <K> The key type
     * @param <V> The value type
     */
    public static <K, V> StateDefinition<K, V> inMemory(
            @NonNull final String stateKey, @NonNull final Codec<K> keyCodec, @NonNull final Codec<V> valueCodec) {
        return new StateDefinition<>(stateKey, keyCodec, valueCodec, NO_MAX, false, false, false);
    }

    /**
     * Convenience method for creating a {@link StateDefinition} for on-disk k/v states.
     *
     * @param stateKey The state key
     * @param keyCodec The codec for the key
     * @param valueCodec The codec for the value
     * @param maxKeysHint A hint as to the maximum number of keys to be stored in this state. This
     *     value * CANNOT CHANGE from one schema version to another. If it is changed, you will need
     *     to do a * long-form migration to a new state.
     * @return An instance of {@link StateDefinition}
     * @param <K> The key type
     * @param <V> The value type
     */
    public static <K, V> StateDefinition<K, V> onDisk(
            @NonNull final String stateKey,
            @NonNull final Codec<K> keyCodec,
            @NonNull final Codec<V> valueCodec,
            final long maxKeysHint) {
        return new StateDefinition<>(stateKey, keyCodec, valueCodec, maxKeysHint, true, false, false);
    }

    /**
     * Convenience method for creating a {@link StateDefinition} for singleton states.
     *
     * @param stateKey The state key
     * @param valueCodec The codec for the singleton value
     * @return An instance of {@link StateDefinition}
     * @param <K> The key type
     * @param <V> The value type
     */
    public static <K, V> StateDefinition<K, V> singleton(
            @NonNull final String stateKey, @NonNull final Codec<V> valueCodec) {
        return new StateDefinition<>(stateKey, null, valueCodec, NO_MAX, false, true, false);
    }

    /**
     * Convenience method for creating a {@link StateDefinition} for queue states.
     *
     * @param stateKey The state key
     * @param elementCodec The codec for the elements of the queue
     * @return An instance of {@link StateDefinition}
     * @param <K> The key type
     * @param <V> The value type
     */
    public static <K, V> StateDefinition<K, V> queue(
            @NonNull final String stateKey, @NonNull final Codec<V> elementCodec) {
        return new StateDefinition<>(stateKey, null, elementCodec, NO_MAX, false, false, true);
    }
}
