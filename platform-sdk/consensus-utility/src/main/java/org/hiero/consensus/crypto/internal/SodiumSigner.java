package org.hiero.consensus.crypto.internal;

import com.goterl.lazysodium.interfaces.Sign;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.KeyPair;
import org.hiero.consensus.crypto.BytesSigner;

public class SodiumSigner implements BytesSigner {
    private final byte[] sodiumSecretKey;

    public SodiumSigner(final KeyPair keyPair) {
        // Extract 32-byte seed from PKCS#8 encoded private key
        final byte[] privateEncoded = keyPair.getPrivate().getEncoded();
        final byte[] privateSeed = new byte[32];
        System.arraycopy(privateEncoded, privateEncoded.length - 32, privateSeed, 0, 32);

        // Extract 32-byte raw public key from X.509 encoded public key
        final byte[] publicEncoded = keyPair.getPublic().getEncoded();
        final byte[] publicKey = new byte[32];
        System.arraycopy(publicEncoded, publicEncoded.length - 32, publicKey, 0, 32);

        // libsodium expects 64-byte secret key: [32-byte seed || 32-byte public key]
        sodiumSecretKey = new byte[64];
        System.arraycopy(privateSeed, 0, sodiumSecretKey, 0, 32);
        System.arraycopy(publicKey, 0, sodiumSecretKey, 32, 32);
    }

    @Override
    public Bytes sign(@NonNull final Bytes data) {
        final byte[] signature = new byte[Sign.BYTES];
        final boolean signed = SodiumJni.SODIUM.cryptoSignDetached(signature, data.toByteArray(), data.length(), sodiumSecretKey);
        if (!signed) {
            throw new RuntimeException("Failed to sign data using Ed25519 with Sodium");
        }
        return Bytes.wrap(signature);
    }
}
