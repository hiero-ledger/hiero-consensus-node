// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A workflow for processing CLPR endpoint-to-endpoint sync requests.
 * <p>
 * Unlike {@link com.hedera.node.app.workflows.ingest.IngestWorkflow} (which submits transactions
 * for consensus) or {@link com.hedera.node.app.workflows.query.QueryWorkflow} (which reads state
 * to answer queries), this workflow handles peer-to-peer sync calls from other ledgers' CLPR
 * endpoints. It reads from the latest immutable state to construct proof-backed response payloads
 * and asynchronously submits received bundles as transactions.
 */
public interface ClprSyncWorkflow {

    /**
     * Called to handle a single CLPR sync request from a peer endpoint.
     *
     * @param requestBytes The raw protobuf bytes of the incoming {@code ClprSyncPayload}.
     * @param responseBuffer A {@link BufferedData} into which the outbound {@code ClprSyncPayload}
     *                       response bytes are written.
     */
    void handleSync(@NonNull Bytes requestBytes, @NonNull BufferedData responseBuffer);

    /**
     * Called to handle a CLPR endpoint discovery request from a peer endpoint.
     *
     * @param requestBytes The raw protobuf bytes of the incoming {@code ClprDiscoverEndpointsRequest}.
     * @param responseBuffer A {@link BufferedData} into which the outbound
     *                       {@code ClprDiscoverEndpointsResponse} bytes are written.
     */
    void handleDiscovery(@NonNull Bytes requestBytes, @NonNull BufferedData responseBuffer);

    /**
     * Opens a server-side session for one inbound {@code streamingSync} stream. Unlike {@link #handleSync} — a
     * one-shot request/response — a stream is a multi-message exchange with state that lives across messages, so the
     * transport gets a fresh session object per stream to drive rather than a method to call.
     *
     * @return a new session, scoped to a single stream
     */
    @NonNull
    ClprStreamingSyncSession openStreamingSync();

    /**
     * Same as {@link #openStreamingSync()}, but threads a correlation id through to the session so its log lines
     * can be tied back to the transport-level call that owns them. Falls back to the uncorrelated overload by
     * default, so implementations that don't care about correlation need not override this.
     *
     * @param correlationId an id identifying the owning call, or {@code null} if none is available. This id is used
     *                      on logs to tie log lines belonging to the same stream.
     * @return a new session, scoped to a single stream
     */
    @NonNull
    default ClprStreamingSyncSession openStreamingSync(@Nullable final String correlationId) {
        return openStreamingSync();
    }
}
