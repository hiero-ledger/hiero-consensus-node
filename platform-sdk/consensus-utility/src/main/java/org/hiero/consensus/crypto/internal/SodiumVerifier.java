package org.hiero.consensus.crypto.internal;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.PublicKey;
import org.hiero.consensus.crypto.BytesVerifier;

public class SodiumVerifier implements BytesVerifier {
    final byte[] publicKey;

    public SodiumVerifier(final PublicKey publicKey) {
        final byte[] encoded = publicKey.getEncoded();
        this.publicKey = new byte[32];
        // Extract 32-byte raw public key from X.509 encoded public key
        System.arraycopy(encoded, encoded.length - 32, this.publicKey, 0, 32);
    }

    @Override
    public boolean verify(final Bytes signature, final Bytes data) {
        return SodiumJni.SODIUM.cryptoSignVerifyDetached(signature.toByteArray(), data.toByteArray(), Math.toIntExact(data.length()), publicKey);
    }
}
