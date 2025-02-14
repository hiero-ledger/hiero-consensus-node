/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.state;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.state.State;
import com.swirlds.state.merkle.StateMetadata;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Represent a state backed up by the Merkle tree. It's a {@link MerkleNode} and provides methods to put service states
 * into the merkle tree.
 */
public interface MerkeNodeState extends State, MerkleNode {

    @NonNull
    @Override
    MerkeNodeState copy();

    /**
     * Puts the defined service state and its associated node into the merkle tree. The precondition
     * for calling this method is that node MUST be a {@link MerkleMap} or {@link VirtualMap} and
     * MUST have a correct label applied. If the node is already present, then this method does nothing
     * else.
     *
     * @param md The metadata associated with the state
     * @param nodeSupplier Returns the node to add. Cannot be null. Can be used to create the node on-the-fly.
     * @throws IllegalArgumentException if the node is neither a merkle map nor virtual map, or if
     * it doesn't have a label, or if the label isn't right.
     */
    default void putServiceStateIfAbsent(
            @NonNull final StateMetadata<?, ?> md, @NonNull final Supplier<? extends MerkleNode> nodeSupplier) {
        putServiceStateIfAbsent(md, nodeSupplier, n -> {});
    }

    /**
     * Puts the defined service state and its associated node into the merkle tree. The precondition
     * for calling this method is that node MUST be a {@link MerkleMap} or {@link VirtualMap} and
     * MUST have a correct label applied. No matter if the resulting node is newly created or already
     * present, calls the provided initialization consumer with the node.
     *
     * @param md The metadata associated with the state
     * @param nodeSupplier Returns the node to add. Cannot be null. Can be used to create the node on-the-fly.
     * @param nodeInitializer The node's initialization logic.
     * @throws IllegalArgumentException if the node is neither a merkle map nor virtual map, or if
     * it doesn't have a label, or if the label isn't right.
     */
    <T extends MerkleNode> void putServiceStateIfAbsent(
            @NonNull final StateMetadata<?, ?> md,
            @NonNull final Supplier<T> nodeSupplier,
            @NonNull final Consumer<T> nodeInitializer);
}
