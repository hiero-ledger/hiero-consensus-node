package org.hiero.consensus.crypto;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import org.hiero.base.crypto.BytesSigner;

public class SignerFactory {
    public static BytesSigner createSigner(SigningType signType, final SecureRandom secureRandom)
            throws NoSuchAlgorithmException, NoSuchProviderException {
        return switch (signType) {
            case RSA_BC, RSA_SUN, EC_SUN, ED25519_SUN -> {
                final KeyPairGenerator keyPairGen =
                        KeyPairGenerator.getInstance(signType.getKeyType(), signType.getProvider());
                keyPairGen.initialize(signType.getKeySizeBits(), secureRandom);
                final KeyPair keyPair = keyPairGen.generateKeyPair();
                yield new JcaSigner(keyPair.getPrivate(), signType.getSigningAlgorithm(), signType.getProvider());
            }
            case ED25519_SODIUM -> {
                final SodiumJava sodium = new SodiumJava();
                final Sign.Native signer = new LazySodiumJava(sodium);

                final byte[] publicKey = new byte[Sign.PUBLICKEYBYTES];
                final byte[] privateKey = new byte[Sign.SECRETKEYBYTES];

                final boolean keysCreated = signer.cryptoSignKeypair(publicKey, privateKey);
                if(!keysCreated) {
                    throw new RuntimeException("Failed to generate Ed25519 keypair using Sodium");
                }
                yield data -> {
                    final byte[] signature = new byte[Sign.BYTES];
                    final boolean signed = signer.cryptoSignDetached(signature, data.toByteArray(), data.length(), privateKey);
                    if (!signed) {
                        throw new RuntimeException("Failed to sign data using Ed25519 with Sodium");
                    }
                    return Bytes.wrap(signature);
                };
            }
        };
    }
}
