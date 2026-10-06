// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Verifies BLS12-381 aggregate signatures as used by the Ethereum beacon chain.
 *
 * <p>Implementations must follow the IETF BLS signature scheme with the Ethereum ciphersuite
 * {@code BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_POP_} in the "minimal-pubkey-size" variant:
 * public keys are 48-byte compressed G1 points, signatures are 96-byte compressed G2 points,
 * and messages are hashed to G2 per RFC 9380 with the ciphersuite's domain-separation tag.
 *
 * <p>This interface exists so the consensus-critical pairing/hash-to-curve code can be supplied
 * by an audited native library (e.g. supranational/blst) without this module depending on it
 * directly; tests use a fake.
 */
public interface BlsSignatureVerifier {

    /**
     * Verifies an aggregate signature produced by every listed key signing the same message
     * (the {@code FastAggregateVerify} operation of the BLS signature spec).
     *
     * <p>Implementations MUST reject (return {@code false} or throw) malformed or non-subgroup
     * points and the identity public key; they must never treat such inputs as valid.
     *
     * @param publicKeys the signers' public keys, each a 48-byte compressed G1 point; non-empty
     * @param message the signed message (for sync committees, the 32-byte signing root)
     * @param signature the 96-byte compressed G2 aggregate signature
     * @return true if the signature is valid for the aggregate of {@code publicKeys}
     */
    boolean fastAggregateVerify(@NonNull List<byte[]> publicKeys, @NonNull byte[] message, @NonNull byte[] signature);
}
