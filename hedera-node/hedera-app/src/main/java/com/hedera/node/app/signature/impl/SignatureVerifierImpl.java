// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static com.hedera.hapi.node.base.SignaturePair.SignatureOneOfType.ECDSA_SECP256K1;
import static com.hedera.hapi.node.base.SignaturePair.SignatureOneOfType.ED25519;
import static com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType.KECCAK_256_HASH;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.signature.ExpandedSignaturePair;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.signature.SignatureVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyFactory;
import com.swirlds.common.crypto.SignatureType;
import com.swirlds.common.crypto.TransactionSignature;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A concrete implementation of {@link SignatureVerifier} that uses the {@link Cryptography} engine to verify the
 * signatures.
 */
@Singleton
public final class SignatureVerifierImpl implements SignatureVerifier {

    /** The {@link Cryptography} engine to use for signature verification. */
    private final Cryptography cryptoEngine;

    /** Create a new instance with new {@link Cryptography} engine. */
    @Inject
    public SignatureVerifierImpl() {
        this(CryptographyFactory.create());
    }

    /** Create a new instance with the given {@link Cryptography} engine. */
    SignatureVerifierImpl(@NonNull final Cryptography cryptoEngine) {
        this.cryptoEngine = requireNonNull(cryptoEngine);
    }

    @NonNull
    @Override
    public Map<Key, SignatureVerificationFuture> verify(
            @NonNull final Bytes signedBytes,
            @NonNull final Set<ExpandedSignaturePair> sigs,
            @NonNull final MessageType messageType) {
        requireNonNull(signedBytes);
        requireNonNull(sigs);
        requireNonNull(messageType);
        if (messageType == KECCAK_256_HASH && signedBytes.length() != 32) {
            throw new IllegalArgumentException(
                    "Message type " + KECCAK_256_HASH + " must be 32 bytes long, got '" + signedBytes.toHex() + "'");
        }

        Preparer edPreparer = null;
        final var hasEDSignature =
                sigs.stream().anyMatch(sigPair -> sigPair.sigPair().signature().kind() == ED25519);
        if (hasEDSignature) {
            edPreparer = createPreparerForED(signedBytes);
        }

        Preparer ecPreparer = null;
        final var hasECSignature =
                sigs.stream().anyMatch(sigPair -> sigPair.sigPair().signature().kind() == ECDSA_SECP256K1);
        if (hasECSignature) {
            ecPreparer = createPreparerForEC(signedBytes, messageType);
        }

        // Gather each TransactionSignature to send to the platform and the resulting SignatureVerificationFutures
        final var futures = HashMap.<Key, SignatureVerificationFuture>newHashMap(sigs.size());
        for (ExpandedSignaturePair sigPair : sigs) {
            final var kind = sigPair.sigPair().signature().kind();
            final var preparer =
                    switch (kind) {
                        case ECDSA_SECP256K1 -> ecPreparer;
                        case ED25519 -> edPreparer;
                        case CONTRACT, ECDSA_384, RSA_3072, UNSET -> throw new IllegalArgumentException(
                                "Unsupported signature type: " + kind);
                    };

            if (preparer == null) {
                throw new RuntimeException("Preparer should not be null");
            }
            preparer.addSignature(sigPair.signature());
            preparer.addKey(sigPair.keyBytes());
            final TransactionSignature txSig = preparer.prepareTransactionSignature();
            cryptoEngine.verifySync(txSig);
            final SignatureVerificationFuture future =
                    new SignatureVerificationFutureImpl(sigPair.key(), sigPair.evmAlias(), txSig);
            futures.put(sigPair.key(), future);
        }

        return futures;
    }

    private static Preparer createPreparerForED(@NonNull final Bytes signedBytes) {
        return new Preparer(signedBytes, SignatureType.ED25519);
    }

    private static Preparer createPreparerForEC(
            @NonNull final Bytes signedBytes, @NonNull final MessageType messageType) {
        return switch (messageType) {
            case RAW -> {
                final var bytes = new byte[(int) signedBytes.length()];
                signedBytes.getBytes(0, bytes, 0, bytes.length);
                yield new Preparer(Bytes.wrap(MiscCryptoUtils.keccak256DigestOf(bytes)), SignatureType.ECDSA_SECP256K1);
            }
            case KECCAK_256_HASH -> new Preparer(signedBytes, SignatureType.ECDSA_SECP256K1);
        };
    }

    // The Hashgraph Platform crypto engine takes a list of TransactionSignature objects to verify. Each of these
    // is fed a byte array of the signed bytes, the public key, the signature, and the signature type, with
    // appropriate offsets. Rather than many small arrays, we're going to create one big array for all ED25519
    // verifications and one for ECDSA_SECP256K1 verifications and reuse them across all TransactionSignature
    // objects.
    private static final class Preparer {
        // Each transaction, encoded as protobuf, is a maximum of 6K, *including* signatures. A single instance of the
        // Preparer is used for verifying keys on a single transaction. If the transaction has 6K signed bytes, there
        // is virtually no space left for signatures. If the transaction has no signed bytes, the maximum number of
        // signatures would still be less than 6K. This array wants to be large enough to not need any copies as it is
        // being built, but small enough to not waste too much space. 10K seems like it will fit the bill. In the off
        // chance that it *is* too small, an array copy will be made to enlarge it.
        private static final int DEFAULT_SIZE = 10 * 1024;
        private final int signedBytesLength;
        private final SignatureType signatureType;
        private byte[] content = new byte[DEFAULT_SIZE];
        private int offset;
        private int signatureOffset;
        private int keyOffset;
        private int signatureLength;
        private int keyLength;

        Preparer(@NonNull final Bytes signedBytes, @NonNull final SignatureType signatureType) {
            this.signatureType = requireNonNull(signatureType);
            signedBytesLength = (int) signedBytes.length();
            signedBytes.getBytes(0, content, 0, signedBytesLength);
            offset = signedBytesLength;
        }

        void addSignature(@NonNull final Bytes signature) {
            signatureOffset = offset;
            signatureLength = (int) signature.length();
            add(signature);
        }

        void addKey(@NonNull final Bytes key) {
            keyOffset = offset;
            keyLength = (int) key.length();
            add(key);
        }

        @NonNull
        TransactionSignature prepareTransactionSignature() {
            return new TransactionSignature(
                    content,
                    signatureOffset,
                    signatureLength,
                    keyOffset,
                    keyLength,
                    0,
                    signedBytesLength,
                    signatureType);
        }

        private void add(@NonNull final Bytes bytes) {
            final var length = (int) bytes.length();
            if (offset + length > content.length) {
                final var oldContent = content;
                content = new byte[oldContent.length * 2];
                System.arraycopy(oldContent, 0, content, 0, offset);
            }
            bytes.getBytes(0, content, offset, length);
            offset += length;
        }
    }
}
