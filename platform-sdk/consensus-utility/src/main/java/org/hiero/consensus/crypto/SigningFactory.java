// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import static org.hiero.consensus.crypto.SigningImplementation.ED25519_SODIUM;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.Map;
import org.hiero.base.crypto.BytesSignatureVerifier;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.consensus.crypto.internal.JcaSigner;
import org.hiero.consensus.crypto.internal.JcaVerifier;
import org.hiero.consensus.crypto.internal.SodiumSigner;
import org.hiero.consensus.crypto.internal.SodiumVerifier;

/**
 * Factory for creating instances to use for the supported signing schemas.
 */
public final class SigningFactory {
    private SigningFactory() {}

    /**
     * The default implementations to use for each schema.
     */
    private static final Map<SigningSchema, SigningImplementation> defaultImplementations = new EnumMap<>(
            Map.of(SigningSchema.RSA, SigningImplementation.RSA_BC, SigningSchema.ED25519, ED25519_SODIUM));

    /**
     * Generates a new key pair for the specified signing schema.
     *
     * @param signingSchema the signing schema
     * @param secureRandom  the source of randomness to use
     * @return the generated key pair
     */
    public static @NonNull KeyPair generateKeyPair(
            @NonNull final SigningSchema signingSchema, @NonNull final SecureRandom secureRandom) {
        final KeyPairGenerator keyPairGen;
        try {
            keyPairGen = KeyPairGenerator.getInstance(signingSchema.getKeyType());
        } catch (final NoSuchAlgorithmException e) {
            throw new CryptographyException(e);
        }
        keyPairGen.initialize(signingSchema.getKeySizeBits(), secureRandom);
        return keyPairGen.generateKeyPair();
    }

    /**
     * Creates a signer for the specified key pair using the default implementation for the key type.
     *
     * @param keyPair the key pair to use for signing
     * @return the signer
     */
    public static @NonNull BytesSigner createSigner(@NonNull final KeyPair keyPair) {
        final SigningImplementation implementation =
                defaultImplementations.get(SigningSchema.fromKeyType(keyPair.getPrivate()));
        if (implementation == null) {
            throw new IllegalArgumentException(
                    "No implementation for key type: " + keyPair.getPrivate().getAlgorithm());
        }
        return createSigner(implementation, keyPair);
    }

    /**
     * Creates a signer for the specified key pair using the specified implementation.
     *
     * @param signType the signing implementation to use
     * @param keyPair  the key pair to use for signing
     * @return the signer
     */
    public static @NonNull BytesSigner createSigner(
            @NonNull final SigningImplementation signType, @NonNull final KeyPair keyPair) {
        if (signType == ED25519_SODIUM) {
            return new SodiumSigner(keyPair);
        }
        return new JcaSigner(
                keyPair.getPrivate(), signType.getSigningSchema().getSigningAlgorithm(), signType.getProvider());
    }

    /**
     * Creates a verifier for the specified key pair using the default implementation for the key type.
     *
     * @param publicKey the key to use for verification
     * @return the verifier
     */
    public static @NonNull BytesSignatureVerifier createVerifier(@NonNull final PublicKey publicKey) {
        final SigningImplementation implementation = defaultImplementations.get(SigningSchema.fromKeyType(publicKey));
        if (implementation == null) {
            throw new IllegalArgumentException("No implementation for key type: " + publicKey.getAlgorithm());
        }
        return createVerifier(implementation, publicKey);
    }

    /**
     * Creates a verifier for the specified public key using the specified implementation.
     *
     * @param signType  the signing implementation to use
     * @param publicKey the public key to use for verification
     * @return the verifier
     */
    public static @NonNull BytesSignatureVerifier createVerifier(
            @NonNull final SigningImplementation signType, @NonNull final PublicKey publicKey) {
        if (signType == ED25519_SODIUM) {
            return new SodiumVerifier(publicKey);
        }
        return new JcaVerifier(publicKey, signType.getSigningSchema().getSigningAlgorithm(), signType.getProvider());
    }
}
