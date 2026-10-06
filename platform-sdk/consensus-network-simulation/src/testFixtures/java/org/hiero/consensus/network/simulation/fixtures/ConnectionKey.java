// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation.fixtures;

import org.hiero.consensus.model.node.NodeId;

/**
 * Uniquely identifies a connection from one node to another.
 *
 * @param node1 one node
 * @param node2 the other node
 */
public record ConnectionKey(NodeId node1, NodeId node2) {}
