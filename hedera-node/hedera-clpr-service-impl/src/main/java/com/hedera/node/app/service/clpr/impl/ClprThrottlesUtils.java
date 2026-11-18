// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprThrottles;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Utility class with functional operations over ClprThrottles.
 */
public final class ClprThrottlesUtils {

    private ClprThrottlesUtils() {}

    /**
     * Returns the largest payload this ledger may enqueue for a peer, whether a data message or a reply. The
     * destination is authoritative for its own max_message_payload_bytes, so a payload within this limit always fits in
     * a bundle the peer accepts. Falls back to the local limit when the peer has not advertised one.
     *
     * @param peerThrottles  the throttles last advertised by the peer, if any
     * @param localThrottles this ledger's throttles
     * @return the maximum payload size in bytes
     */
    public static int effectiveMaxPayloadBytes(
            @Nullable final ClprThrottles peerThrottles, @NonNull final ClprThrottles localThrottles) {
        requireNonNull(localThrottles);
        return (peerThrottles != null && peerThrottles.maxMessagePayloadBytes() > 0)
                ? peerThrottles.maxMessagePayloadBytes()
                : localThrottles.maxMessagePayloadBytes();
    }
}
