// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.crypto;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.Signer;
import org.hiero.consensus.model.node.KeysAndCerts;

/**
 * An instance capable of signing data with the platforms private signing key. This class is not thread safe.
 */
public class PlatformSigner implements Signer, BytesSigner {
    private final BytesSigner signer;

    /**
     * @param keysAndCerts the platform's keys and certificates
     */
    public PlatformSigner(@NonNull final KeysAndCerts keysAndCerts) {
        this.signer = SigningFactory.createSigner(keysAndCerts.sigKeyPair());
    }

    @Override
    public @NonNull Signature sign(@NonNull final byte[] data) {
        return signBytes(Bytes.wrap(data));
    }

    /**
     * Same as {@link #sign(byte[])} but takes a {@link Bytes} object instead of a byte array.
     */
    private @NonNull Signature signBytes(@NonNull final Bytes data) {
        return new Signature(SignatureType.RSA, sign(data));
    }

    /**
     * Signs the given hash and returns the signature.
     *
     * @param hash the hash to sign
     * @return the signature
     */
    public @NonNull Signature sign(@NonNull final Hash hash) {
        Objects.requireNonNull(hash, "hash must not be null");
        return signBytes(hash.getBytes());
    }

    /**
     * Signs the given hash and returns the signature as immutable bytes.
     *
     * @param hash the hash to sign
     * @return the signature as immutable bytes
     */
    public @NonNull Bytes signImmutable(@NonNull final Hash hash) {
        return sign(hash.getBytes());
    }

    @Override
    public @NonNull Bytes sign(@NonNull final Bytes data) {
        return signer.sign(data);
    }
}
