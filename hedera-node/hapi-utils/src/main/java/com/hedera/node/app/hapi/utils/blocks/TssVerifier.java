// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Pluggable abstraction over a peer-ledger TSS aggregate signature check.
 *
 * <p>Callers (typically CLPR verifier paths that have already parsed a {@code StateProof} and
 * computed its block root hash via {@link StateProofVerifier#computeBlockRootHash}) invoke
 * {@link #verifyTss(Bytes, Bytes, Bytes)} to ask whether {@code signature} is a valid TSS
 * aggregate signature, attributed to the peer ledger identified by {@code ledgerId}, over
 * {@code blockRootHash}.
 *
 * <p>The implementation chosen at runtime determines whether this is a real cryptographic check
 * (e.g. delegating to {@code HintsLibrary.verifyAggregate(...)} with a per-ledger verification
 * key) or a mock byte-equality check used for early integration testing.
 */
public interface TssVerifier {
    /**
     * Returns {@code true} iff {@code signature} is a valid TSS aggregate signature, produced by
     * the peer ledger identified by {@code ledgerId}, over the message {@code blockRootHash}.
     */
    boolean verifyTss(@NonNull Bytes ledgerId, @NonNull Bytes signature, @NonNull Bytes blockRootHash);
}
