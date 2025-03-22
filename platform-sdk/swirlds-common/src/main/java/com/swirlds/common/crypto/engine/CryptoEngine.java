// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto.engine;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyException;
import com.swirlds.common.crypto.Message;
import com.swirlds.common.crypto.SignatureType;
import com.swirlds.common.crypto.TransactionSignature;
import com.swirlds.common.crypto.VerificationStatus;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.List;
import java.util.Objects;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.SerializableHashable;
import org.hiero.consensus.model.io.SelfSerializable;

public class CryptoEngine implements Cryptography {

    static {
        // Register the BouncyCastle Provider instance with the JVM
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * The digest provider instance that is used to generate hashes of SelfSerializable objects.
     */
    private final SerializationDigestProvider serializationDigestProvider;

    /**
     * The digest provider instance that is used to generate running hashes.
     */
    private final RunningHashProvider runningHashProvider;

    /**
     * The digest provider instance that is used to compute hashes of {@link Message} instances and byte arrays.
     */
    private final DigestProvider digestProvider;

    /**
     * The verification provider used to perform signature verification of {@link TransactionSignature} instances.
     */
    private final Ed25519VerificationProvider ed25519VerificationProvider;

    /**
     * The verification provider used to perform signature verification of {@link TransactionSignature} instances.
     */
    private final EcdsaSecp256k1VerificationProvider ecdsaSecp256k1VerificationProvider;

    /**
     * Constructor.
     */
    public CryptoEngine() {
        this.digestProvider = new DigestProvider();

        this.ed25519VerificationProvider = new Ed25519VerificationProvider();
        this.ecdsaSecp256k1VerificationProvider = new EcdsaSecp256k1VerificationProvider();

        this.serializationDigestProvider = new SerializationDigestProvider();
        this.runningHashProvider = new RunningHashProvider();
    }

    /**
     * Common private utility method for performing synchronous signature verification.
     *
     * @param signature the signature to be verified
     * @param provider  the underlying provider to be used
     * @return true if the signature is valid; otherwise false
     */
    private static boolean verifySyncInternal(
            final TransactionSignature signature,
            final OperationProvider<TransactionSignature, Void, Boolean, ?, SignatureType> provider) {
        final boolean isValid;

        try {
            isValid = provider.compute(signature, signature.getSignatureType());
            signature.setSignatureStatus(isValid ? VerificationStatus.VALID : VerificationStatus.INVALID);
        } catch (final NoSuchAlgorithmException ex) {
            throw new CryptographyException(ex, LogMarker.EXCEPTION);
        }

        return isValid;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Hash digestSync(@NonNull final byte[] message) {
        return new Hash(digestSyncInternal(message, digestProvider), DEFAULT_DIGEST_TYPE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] digestBytesSync(@NonNull final SelfSerializable serializable) {
        Objects.requireNonNull(serializable, "serializable must not be null");
        try {
            return serializationDigestProvider.compute(serializable, DEFAULT_DIGEST_TYPE);
        } catch (final NoSuchAlgorithmException ex) {
            throw new CryptographyException(ex, LogMarker.EXCEPTION);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash digestSync(@NonNull final SerializableHashable serializableHashable, final boolean setHash) {
        final Hash hash = new Hash(digestBytesSync(serializableHashable), DEFAULT_DIGEST_TYPE);
        if (setHash) {
            serializableHashable.setHash(hash);
        }
        return hash;
    }

    @NonNull
    @Override
    public byte[] digestBytesSync(@NonNull final byte[] message) {
        return digestSyncInternal(message, digestProvider);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean verifySync(@NonNull final TransactionSignature signature) {
        if (signature.getSignatureType() == SignatureType.ECDSA_SECP256K1) {
            return verifySyncInternal(signature, ecdsaSecp256k1VerificationProvider);
        } else {
            return verifySyncInternal(signature, ed25519VerificationProvider);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean verifySync(@NonNull final List<TransactionSignature> signatures) {
        boolean finalOutcome = true;

        OperationProvider<TransactionSignature, Void, Boolean, ?, SignatureType> provider;
        for (final TransactionSignature signature : signatures) {
            if (signature.getSignatureType() == SignatureType.ECDSA_SECP256K1) {
                provider = ecdsaSecp256k1VerificationProvider;
            } else {
                provider = ed25519VerificationProvider;
            }

            if (!verifySyncInternal(signature, provider)) {
                finalOutcome = false;
            }
        }

        return finalOutcome;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean verifySync(
            @NonNull final byte[] data,
            @NonNull final byte[] signature,
            @NonNull final byte[] publicKey,
            @NonNull final SignatureType signatureType) {
        if (signatureType == SignatureType.ECDSA_SECP256K1) {
            return ecdsaSecp256k1VerificationProvider.compute(data, signature, publicKey, signatureType);
        } else {
            return ed25519VerificationProvider.compute(data, signature, publicKey, signatureType);
        }
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Hash calcRunningHash(@NonNull final Hash runningHash, @NonNull final Hash newHashToAdd) {
        try {
            return runningHashProvider.compute(runningHash, newHashToAdd, Cryptography.DEFAULT_DIGEST_TYPE);
        } catch (final NoSuchAlgorithmException e) {
            throw new CryptographyException(e, LogMarker.EXCEPTION);
        }
    }
    /**
     * Common private utility method for performing synchronous digest computations.
     *
     * @param message  the message contents to be hashed
     * @param provider the underlying provider to be used
     * @return the cryptographic hash for the given message contents
     */
    private @NonNull byte[] digestSyncInternal(@NonNull final byte[] message, @NonNull final DigestProvider provider) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        try {
            return provider.compute(message, Cryptography.DEFAULT_DIGEST_TYPE);
        } catch (final NoSuchAlgorithmException ex) {
            throw new CryptographyException(ex, LogMarker.EXCEPTION);
        }
    }
}
