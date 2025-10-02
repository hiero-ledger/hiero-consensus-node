// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.node.NodeId;

/**
 * A notification about the performance of a completed reconnect.
 *
 * @param payload the reconnect performance payload that triggered this notification
 * @param nodeId the node ID that logged the payload, or null if the node ID is not available
 */
public record SynchronizationCompleteNotification(
        @NonNull SynchronizationCompletePayload payload, @Nullable NodeId nodeId)
        implements ReconnectNotification<SynchronizationCompletePayload> {}
