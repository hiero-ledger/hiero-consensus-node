// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import com.swirlds.state.BinaryState;
import com.swirlds.state.State;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represent a state backed up by the Virtual Map tree. It's a {@link State} and {@link BinaryState}
 * implementation that is backed by a {@link VirtualMap}.
 * It provides methods to manage the service states in the merkle tree.
 * This interface supports two level of state abstractions:
 * <ul>
 *     <li> codec-based State API, as used by execution </li>
 *     <li> protobuf binary states API supporting notions of singletons, queues, and key-value pairs</li>
 * </ul>
 *
 */
public interface VirtualMapState extends State, BinaryState {
    /**
     * @return an instance representing a root of the Merkle tree. For most of the implementations
     * this default implementation will be sufficient.
     */
    VirtualMap getRoot();

}
