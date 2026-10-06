// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The two fields of an EVM state-trie account that proof verification needs: the storage root (to
 * anchor storage proofs) and the code hash (to optionally pin the contract bytecode).
 */
public record EvmAccount(
        @NonNull byte[] storageRoot32, @NonNull byte[] codeHash32) {

    public EvmAccount {
        storageRoot32 = storageRoot32.clone();
        codeHash32 = codeHash32.clone();
    }

    @Override
    public byte[] storageRoot32() {
        return storageRoot32.clone();
    }

    @Override
    public byte[] codeHash32() {
        return codeHash32.clone();
    }
}
