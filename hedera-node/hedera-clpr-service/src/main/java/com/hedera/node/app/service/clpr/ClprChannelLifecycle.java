// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * SPI hook invoked by CLPR transaction handlers to inform the runtime sync
 * orchestrator about Channel lifecycle transitions. Handlers should call
 * these after a successful state write; if the surrounding transaction is
 * later rolled back, the orchestrator will self-correct on its next tick by
 * detecting the absent state record.
 */
public interface ClprChannelLifecycle {

    /**
     * A lifecycle for contexts with no sync orchestrator to notify, such as standalone transaction dispatch.
     */
    ClprChannelLifecycle NO_OP = new ClprChannelLifecycle() {
        @Override
        public void onChannelActivated(@NonNull final Bytes channelId) {
            // Nothing to notify
        }

        @Override
        public void onChannelClosed(@NonNull final Bytes channelId) {
            // Nothing to notify
        }

        @Override
        public void recordPeerObservedManifestVersion(@NonNull final Bytes channelId, final long peerObservedVersion) {
            // Nothing to notify
        }
    };

    /**
     * Notify the orchestrator that a Channel has just become active and
     * should be included in the outbound sync loop.
     *
     * @param channelId the 32-byte Channel ID
     */
    void onChannelActivated(@NonNull Bytes channelId);

    /**
     * Notify the orchestrator that a Channel has been closed and should
     * be removed from the outbound sync loop.
     *
     * @param channelId the 32-byte Channel ID
     */
    void onChannelClosed(@NonNull Bytes channelId);

    /**
     * Record the peer's most recently reported cache of <em>this</em> ledger's endpoint-manifest
     * version, extracted from an inbound bundle's {@code ClprQueueMetadata.endpoint_manifest_version}
     * (spec §4.5). The runtime sync orchestrator compares this against the local
     * {@code ClprEndpointManifest.version()} on the next outbound cycle: when the peer is behind, it
     * embeds a proof of our manifest so the peer's {@code verifyBundle} can refresh its cache via
     * Step 1b (see #335).
     *
     * <p>This is a node-local, in-memory signal — deliberately <b>not</b> consensus state. It is
     * consumed only by the (non-consensus) orchestrator and self-heals from the live metadata
     * stream: every inbound bundle overwrites it, so once the peer reports a caught-up version the
     * comparison naturally stops firing, and a restart simply rebuilds it from the next inbound
     * bundle.
     *
     * @param channelId the 32-byte Channel ID
     * @param peerObservedVersion the manifest version the peer reported holding for this ledger
     */
    void recordPeerObservedManifestVersion(@NonNull Bytes channelId, long peerObservedVersion);
}
