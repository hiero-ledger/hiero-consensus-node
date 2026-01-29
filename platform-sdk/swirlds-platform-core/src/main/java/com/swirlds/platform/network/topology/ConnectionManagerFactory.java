// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.topology;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.network.ConnectionManager;
import com.swirlds.platform.network.ConnectionTracker;
import com.swirlds.platform.network.InboundConnectionManager;
import com.swirlds.platform.network.OutboundConnectionManager;
import com.swirlds.platform.network.PeerInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

/**
 * Factory for creating inbound and outbound connection managers. Mostly needed for unit testing purposes, as
 * {@link DynamicConnectionManagers} is happily using the {@link #DEFAULT} one
 */
public interface ConnectionManagerFactory {

    /**
     * Creates new inbound connection manager
     *
     * @param otherPeer information about the peer we are supposed to connect to
     * @return new instance of inbound connection manager
     */
    ConnectionManager createInboundConnectionManager(@NonNull final PeerInfo otherPeer);

    /**
     * Creates new outbound connection manager
     *
     * @param configuration platform configuration
     * @param time source of time
     * @param selfId self's node id
     * @param otherPeer information about the peer we are supposed to connect to
     * @param connectionTracker connection tracker for all platform connections
     * @param ownKeysAndCerts private keys and public certificates
     * @return new outbound connection manager for these values
     */
    ConnectionManager createOutboundConnectionManager(
            @NonNull Configuration configuration,
            @NonNull Time time,
            @NonNull NodeId selfId,
            @NonNull PeerInfo otherPeer,
            @NonNull ConnectionTracker connectionTracker,
            @NonNull KeysAndCerts ownKeysAndCerts);

    /**
     * Default implementation of factory, returning real {@link InboundConnectionManager} and
     * {@link OutboundConnectionManager}
     */
    ConnectionManagerFactory DEFAULT = new ConnectionManagerFactory() {
        @Override
        public InboundConnectionManager createInboundConnectionManager(@NonNull final PeerInfo otherPeer) {
            return new InboundConnectionManager();
        }

        @Override
        public OutboundConnectionManager createOutboundConnectionManager(
                @NonNull final Configuration configuration,
                @NonNull final Time time,
                @NonNull final NodeId selfId,
                @NonNull final PeerInfo otherPeer,
                @NonNull final ConnectionTracker connectionTracker,
                @NonNull final KeysAndCerts ownKeysAndCerts) {
            return new OutboundConnectionManager(
                    configuration, time, selfId, otherPeer, connectionTracker, ownKeysAndCerts);
        }
    };
}
