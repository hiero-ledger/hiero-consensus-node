// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * The object representation of the RLP encoded bundle payload {@code [ [rotation...], delivery ]}: zero or more
 * sync-committee rotations, applied in order, then one message delivery signed by the committee trusted after them.
 *
 * @param rotations the committee rotations, each advancing the trusted committee by one period; empty in steady state
 * @param delivery  the message delivery
 */
record BundlePayload(
        @NonNull List<CommitteeRotation> rotations, @NonNull MessageDelivery delivery) {

    /**
     * A beacon block header together with the sync-committee signature over it.
     *
     * @param attestedHeader  the signed beacon block header
     * @param syncAggregate   the participation bits and the aggregate signature
     * @param nonSignerProofs one proof per clear participation bit, in ascending committee index order
     */
    record SignedHeader(
            @NonNull BeaconHeader attestedHeader,
            @NonNull SyncAggregate syncAggregate,
            @NonNull List<NonSignerProof> nonSignerProofs) {}

    /**
     * The key of a committee member that did not sign, with its SSZ inclusion branch under the committee's
     * {@code pubkeysRoot}. The member's committee index is not carried: it is the position of the matching clear bit.
     *
     * @param pubkey128 the member's key, a 128-byte EIP-2537 uncompressed G1 point
     * @param branch    the sibling hashes from the key's leaf up to {@code pubkeysRoot}, bottom-up
     */
    record NonSignerProof(
            @NonNull byte[] pubkey128, @NonNull byte[][] branch) {}

    /**
     * One sync-committee transition: a header signed by the trusted committee whose beacon state proves both a
     * finalized header in the same period and the committee of the next period.
     *
     * <p>A rotation carries no signature slot: a beacon node attaches the next committee to an update only when the
     * signature slot is in the attested header's period, so the signing committee is the one of that period.
     *
     * @param signedHeader        the attested header and its signature
     * @param finalizedHeader     the finalized beacon header; only its root and slot are used
     * @param finalityBranch      the branch of the finalized header's root in the attested header's {@code stateRoot}
     * @param nextCommittee       the committee of the next period
     * @param nextCommitteeBranch the branch of the next committee's root in the attested header's {@code stateRoot}
     */
    record CommitteeRotation(
            @NonNull SignedHeader signedHeader,
            @NonNull BeaconHeader finalizedHeader,
            @NonNull byte[][] finalityBranch,
            @NonNull NextCommittee nextCommittee,
            @NonNull byte[][] nextCommitteeBranch) {}

    /**
     * The next sync committee as it travels on the wire. Its member keys are not carried.
     *
     * @param pubkeysRoot32      the SSZ root of the committee's {@code pubkeys} vector
     * @param aggregatePubkey128 the committee's aggregate key, a 128-byte EIP-2537 uncompressed G1 point
     */
    record NextCommittee(
            @NonNull byte[] pubkeysRoot32, @NonNull byte[] aggregatePubkey128) {}

    /**
     * The finalized execution block (Gloas, EIP-7732): the beacon body commits to the execution block hash, not to
     * the execution state root, so the state root is read from the EVM block header whose keccak is that hash.
     *
     * @param finalizedHeader the finalized beacon header
     * @param executionBranch the branch of the execution block hash in {@code finalizedHeader.bodyRoot}
     * @param blockHeaderRlp  the RLP-encoded EVM block header
     */
    record FinalizedExecution(
            @NonNull BeaconHeader finalizedHeader,
            @NonNull byte[][] executionBranch,
            @NonNull byte[] blockHeaderRlp) {}

    /**
     * The message/state payload, verified against the committee trusted after every rotation.
     *
     * @param signedHeader         the attested header and its signature
     * @param signatureSlot        the slot at which the committee signed the attested header; its period selects the
     *                             signing committee
     * @param finalizedExecution   the finalized execution block that anchors the account and storage proofs
     * @param finalityBranch       the branch of the finalized header's root in the attested header's
     *                             {@code stateRoot}
     * @param accountProof         the Merkle-Patricia proof of the CLPR service account under the execution state root
     * @param storageProof         the Channel slot proofs: 5 entries, 6 when the bundle carries messages, or none for
     *                             a manifest-only bundle (spec §8.1.4)
     * @param bundleContent        the protobuf-encoded {@code ClprBundleContent}
     * @param manifestStorageProof the proof of the endpoint-manifest commitment slot, or {@code null} when the bundle
     *                             carries no manifest update; present iff {@code manifestPreimage} is present
     * @param manifestPreimage     the protobuf-encoded {@code ClprEndpointManifest} whose keccak256 is the proven
     *                             commitment, or {@code null} when the bundle carries no manifest update
     */
    record MessageDelivery(
            @NonNull SignedHeader signedHeader,
            long signatureSlot,
            @NonNull FinalizedExecution finalizedExecution,
            @NonNull byte[][] finalityBranch,
            @NonNull byte[][] accountProof,
            @NonNull List<StorageProofEntry> storageProof,
            @NonNull byte[] bundleContent,
            @Nullable StorageProofEntry manifestStorageProof,
            @Nullable byte[] manifestPreimage) {}
}
