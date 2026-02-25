// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.swirlds.logging.legacy.payload.LogPayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.node.NodeId;

/**
 * A notification about a reconnect event. Reconnect events are discovered by looking for certain log payloads. This
 * notification provides the relevant payload and node ID the payload was logged on.
 *
 * @param <T> the type of log payload that this notification contains
 */
public interface ReconnectNotification<T extends LogPayload> {

    /**
     * Get the payload that triggered this notification.
     *
     * @return the log payload
     */
    @NonNull
    T payload();

    /**
     * Get the node ID that logged the payload.
     *
     * @return the node ID, or null if the node ID is not available
     */
    @Nullable
    NodeId nodeId();
}
