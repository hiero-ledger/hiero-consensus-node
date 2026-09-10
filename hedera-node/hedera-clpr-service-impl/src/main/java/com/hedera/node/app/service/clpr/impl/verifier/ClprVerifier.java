// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Verifies cross-ledger proofs and extracts the attested data.
 *
 * <p>Implementations range from a pass-through (for testing) to hard-coded fast-path verifiers
 * for known chains, to full cryptographic verification via EVM contract dispatch (CLPR-5.2/5.3).
 */
public interface ClprVerifier {

    /**
     * Verifies a configuration proof plus an endpoint-manifest proof, returning the attested
     * peer {@link ClprLedgerConfiguration} and initial {@link ClprEndpointManifest} bundled in a
     * {@link VerifiedConfig} (spec §4.8).
     *
     * <p>The trust anchor used to verify both proofs is the {@code initial_trust_anchor}
     * carried inside the proven configuration - the source ledger populates that field with
     * its own ledger_id at {@code ClprUpdateLedgerConfiguration} time, so the proof is
     * self-describing. Implementations MUST reject:
     * <ul>
     *   <li>A proof whose inner config has an empty {@code initial_trust_anchor} (unless the
     *       verifier has no rotating-authority concept).</li>
     *   <li>A manifest whose {@code service_address} does not match the config's
     *       {@code service_address}.</li>
     *   <li>A manifest with {@code version == 0}.</li>
     * </ul>
     * Implementations MUST NOT reject a manifest solely because its {@code endpoints} list is
     * empty (spec §2.4.1 - empty manifest at version >= 1 is valid).
     *
     * @param configProofBytes opaque proof bytes attesting the peer's configuration
     * @param channelId 32-byte Channel identifier; echoed by the verifier into the
     *     returned {@code ChannelContext} on platforms that carry one. MUST be
     *     {@link Bytes#EMPTY} when {@code clpr.endpointManifestEnabled=false} (the legacy
     *     verifier ABI does not take a channel id).
     * @param endpointManifestProofBytes opaque proof bytes attesting the peer's endpoint
     *     manifest; MUST be verifiable against the same initial trust anchor as
     *     {@code configProofBytes}. MUST be {@link Bytes#EMPTY} when
     *     {@code clpr.endpointManifestEnabled=false} — implementations select between the
     *     legacy 1-arg ABI and the manifest-aware 3-arg ABI based on the flag and ignore
     *     this argument on the legacy path
     * @param context the current handle context, available for EVM dispatch if needed
     * @return the verified config plus the initial endpoint manifest
     * @throws HandleException if any proof verification or invariant check fails
     */
    @NonNull
    VerifiedConfig verifyConfig(
            @NonNull Bytes configProofBytes,
            @NonNull Bytes channelId,
            @NonNull Bytes endpointManifestProofBytes,
            @NonNull HandleContext context)
            throws HandleException;

    /**
     * Verifies a bundle proof against the Channel's current trust anchor and returns the
     * attested queue metadata, ordered messages, and optionally a successor trust anchor.
     *
     * @param bundlePayload opaque proof bytes from the peer
     * @param trustAnchor the Channel's current signing-authority material; empty bytes for
     *     verifiers whose proof system has no rotating-authority concept
     * @param channelContext opaque channel identity ({@code abi.encodePacked(channelId,
     *     serviceAddress)}); threaded so manifest-aware verifiers can bind the proven Channel
     *     storage slots by exact key rather than by heuristic
     * @param context the current handle context, available for EVM dispatch if needed
     * @return the verified bundle content (metadata + messages + optional new trust anchor)
     * @throws HandleException if verification fails
     */
    @NonNull
    ClprBundleContent verifyBundle(
            @NonNull Bytes bundlePayload,
            @NonNull Bytes trustAnchor,
            @NonNull Bytes channelContext,
            @NonNull HandleContext context)
            throws HandleException;
}
