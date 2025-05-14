// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.proof;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.PublicKey;
import org.hiero.base.crypto.Signature;

/**
 * A method that verifies a signature.
 */
@FunctionalInterface
public interface SignatureVerifier {

    /**
     * Check if a signature is valid.
     *
     * @param signature the signature to check
     * @param bytes     the data that was signed
     * @param publicKey the public key corresponding to the private key that signed the data
     * @return true if the signature is valid, false otherwise
     */
    boolean verifySignature(@NonNull Signature signature, @NonNull byte[] bytes, @NonNull PublicKey publicKey);
}
