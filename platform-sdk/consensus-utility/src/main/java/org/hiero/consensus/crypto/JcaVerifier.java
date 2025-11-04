package org.hiero.consensus.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;

public class JcaVerifier implements BytesVerifier {
    private final Signature verifier;

    public JcaVerifier(final PublicKey publicKey, final String algorithm, final String provider) {
        try {
            verifier = Signature.getInstance(algorithm, provider);
            verifier.initVerify(publicKey);
        } catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean verify(final Bytes signature, final Bytes data) {
        try {
            data.updateSignature(verifier);
            return signature.verifySignature(verifier);
        } catch (final SignatureException e) {
            throw new RuntimeException(e);
        }
    }
}
