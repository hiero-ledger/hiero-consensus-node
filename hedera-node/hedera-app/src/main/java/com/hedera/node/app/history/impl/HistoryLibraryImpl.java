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

/**
 * Default implementation of the {@link HistoryLibrary}.
 */
public class HistoryLibraryImpl implements HistoryLibrary {
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
        return WRAPS.hashAddressBook(addressBook.publicKeys(), addressBook.weights(), addressBook.nodeIds());
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
}
