// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import com.hedera.hapi.node.state.history.ChainOfTrustProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Provides the ability to asynchronously sign a block hash.
 */
public interface BlockHashSigner {
    /**
     * The type of block hash signature being requested.
     */
    enum Request {
        /**
         * A succinct signature over the block hash.
         */
        SUCCINCT_SIGNATURE,
        /**
         * A list of partial signatures over the block hash.
         */
        LIST_OF_PARTIAL_SIGNATURES
    }

    /**
     * The result of attempting to sign a block hash.
     * @param verificationKey if not null, the verification key being used for the attempt
     * @param chainOfTrustProof if not null, the chain of trust proof for the verification key being used
     * @param signatureFuture a future that resolves to the signature
     * @param submissionFuture a future that resolves when the node has submitted its partial signature
     */
    record Attempt(
            @Nullable Bytes verificationKey,
            @Nullable ChainOfTrustProof chainOfTrustProof,
            CompletableFuture<Bytes> signatureFuture,
            CompletableFuture<Void> submissionFuture) {
        public Attempt(
                @Nullable final Bytes verificationKey,
                @Nullable final ChainOfTrustProof chainOfTrustProof,
                @NonNull final CompletableFuture<Bytes> signatureFuture) {
            this(verificationKey, chainOfTrustProof, signatureFuture, completedFuture(null));
        }

        public Attempt {
            requireNonNull(signatureFuture);
            requireNonNull(submissionFuture);
        }
    }

    /**
     * Whether the signer is ready.
     */
    boolean isReady();

    /**
     * Returns an attempt to sign the given block hash.
     * @param blockHash the block hash
     * @param request the type of signature being requested
     * @return the signing attempt
     */
    Attempt sign(@NonNull Bytes blockHash, @NonNull Request request);
}
