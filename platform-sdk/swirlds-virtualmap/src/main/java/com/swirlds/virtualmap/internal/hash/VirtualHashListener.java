/*
 * Copyright (C) 2021-2025 Hedera Hashgraph, LLC
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

package com.swirlds.virtualmap.internal.hash;

import com.swirlds.common.crypto.Hash;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;

/**
 * Listens to various events that occur during the hashing process.
 */
public interface VirtualHashListener {
    /**
     * Called when starting a new fresh hash operation.
     */
    default void onHashingStarted() {}

    /**
     * Called after each node is hashed, internal or leaf. This is called between
     * {@link #onHashingStarted()} and {@link #onHashingCompleted()}.
     *
     * @param path
     * 		Node path
     * @param hash
     * 		A non-null node hash
     */
    default void onNodeHashed(final long path, final Hash hash) {}

    /**
     * Called after each leaf node on a rank is hashed. This is called between
     * {@link #onHashingStarted()} and {@link #onHashingCompleted()}.
     *
     * @param leaf
     * 		A non-null leaf record representing the hashed leaf.
     */
    default void onLeafHashed(VirtualLeafBytes<?> leaf) {}

    /**
     * Called when all hashing has completed.
     */
    default void onHashingCompleted() {}
}
