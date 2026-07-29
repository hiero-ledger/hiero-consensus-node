// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.gossip;

import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.hashgraph.EventWindow;

public record SyncProgress(NodeId peerId, EventWindow localWindow, EventWindow peerWindow) {}
