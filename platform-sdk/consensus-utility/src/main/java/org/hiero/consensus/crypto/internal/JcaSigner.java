// SPDX-License-Identifier: Apache-2.0
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
import org.hiero.base.crypto.CryptographyException;

/**
 * JCA-based implementation of {@link BytesSigner}.
 */
public class JcaSigner implements BytesSigner {
    private final Signature signature;

    /**
     * Constructor
     *
     * @param privateKey the private key
     * @param algorithm  the signature algorithm
     * @param provider   the security provider
     */
    public JcaSigner(
            @NonNull final PrivateKey privateKey, @NonNull final String algorithm, @NonNull final String provider) {
        try {
            this.signature = Signature.getInstance(algorithm, provider);
            signature.initSign(privateKey);
        } catch (final NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException e) {
            throw new CryptographyException(e);
        }
    }

    @Override
    public @NonNull Bytes sign(@NonNull final Bytes data) {
        try {
            data.updateSignature(signature);
            return Bytes.wrap(signature.sign());
        } catch (final SignatureException e) {
            throw new CryptographyException(e);
        }
    }
}
