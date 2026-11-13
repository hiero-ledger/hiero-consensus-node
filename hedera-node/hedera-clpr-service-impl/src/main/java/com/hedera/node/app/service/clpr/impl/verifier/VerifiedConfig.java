// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The verified peer state returned from {@link ClprVerifier#verifyConfig}. Bundles the
 * verified {@link ClprLedgerConfiguration} and the initial peer {@link ClprEndpointManifest}
 * per spec §4.8 - both are attested by the peer's initial trust anchor and stored on the
 * newly-registered Channel at {@code completeChannel} time.
 */
public record VerifiedConfig(
        @NonNull ClprLedgerConfiguration config, @NonNull ClprEndpointManifest manifest) {}
