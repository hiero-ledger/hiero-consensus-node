// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public record ReconnectFailureNotification(@NonNull ReconnectFailurePayload payload, @Nullable NodeId nodeId)
        implements ReconnectNotification<ReconnectFailurePayload> {}
