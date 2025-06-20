// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.proof.algorithms;

import static org.hiero.base.crypto.DigestType.SHA_384;

import com.swirlds.platform.proof.SignatureVerifier;
import com.swirlds.platform.proof.tree.StateProofInternalNode;
import com.swirlds.platform.proof.tree.StateProofNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.Address;
import org.hiero.consensus.model.roster.AddressBook;

/**
 * Utility methods for validating state proofs.
 */
public final class StateProofUtils {

    private StateProofUtils() {}

    /**
     * Compute the weight of all signatures in a state proof that are valid.
     *
     * @param addressBook       the address book to use for verification, should be a trusted address book
     * @param signatures        the list of signatures to check
     * @param signatureVerifier a method that checks if a signature is valid
     * @param hashBytes         the bytes that were signed (i.e. the hash at the root of the state the proof was derived
     *                          from)
     * @return the total weight of all valid signatures
     */
    public static long computeValidSignatureWeight(
            @NonNull final AddressBook addressBook,
            @NonNull final List<NodeSignature> signatures,
            @NonNull final SignatureVerifier signatureVerifier,
            @NonNull final byte[] hashBytes) {

        final Set<NodeId> signingNodes = new HashSet<>();

        long validWeight = 0;
        for (final NodeSignature nodeSignature : signatures) {
            if (!signingNodes.add(nodeSignature.nodeId())) {
                // Signature is not unique.
                continue;
            }

            if (!addressBook.contains(nodeSignature.nodeId())) {
                // Signature is not in the address book.
                continue;
            }
            final Address address = addressBook.getAddress(nodeSignature.nodeId());
            if (address.getWeight() == 0) {
                // Don't bother validating the signature of a zero weight node.
                continue;
            }

            if (address.getSigPublicKey() == null) {
                // Signature cannot be validated.
                continue;
            }

            if (!signatureVerifier.verifySignature(nodeSignature.signature(), hashBytes, address.getSigPublicKey())) {
                // Signature is invalid.
                continue;
            }
            validWeight += address.getWeight();
        }

        return validWeight;
    }

    /**
     * Compute the root hash of a state proof tree.
     *
     * @param root the root of the state proof tree
     * @return the computed root hash
     */
    public static byte[] computeStateProofTreeHash(@NonNull final StateProofNode root) {

        final MessageDigest digest = SHA_384.buildDigest();

        // Walk the state proof tree post-ordered depth first order.
        final Deque<StateProofNode> stack = new LinkedList<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            final StateProofNode node = stack.pop();

            if (node instanceof final StateProofInternalNode internal) {
                if (internal.hasBeenVisited()) {
                    // The second time we visit an internal node.
                    // We will have already visited all descendants of this node.
                    node.computeHashableBytes(digest);
                } else {
                    // The first time we visit an internal node.
                    // We need to visit its descendants before we can compute its hashable bytes.
                    stack.push(node);
                    for (int childIndex = internal.getChildren().size() - 1; childIndex >= 0; childIndex--) {
                        stack.push(internal.getChildren().get(childIndex));
                    }
                    internal.markAsVisited();
                }
            } else {
                node.computeHashableBytes(digest);
            }
        }

        return root.getHashableBytes();
    }
}
