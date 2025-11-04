package org.hiero.consensus.crypto;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import org.hiero.consensus.crypto.internal.JcaSigner;
import org.hiero.consensus.crypto.internal.JcaVerifier;
import org.hiero.consensus.crypto.internal.SodiumSigner;
import org.hiero.consensus.crypto.internal.SodiumVerifier;

public class SigningFactory {

    public static KeyPair generateKeyPair(final SigningAlgorithm signType, final SecureRandom secureRandom) throws NoSuchAlgorithmException, NoSuchProviderException {
        final KeyPairGenerator keyPairGen =
                KeyPairGenerator.getInstance(signType.getKeyType());
        keyPairGen.initialize(signType.getKeySizeBits(), secureRandom);
        return keyPairGen.generateKeyPair();
    }

    public static BytesSigner createSigner(final SigningAlgorithm signType, final KeyPair keyPair){
        return switch (signType) {
            case RSA_BC, RSA_SUN, EC_SUN, ED25519_SUN ->
                    new JcaSigner(keyPair.getPrivate(), signType.getSigningAlgorithm(), signType.getProvider());
            case ED25519_SODIUM -> new SodiumSigner(keyPair);
        };
    }

    public static BytesVerifier createVerifier(final SigningAlgorithm signType, final PublicKey publicKey){
        return switch (signType) {
            case RSA_BC, RSA_SUN, EC_SUN, ED25519_SUN ->
                    new JcaVerifier(publicKey, signType.getSigningAlgorithm(), signType.getProvider());
            case ED25519_SODIUM -> new SodiumVerifier(publicKey);
        };
    }
}
