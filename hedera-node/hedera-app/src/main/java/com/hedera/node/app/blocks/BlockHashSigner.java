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
}
