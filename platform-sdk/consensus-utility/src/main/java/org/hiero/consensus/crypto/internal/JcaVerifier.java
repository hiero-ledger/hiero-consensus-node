// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto.internal;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.BytesSignatureVerifier;
import org.hiero.base.crypto.CryptographyException;

/**
 * JCA-based implementation of {@link BytesSignatureVerifier}.
 */
public class JcaVerifier implements BytesSignatureVerifier {
    private static final Logger logger = LogManager.getLogger(JcaVerifier.class);
    private static final Set<String> loggedCombos = ConcurrentHashMap.newKeySet();

    private final Signature verifier;

    /**
     * Constructor
     *
     * @param publicKey the public key
     * @param algorithm the signature algorithm
     * @param provider  the security provider
     */
    public JcaVerifier(
            @NonNull final PublicKey publicKey, @NonNull final String algorithm, @NonNull final String provider) {
        try {
            verifier = Signature.getInstance(algorithm, provider);
            verifier.initVerify(publicKey);
        } catch (final NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException e) {
            throw new CryptographyException(e);
        }
        final Provider actual = verifier.getProvider();
        final String key = algorithm + '|' + provider + '|' + actual.getName();
        if (loggedCombos.add(key)) {
            logger.info(
                    "JcaVerifier: algorithm={} requestedProvider={} resolvedProvider={} version={} class={}",
                    algorithm,
                    provider,
                    actual.getName(),
                    actual.getVersionStr(),
                    actual.getClass().getName());
        }
    }

    @Override
    public boolean verify(@NonNull final Bytes data, @NonNull final Bytes signature) {
        try {
            data.updateSignature(verifier);
            return signature.verifySignature(verifier);
        } catch (final SignatureException e) {
            throw new CryptographyException(e);
        }
    }
}
