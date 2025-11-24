// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.Aggregate;
import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.R1;
import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.R2;
import static com.hedera.cryptography.wraps.WRAPSLibraryBridge.SigningProtocolPhase.R3;
import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.rpm.HistoryLibraryBridge;
import com.hedera.cryptography.rpm.SigningAndVerifyingSchnorrKeys;
import com.hedera.cryptography.wraps.Proof;
import com.hedera.cryptography.wraps.WRAPSLibraryBridge;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import java.util.SplittableRandom;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of the {@link HistoryLibrary}.
 */
public class HistoryLibraryImpl implements HistoryLibrary {
    private static final Logger log = LogManager.getLogger(HistoryLibraryImpl.class);

    private static final byte[] DUMMY_HINTS_KEY = new byte[1280];
    public static final SplittableRandom RANDOM = new SplittableRandom();
    private static final HistoryLibraryBridge RPM_BRIDGE = HistoryLibraryBridge.getInstance();
    public static final WRAPSLibraryBridge WRAPS = WRAPSLibraryBridge.getInstance();

    @Override
    public SigningAndVerifyingSchnorrKeys newSchnorrKeyPair() {
        final var seed = new byte[32];
        RANDOM.nextBytes(seed);
        final var wrapsKeys = WRAPS.generateSchnorrKeys(seed);
        return new SigningAndVerifyingSchnorrKeys(wrapsKeys.privateKey(), wrapsKeys.publicKey());
    }

    @Override
    public Bytes signSchnorr(@NonNull final Bytes message, @NonNull final Bytes privateKey) {
        requireNonNull(message);
        requireNonNull(privateKey);
        return Bytes.wrap(RPM_BRIDGE.signSchnorr(message.toByteArray(), privateKey.toByteArray()));
    }

    @Override
    public boolean verifySchnorr(
            @NonNull final Bytes signature, @NonNull final Bytes message, @NonNull final Bytes publicKey) {
        requireNonNull(signature);
        requireNonNull(message);
        requireNonNull(publicKey);
        // TODO - swap in the WRAPS Schnorr verification call when it is available
        return true;
    }

    @Override
    public byte[] hashAddressBook(@NonNull final AddressBook addressBook) {
        final var answer = WRAPS.hashAddressBook(addressBook.publicKeys(), addressBook.weights());
        log.info(
                "# nodes = {}, # weights = {}, # public keys = {}",
                addressBook.nodeIds().length,
                addressBook.weights().length,
                addressBook.publicKeys().length);
        log.info("Hashed {} and got answer {}", addressBook, answer);
        return answer;
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
        return WRAPS.constructWrapsProof(
                genesisAddressBookHash,
                addressBook.publicKeys(),
                addressBook.weights(),
                addressBook.publicKeys(),
                addressBook.weights(),
                null,
                DUMMY_HINTS_KEY,
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

    // --- DEPRECATED METHODS ---

}
