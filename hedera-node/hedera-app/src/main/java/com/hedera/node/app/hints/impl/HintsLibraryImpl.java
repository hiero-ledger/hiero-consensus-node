// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.hints.HintsLibrary.isSupportedPartySize;
import static com.hedera.node.app.hints.HintsLibrary.isValidPartyId;
import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.hints.AggregationAndVerificationKeys;
import com.hedera.cryptography.hints.HintsLibraryBridge;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import org.hiero.base.crypto.CryptoUtils;

/**
 * Default implementation of {@link HintsLibrary}.
 */
public class HintsLibraryImpl implements HintsLibrary {
    private static final SecureRandom RANDOM = CryptoUtils.getNonDetRandom();
    private static final HintsLibraryBridge BRIDGE = HintsLibraryBridge.getInstance();
    private static final int MIN_AGGREGATION_KEY_LENGTH = 49;
    // The original native image is reserved for signing. Separate native images own the CRS
    // caches used by key work and preprocessing, so neither can hold up ACTIVE signing.
    private static final ReentrantReadWriteLock SIGNING_CACHE_LOCK = new ReentrantReadWriteLock(true);
    private static @Nullable Bytes cachedCrs;
    private static @Nullable Bytes cachedAggregationKey;

    private final Supplier<IsolatedHintsLibrary> keyLibrary;
    private final Supplier<IsolatedHintsLibrary> preprocessingLibrary;

    // Load only two additional native images for the lifetime of the JVM, even when multiple
    // HintsLibraryImpl instances are created. A Java adapter alone would still share native caches.
    // Independent holders defer each image until its first operation and keep initialization
    // failures in one preparation path from poisoning the other or ordinary construction.
    private static final class KeyLibraryHolder {
        private static final IsolatedHintsLibrary INSTANCE = new IsolatedHintsLibrary();
    }

    private static final class PreprocessingLibraryHolder {
        private static final IsolatedHintsLibrary INSTANCE = new IsolatedHintsLibrary();
    }

    public HintsLibraryImpl() {
        this(() -> KeyLibraryHolder.INSTANCE, () -> PreprocessingLibraryHolder.INSTANCE);
    }

    HintsLibraryImpl(
            final Supplier<IsolatedHintsLibrary> keyLibrary,
            final Supplier<IsolatedHintsLibrary> preprocessingLibrary) {
        this.keyLibrary = requireNonNull(keyLibrary);
        this.preprocessingLibrary = requireNonNull(preprocessingLibrary);
    }

    public static final int VK_LENGTH = 1096;
    public static final int SIGNATURE_LENGTH = HintsLibraryBridge.AGGREGATE_SIGNATURE_LENGTH_BYTES;

    private void requireExactCapacity(@NonNull final Bytes crs, final int n) {
        if (crsPartySize(crs) != n) {
            throw new IllegalArgumentException(
                    "hinTS party count must equal CRS capacity; implicit resizing is forbidden");
        }
    }

    @Override
    public Bytes newCrs(final short n) {
        if (!isSupportedPartySize(n)) {
            throw new IllegalArgumentException("Unsupported hinTS CRS party count: " + n);
        }
        // In 3.20.1, CRS ceremony operations deserialize their own inputs and never access
        // the signing CRS/AK caches. They must not acquire the signing cache guard.
        return Bytes.wrap(BRIDGE.initCRS(n));
    }

    @Override
    public Bytes updateCrs(@NonNull final Bytes crs, @NonNull final Bytes entropy) {
        requireNonNull(crs);
        requireNonNull(entropy);
        final var updatedCrs = BRIDGE.updateCRS(crs.toByteArray(), entropy.toByteArray());
        return updatedCrs == null ? null : Bytes.wrap(updatedCrs);
    }

    @Override
    public boolean verifyCrsUpdate(@NonNull Bytes oldCrs, @NonNull Bytes newCrs, @NonNull Bytes proof) {
        requireNonNull(oldCrs);
        requireNonNull(newCrs);
        requireNonNull(proof);
        return BRIDGE.verifyCRS(oldCrs.toByteArray(), newCrs.toByteArray(), proof.toByteArray());
    }

    @Override
    public Bytes newBlsPrivateKey() {
        final byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        final var key = BRIDGE.generateSecretKey(randomBytes);
        return key == null ? null : Bytes.wrap(key);
    }

    @Override
    public Bytes computeHints(
            @NonNull final Bytes crs, @NonNull final Bytes blsPrivateKey, final int partyId, final int n) {
        requireNonNull(blsPrivateKey);
        requireExactCapacity(crs, n);
        requireValidPartyId(partyId, n);
        final var hints = keyLibrary.get().computeHints(crs.toByteArray(), blsPrivateKey.toByteArray(), partyId, n);
        return hints == null ? null : Bytes.wrap(hints);
    }

    @Override
    public boolean validateHintsKey(
            @NonNull final Bytes crs, @NonNull final Bytes hintsKey, final int partyId, final int n) {
        requireNonNull(crs);
        requireNonNull(hintsKey);
        requireExactCapacity(crs, n);
        if (!isValidPartyId(partyId, n)) {
            return false;
        }
        return keyLibrary.get().validateHintsKey(crs.toByteArray(), hintsKey.toByteArray(), partyId, n);
    }

    @Override
    public AggregationAndVerificationKeys preprocess(
            @NonNull final Bytes crs,
            @NonNull final SortedMap<Integer, Bytes> hintsKeys,
            @NonNull final SortedMap<Integer, Long> weights,
            final int n) {
        requireNonNull(crs);
        requireNonNull(hintsKeys);
        requireNonNull(weights);
        requireExactCapacity(crs, n);
        if (!hintsKeys.keySet().equals(weights.keySet())) {
            throw new IllegalArgumentException("The number of hint keys and weights must be the same");
        }
        hintsKeys.keySet().forEach(partyId -> requireValidPartyId(partyId, n));
        final int[] parties =
                hintsKeys.keySet().stream().mapToInt(Integer::intValue).toArray();
        final byte[][] hintsPublicKeys = Arrays.stream(parties)
                .mapToObj(hintsKeys::get)
                .map(Bytes::toByteArray)
                .toArray(byte[][]::new);
        final long[] weightsArray =
                Arrays.stream(parties).mapToLong(weights::get).toArray();
        return preprocessingLibrary.get().preprocess(crs.toByteArray(), parties, hintsPublicKeys, weightsArray, n);
    }

    @Override
    public Bytes signBls(@NonNull final Bytes message, @NonNull final Bytes privateKey) {
        requireNonNull(message);
        requireNonNull(privateKey);
        final var signature = BRIDGE.signBls(message.toByteArray(), privateKey.toByteArray());
        return signature == null ? null : Bytes.wrap(signature);
    }

    @Override
    public boolean verifyBls(
            @NonNull final Bytes crs,
            @NonNull final Bytes signature,
            @NonNull final Bytes message,
            @NonNull final Bytes aggregationKey,
            int partyId) {
        requireNonNull(crs);
        requireNonNull(signature);
        requireNonNull(message);
        requireNonNull(aggregationKey);
        if (aggregationKey.length() < MIN_AGGREGATION_KEY_LENGTH || !isValidPartyId(partyId, crsPartySize(crs))) {
            return false;
        }
        return withNativeCache(
                crs,
                aggregationKey,
                () -> BRIDGE.verifyBls(
                        signature.toByteArray(), message.toByteArray(), aggregationKey.toByteArray(), partyId));
    }

    @Override
    public Bytes aggregateSignatures(
            @NonNull final Bytes crs,
            @NonNull final Bytes aggregationKey,
            @NonNull final Bytes verificationKey,
            @NonNull final Map<Integer, Bytes> partialSignatures) {
        requireNonNull(crs);
        requireNonNull(aggregationKey);
        requireNonNull(verificationKey);
        requireNonNull(partialSignatures);
        if (aggregationKey.length() < MIN_AGGREGATION_KEY_LENGTH) {
            return null;
        }
        final int numParties = crsPartySize(crs);
        if (partialSignatures.keySet().stream().anyMatch(partyId -> !isValidPartyId(partyId, numParties))) {
            return null;
        }
        final int[] parties =
                partialSignatures.keySet().stream().mapToInt(Integer::intValue).toArray();
        final byte[][] signatures = Arrays.stream(parties)
                .mapToObj(party -> partialSignatures.get(party).toByteArray())
                .toArray(byte[][]::new);
        final var aggregatedSignature = withNativeCache(
                crs,
                aggregationKey,
                () -> BRIDGE.aggregateSignatures(
                        crs.toByteArray(),
                        aggregationKey.toByteArray(),
                        verificationKey.toByteArray(),
                        parties,
                        signatures));
        return aggregatedSignature == null ? null : Bytes.wrap(aggregatedSignature);
    }

    @Override
    public void resetCache() {
        final var lock = SIGNING_CACHE_LOCK.writeLock();
        lock.lock();
        try {
            BRIDGE.resetCache();
            cachedCrs = null;
            cachedAggregationKey = null;
        } finally {
            lock.unlock();
        }
    }

    private static <T> T withNativeCache(
            @Nullable final Bytes crs, @Nullable final Bytes aggregationKey, final Supplier<T> operation) {
        final var readLock = SIGNING_CACHE_LOCK.readLock();
        readLock.lock();
        try {
            if (cacheMatches(crs, aggregationKey)) {
                return operation.get();
            }
        } finally {
            readLock.unlock();
        }
        final var writeLock = SIGNING_CACHE_LOCK.writeLock();
        writeLock.lock();
        try {
            if (!cacheMatches(crs, aggregationKey)) {
                BRIDGE.resetCache();
                cachedCrs = crs;
                cachedAggregationKey = aggregationKey;
            }
            return operation.get();
        } finally {
            writeLock.unlock();
        }
    }

    private static boolean cacheMatches(@Nullable final Bytes crs, @Nullable final Bytes aggregationKey) {
        return (crs == null || crs.equals(cachedCrs))
                && (aggregationKey == null || aggregationKey.equals(cachedAggregationKey));
    }

    private static void requireValidPartyId(final int partyId, final int n) {
        if (!isValidPartyId(partyId, n)) {
            throw new IllegalArgumentException("Unsupported hinTS party ID " + partyId + " for capacity " + n);
        }
    }

    @Override
    public boolean verifyAggregate(
            @NonNull final Bytes signature,
            @NonNull final Bytes message,
            @NonNull final Bytes verificationKey,
            final long thresholdNumerator,
            long thresholdDenominator) {
        requireNonNull(signature);
        requireNonNull(message);
        requireNonNull(verificationKey);
        return BRIDGE.verifyAggregate(
                signature.toByteArray(),
                message.toByteArray(),
                verificationKey.toByteArray(),
                thresholdNumerator,
                thresholdDenominator);
    }
}
