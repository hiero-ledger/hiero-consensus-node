// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto.engine;

import static com.swirlds.logging.legacy.LogMarker.TESTING_EXCEPTIONS;
import static org.hiero.consensus.model.utility.CommonUtils.hex;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

/**
 * Verifies signatures created with an ECDSA(secp256k1) private key.
 */
public class EcdsaSecp256k1Verifier {

    /**
     * Record for caching thread local variables to avoid allocating memory for each verification.
     */
    private record ThreadLocalCache(
            LibSecp256k1.secp256k1_ecdsa_signature signature,
            LibSecp256k1.secp256k1_pubkey publicKey,
            byte[] uncompressedPublicKeyInput) {
        public ThreadLocalCache() {
            this(
                    new LibSecp256k1.secp256k1_ecdsa_signature(),
                    new LibSecp256k1.secp256k1_pubkey(),
                    new byte[ECDSA_UNCOMPRESSED_KEY_SIZE_WITH_HEADER_BYTE]);
            // set the type header byte for uncompressed public keys, this is always the same
            uncompressedPublicKeyInput[0] = 0x04;
        }
    }

    /** Length of a Keccak256 hash */
    public static final int ECDSA_KECCAK_256_SIZE = 32;

    /** Length of an uncompressed ECDSA public key */
    public static final int ECDSA_UNCOMPRESSED_KEY_SIZE = 64;

    /** Length of an ECDSA signature */
    public static final int ECDSA_SIGNATURE_SIZE = 64;

    /** Length of an uncompressed ECDSA public key including a header byte */
    private static final int ECDSA_UNCOMPRESSED_KEY_SIZE_WITH_HEADER_BYTE = ECDSA_UNCOMPRESSED_KEY_SIZE + 1;

    /**
     * Thread local caches to avoid allocating memory for each verification. They will leak memory for each thread used
     * for verification but only just over 100 bytes to totally worth it.
     */
    private static final ThreadLocal<ThreadLocalCache> CACHE = ThreadLocal.withInitial(ThreadLocalCache::new);

    /** Logger */
    private static final Logger logger = LogManager.getLogger(EcdsaSecp256k1Verifier.class);

    /**
     * Verifies a ECDSA(secp256k1) signature of a message is valid for a given public key.
     *
     * <p>The public key must be 64 bytes where the first 32 bytes are the x-coordinate
     * of the public key, and the second 32 bytes are the y-coordinate of the public key.
     *
     * <p>The signature must be 64 bytes where the first 32 bytes are the {code r} value of
     * the signature and the second 32 bytes are the {@code s} value of the signature.
     *
     * <p>The msgHash must be 32 bytes keccak 256 hash of the message that was signed.
     *
     * <p>All encodings are to be unsigned big-endian.
     *
     * @param rawSig
     * 		the (r, s) signature to be verified
     * @param msgHash
     * 		the 32 bytes 256bit keccak hash of the message that was signed
     * @param pubKey
     * 		the public key to use to verify the signature
     * @return true if the signature is valid
     */
    public boolean verify(@NonNull final byte[] rawSig, @NonNull final byte[] msgHash, @NonNull final byte[] pubKey) {
        // check message is already Keccak256 hash size
        if (msgHash.length != ECDSA_KECCAK_256_SIZE) {
            logger.warn(TESTING_EXCEPTIONS.getMarker(), () -> "Message is not Keccak256 hash size 32 bytes [ msg = %s ]"
                    .formatted(hex(msgHash)));
            return false;
        }
        // check public key size
        if (pubKey.length != ECDSA_UNCOMPRESSED_KEY_SIZE) {
            logger.warn(TESTING_EXCEPTIONS.getMarker(), () -> "Public key is not %d bytes [ publicKey = %s ]"
                    .formatted(ECDSA_UNCOMPRESSED_KEY_SIZE, hex(pubKey)));
            return false;
        }
        // check signature size
        if (rawSig.length != ECDSA_SIGNATURE_SIZE) {
            logger.warn(TESTING_EXCEPTIONS.getMarker(), () -> "Signature is not %d bytes [ rawSig = %s ]"
                    .formatted(ECDSA_SIGNATURE_SIZE, hex(rawSig)));
            return false;
        }
        // get cached buffers so we can reuse them and avoid allocating memory for each verification
        final ThreadLocalCache cache = CACHE.get();
        // convert signature to native format
        final LibSecp256k1.secp256k1_ecdsa_signature nativeSignature = cache.signature;
        final var signatureParseResult =
                LibSecp256k1.secp256k1_ecdsa_signature_parse_compact(LibSecp256k1.CONTEXT, nativeSignature, rawSig);
        if (signatureParseResult != 1) {
            logger.warn(
                    TESTING_EXCEPTIONS.getMarker(), () -> "Failed to parse signature [ publicKey = %s, rawSig = %s ]"
                            .formatted(hex(pubKey), hex(rawSig)));
            return false;
        }
        // Normalize the signature to lower-S form. This will return 1 if the signature was normalized, 0 otherwise.
        LibSecp256k1.secp256k1_ecdsa_signature_normalize(LibSecp256k1.CONTEXT, nativeSignature, nativeSignature);
        // convert public key to input format
        final byte[] publicKeyInput = cache.uncompressedPublicKeyInput;
        System.arraycopy(pubKey, 0, publicKeyInput, 1, ECDSA_UNCOMPRESSED_KEY_SIZE);
        // convert public key to native format
        final LibSecp256k1.secp256k1_pubkey nativePublicKey = cache.publicKey;
        final int keyParseResult = LibSecp256k1.secp256k1_ec_pubkey_parse(
                LibSecp256k1.CONTEXT, nativePublicKey, publicKeyInput, publicKeyInput.length);
        if (keyParseResult != 1) {
            logger.warn(
                    TESTING_EXCEPTIONS.getMarker(), () -> "Failed to parse public key [ publicKey = %s, rawSig = %s ]"
                            .formatted(hex(pubKey), hex(rawSig)));
            return false;
        }
        // verify signature
        final int result =
                LibSecp256k1.secp256k1_ecdsa_verify(LibSecp256k1.CONTEXT, nativeSignature, msgHash, nativePublicKey);
        return result == 1;
    }
}
