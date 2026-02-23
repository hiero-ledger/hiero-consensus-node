// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.blocks;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Factory that creates a {@link TssSignatureVerifier} bound to a specific ledger identity.
 *
 * <p>The ledger identity is the genesis address book hash produced by
 * {@code WRAPS.hashAddressBook(genesisAddressBook)}. Each verifier uses this identity
 * to validate TSS signatures anchored to that ledger's chain of trust.
 */
@FunctionalInterface
public interface TssSignatureVerifierFactory {
    /**
     * Creates a verifier for the given ledger.
     *
     * @param ledgerId the genesis address book hash identifying the ledger
     * @return a verifier that validates TSS signatures for that ledger
     */
    @NonNull
    TssSignatureVerifier forLedger(@NonNull Bytes ledgerId);
}
