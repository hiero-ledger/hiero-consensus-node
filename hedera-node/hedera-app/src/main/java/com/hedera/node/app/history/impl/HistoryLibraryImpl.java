// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.rpm.HistoryLibraryBridge;
import com.hedera.cryptography.rpm.ProvingAndVerifyingSnarkKeys;
import com.hedera.cryptography.rpm.SigningAndVerifyingSchnorrKeys;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.SplittableRandom;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of the {@link HistoryLibrary}.
 */
public class HistoryLibraryImpl implements HistoryLibrary {
    private static final Logger log = LogManager.getLogger(HistoryLibraryImpl.class);

    private static final SplittableRandom RANDOM = new SplittableRandom();
    private static final HistoryLibraryBridge BRIDGE = HistoryLibraryBridge.getInstance();
    private static final ProvingAndVerifyingSnarkKeys SNARK_KEYS;

    static {
        try {
            final var elf = HistoryLibraryBridge.loadAddressBookRotationProgram();
            SNARK_KEYS = BRIDGE.snarkVerificationKey(elf);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load HistoryLibrary ELF", e);
        }
    }

    @Override
    public Bytes snarkVerificationKey() {
        return Bytes.wrap(SNARK_KEYS.verifyingKey());
    }

    @Override
    public SigningAndVerifyingSchnorrKeys newSchnorrKeyPair() {
        final var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return BRIDGE.newSchnorrKeyPair(bytes);
    }

    @Override
    public Bytes signSchnorr(@NonNull final Bytes message, @NonNull final Bytes privateKey) {
        requireNonNull(message);
        requireNonNull(privateKey);
        return Bytes.wrap(BRIDGE.signSchnorr(message.toByteArray(), privateKey.toByteArray()));
    }

    @Override
    public boolean verifySchnorr(
            @NonNull final Bytes signature, @NonNull final Bytes message, @NonNull final Bytes publicKey) {
        requireNonNull(signature);
        requireNonNull(message);
        requireNonNull(publicKey);
        return BRIDGE.verifySchnorr(signature.toByteArray(), message.toByteArray(), publicKey.toByteArray());
    }

    @Override
    public Bytes hashAddressBook(@NonNull long[] weights, @NonNull byte[][] publicKeys) {
        requireNonNull(weights);
        requireNonNull(publicKeys);
        if (weights.length != publicKeys.length) {
            throw new IllegalArgumentException("The number of weights and public keys must be the same");
        }
        return Bytes.wrap(BRIDGE.hashAddressBook(publicKeys, weights));
    }

    @Override
    public Bytes hashHintsVerificationKey(@NonNull final Bytes hintsVerificationKey) {
        requireNonNull(hintsVerificationKey);
        return Bytes.wrap(BRIDGE.hashHintsVerificationKey(hintsVerificationKey.toByteArray()));
    }

    @NonNull
    @Override
    public Bytes proveChainOfTrust(
            @NonNull final Bytes genesisAddressBookHash,
            @Nullable final Bytes sourceProof,
            @NonNull final long[] currentAddressBookWeights,
            @NonNull final byte[][] currentAddressBookVerifyingKeys,
            @NonNull final long[] nextAddressBookWeights,
            @NonNull final byte[][] nextAddressBookVerifyingKeys,
            @NonNull byte[][] sourceSignatures,
            @NonNull final Bytes targetMetadataHash) {
        requireNonNull(genesisAddressBookHash);
        requireNonNull(currentAddressBookWeights);
        requireNonNull(currentAddressBookVerifyingKeys);
        requireNonNull(nextAddressBookWeights);
        requireNonNull(nextAddressBookVerifyingKeys);
        requireNonNull(sourceSignatures);
        requireNonNull(targetMetadataHash);
        if (currentAddressBookWeights.length != currentAddressBookVerifyingKeys.length) {
            throw new IllegalArgumentException("The number of weights and verifying keys must be the same");
        }
        if (nextAddressBookWeights.length != nextAddressBookVerifyingKeys.length) {
            throw new IllegalArgumentException("The number of weights and verifying keys must be the same");
        }
        final var proof = BRIDGE.proveChainOfTrust(
                SNARK_KEYS.provingKey(),
                SNARK_KEYS.verifyingKey(),
                genesisAddressBookHash.toByteArray(),
                currentAddressBookVerifyingKeys,
                currentAddressBookWeights,
                nextAddressBookVerifyingKeys,
                nextAddressBookWeights,
                sourceProof == null ? null : sourceProof.toByteArray(),
                targetMetadataHash.toByteArray(),
                sourceSignatures);
        requireNonNull(proof);
        return Bytes.wrap(proof);
    }

    @Override
    public boolean verifyChainOfTrust(@NonNull final Bytes proof) {
        requireNonNull(proof);
        return BRIDGE.verifyChainOfTrust(SNARK_KEYS.verifyingKey(), proof.toByteArray());
    }
}
