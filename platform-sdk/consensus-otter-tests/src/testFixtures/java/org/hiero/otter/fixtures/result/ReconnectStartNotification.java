// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public record ReconnectStartNotification(@NonNull ReconnectStartPayload payload, @Nullable NodeId nodeId)
        implements ReconnectNotification<ReconnectStartPayload> {}
