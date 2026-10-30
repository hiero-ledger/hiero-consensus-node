// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * Result of a successful {@link EthereumSyncCommitteeProofVerifier#verifyBundle} call.
 *
 * @param beaconBlockRoot32 the SSZ hash_tree_root of the attested beacon block header
 * @param bundleContentBytes the protobuf-serialized {@code ClprBundleContent} bytes
 *     pulled from the RLP item of the payload
 * @param queueMetadata the {@code ClprQueueMetadata} fields decoded from the five Merkle-proven
 *     storage slots, or the all-zero absent sentinel ({@code nextMessageId == 0}) when the bundle carried
 *     no queue storage proof — a bundle that advances only the endpoint manifest (spec §8.1.4)
 * @param nextTrustAnchor the successor trust anchor proven by the payload's next-sync-committee
 *     branch, or {@code null} if the payload carried no rotation proof
 * @param nextTrustAnchorId defines uniquely the trust anchor. For Ethereum, this is the slot number.
 * @param newEndpointManifest the endpoint manifest the bundle advances to — verified against the proven
 *     account storage root — or {@code null} when the bundle carried no manifest advance
 */
public record VerifiedBundle(
        @NonNull byte[] beaconBlockRoot32,
        @NonNull byte[] bundleContentBytes,
        @NonNull QueueMetadata queueMetadata,
        @Nullable byte[] nextTrustAnchor,
        @Nullable byte[] nextTrustAnchorId,
        @Nullable ClprEndpointManifest newEndpointManifest) {
    public VerifiedBundle {
        beaconBlockRoot32 = checkedCopy(beaconBlockRoot32, 32, "beaconBlockRoot32");
        bundleContentBytes =
                Objects.requireNonNull(bundleContentBytes, "bundleContentBytes").clone();
        Objects.requireNonNull(queueMetadata, "queueMetadata");
        nextTrustAnchor = nextTrustAnchor == null ? null : nextTrustAnchor.clone();
        nextTrustAnchorId = nextTrustAnchorId == null ? null : nextTrustAnchorId.clone();
    }

    @Override
    public byte[] beaconBlockRoot32() {
        return beaconBlockRoot32.clone();
    }

    @Override
    public byte[] bundleContentBytes() {
        return bundleContentBytes.clone();
    }

    @Override
    public @Nullable byte[] nextTrustAnchor() {
        return nextTrustAnchor == null ? null : nextTrustAnchor.clone();
    }

    @Override
    public @Nullable byte[] nextTrustAnchorId() {
        return nextTrustAnchorId == null ? null : nextTrustAnchorId.clone();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private byte[] beaconBlockRoot32;
        private byte[] bundleContentBytes;
        private QueueMetadata queueMetadata;
        private byte[] nextTrustAnchor;
        private byte[] nextTrustAnchorId;
        private ClprEndpointManifest newEndpointManifest;

        public Builder beaconBlockRoot32(@NonNull final byte[] beaconBlockRoot32) {
            this.beaconBlockRoot32 = beaconBlockRoot32;
            return this;
        }

        public Builder bundleContentBytes(@NonNull final byte[] bundleContentBytes) {
            this.bundleContentBytes = bundleContentBytes;
            return this;
        }

        public Builder queueMetadata(@NonNull final QueueMetadata queueMetadata) {
            this.queueMetadata = queueMetadata;
            return this;
        }

        public Builder nextTrustAnchor(@Nullable final byte[] nextTrustAnchor) {
            this.nextTrustAnchor = nextTrustAnchor;
            return this;
        }

        public Builder nextTrustAnchorId(@Nullable final byte[] nextTrustAnchorId) {
            this.nextTrustAnchorId = nextTrustAnchorId;
            return this;
        }

        public Builder newEndpointManifest(@Nullable final ClprEndpointManifest newEndpointManifest) {
            this.newEndpointManifest = newEndpointManifest;
            return this;
        }

        public VerifiedBundle build() {
            return new VerifiedBundle(
                    beaconBlockRoot32,
                    bundleContentBytes,
                    queueMetadata,
                    nextTrustAnchor,
                    nextTrustAnchorId,
                    newEndpointManifest);
        }
    }
}
