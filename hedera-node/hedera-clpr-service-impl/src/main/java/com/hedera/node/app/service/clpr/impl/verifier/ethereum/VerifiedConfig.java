// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Result of a successful {@link EthereumSyncCommitteeProofVerifier#verifyConfigPayload} call.
 *
 * @param ledgerConfiguration the advertised {@link ClprLedgerConfiguration} with
 *     {@code initialTrustAnchor} / {@code initialTrustAnchorId} derived from the self-described committee
 * @param slot the beacon slot the initial committee is current for; its sync-committee period is the
 *     initial trust anchor's period
 * @param endpointManifest the peer's endpoint manifest proven by the endpoint-manifest proof, or the uninitialized
 *     manifest (version 0, no endpoints, bound to the configured service address) when no proof was given
 */
public record VerifiedConfig(
        @NonNull ClprLedgerConfiguration ledgerConfiguration,
        long slot,
        @NonNull ClprEndpointManifest endpointManifest) {
    public VerifiedConfig {
        Objects.requireNonNull(ledgerConfiguration, "ledgerConfiguration");
        Objects.requireNonNull(endpointManifest, "endpointManifest");
    }
}
