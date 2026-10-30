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
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.SortedMap;
import org.hiero.base.crypto.CryptoUtils;

/**
 * Default implementation of {@link HintsLibrary}.
 */
public class HintsLibraryImpl implements HintsLibrary {
    private static final SecureRandom RANDOM = CryptoUtils.getNonDetRandom();
    private static final HintsLibraryBridge BRIDGE = HintsLibraryBridge.getInstance();
    private static final int MIN_AGGREGATION_KEY_LENGTH = 49;
    // 3.23.0 keys the bounded native CRS/AK caches by their input bytes. Preparation
    // and signing share this bridge without a Java cache-switch lock or extra native images.

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
        final var hints = BRIDGE.computeHints(crs.toByteArray(), blsPrivateKey.toByteArray(), partyId, n);
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
        return BRIDGE.validateHintsKey(crs.toByteArray(), hintsKey.toByteArray(), partyId, n);
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
        return BRIDGE.preprocess(crs.toByteArray(), parties, hintsPublicKeys, weightsArray, n);
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
        return BRIDGE.verifyBls(signature.toByteArray(), message.toByteArray(), aggregationKey.toByteArray(), partyId);
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
        final var aggregatedSignature = BRIDGE.aggregateSignatures(
                crs.toByteArray(), aggregationKey.toByteArray(), verificationKey.toByteArray(), parties, signatures);
        return aggregatedSignature == null ? null : Bytes.wrap(aggregatedSignature);
    }

    @Override
    public void resetCache() {
        BRIDGE.resetCache();
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
