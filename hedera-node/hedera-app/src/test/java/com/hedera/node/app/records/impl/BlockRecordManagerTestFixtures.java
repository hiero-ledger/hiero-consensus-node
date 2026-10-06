// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CompletableFuture;

public final class BlockRecordManagerTestFixtures {
    public static final BlockHashSigner NO_OP_BLOCK_HASH_SIGNER = new BlockHashSigner() {
        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public Attempt sign(@NonNull final Bytes blockHash, @NonNull final Request request) {
            requireNonNull(blockHash);
            requireNonNull(request);
            return new Attempt(null, null, new CompletableFuture<>());
        }
    };

    private BlockRecordManagerTestFixtures() {}
}
