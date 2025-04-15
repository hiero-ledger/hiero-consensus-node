// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static com.hedera.hapi.node.base.SignaturePair.SignatureOneOfType.ECDSA_SECP256K1;
import static com.hedera.hapi.node.base.SignaturePair.SignatureOneOfType.ED25519;
import static com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType.KECCAK_256_HASH;
import static com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType.RAW;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.signature.ExpandedSignaturePair;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.signature.SignatureVerifier;
import com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.TransactionSignature;

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
        this(CryptographyProvider.getInstance());
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

        // Gather each TransactionSignature to send to the platform and the resulting SignatureVerificationFutures
        final var futures = HashMap.<Key, SignatureVerificationFuture>newHashMap(sigs.size());
        for (ExpandedSignaturePair sigPair : sigs) {
            final TransactionSignature txSig;
            final var kind = sigPair.sigPair().signature().kind();
            if (kind == ECDSA_SECP256K1) {
                Bytes message = signedBytes;
                if (messageType == RAW) {
                    message = MiscCryptoUtils.keccak256DigestOf(message);
                }
                txSig = new TransactionSignature(
                        message, sigPair.keyBytes(), sigPair.signature(), SignatureType.ECDSA_SECP256K1);
            } else if (kind == ED25519) {
                txSig = new TransactionSignature(
                        signedBytes, sigPair.keyBytes(), sigPair.signature(), SignatureType.ED25519);
            } else {
                throw new IllegalArgumentException("Unsupported signature type: " + kind);
            }
            cryptoEngine.verifySync(txSig);
            final SignatureVerificationFuture future =
                    new SignatureVerificationFutureImpl(sigPair.key(), sigPair.evmAlias(), txSig);
            futures.put(sigPair.key(), future);
        }

        return futures;
    }
}
