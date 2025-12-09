// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.Aggregate;
import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.R1;
import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.R2;
import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.R3;
import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.rpm.SigningAndVerifyingSchnorrKeys;
import com.hedera.cryptography.wraps.Proof;
import com.hedera.cryptography.wraps.WRAPSLibraryBridge;
import com.hedera.node.app.history.HistoryLibrary;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Set;
import java.util.SplittableRandom;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.utility.CommonUtils;

/**
 * Default implementation of the {@link HistoryLibrary}.
 */
public class HistoryLibraryImpl implements HistoryLibrary {
    private static final Logger logger = LogManager.getLogger(HistoryLibraryImpl.class);

    public static final SplittableRandom RANDOM = new SplittableRandom();
    public static final WRAPSLibraryBridge WRAPS = WRAPSLibraryBridge.getInstance();

    @Override
    public SigningAndVerifyingSchnorrKeys newSchnorrKeyPair() {
        final var seed = new byte[32];
        RANDOM.nextBytes(seed);
        final var wrapsKeys = WRAPS.generateSchnorrKeys(seed);
        return new SigningAndVerifyingSchnorrKeys(wrapsKeys.privateKey(), wrapsKeys.publicKey());
    }

    @Override
    public byte[] hashAddressBook(@NonNull final AddressBook addressBook) {
        requireNonNull(addressBook);
        return WRAPS.hashAddressBook(addressBook.publicKeys(), addressBook.weights());
    }

    @Override
    public byte[] computeWrapsMessage(
            @NonNull final AddressBook addressBook, @NonNull final byte[] hintsVerificationKey) {
        requireNonNull(addressBook);
        requireNonNull(hintsVerificationKey);
        return WRAPS.formatRotationMessage(addressBook.publicKeys(), addressBook.weights(), hintsVerificationKey);
    }

    @Override
    public byte[] runWrapsPhaseR1(
            @NonNull final byte[] entropy, @NonNull final byte[] message, @NonNull final byte[] privateKey) {
        requireNonNull(entropy);
        requireNonNull(message);
        requireNonNull(privateKey);
        return WRAPS.runSigningProtocolPhase(
                R1, entropy, message, privateKey, new byte[0][], new byte[0][], new byte[0][], new byte[0][]);
    }

    @Override
    public byte[] runWrapsPhaseR2(
            @NonNull byte[] entropy,
            @NonNull byte[] message,
            @NonNull byte[][] r1Messages,
            @NonNull byte[] privateKey,
            @NonNull byte[][] publicKeys) {
        requireNonNull(entropy);
        requireNonNull(message);
        requireNonNull(privateKey);
        requireNonNull(r1Messages);
        requireNonNull(publicKeys);
        return WRAPS.runSigningProtocolPhase(
                R2, entropy, message, privateKey, publicKeys, r1Messages, new byte[0][], new byte[0][]);
    }

    @Override
    public byte[] runWrapsPhaseR3(
            @NonNull byte[] entropy,
            @NonNull byte[] message,
            @NonNull byte[][] r1Messages,
            @NonNull byte[][] r2Messages,
            @NonNull byte[] privateKey,
            @NonNull byte[][] publicKeys) {
        requireNonNull(entropy);
        requireNonNull(message);
        requireNonNull(privateKey);
        requireNonNull(r1Messages);
        requireNonNull(r2Messages);
        requireNonNull(publicKeys);
        return WRAPS.runSigningProtocolPhase(
                R3, entropy, message, privateKey, publicKeys, r1Messages, r2Messages, new byte[0][]);
    }

    @Override
    public byte[] runAggregationPhase(
            @NonNull final byte[] message,
            @NonNull final byte[][] r1Messages,
            @NonNull final byte[][] r2Messages,
            @NonNull final byte[][] r3Messages,
            @NonNull final byte[][] publicKeys) {
        requireNonNull(message);
        requireNonNull(r1Messages);
        requireNonNull(r2Messages);
        requireNonNull(r3Messages);
        requireNonNull(publicKeys);
        return WRAPS.runSigningProtocolPhase(
                Aggregate, null, message, null, publicKeys, r1Messages, r2Messages, r3Messages);
    }

    @Override
    public boolean verifyAggregateSignature(
            @NonNull final byte[] message, @NonNull final byte[][] publicKeys, @NonNull final byte[] signature) {
        requireNonNull(message);
        requireNonNull(publicKeys);
        requireNonNull(signature);
        return WRAPS.verifySignature(publicKeys, message, signature);
    }

    @Override
    public Proof constructGenesisWrapsProof(
            @NonNull final byte[] genesisAddressBookHash,
            @NonNull final byte[] aggregatedSignature,
            @NonNull final Set<Long> signers,
            @NonNull final AddressBook addressBook) {
        requireNonNull(genesisAddressBookHash);
        requireNonNull(aggregatedSignature);
        requireNonNull(signers);
        requireNonNull(addressBook);

        final var prevSchnorrPublicKeys = addressBook.publicKeys();
        final var prevWeights = addressBook.weights();
        final var nextSchnorrPublicKeys = addressBook.publicKeys();
        final var nextWeights = addressBook.weights();
        final var mask = addressBook.signersMask(signers);
        final var tssVerificationKey = GENESIS_WRAPS_METADATA;
        logger.info(" genesisAddressBookHash == null ? {}", genesisAddressBookHash == null);
        logger.info(" genesisAddressBookHash.length == 0 ? {}", genesisAddressBookHash.length == 0);
        logger.info(" prevSchnorrPublicKeys == null ? {}", prevSchnorrPublicKeys == null);
        logger.info(" prevWeights == null ? {}", prevWeights == null);
        logger.info(" prevSchnorrPublicKeys.length == 0 ? {}", prevSchnorrPublicKeys.length == 0);
        logger.info(
                " prevSchnorrPublicKeys.length != prevWeights.length ? {}",
                prevSchnorrPublicKeys.length != prevWeights.length);
        logger.info(" !WRAPSLibraryBridge.validateWeightsSum(prevWeights) ? {}", !validateWeightsSum(prevWeights));
        logger.info(" nextSchnorrPublicKeys == null ? {}", nextSchnorrPublicKeys == null);
        logger.info(" nextWeights == null ? {}", nextWeights == null);
        logger.info(" nextSchnorrPublicKeys.length == 0 ? {}", nextSchnorrPublicKeys.length == 0);
        logger.info(
                " nextSchnorrPublicKeys.length != nextWeights.length ? {}",
                nextSchnorrPublicKeys.length != nextWeights.length);
        logger.info(" !WRAPSLibraryBridge.validateWeightsSum(nextWeights) ? {}", !validateWeightsSum(nextWeights));
        logger.info(" tssVerificationKey == null ? {}", tssVerificationKey == null);
        logger.info(" tssVerificationKey.length == 0 ? {}", tssVerificationKey.length == 0);
        logger.info(" aggregateSignature == null ? {}", aggregatedSignature == null);
        logger.info(" aggregateSignature.length == 0 ? {}", aggregatedSignature.length == 0);
        logger.info(" signers == null ? {}", mask == null);
        logger.info(
                " signers.length != prevSchnorrPublicKeys.length ? {}", mask.length != prevSchnorrPublicKeys.length);
        logger.info(
                " !WRAPSLibraryBridge.validateSchnorrPublicKeys(prevSchnorrPublicKeys) ? {}",
                !validateSchnorrPublicKeys(prevSchnorrPublicKeys));
        logger.info(
                ") !WRAPSLibraryBridge.validateSchnorrPublicKeys(nextSchnorrPublicKeys) ? {}",
                !validateSchnorrPublicKeys(nextSchnorrPublicKeys));
        logger.info("Is proof supported? {}", WRAPSLibraryBridge.isProofSupported());
        logger.info("Genesis hash: " + CommonUtils.hex(genesisAddressBookHash));
        logger.info("Signers: " + Arrays.toString(mask));
        return WRAPS.constructWrapsProof(
                genesisAddressBookHash,
                addressBook.publicKeys(),
                addressBook.weights(),
                addressBook.publicKeys(),
                addressBook.weights(),
                null,
                GENESIS_WRAPS_METADATA,
                aggregatedSignature,
                addressBook.signersMask(signers));
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
                targetAddressBook.publicKeys(),
                targetAddressBook.weights(),
                sourceProof,
                targetHintsVerificationKey,
                aggregatedSignature,
                sourceAddressBook.signersMask(signers));
    }

    @Override
    public boolean isValidWraps(@NonNull final byte[] compressedProof) {
        requireNonNull(compressedProof);
        return WRAPS.verifyCompressedProof(compressedProof);
    }

    @Override
    public boolean wrapsProverReady() {
        return WRAPSLibraryBridge.isProofSupported();
    }

    // TEMP
    private static boolean validateWeightsSum(final long weights[]) {
        try {
            long sum = 0;
            for (int i = 0; i < weights.length; i++) {
                if (weights[i] < 0) {
                    return false;
                }
                // Math.addExact() throws ArithmeticException if the sum overflows
                sum = Math.addExact(sum, weights[i]);
            }
            return sum <= Long.MAX_VALUE;
        } catch (final ArithmeticException e) {
            return false;
        }
    }

    private static boolean validateSchnorrPublicKeys(final byte[][] schnorrPublicKeys) {
        for (int i = 0; i < schnorrPublicKeys.length; i++) {
            if (schnorrPublicKeys[i] == null || schnorrPublicKeys[i].length == 0) {
                return false;
            }
        }
        return true;
    }
}
