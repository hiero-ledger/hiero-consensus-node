// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import com.hedera.node.app.service.clpr.impl.verifier.ethereum.BundlePayload.ServiceAccountProof;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The object representation of the RLP-encoded endpoint-manifest proof that {@code verifyConfig} takes:
 * {@code [attestedHeader, signatureSlot, syncAggregate, nonSignerProofs, finalizedExecution, finalityBranch,
 * accountProof, manifestStorageProof, manifestPreimage]}. Its first seven items are those of a bundle's
 * {@link BundlePayload.MessageDelivery}; the Channel slots and the bundle content are left out, and the manifest pair is
 * required.
 *
 * @param serviceAccount       the signed header, the finalized execution block and the CLPR service account proof
 * @param manifestStorageProof the proof of the endpoint-manifest commitment slot
 * @param manifestPreimage     the protobuf-encoded {@code ClprEndpointManifest} whose keccak256 is the proven commitment
 */
record ConfigManifestProof(
        @NonNull ServiceAccountProof serviceAccount,
        @NonNull StorageProofEntry manifestStorageProof,
        @NonNull byte[] manifestPreimage) {}
