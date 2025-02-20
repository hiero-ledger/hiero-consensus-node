// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.signatures;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Function;

/**
 * Verifies whether a {@link Key} has a valid signature for a given {@link Bytes} payload based on the cryptographic
 * signatures given in a {@link SignatureMap} and, optionally, an override strategy for verifying simple key signatures.
 * <p>
 * The cryptographic signatures in the {@link SignatureMap} may be either Ed25519 or ECDSA(secp256k1) signatures. For
 * the latter, the bytes signed are taken as the Keccak-256 (SHA-2) hash of the payload; while Ed25519 signatures are
 * of the payload itself.
 */
public interface SignatureVerifier {
    /**
     * Enumerates the types of messages we support verifying signatures over.
     */
    enum MessageType {
        /**
         * A raw message that must be hashed via Keccak-256 (SHA-2) before verifying ECDSA(secp256k1) signatures.
         */
        RAW,
        /**
         * A message that is already hashed via Keccak-256 (SHA-2) and should be used directly for verifying both
         * Ed25519 and ECDSA(secp256k1) signatures.
         */
        KECCAK_256_HASH
    }

    /**
     * Verifies the signature of a {@link Key} for a given {@link Bytes} payload based on the cryptographic signatures
     * given in a {@link SignatureMap} and, optionally, an override strategy for verifying simple key signatures.
     * <p>
     * It is not necessary to include the full public key of a cryptographic key in the {@link SignatureMap}, as long
     * as the prefix given is enough to uniquely identify the key. This is because the {@link Key} structure will
     * contain the full public key, and the verifier will use this to verify the signature. If there are multiple
     * prefixes that match the same key, the verifier will use the first pair encountered when traversing the
     * {@link SignaturePair}s in the order given.
     * <p>
     * For a ECDSA(secp256k1) {@link SignaturePair}, the public key prefix must be of the compressed form of the key
     * (33 bytes); that is, it must match the format for ECDSA(secp256k1) public keys used in {@link Key} structures
     * with the Hedera gRPC API. Any prefix that is longer than 32 bytes for an Ed25519 {@link SignaturePair} or
     * 33 bytes for an ECDSA(secp256k1) {@link SignaturePair} will be completely ignored.
     *
     * @param key the key whose signature should be verified
     * @param bytes the message to verify signatures over
     * @param messageType the type of message to verify the signature over
     * @param signatureMap the cryptographic signatures to verify against
     * @param simpleKeyVerifier an optional override strategy for verifying simple key signatures
     * @return true if the signature is valid; false otherwise
     * @throws IllegalArgumentException if the message is not 32 bytes long with type KECCAK_256_HASH
     */
    boolean verifySignature(
            @NonNull Key key,
            @NonNull Bytes bytes,
            @NonNull MessageType messageType,
            @NonNull SignatureMap signatureMap,
            @Nullable Function<Key, SimpleKeyStatus> simpleKeyVerifier);

    /**
     * Convenience method for getting the number of Ed25519 and ECDSA(secp256k1) keys in a {@link Key} structure,
     * useful for estimating the maximum amount of work that will be done in verifying the signature of the key.
     * @param key the key structure to count simple keys in
     * @return the number of Ed25519 and ECDSA(secp256k1) keys in the key
     */
    KeyCounts countSimpleKeys(@NonNull Key key);

    /**
     * Contains the number of Ed25519 and ECDSA(secp256k1) keys in a {@link Key} structure.
     */
    record KeyCounts(int numEddsaKeys, int numEcdsaKeys) {}

    /**
     * Enumerates the statuses of the signature verification for a simple key in the context of a call to
     * {@link SignatureVerifier#verifySignature(Key, Bytes, MessageType, SignatureMap, Function)}, where a <i>simple</i> key is any
     * {@link Key} that is neither a {@link KeyList} nor a {@link ThresholdKey}.
     */
    enum SimpleKeyStatus {
        /**
         * The key's signature should be considered valid.
         */
        VALID,
        /**
         * The key's signature should be considered invalid.
         */
        INVALID,
        /**
         * The key's signature should be considered valid only if the key has a valid cryptographic signature in context.
         */
        ONLY_IF_CRYPTO_SIG_VALID
    }
}
