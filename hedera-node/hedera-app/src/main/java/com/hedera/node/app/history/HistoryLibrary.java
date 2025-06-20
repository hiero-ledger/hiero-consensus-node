// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import com.hedera.cryptography.rpm.SigningAndVerifyingSchnorrKeys;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * The cryptographic operations required by the {@link HistoryService}.
 */
public interface HistoryLibrary {
    /**
     * Returns the SNARK verification key in use by this library.
     * <p>
     * <b>Important:</b> If this changes, the ledger id must also change.
     */
    Bytes snarkVerificationKey();

    /**
     * Returns a new Schnorr key pair.
     */
    SigningAndVerifyingSchnorrKeys newSchnorrKeyPair();

    /**
     * Signs a message with a Schnorr private key. In Hiero TSS, this will always be the concatenation
     * of an address book hash and the associated metadata.
     *
     * @param message the message
     * @param privateKey the private key
     * @return the signature
     */
    Bytes signSchnorr(@NonNull Bytes message, @NonNull Bytes privateKey);

    /**
     * Checks that a signature on a message verifies under a Schnorr public key.
     *
     * @param signature the signature
     * @param message the message
     * @param publicKey the public key
     * @return true if the signature is valid; false otherwise
     */
    boolean verifySchnorr(@NonNull Bytes signature, @NonNull Bytes message, @NonNull Bytes publicKey);

    /**
     * Computes the hash of the given address book with the same algorithm used by the SNARK circuit.
     *
     * @param weights the node weights in the roster
     * @param publicKeys the available Schnorr public keys for the nodes in the roster
     */
    Bytes hashAddressBook(@NonNull long[] weights, @NonNull byte[][] publicKeys);

    /**
     * Computes the hash of the given hints verification key.
     * @param hintsVerificationKey the hints verification key
     * @return the hash of the hints verification key
     */
    Bytes hashHintsVerificationKey(@NonNull Bytes hintsVerificationKey);

    /**
     * Returns a SNARK recursively proving the target address book and associated metadata belong to the given ledger
     * id's chain of trust that includes the given source address book, based on its own proof of belonging. (Unless the
     * source address book hash <i>is</i> the ledger id, which is the base case of the recursion).
     *
     * @param genesisAddressBookHash the ledger id, the concatenation of the genesis address book hash and the SNARK verification key
     * @param sourceProof if not null, the proof the source address book is in the ledger id's chain of trust
     * @param currentAddressBookWeights the weights of the current address book, indexed by node index in the roster
     * @param currentAddressBookVerifyingKeys the verifying keys of the current address book, indexed by node index in the roster
     * the same order as the weights
     * @param nextAddressBookWeights the weights of the next address book, indexed by node index in the roster
     * @param nextAddressBookVerifyingKeys the verifying keys of the next address book, indexed by node index in the roster
     * @param sourceSignatures the source address book signatures on the target address book hash and its metadata
     * @param targetMetadataHash the hash of the metadata of the target address book
     * @return the SNARK proving the target address book and metadata belong to the ledger id's chain of trust
     */
    @NonNull
    Bytes proveChainOfTrust(
            @NonNull Bytes genesisAddressBookHash,
            @Nullable Bytes sourceProof,
            @NonNull long[] currentAddressBookWeights,
            @NonNull byte[][] currentAddressBookVerifyingKeys,
            @NonNull long[] nextAddressBookWeights,
            @NonNull byte[][] nextAddressBookVerifyingKeys,
            @NonNull byte[][] sourceSignatures,
            @NonNull Bytes targetMetadataHash);

    /**
     * Verifies the given proof validates under this library's SNARK verification key.
     *
     * @param proof the proof to verify
     * @return whether the proof is valid under this library's SNARK verification key
     */
    boolean verifyChainOfTrust(@NonNull Bytes proof);
}
