package org.hiero.consensus.crypto;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Sign;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.PublicKey;

public class SodiumVerifier implements BytesVerifier {

    /**
     * The JNI interface to the underlying native libSodium dynamic library. This variable is initialized when this
     * class is loaded and initialized by the {@link ClassLoader}.
     */
    private static final Sign.Native algorithm;

    static {
        final SodiumJava sodiumJava = new SodiumJava();
        algorithm = new LazySodiumJava(sodiumJava);
    }

    final byte[] publicKey;

    public SodiumVerifier(final PublicKey publicKey) {
        final byte[] encoded = publicKey.getEncoded();
        this.publicKey = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, this.publicKey, 0, 32);
    }

    @Override
    public boolean verify(final Bytes signature, final Bytes data) {
        return algorithm.cryptoSignVerifyDetached(signature.toByteArray(), data.toByteArray(), Math.toIntExact(data.length()), publicKey);
    }
}
