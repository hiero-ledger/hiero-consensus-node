// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

import java.util.LinkedList;
import java.util.List;

/**
 * Provides information about peers we tried to reconnect with
 */
public class ReconnectPeerInfoPayload extends AbstractLogPayload {
    /** A list of peers we tried to reconnect with */
    private final List<PeerInfo> peerInfo;

    public ReconnectPeerInfoPayload() {
        super("");
        peerInfo = new LinkedList<>();
    }

    public void addPeerInfo(final long peerId, final String info) {
        peerInfo.add(new PeerInfo(peerId, info));
    }

    public List<PeerInfo> getPeerInfo() {
        return peerInfo;
    }

    public static class PeerInfo {
        private final long node;
        private final String message;

        public PeerInfo(long node, String message) {
            this.node = node;
            this.message = message;
        }

        public long getNode() {
            return node;
        }

        public String getMessage() {
            return message;
        }
    }
}
