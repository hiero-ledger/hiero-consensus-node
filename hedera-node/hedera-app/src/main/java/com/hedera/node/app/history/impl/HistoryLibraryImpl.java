// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.Aggregate;
import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.R1;
import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.R2;
import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.R3;
import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.wraps.Proof;
import com.hedera.cryptography.wraps.SchnorrKeys;
import com.hedera.cryptography.wraps.WRAPSLibraryBridge;
import com.hedera.cryptography.wraps.WRAPSVerificationKey;
import com.hedera.node.app.history.HistoryLibrary;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.StringJoiner;

/**
 * Default implementation of the {@link HistoryLibrary}.
 */
public class HistoryLibraryImpl implements HistoryLibrary {
    private static final int SCHNORR_PUBLIC_KEY_LENGTH = (int) HistoryLibrary.MISSING_SCHNORR_KEY.length();

    public static final SplittableRandom RANDOM = new SplittableRandom();
    public static final WRAPSLibraryBridge WRAPS = WRAPSLibraryBridge.getInstance();

    @Override
    public byte[] wrapsVerificationKey() {
        return WRAPSVerificationKey.getCurrentKey();
    }

    @Override
    public SchnorrKeys newSchnorrKeyPair() {
        final var seed = new byte[WRAPSLibraryBridge.ENTROPY_SIZE];
        RANDOM.nextBytes(seed);
        return WRAPS.generateSchnorrKeys(seed);
    }

    @Override
    public byte[] hashAddressBook(@NonNull final AddressBook addressBook) {
        requireNonNull(addressBook);
        final var hash = WRAPS.hashAddressBook(addressBook.publicKeys(), addressBook.weights(), addressBook.nodeIds());
        if (hash == null) {
            throw new IllegalArgumentException(hashAddressBookFailureDetails(addressBook));
        }
        return hash;
    }

    @Override
    public byte[] computeWrapsMessage(
            @NonNull final AddressBook addressBook, @NonNull final byte[] hintsVerificationKey) {
        requireNonNull(addressBook);
        requireNonNull(hintsVerificationKey);
        return WRAPS.formatRotationMessage(
                addressBook.publicKeys(), addressBook.weights(), addressBook.nodeIds(), hintsVerificationKey);
    }

    @Override
    public byte[] runWrapsPhaseR1(
            @NonNull final byte[] entropy, @NonNull final byte[] message, @NonNull final byte[] privateKey) {
        requireNonNull(entropy);
        requireNonNull(message);
        requireNonNull(privateKey);
        return WRAPS.runSigningProtocolPhase(
                R1,
                entropy,
                message,
                privateKey,
                new byte[0][],
                null,
                null,
                null,
                new byte[0][],
                new byte[0][],
                new byte[0][]);
    }

    @Override
    public byte[] runWrapsPhaseR2(
            @NonNull final byte[] entropy,
            @NonNull final byte[] message,
            @NonNull final byte[][] r1Messages,
            @NonNull final byte[] privateKey,
            @NonNull final AddressBook currentBook,
            @NonNull final Set<Long> r1NodeIds) {
        requireNonNull(entropy);
        requireNonNull(message);
        requireNonNull(privateKey);
        requireNonNull(r1Messages);
        requireNonNull(currentBook);
        requireNonNull(r1NodeIds);
        return WRAPS.runSigningProtocolPhase(
                R2,
                entropy,
                message,
                privateKey,
                currentBook.publicKeys(),
                currentBook.weights(),
                currentBook.nodeIds(),
                currentBook.signersMask(r1NodeIds),
                r1Messages,
                new byte[0][],
                new byte[0][]);
    }

    @Override
    public byte[] runWrapsPhaseR3(
            @NonNull final byte[] entropy,
            @NonNull final byte[] message,
            @NonNull final byte[][] r1Messages,
            @NonNull final byte[][] r2Messages,
            @NonNull final byte[] privateKey,
            @NonNull final AddressBook currentBook,
            @NonNull final Set<Long> r1NodeIds) {
        requireNonNull(entropy);
        requireNonNull(message);
        requireNonNull(privateKey);
        requireNonNull(r1Messages);
        requireNonNull(r2Messages);
        requireNonNull(currentBook);
        requireNonNull(r1NodeIds);
        return WRAPS.runSigningProtocolPhase(
                R3,
                entropy,
                message,
                privateKey,
                currentBook.publicKeys(),
                currentBook.weights(),
                currentBook.nodeIds(),
                currentBook.signersMask(r1NodeIds),
                r1Messages,
                r2Messages,
                new byte[0][]);
    }

    @Override
    public byte[] runAggregationPhase(
            @NonNull final byte[] message,
            @NonNull final byte[][] r1Messages,
            @NonNull final byte[][] r2Messages,
            @NonNull final byte[][] r3Messages,
            @NonNull final AddressBook currentBook,
            @NonNull final Set<Long> r1NodeIds) {
        requireNonNull(message);
        requireNonNull(r1Messages);
        requireNonNull(r2Messages);
        requireNonNull(r3Messages);
        requireNonNull(currentBook);
        requireNonNull(r1NodeIds);
        return WRAPS.runSigningProtocolPhase(
                Aggregate,
                null,
                message,
                null,
                currentBook.publicKeys(),
                currentBook.weights(),
                currentBook.nodeIds(),
                currentBook.signersMask(r1NodeIds),
                r1Messages,
                r2Messages,
                r3Messages);
    }

    @Override
    public boolean verifyAggregateSignature(
            @NonNull final byte[] message,
            @NonNull final long[] nodeIds,
            @NonNull final byte[][] publicKeys,
            @NonNull final long[] weights,
            @NonNull final byte[] signature) {
        requireNonNull(message);
        requireNonNull(publicKeys);
        requireNonNull(signature);
        requireNonNull(nodeIds);
        requireNonNull(weights);
        return WRAPS.verifySignature(publicKeys, weights, nodeIds, message, signature);
    }

    @Override
    public Proof constructGenesisWrapsProof(
            @NonNull final byte[] genesisAddressBookHash,
            @NonNull final byte[] genesisHintsVerificationKey,
            @NonNull final byte[] aggregatedSignature,
            @NonNull final Set<Long> signers,
            @NonNull final AddressBook addressBook) {
        requireNonNull(genesisAddressBookHash);
        requireNonNull(genesisHintsVerificationKey);
        requireNonNull(aggregatedSignature);
        requireNonNull(signers);
        requireNonNull(addressBook);
        return WRAPS.constructWrapsProof(
                genesisAddressBookHash,
                addressBook.publicKeys(),
                addressBook.weights(),
                addressBook.nodeIds(),
                addressBook.publicKeys(),
                addressBook.weights(),
                addressBook.nodeIds(),
                null,
                genesisHintsVerificationKey,
                aggregatedSignature);
    }

    @Override
    public Proof constructIncrementalWrapsProof(
            @NonNull final byte[] genesisAddressBookHash,
            @NonNull final byte[] sourceProof,
            @NonNull final AddressBook sourceAddressBook,
            @NonNull final AddressBook targetAddressBook,
            @NonNull final byte[] targetHintsVerificationKey,
            @NonNull final byte[] aggregatedSignature,
            @NonNull final Set<Long> signers) {
        requireNonNull(genesisAddressBookHash);
        requireNonNull(sourceProof);
        requireNonNull(sourceAddressBook);
        requireNonNull(targetAddressBook);
        requireNonNull(targetHintsVerificationKey);
        requireNonNull(aggregatedSignature);
        requireNonNull(signers);
        return WRAPS.constructWrapsProof(
                genesisAddressBookHash,
                sourceAddressBook.publicKeys(),
                sourceAddressBook.weights(),
                sourceAddressBook.nodeIds(),
                targetAddressBook.publicKeys(),
                targetAddressBook.weights(),
                targetAddressBook.nodeIds(),
                sourceProof,
                targetHintsVerificationKey,
                aggregatedSignature);
    }

    @Override
    public boolean wrapsProverReady() {
        return WRAPSLibraryBridge.isProofSupported();
    }

    private static String hashAddressBookFailureDetails(@NonNull final AddressBook addressBook) {
        final var publicKeys = addressBook.publicKeys();
        final var weights = addressBook.weights();
        final var nodeIds = addressBook.nodeIds();
        final var publicKeyCount = publicKeys.length;
        final var weightCount = weights.length;
        final var nodeIdCount = nodeIds.length;
        final boolean publicKeyCountWithinMax = publicKeyCount <= WRAPSLibraryBridge.MAX_AB_SIZE;
        final boolean publicKeyCountMatchesWeights = publicKeyCount == weightCount;
        final boolean publicKeyCountMatchesNodeIds = publicKeyCount == nodeIdCount;
        final var weightValidation = describeWeightValidation(weights);
        final var publicKeyValidation = describePublicKeyValidation(publicKeys);
        final boolean bridgePrechecksPassed = publicKeyCountWithinMax
                && publicKeyCountMatchesWeights
                && publicKeyCountMatchesNodeIds
                && weightValidation.valid()
                && publicKeyValidation.valid();
        return "WRAPS.hashAddressBook() returned null. Validation details: "
                + "schnorrPublicKeys.length=" + publicKeyCount
                + ", weights.length=" + weightCount
                + ", nodeIds.length=" + nodeIdCount
                + ", schnorrPublicKeys.length<=" + WRAPSLibraryBridge.MAX_AB_SIZE + "=" + publicKeyCountWithinMax
                + ", schnorrPublicKeys.length==weights.length=" + publicKeyCountMatchesWeights
                + ", schnorrPublicKeys.length==nodeIds.length=" + publicKeyCountMatchesNodeIds
                + ", validateWeightsSum=" + weightValidation.valid()
                + " (" + weightValidation.details() + ")"
                + ", validateSchnorrPublicKeys=" + publicKeyValidation.valid()
                + " (" + publicKeyValidation.details() + ")"
                + ", bridgePrechecksPassed=" + bridgePrechecksPassed;
    }

    private static ValidationResult describeWeightValidation(@NonNull final long[] weights) {
        boolean allNonNegative = true;
        boolean overflowed = false;
        long sum = 0;
        final var negativeWeights = new StringJoiner(", ", "[", "]");
        for (int i = 0; i < weights.length; i++) {
            final var weight = weights[i];
            if (weight < 0) {
                allNonNegative = false;
                negativeWeights.add("#" + i + "=" + weight);
            }
            if (!overflowed) {
                try {
                    sum = Math.addExact(sum, weight);
                } catch (final ArithmeticException e) {
                    overflowed = true;
                }
            }
        }
        final boolean valid = allNonNegative && !overflowed;
        return new ValidationResult(
                valid,
                "allWeightsNonNegative="
                        + allNonNegative
                        + ", negativeWeights="
                        + negativeWeights
                        + ", sumOverflowed="
                        + overflowed
                        + ", sum="
                        + (overflowed ? "overflow" : sum));
    }

    private static ValidationResult describePublicKeyValidation(@NonNull final byte[][] publicKeys) {
        boolean allNonNull = true;
        boolean allExpectedLength = true;
        final var details = new StringJoiner(", ", "[", "]");
        for (int i = 0; i < publicKeys.length; i++) {
            final var publicKey = publicKeys[i];
            final boolean nonNull = publicKey != null;
            final var length = nonNull ? Integer.toString(publicKey.length) : "null";
            final boolean expectedLength = nonNull && publicKey.length == SCHNORR_PUBLIC_KEY_LENGTH;
            allNonNull &= nonNull;
            allExpectedLength &= expectedLength;
            details.add("#"
                    + i
                    + "(nonNull="
                    + nonNull
                    + ", length="
                    + length
                    + ", length=="
                    + SCHNORR_PUBLIC_KEY_LENGTH
                    + "="
                    + expectedLength
                    + ")");
        }
        final boolean valid = allNonNull && allExpectedLength;
        return new ValidationResult(
                valid,
                "allPublicKeysNonNull="
                        + allNonNull
                        + ", allPublicKeysLength=="
                        + SCHNORR_PUBLIC_KEY_LENGTH
                        + "="
                        + allExpectedLength
                        + ", publicKeyDetails="
                        + details);
    }

    private record ValidationResult(boolean valid, @NonNull String details) {}
}
