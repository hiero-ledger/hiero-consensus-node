// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.gossip;

import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

public record SyncProgress(NodeId peerId, EventWindow localWindow, EventWindow peerWindow) {}
