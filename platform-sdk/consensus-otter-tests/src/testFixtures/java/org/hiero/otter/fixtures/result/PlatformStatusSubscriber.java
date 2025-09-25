// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Defines a subscriber that will receive each {@link PlatformStatus} a node enters.
 */
@FunctionalInterface
public interface PlatformStatusSubscriber {

    /**
     * Called when the node enters a new {@link PlatformStatus}.
     *
     * @param nodeId the node that created the round
     * @param status the new platform status
     * @return {@link SubscriberAction#UNSUBSCRIBE} to unsubscribe, {@link SubscriberAction#CONTINUE} to continue
     */
    SubscriberAction onPlatformStatusChange(@NonNull NodeId nodeId, @NonNull PlatformStatus status);
}
