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



                final byte[] encoded = keyPair.getPrivate().getEncoded();
                final byte[] privateKey = new byte[32];
                System.arraycopy(encoded, encoded.length - 32, privateKey, 0, 32);



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
