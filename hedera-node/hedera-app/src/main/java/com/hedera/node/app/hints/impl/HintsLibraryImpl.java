// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.hints.AggregationAndVerificationKeys;
import com.hedera.cryptography.hints.HintsLibraryBridge;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Map;
import java.util.SortedMap;
import java.util.SplittableRandom;

/**
 * Default implementation of {@link HintsLibrary}.
 */
public class HintsLibraryImpl implements HintsLibrary {
    private static final SplittableRandom RANDOM = new SplittableRandom();
    private static final HintsLibraryBridge BRIDGE = HintsLibraryBridge.getInstance();

    @Override
    public Bytes newCrs(final int n) {
        return Bytes.wrap(BRIDGE.initCRS(n));
    }

    @Override
    public Bytes updateCrs(@NonNull final Bytes crs, @NonNull final Bytes entropy) {
        requireNonNull(crs);
        requireNonNull(entropy);
        return Bytes.wrap(BRIDGE.updateCRS(crs.toByteArray(), entropy.toByteArray()));
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
        final var hints = BRIDGE.computeHints(crs.toByteArray(), blsPrivateKey.toByteArray(), partyId, n);
        return hints == null ? null : Bytes.wrap(hints);
    }

    @Override
    public boolean validateHintsKey(
            @NonNull final Bytes crs, @NonNull final Bytes hintsKey, final int partyId, final int n) {
        requireNonNull(crs);
        requireNonNull(hintsKey);
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
        if (!hintsKeys.keySet().equals(weights.keySet())) {
            throw new IllegalArgumentException("The number of hint keys and weights must be the same");
        }
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
        return BRIDGE.verifyBls(
                crs.toByteArray(),
                signature.toByteArray(),
                message.toByteArray(),
                aggregationKey.toByteArray(),
                partyId);
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
