// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Verifies BLS12-381 sync-committee aggregate signatures as used by the Ethereum beacon chain.
 *
 * <p>Implementations must follow the IETF BLS signature scheme with the Ethereum ciphersuite
 * {@code BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_POP_} in the "minimal-pubkey-size" variant: public keys are G1
 * points, signatures are G2 points, and messages are hashed to G2 per RFC 9380 with the ciphersuite's
 * domain-separation tag. Every point uses the EIP-2537 uncompressed encoding.
 *
 * <p>The production implementation is {@link NativeBlsSignatureVerifier}; this interface lets unit tests of the
 * verifier check its other steps without real signatures.
 */
public interface BlsSignatureVerifier {

    /**
     * Verifies a sync-committee aggregate signature given the whole committee's aggregate key and the keys of the
     * members that did not sign. The participants' aggregate key is {@code aggregatePubkey − Σ nonSignerPubkeys}, and
     * the signature must verify against it ({@code FastAggregateVerify} over the participants). Subtracting the
     * non-signers is cheaper than aggregating the participants, which are at least 2/3 of the committee.
     *
     * <p>Implementations MUST reject (return {@code false} or throw) malformed or non-subgroup points, the identity
     * point as any key or as the participants' aggregate key; they must never treat such inputs as valid.
     *
     * @param aggregatePubkey  the aggregate of all committee members' keys, a 128-byte EIP-2537 G1 point
     * @param nonSignerPubkeys the keys of the members that did not sign, each a 128-byte EIP-2537 G1 point; empty at
     *                         full participation
     * @param message          the signed message (for sync committees, the 32-byte signing root)
     * @param signature        the aggregate signature, a 256-byte EIP-2537 G2 point
     * @return true if the signature is valid for the participants' aggregate key
     */
    boolean verifySyncAggregate(
            @NonNull byte[] aggregatePubkey,
            @NonNull List<byte[]> nonSignerPubkeys,
            @NonNull byte[] message,
            @NonNull byte[] signature);

    /**
     * Checks that every key is a valid public key: a G1 point on the curve, in the prime-order subgroup and not the
     * identity (the {@code KeyValidate} procedure of the BLS signature spec).
     *
     * @param pubkeys the 128-byte EIP-2537 G1 keys; not empty
     * @return true if every key is valid
     */
    boolean validatePubkeys(@NonNull List<byte[]> pubkeys);
}
