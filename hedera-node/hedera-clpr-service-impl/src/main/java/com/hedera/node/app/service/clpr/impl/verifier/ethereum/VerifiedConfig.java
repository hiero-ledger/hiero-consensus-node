// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Result of a successful {@link EthereumSyncCommitteeProofVerifier#verifyConfigPayload} call.
 *
 * @param ledgerConfiguration the advertised {@link ClprLedgerConfiguration} with
 *     {@code initialTrustAnchor} / {@code initialTrustAnchorId} derived from the self-described committee
 * @param slot the beacon slot the initial committee is current for; its period ({@code slot / 8192})
 *     will pin the initial trust anchor when sync-committee rotation lands
 */
public record VerifiedConfig(@NonNull ClprLedgerConfiguration ledgerConfiguration, long slot) {
    public VerifiedConfig {
        Objects.requireNonNull(ledgerConfiguration, "ledgerConfiguration");
    }
}
