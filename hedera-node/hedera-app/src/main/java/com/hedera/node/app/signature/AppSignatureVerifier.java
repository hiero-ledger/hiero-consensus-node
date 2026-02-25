// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import static com.hedera.node.app.spi.signatures.SignatureVerifier.MessageType.KECCAK_256_HASH;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Implements the {@link SignatureVerifier} interface for verifying signatures using the same components
 * as used by the app workflows.
 */
@Singleton
public class AppSignatureVerifier implements SignatureVerifier {
    private static final int EDDSA_COUNT_INDEX = 0;
    private static final int ECDSA_COUNT_INDEX = 1;

    private final HederaConfig hederaConfig;
    private final SignatureExpander signatureExpander;
    private final com.hedera.node.app.signature.SignatureVerifier signatureVerifier;

    @Inject
    public AppSignatureVerifier(
            @NonNull final HederaConfig hederaConfig,
            @NonNull final SignatureExpander signatureExpander,
            @NonNull final com.hedera.node.app.signature.SignatureVerifier signatureVerifier) {
        this.hederaConfig = requireNonNull(hederaConfig);
        this.signatureExpander = requireNonNull(signatureExpander);
        this.signatureVerifier = requireNonNull(signatureVerifier);
    }

    @Override
    public boolean verifySignature(
            @NonNull final Key key,
            @NonNull final Bytes bytes,
            @NonNull final MessageType messageType,
            @NonNull final SignatureMap signatureMap,
            @Nullable final Function<Key, SimpleKeyStatus> simpleKeyVerifier) {
        requireNonNull(key);
        requireNonNull(bytes);
        requireNonNull(messageType);
        requireNonNull(signatureMap);
        if (messageType == KECCAK_256_HASH && bytes.length() != 32) {
            throw new IllegalArgumentException(
                    "Message type " + KECCAK_256_HASH + " must be 32 bytes long, got '" + bytes.toHex() + "'");
        }
        final Set<ExpandedSignaturePair> sigPairs = new HashSet<>();
        signatureExpander.expand(key, signatureMap.sigPair(), sigPairs);
        final var results = signatureVerifier.verify(bytes, sigPairs, messageType);
        final var verifier = new DefaultKeyVerifier(hederaConfig, results);
        return simpleKeyVerifier == null
                ? verifier.verificationFor(key).passed()
                // The "verification assistant" callback here receives a simple key and its cryptographic
                // verification (if present); we fall back to that verification if our simpleKeyVerifier
                // returns the ONLY_IF_CRYPTO_SIG_VALID decision
                : verifier.verificationFor(key, (k, v) -> switch (simpleKeyVerifier.apply(k)) {
                            case VALID -> true;
                            case INVALID -> false;
                            case ONLY_IF_CRYPTO_SIG_VALID -> v.passed();
                        })
                        .passed();
    }

    @Override
    public KeyCounts countSimpleKeys(@NonNull final Key key) {
        final int[] counts = new int[2];
        countSimpleKeys(key, counts);
        return new KeyCounts(counts[EDDSA_COUNT_INDEX], counts[ECDSA_COUNT_INDEX]);
    }

    private void countSimpleKeys(@NonNull final Key key, @NonNull final int[] counts) {
        switch (key.key().kind()) {
            case ED25519 -> counts[EDDSA_COUNT_INDEX]++;
            case ECDSA_SECP256K1 -> counts[ECDSA_COUNT_INDEX]++;
            case KEY_LIST -> key.keyListOrThrow().keys().forEach(k -> countSimpleKeys(k, counts));
            case THRESHOLD_KEY ->
                key.thresholdKeyOrThrow().keysOrThrow().keys().forEach(k -> countSimpleKeys(k, counts));
            default -> {
                // No-op, we only count these two key types
            }
        }
    }
}
