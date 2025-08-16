// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle.memory;

import com.swirlds.merkle.map.MerkleMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a key in the {@link MerkleMap} used for in-memory states
 *
 * @param key The key to use. Cannot be null.
 * @param <K> The type of the key
 */
public record InMemoryKey<K>(@NonNull K key) {}
