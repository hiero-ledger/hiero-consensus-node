// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.system.status.PlatformStatus;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * API for building network protocols
 */
public interface Protocol {

    /**
     * Constructs an instance of a network protocol using the provided peerId
     * @return a network protocol for connectivity over the bidirectional network
     */
    PeerProtocol createPeerInstance(@NonNull final NodeId peerId);

    /**
     * Called from the wiring when platform status is changing
     * @param status new platform status
     */
    void updatePlatformStatus(@NonNull final PlatformStatus status);
}
