// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents a pair of private and public keys.
 * @param privateKey the private key
 * @param publicKey the public key
 */
public record TssKeyPair(@NonNull Bytes privateKey, @NonNull Bytes publicKey) {
    public TssKeyPair {
        requireNonNull(privateKey);
        requireNonNull(publicKey);
    }
}
