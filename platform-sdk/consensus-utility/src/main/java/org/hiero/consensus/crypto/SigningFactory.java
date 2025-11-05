package org.hiero.consensus.crypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Map;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.base.crypto.BytesVerifier;
import org.hiero.consensus.crypto.internal.JcaSigner;
import org.hiero.consensus.crypto.internal.JcaVerifier;
import org.hiero.consensus.crypto.internal.SodiumSigner;
import org.hiero.consensus.crypto.internal.SodiumVerifier;

public class SigningFactory {
    private static final Map<String, SigningImplementation> defaultImplemetations = Map.of(
            SigningScheme.RSA.getKeyType(), SigningImplementation.RSA_BC,
            SigningScheme.EC.getKeyType(), SigningImplementation.EC_JDK,
            SigningScheme.ED25519.getKeyType(), SigningImplementation.ED25519_SODIUM
    );

    public static KeyPair generateKeyPair(final SigningScheme signType, final SecureRandom secureRandom) throws NoSuchAlgorithmException, NoSuchProviderException {
        final KeyPairGenerator keyPairGen =
                KeyPairGenerator.getInstance(signType.getKeyType());
        keyPairGen.initialize(signType.getKeySizeBits(), secureRandom);
        return keyPairGen.generateKeyPair();
    }

    public static BytesSigner createSigner(final KeyPair keyPair){
        final SigningImplementation implementation = defaultImplemetations.get(
                keyPair.getPrivate().getAlgorithm());
        if(implementation == null){
            throw new IllegalArgumentException("No implementation for key type: "
                    + keyPair.getPrivate().getAlgorithm());
        }
        return createSigner(implementation, keyPair);
    }

    public static BytesSigner createSigner(final SigningImplementation signType, final KeyPair keyPair){
        return switch (signType) {
            case RSA_BC, RSA_JDK, EC_JDK, ED25519_SUN ->
                    new JcaSigner(keyPair.getPrivate(), signType.getSigningScheme().getSigningAlgorithm(), signType.getProvider());
            case ED25519_SODIUM -> new SodiumSigner(keyPair);
        };
    }

    public static BytesVerifier createVerifier(final KeyPair keyPair){
        final SigningImplementation implementation = defaultImplemetations.get(
                keyPair.getPublic().getAlgorithm());
        if(implementation == null){
            throw new IllegalArgumentException("No implementation for key type: "
                    + keyPair.getPublic().getAlgorithm());
        }
        return createVerifier(implementation, keyPair.getPublic());
    }

    public static BytesVerifier createVerifier(final SigningImplementation signType, final PublicKey publicKey){
        return switch (signType) {
            case RSA_BC, RSA_JDK, EC_JDK, ED25519_SUN ->
                    new JcaVerifier(publicKey, signType.getSigningScheme().getSigningAlgorithm(), signType.getProvider());
            case ED25519_SODIUM -> new SodiumVerifier(publicKey);
        };
    }
}
