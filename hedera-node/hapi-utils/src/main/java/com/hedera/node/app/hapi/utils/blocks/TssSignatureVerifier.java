// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Verifies a TSS signature over a block hash.
 *
 * <p>Implementations are typically created per-ledger via {@link TssSignatureVerifierFactory#forLedger(Bytes)},
 * binding the ledger identity (genesis address book hash) into each verification call.
 */
@FunctionalInterface
public interface TssSignatureVerifier {
    /**
     * Verifies that {@code signature} is a valid TSS signature over {@code blockHash}.
     *
     * @param blockHash the block root hash that was signed
     * @param signature the TSS signature bytes
     * @return {@code true} if the signature is valid for the given block hash
     */
    boolean verify(@NonNull Bytes blockHash, @NonNull Bytes signature);
}
