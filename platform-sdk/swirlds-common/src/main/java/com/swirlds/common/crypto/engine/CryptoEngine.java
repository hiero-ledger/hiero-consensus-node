// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.crypto.engine;

import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyException;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.HashBuilder;
import com.swirlds.common.crypto.Message;
import com.swirlds.common.crypto.SerializableHashable;
import com.swirlds.common.crypto.SignatureType;
import com.swirlds.common.crypto.TransactionSignature;
import com.swirlds.common.crypto.VerificationStatus;
import com.swirlds.common.io.SelfSerializable;
import com.swirlds.logging.legacy.LogMarker;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

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
     * a pre-computed {@link Map} of each algorithm's {@code null} hash value.
     */
    private Map<DigestType, Hash> nullHashes;

    /**
     * Constructor.
     */
    public CryptoEngine() {
        this.digestProvider = new DigestProvider();

        this.ed25519VerificationProvider = new Ed25519VerificationProvider();
        this.ecdsaSecp256k1VerificationProvider = new EcdsaSecp256k1VerificationProvider();

        this.serializationDigestProvider = new SerializationDigestProvider();
        this.runningHashProvider = new RunningHashProvider();

        buildNullHashes();
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
    @Override
    public Hash digestSync(final byte[] message, final DigestType digestType) {
        return new Hash(digestSyncInternal(message, digestType, digestProvider), digestType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] digestBytesSync(final SelfSerializable serializable, final DigestType digestType) {
        try {
            return serializationDigestProvider.compute(serializable, digestType);
        } catch (final NoSuchAlgorithmException ex) {
            throw new CryptographyException(ex, LogMarker.EXCEPTION);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash digestSync(
            final SerializableHashable serializableHashable, final DigestType digestType, final boolean setHash) {
        try {
            final byte[] bytes = serializationDigestProvider.compute(serializableHashable, digestType);
            final Hash hash = new Hash(bytes, digestType);
            if (setHash) {
                serializableHashable.setHash(hash);
            }
            return hash;
        } catch (final NoSuchAlgorithmException ex) {
            throw new CryptographyException(ex, LogMarker.EXCEPTION);
        }
    }

    @NonNull
    @Override
    public byte[] digestBytesSync(@NonNull final byte[] message, @NonNull final DigestType digestType) {
        return digestSyncInternal(message, digestType, digestProvider);
    }

    /**
     * Compute and store hash for null using different digest types.
     */
    private void buildNullHashes() {
        nullHashes = new HashMap<>();
        for (final DigestType digestType : DigestType.values()) {
            final HashBuilder hb = new HashBuilder(digestType);
            nullHashes.put(digestType, hb.build());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash getNullHash(final DigestType digestType) {
        return nullHashes.get(digestType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean verifySync(final TransactionSignature signature) {
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
    public boolean verifySync(final List<TransactionSignature> signatures) {
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
            final byte[] data, final byte[] signature, final byte[] publicKey, final SignatureType signatureType) {
        if (signatureType == SignatureType.ECDSA_SECP256K1) {
            return ecdsaSecp256k1VerificationProvider.compute(data, signature, publicKey, signatureType);
        } else {
            return ed25519VerificationProvider.compute(data, signature, publicKey, signatureType);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Hash calcRunningHash(final Hash runningHash, final Hash newHashToAdd, final DigestType digestType) {
        try {
            return runningHashProvider.compute(runningHash, newHashToAdd, digestType);
        } catch (final NoSuchAlgorithmException e) {
            throw new CryptographyException(e, LogMarker.EXCEPTION);
        }
    }
    /**
     * Common private utility method for performing synchronous digest computations.
     *
     * @param message    the message contents to be hashed
     * @param digestType the type of digest used to compute the hash
     * @param provider   the underlying provider to be used
     * @return the cryptographic hash for the given message contents
     */
    private @NonNull byte[] digestSyncInternal(
            @NonNull final byte[] message,
            @NonNull final DigestType digestType,
            @NonNull final DigestProvider provider) {
        try {
            return provider.compute(message, digestType);
        } catch (final NoSuchAlgorithmException ex) {
            throw new CryptographyException(ex, LogMarker.EXCEPTION);
        }
    }
}
