// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Interface for synchronizing two connected ledgers by exchanging message bundles.
 */
public interface ClprSynchronizer {
    /**
     * Initiate an outbound sync against a peer for the given Channel.
     *
     * @param channel the Channel metadata.
     * @param providedEndpoints dial targets chosen by the caller. Under
     *     {@code clpr.endpointManifestEnabled=true} the caller reads these from
     *     {@link ClprChannel#endpointManifest()} (spec §4.7 — the authoritative cached
     *     peer manifest); under flag-off legacy the caller seeds them from
     *     {@code ClprLedgerConfiguration.endpoints}. The synchronizer treats the list
     *     opaquely — an empty list means "nothing to dial" and the tick is skipped.
     * @param localEndpointManifestVersion the current local {@code ClprEndpointManifest.version()}.
     * @param peerObservedManifestVersion the peer's most recently reported cache of <em>this</em>
     *     ledger's manifest version (node-local, in-memory; absent ⇒ 0). Compared against
     *     {@code localEndpointManifestVersion} to decide whether to embed a proof of our manifest in
     *     the outbound bundle so the peer's cache is refreshed via Step 1b (see #335). Peer stale ⇒
     *     {@code peerObservedManifestVersion < localEndpointManifestVersion} ⇒ include. This is the
     *     correct axis — it is NOT {@code channel.endpointManifestVersion()}, which tracks the
     *     orthogonal "our cache of the peer's manifest".
     */
    void synchronize(
            @NonNull ClprChannel channel,
            @NonNull List<ClprEndpoint> providedEndpoints,
            long localEndpointManifestVersion,
            long peerObservedManifestVersion);
}
