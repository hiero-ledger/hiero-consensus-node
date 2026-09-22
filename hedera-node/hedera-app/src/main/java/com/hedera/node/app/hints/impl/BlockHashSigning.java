// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ConcurrentMap;

/**
 * The mechanism-independent orchestration of an in-progress block hash signing.
 */
public sealed interface BlockHashSigning permits HintsContext.Signing, RsaContext.Signing {
    /**
     * Cancels and removes all of the given signing attempts.
     *
     * @param signings the signing attempts to cancel and remove
     */
    static void cancelAndRemoveAll(@NonNull final ConcurrentMap<Bytes, BlockHashSigning> signings) {
        requireNonNull(signings);
        signings.forEach((blockHash, signing) -> {
            if (signings.remove(blockHash, signing)) {
                signing.cancel();
            }
        });
    }

    /**
     * Incorporates a node's pre-validated signature contribution into this signing attempt.
     *
     * @param context any additional signing context required to incorporate the signature
     * @param nodeId the node ID
     * @param signature the pre-validated signature contribution
     */
    void incorporateValid(@NonNull Bytes context, long nodeId, @NonNull Bytes signature);

    /**
     * Cancels this signing attempt.
     */
    void cancel();
}
