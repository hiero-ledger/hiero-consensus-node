// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;

/**
 * Responsible for outbound CLPR RPC calls to a single peer endpoint.
 *
 * <p>Implementations may be long-lived and shared across many calls — {@link ClprEndpointClientCache}
 * caches one per peer and reuses its underlying channel for as long as the cache keeps it. This
 * interface deliberately exposes no way to close or shut anything down: a caller holding a
 * {@code ClprEndpointClient} obtained from the cache has no way, accidental or otherwise, to tear it
 * down out from under other callers. Only the concrete implementation the cache builds internally
 * exposes that lifecycle control, and only to the cache.
 */
public interface ClprEndpointClient {

    /**
     * Sends a sync request to the peer endpoint and returns the response.
     *
     * @param request the outbound sync payload
     * @param timeout the call deadline
     * @return the peer's response payload
     * @throws ClprSyncException if the call fails
     */
    @NonNull
    ClprSyncPayload sync(@NonNull ClprSyncPayload request, @NonNull Duration timeout) throws ClprSyncException;

    /**
     * Sends a discoverEndpoints request to the peer endpoint and returns the peer's known
     * endpoint list for the given Channel.
     *
     * @param channelId the 32-byte Channel ID to discover endpoints for
     * @param timeout the call deadline
     * @return the peer's known endpoints for this Channel (possibly empty)
     * @throws ClprDiscoveryException if the call fails
     */
    @NonNull
    List<ClprEndpoint> discoverEndpoints(@NonNull Bytes channelId, @NonNull Duration timeout)
            throws ClprDiscoveryException;

    /**
     * Opens the streaming sync RPC and returns a handle for driving it.
     * A single request/response pair is not enough to complete a cycle — the streaming sync protocol's
     * ClprBundleRequest/ClprBundleResponse exchange can call for each side to write more than once
     * before half-closing (e.g., the initiator sends its own request, reads the peer's
     * request-plus-bundle, then writes a second message carrying just its bundle for the peer). The
     * caller drives that sequence; this method only opens the call.
     *
     * <p>The returned handle owns a live gRPC stream and must be closed — it is
     * {@link AutoCloseable}, so a try-with-resources block is the intended usage.
     *
     * @param timeout the deadline for the <em>entire</em> exchange, not for a single message. It
     *     starts running when this method is called and covers every subsequent write and read on
     *     the returned handle, so it must be sized for the whole multi-bundle conversation rather
     *     than for one round trip the way {@link #sync}'s timeout is.
     * @return a handle for writing/reading messages on the new stream
     */
    @NonNull
    ClprStreamingSyncCall streamingSync(@NonNull Duration timeout);

    /**
     * Exception thrown when an outbound CLPR sync call fails.
     */
    class ClprSyncException extends Exception {
        /**
         * @param message the detail message
         */
        public ClprSyncException(@NonNull final String message) {
            super(message);
        }

        /**
         * @param message the detail message
         * @param cause   the underlying throwable
         */
        public ClprSyncException(@NonNull final String message, @NonNull final Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when an outbound CLPR discoverEndpoints call fails.
     */
    class ClprDiscoveryException extends Exception {
        /**
         * @param message the detail message
         * @param cause   the underlying throwable
         */
        public ClprDiscoveryException(@NonNull final String message, @NonNull final Throwable cause) {
            super(message, cause);
        }
    }
}
