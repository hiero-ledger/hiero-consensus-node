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

    public static KeyPair generateKeyPair(final SigningType signType, final SecureRandom secureRandom) throws NoSuchAlgorithmException, NoSuchProviderException {
        final KeyPairGenerator keyPairGen =
                KeyPairGenerator.getInstance(signType.getKeyType(), signType.getProvider());
        keyPairGen.initialize(signType.getKeySizeBits(), secureRandom);
        return keyPairGen.generateKeyPair();
    }

    public static BytesSigner createSigner(final SigningType signType, final KeyPair keyPair){
        return switch (signType) {
            case RSA_BC, RSA_SUN, EC_SUN, ED25519_SUN ->
                    new JcaSigner(keyPair.getPrivate(), signType.getSigningAlgorithm(), signType.getProvider());
            case ED25519_SODIUM -> {
                final SodiumJava sodium = new SodiumJava();
                final Sign.Native signer = new LazySodiumJava(sodium);

                // Extract 32-byte seed from PKCS#8 encoded private key
                final byte[] privateEncoded = keyPair.getPrivate().getEncoded();
                final byte[] privateSeed = new byte[32];
                System.arraycopy(privateEncoded, privateEncoded.length - 32, privateSeed, 0, 32);

                // Extract 32-byte raw public key from X.509 encoded public key
                final byte[] publicEncoded = keyPair.getPublic().getEncoded();
                final byte[] publicKey = new byte[32];
                System.arraycopy(publicEncoded, publicEncoded.length - 32, publicKey, 0, 32);

                // libsodium expects 64-byte secret key: [32-byte seed || 32-byte public key]
                final byte[] sodiumSecretKey = new byte[64];
                System.arraycopy(privateSeed, 0, sodiumSecretKey, 0, 32);
                System.arraycopy(publicKey, 0, sodiumSecretKey, 32, 32);

                yield data -> {
                    final byte[] signature = new byte[Sign.BYTES];
                    final boolean signed = signer.cryptoSignDetached(signature, data.toByteArray(), data.length(), sodiumSecretKey);
                    if (!signed) {
                        throw new RuntimeException("Failed to sign data using Ed25519 with Sodium");
                    }
                    return Bytes.wrap(signature);
                };
            }
        };
    }
}
