package org.hiero.consensus.crypto.internal;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import org.hiero.base.crypto.BytesSigner;

public class JcaSigner implements BytesSigner {
    private final Signature signature;

    public JcaSigner(final PrivateKey privateKey, final String algorithm, final String provider) {
        try {
            this.signature = Signature.getInstance(algorithm, provider);
            signature.initSign(privateKey);
        } catch (final NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Bytes sign(@NonNull final Bytes data) {
        try {
            data.updateSignature(signature);
            return Bytes.wrap(signature.sign());
        } catch (final SignatureException e) {
            throw new RuntimeException(e);
        }
    }
}
