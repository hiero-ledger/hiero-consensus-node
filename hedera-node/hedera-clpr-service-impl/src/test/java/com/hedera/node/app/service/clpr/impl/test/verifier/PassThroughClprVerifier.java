// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.verifier;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_VERIFIER_CONFIG_FAILED;

import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.VerifiedConfig;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A pass-through verifier that accepts all proofs without cryptographic verification.
 *
 * <p>Deserializes the proof bytes directly as protobuf-encoded output. For use in tests only.
 * Tests are expected to pass a {@code ClprLedgerConfiguration} whose
 * {@code initial_trust_anchor} is already set to the value they want
 * {@code Channel.trust_anchor} to be seeded with — matching the contract of the real
 * verifier, which reads the anchor from the proven config rather than from a separate argument.
 */
public class PassThroughClprVerifier implements ClprVerifier {

    @Override
    @NonNull
    public VerifiedConfig verifyConfig(
            @NonNull final Bytes configProofBytes,
            @NonNull final Bytes channelId,
            @NonNull final Bytes endpointManifestProofBytes,
            @NonNull final HandleContext context)
            throws HandleException {
        try {
            final var config = ClprLedgerConfiguration.PROTOBUF.parse(configProofBytes.toReadableSequentialData());
            // Test double: if the caller supplied non-empty manifest proof bytes, treat them as
            // a serialized ClprEndpointManifest; otherwise return an empty manifest with the
            // config's service_address so downstream code has a well-formed value.
            final ClprEndpointManifest manifest;
            if (endpointManifestProofBytes.length() > 0) {
                manifest = ClprEndpointManifest.PROTOBUF.parse(endpointManifestProofBytes.toReadableSequentialData());
            } else {
                manifest = ClprEndpointManifest.newBuilder()
                        .version(1L)
                        .serviceAddress(config.serviceAddress())
                        .build();
            }
            return new VerifiedConfig(config, manifest);
        } catch (final Exception e) {
            throw new HandleException(CLPR_VERIFIER_CONFIG_FAILED);
        }
    }

    @Override
    @NonNull
    public ClprBundleContent verifyBundle(
            @NonNull final Bytes bundlePayload,
            @NonNull final Bytes trustAnchor,
            @NonNull final Bytes channelContext,
            @NonNull final HandleContext context)
            throws HandleException {
        try {
            return ClprBundleContent.PROTOBUF.parse(bundlePayload.toReadableSequentialData());
        } catch (final Exception e) {
            throw new HandleException(CLPR_BUNDLE_VERIFICATION_FAILED);
        }
    }
}
