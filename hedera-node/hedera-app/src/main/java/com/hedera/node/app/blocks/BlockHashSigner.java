// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;

/**
 * Provides the ability to asynchronously sign a block hash.
 */
public interface BlockHashSigner {
    /**
     * Whether the signer is ready.
     */
    boolean isReady();

    /**
     * Returns a future that resolves to the signature of the given block hash.
     *
     * @param blockHash the block hash
     * @return the future
     */
    CompletableFuture<Bytes> signFuture(@NonNull Bytes blockHash);

    /**
     * Returns the scheme ids this signer is currently using at a point in the block stream
     * where a proof is needed.
     */
    long activeSchemeId();

    /**
     * Returns the verification key for the active signing scheme.
     */
    Bytes activeVerificationKey();
}
