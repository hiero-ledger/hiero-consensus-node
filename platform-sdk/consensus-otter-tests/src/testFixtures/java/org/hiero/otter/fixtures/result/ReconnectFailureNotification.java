// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.node.NodeId;

/**
 * A notification about a reconnect failure.
 *
 * @param payload the reconnect failure payload that triggered this notification
 * @param nodeId the node ID that logged the payload, or null if the node ID is not available
 */
public record ReconnectFailureNotification(@NonNull ReconnectFailurePayload payload, @Nullable NodeId nodeId)
        implements ReconnectNotification<ReconnectFailurePayload> {}
