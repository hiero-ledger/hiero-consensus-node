// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A notification about a reconnect being started.
 *
 * @param payload the reconnect start payload that triggered this notification
 * @param nodeId the node ID that logged the payload, or null if the node ID is not available
 */
public record ReconnectStartNotification(@NonNull ReconnectStartPayload payload, @Nullable NodeId nodeId)
        implements ReconnectNotification<ReconnectStartPayload> {}
