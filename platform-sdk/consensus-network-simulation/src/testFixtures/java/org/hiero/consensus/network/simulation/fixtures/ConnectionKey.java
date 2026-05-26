// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation.fixtures;

import org.hiero.consensus.model.node.NodeId;

public record ConnectionKey(NodeId node1, NodeId node2) {}
