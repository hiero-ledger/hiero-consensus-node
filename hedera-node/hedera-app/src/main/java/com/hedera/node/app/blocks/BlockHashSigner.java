// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.ChainOfTrustProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Provides the ability to asynchronously sign a block hash.
 */
public interface BlockHashSigner {
    /**
     * The result of attempting to sign a block hash.
     * @param verificationKey if not null, the verification key being used for the attempt
     * @param chainOfTrustProof if not null, the chain of trust proof for the verification key being used
     * @param signatureFuture a future that resolves to the signature
     */
    record Attempt(
            @Nullable Bytes verificationKey,
            @Nullable ChainOfTrustProof chainOfTrustProof,
            CompletableFuture<Bytes> signatureFuture) {}

    /**
     * Whether the signer is ready.
     */
    boolean isReady();

    /**
     * Returns an attempt to sign the given block hash.
     * @param blockHash the block hash
     * @return the signing attempt
     */
    Attempt sign(@NonNull Bytes blockHash);
}
