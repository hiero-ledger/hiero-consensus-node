// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * The object representation of the RLP encoded bundle payload received in the bundle synchronization request.
 *
 * @param attestedHeader       the attested beacon block header. This is the consensus block header
 * @param syncAggregate        the committee's participation bits and aggregate signature.
 * @param executionStateRoot32 the 32-byte encoded root of the Merkle-Patricia Trie of the execution state.  Lives
 *                             inside the {@code body_root} in the attested beacon block header.
 * @param executionBranch      is an SSZ Merkle proof — an array of 32-byte sibling hashes that proves
 *                             {@code executionStateRoot32} is a leaf inside the beacon block's body.
 * @param nextCommittee        the rotation proof's next committee, or {@code null} if none is present
 * @param nextCommitteeBranch  is the SSZ Merkle proof for the sync committee rotation — it's optional (nullable), only
 *                             present when the bundle is rotating to a new sync committee period. It's rooted in the
 *                             beacon state root (not body_root), because next_sync_committee is a field of BeaconState,
 *                             not BeaconBlockBody
 * @param accountProof         the Merkle-Patricia Trie account proof. It's the set of RLP-encoded trie nodes that prove
 *                             a specific contract account exists in the world state trie. It's rooted at
 *                             executionStateRoot32 — the execution-layer state root.
 * @param storageProof         storageProof is a list of StorageProofEntry, each being [slot key, MPT proof nodes] — one
 *                             entry per EVM storage slot being proven. Proves that fields of the bundle exist at the
 *                             CLPR service smart contract storage ({@code provenAccount.storageRoot32()}.
 *                             <p>
 *                             This is the contract's storage trie — a separate MPT per contract, distinct from the
 *                             world state trie. Each contract has its own storage trie whose root is committed inside
 *                             its account entry in the world state. So the storage proof never touches
 *                             executionStateRoot32 directly; it's one level down.
 * @param bundleContent        protobuf encoded {@code ClprBundleContent} object.
 * @param manifestBytes        the protobuf-encoded {@code ClprEndpointManifest} preimage the bundle advances to, or
 *                             {@code null} when the bundle carries no manifest advance. When present, its keccak256
 *                             commitment is proven at the manifest commitment storage slot via {@code manifestStorageProof}.
 * @param manifestStorageProof the {@code eth_getProof}-style MPT nodes proving the manifest commitment slot against the
 *                             CLPR service contract's {@code storageRoot32}, or {@code null} when no manifest advance is
 *                             present. Present iff {@code manifestBytes} is present.
 */
record BundlePayload(
        @NonNull BeaconHeader attestedHeader,
        @NonNull SyncAggregate syncAggregate,
        @NonNull byte[] executionStateRoot32,
        @NonNull byte[][] executionBranch,
        @Nullable SyncCommittee nextCommittee,
        @Nullable byte[][] nextCommitteeBranch,
        @NonNull byte[][] accountProof,
        @NonNull List<StorageProofEntry> storageProof,
        @NonNull byte[] bundleContent,
        @Nullable byte[] manifestBytes,
        @Nullable StorageProofEntry manifestStorageProof) {}
