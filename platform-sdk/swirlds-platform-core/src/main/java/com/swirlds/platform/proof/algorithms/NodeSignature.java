// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.proof.algorithms;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.model.node.NodeId;

/**
 * A signature and the node ID of the signer.
 *
 * @param nodeId    the node ID of the signer
 * @param signature the signature
 */
public record NodeSignature(@NonNull NodeId nodeId, @NonNull Signature signature) implements Comparable<NodeSignature> {

    @Override
    public int compareTo(@NonNull final NodeSignature o) {
        return nodeId.compareTo(o.nodeId);
    }
}
