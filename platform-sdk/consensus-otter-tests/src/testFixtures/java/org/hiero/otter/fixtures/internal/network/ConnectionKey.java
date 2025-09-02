// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.network;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a key for a connection between two nodes in the topology.
 *
 * @param sender the starting node of the connection
 * @param receiver the ending node of the connection
 */
public record ConnectionKey(@NonNull NodeId sender, @NonNull NodeId receiver) {}
