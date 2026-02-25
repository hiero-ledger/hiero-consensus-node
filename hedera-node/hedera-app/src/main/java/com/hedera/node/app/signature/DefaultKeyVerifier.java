// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import static com.hedera.node.app.signature.impl.SignatureVerificationImpl.failedVerification;
import static com.hedera.node.app.signature.impl.SignatureVerificationImpl.passedVerification;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.node.app.hapi.utils.keys.KeyComparator;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base implementation of {@link AppKeyVerifier}
 */
public class DefaultKeyVerifier implements AppKeyVerifier {
    private static final Logger logger = LogManager.getLogger(DefaultKeyVerifier.class);

    private static final Comparator<Key> KEY_COMPARATOR = new KeyComparator();
    private final long timeout;
    private final Map<Key, SignatureVerificationFuture> keyVerifications;

    /**
     * Creates a {@link DefaultKeyVerifier}
     *
     * @param config configuration for the node
     * @param keyVerifications A {@link Map} with all data to verify signatures
     */
    public DefaultKeyVerifier(
            @NonNull final HederaConfig config, @NonNull final Map<Key, SignatureVerificationFuture> keyVerifications) {
        this.timeout = requireNonNull(config, "config must not be null").workflowVerificationTimeoutMS();
        this.keyVerifications = requireNonNull(keyVerifications, "keyVerifications must not be null");
    }

    @Override
    @NonNull
    public SignatureVerification verificationFor(@NonNull final Key key) {
        requireNonNull(key, "key must not be null");
        // FUTURE: Cache the results of this method, if it is usually called several times
        return resolveFuture(verificationFutureFor(key), () -> failedVerification(key));
    }

    @Override
    @NonNull
    public SignatureVerification verificationFor(
            @NonNull final Key key, @NonNull final VerificationAssistant callback) {
        requireNonNull(key, "key must not be null");
        requireNonNull(callback, "callback must not be null");

        return switch (key.key().kind()) {
            case ED25519, ECDSA_SECP256K1 -> {
                final var result = resolveFuture(keyVerifications.get(key), () -> failedVerification(key));
                yield callback.test(key, result) ? passedVerification(key) : failedVerification(key);
            }
            case KEY_LIST -> {
                final var keys = key.keyListOrThrow().keys();
                var failed = keys.isEmpty();
                for (final var childKey : keys) {
                    failed |= verificationFor(childKey, callback).failed();
                }
                yield failed ? failedVerification(key) : passedVerification(key);
            }
            case THRESHOLD_KEY -> {
                final var thresholdKey = key.thresholdKeyOrThrow();
                final var keyList = thresholdKey.keysOrElse(KeyList.DEFAULT);
                final var keys = keyList.keys();
                final var threshold = thresholdKey.threshold();
                final var clampedThreshold = Math.max(1, Math.min(threshold, keys.size()));
                var passed = 0;
                for (final var childKey : keys) {
                    if (verificationFor(childKey, callback).passed()) {
                        passed++;
                    }
                }
                yield passed >= clampedThreshold ? passedVerification(key) : failedVerification(key);
            }
            case CONTRACT_ID, DELEGATABLE_CONTRACT_ID, ECDSA_384, RSA_3072, UNSET -> {
                final var failure = failedVerification(key);
                yield callback.test(key, failure) ? passedVerification(key) : failure;
            }
        };
    }

    @Override
    @NonNull
    public SignatureVerification verificationFor(@NonNull final Bytes evmAlias) {
        requireNonNull(evmAlias, "evmAlias must not be null");
        // FUTURE: Cache the results of this method, if it is usually called several times
        if (evmAlias.length() == 20) {
            for (final var result : keyVerifications.values()) {
                final var account = result.evmAlias();
                if (account != null && evmAlias.matchesPrefix(account)) {
                    return resolveFuture(result, () -> failedVerification(evmAlias));
                }
            }
        }
        return failedVerification(evmAlias);
    }

    @Override
    public int numSignaturesVerified() {
        return keyVerifications.size();
    }

    @Override
    public SortedSet<Key> authorizingSimpleKeys() {
        return keyVerifications.entrySet().stream()
                .map(entry -> new AbstractMap.SimpleImmutableEntry<>(
                        entry.getKey(), resolveFuture(entry.getValue(), () -> failedVerification(entry.getKey()))))
                .filter(e -> e.getValue().passed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(() -> new TreeSet<>(KEY_COMPARATOR)));
    }

    /**
     * Get a {@link Future<SignatureVerification>} for the given key.
     *
     * <p>If the key is a cryptographic key (i.e. a basic key like ED25519 or ECDSA_SECP256K1), and the cryptographic
     * key was in the signature map of the transaction, then a {@link Future} will be returned that will yield the
     * {@link SignatureVerification} for that key. If there was no such cryptographic key in the signature map, then
     * a completed, failed future is returned.
     *
     * <p>If the key is a key list, then a {@link Future} will be returned that aggregates the results of each key in
     * the key list, possibly nested.
     *
     * <p>If the key is a threshold key, then a {@link Future} will be returned that aggregates the results of each key
     * in the threshold key, possibly nested, based on the threshold for that key.
     *
     * @param key The key to check on the verification results for.
     * @return A {@link Future} that will yield the {@link SignatureVerification} for the given key.
     */
    @NonNull
    private Future<SignatureVerification> verificationFutureFor(@NonNull final Key key) {
        return switch (key.key().kind()) {
            case ED25519, ECDSA_SECP256K1 -> {
                final var result = keyVerifications.get(key);
                yield result == null ? completedFuture(failedVerification(key)) : result;
            }
            case KEY_LIST -> {
                final var keys = key.keyListOrThrow().keys();
                yield verificationFutureFor(key, keys, 0);
            }
            case THRESHOLD_KEY -> {
                final var thresholdKey = key.thresholdKeyOrThrow();
                final var keyList = thresholdKey.keysOrElse(KeyList.DEFAULT);
                final var keys = keyList.keys();
                final var threshold = thresholdKey.threshold();
                final var clampedThreshold = Math.max(1, Math.min(threshold, keys.size()));
                yield verificationFutureFor(key, keys, keys.size() - clampedThreshold);
            }
            case CONTRACT_ID, DELEGATABLE_CONTRACT_ID, ECDSA_384, RSA_3072, UNSET ->
                completedFuture(failedVerification(key));
        };
    }

    /**
     * Utility method that converts the keys into a list of {@link Future<SignatureVerification>} and then aggregates
     * them into a single {@link Future<SignatureVerification>}.
     *
     * @param key The key that is being verified.
     * @param keys The sub-keys of the key being verified
     * @param numCanFail The number of sub-keys that can fail verification before the key itself does
     * @return A {@link Future<SignatureVerification>}
     */
    @NonNull
    private Future<SignatureVerification> verificationFutureFor(
            @NonNull final Key key, @NonNull final List<Key> keys, final int numCanFail) {
        // If there are no keys, then we always fail. There must be at least one key in a key list or threshold key
        // for it to be a valid key and to pass any form of verification.
        if (keys.isEmpty() || numCanFail < 0) return completedFuture(failedVerification(key));
        final var futures = keys.stream().map(this::verificationFutureFor).toList();
        return new CompoundSignatureVerificationFuture(key, null, futures, numCanFail);
    }

    @NonNull
    private SignatureVerification resolveFuture(
            @Nullable final Future<SignatureVerification> future,
            @NonNull final Supplier<SignatureVerification> fallback) {
        if (future == null) {
            return fallback.get();
        }
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for signature verification", e);
        } catch (final TimeoutException e) {
            logger.warn("Timed out while waiting for signature verification, probably going to ISS soon", e);
        } catch (final ExecutionException e) {
            logger.error("An unexpected exception was thrown while waiting for SignatureVerification", e);
        }
        return fallback.get();
    }
}
