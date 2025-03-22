// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.CryptographyException;
import com.swirlds.common.crypto.SignatureType;
import com.swirlds.common.stream.HashSigner;
import com.swirlds.common.stream.Signer;
import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.system.PlatformConstructionException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Objects;
import org.hiero.consensus.model.crypto.Hash;

/**
 * An instance capable of signing data with the platforms private signing key. This class is not thread safe.
 */
public class PlatformSigner implements Signer, HashSigner {
    private final Signature signature;

    /**
     * @param keysAndCerts
     * 		the platform's keys and certificates
     */
    public PlatformSigner(@NonNull final KeysAndCerts keysAndCerts) {
        try {
            Objects.requireNonNull(keysAndCerts, "keysAndCerts must not be null");
            final Signature s = Signature.getInstance(CryptoConstants.SIG_TYPE2, CryptoConstants.SIG_PROVIDER);
            s.initSign(keysAndCerts.sigKeyPair().getPrivate());
            this.signature = s;
        } catch (final NoSuchAlgorithmException | NoSuchProviderException | InvalidKeyException e) {
            throw new PlatformConstructionException(e);
        }
    }

    @Override
    public @NonNull com.swirlds.common.crypto.Signature sign(@NonNull final byte[] data) {
        try {
            signature.update(data);
            return new com.swirlds.common.crypto.Signature(SignatureType.RSA, signature.sign());
        } catch (final SignatureException e) {
            // this can only occur if this signature object is not initialized properly, which we ensure is done in the
            // constructor. so this can never happen
            throw new CryptographyException("Unexpected exception occurred while signing!", e, LogMarker.EXCEPTION);
        }
    }

    /**
     * Same as {@link #sign(byte[])} but takes a {@link Bytes} object instead of a byte array.
     */
    private @NonNull com.swirlds.common.crypto.Signature signBytes(@NonNull final Bytes data) {
        try {
            data.updateSignature(signature);
            return new com.swirlds.common.crypto.Signature(SignatureType.RSA, signature.sign());
        } catch (final SignatureException e) {
            // this can only occur if this signature object is not initialized properly, which we ensure is done in the
            // constructor. so this can never happen
            throw new CryptographyException("Unexpected exception occurred while signing!", e, LogMarker.EXCEPTION);
        }
    }

    @Override
    public @NonNull com.swirlds.common.crypto.Signature sign(@NonNull final Hash hash) {
        Objects.requireNonNull(hash, "hash must not be null");
        return signBytes(hash.getBytes());
    }

    /**
     * Signs the given hash and returns the signature as immutable bytes.
     * @param hash the hash to sign
     * @return the signature as immutable bytes
     */
    public @NonNull Bytes signImmutable(@NonNull final Hash hash) {
        try {
            hash.getBytes().updateSignature(signature);
            return Bytes.wrap(signature.sign());
        } catch (final SignatureException e) {
            // this can only occur if this signature object is not initialized properly, which we ensure is done in the
            // constructor. so this can never happen
            throw new CryptographyException(e);
        }
    }
}
