// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history;

import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.hex;

import com.hedera.cryptography.wraps.Proof;
import com.hedera.cryptography.wraps.SchnorrKeys;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.LongFunction;
import java.util.function.LongUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The cryptographic operations required by the {@link HistoryService}.
 */
public interface HistoryLibrary {
    /**
     * The empty public key to use when a node fails to publish its proof key within the grace period.
     */
    Bytes EMPTY_PUBLIC_KEY = Bytes.wrap(new byte[32]);

    /**
     * An address book for use in the history library.
     * @param weights the weights of the nodes in the address book
     * @param publicKeys the public keys of the nodes in the address book
     * @param nodeIds the node ids
     */
    record AddressBook(
            @NonNull long[] weights,
            @NonNull byte[][] publicKeys,
            @NonNull long[] nodeIds) {
        public AddressBook {
            requireNonNull(weights);
            requireNonNull(publicKeys);
            requireNonNull(nodeIds);
        }

        /**
         * Creates an address book from the given weights and public keys (indexed by node id).
         * @param weights the weights of the nodes in the address book
         * @param publicKeys the public keys of the nodes in the address book
         * @return the address book
         */
        public static AddressBook from(
                @NonNull final SortedMap<Long, Long> weights, @NonNull final SortedMap<Long, byte[]> publicKeys) {
            requireNonNull(weights);
            requireNonNull(publicKeys);
            final var emptyPublicKey = EMPTY_PUBLIC_KEY.toByteArray();
            return from(weights, nodeId -> publicKeys.getOrDefault(nodeId, emptyPublicKey));
        }

        /**
         * Creates an address book from the given weights and public keys (indexed by node id).
         * @param weights the weights of the nodes in the address book
         * @param publicKeys the public keys of the nodes in the address book
         * @return the address book
         */
        public static AddressBook from(
                @NonNull final SortedMap<Long, Long> weights, @NonNull final LongFunction<byte[]> publicKeys) {
            requireNonNull(weights);
            requireNonNull(publicKeys);
            final var nodeIds =
                    weights.keySet().stream().mapToLong(Long::longValue).toArray();
            return new AddressBook(
                    Arrays.stream(nodeIds).map(weights::get).toArray(),
                    Arrays.stream(nodeIds).mapToObj(publicKeys).toArray(byte[][]::new),
                    nodeIds);
        }

        /**
         * Returns a mask for the given signers.
         * @param signers the signers
         * @return the mask
         */
        public boolean[] signersMask(@NonNull final Set<Long> signers) {
            final var mask = new boolean[nodeIds.length];
            for (int i = 0; i < nodeIds.length; i++) {
                mask[i] = signers.contains(nodeIds[i]);
            }
            return mask;
        }

        @NonNull
        @Override
        public String toString() {
            return "AddressBook"
                    + IntStream.range(0, nodeIds.length)
                            .mapToObj(i -> "(#" + i + " :: weight="
                                    + weights[i] + " :: public_key="
                                    + hex(publicKeys[i]) + ")")
                            .collect(Collectors.joining(", ", "[", "]"));
        }
    }

    /**
     * Computes the canonical hash of the given situation from a {@link HistoryLibrary}.
     * @param library the library
     * @param nodeIds the node ids
     * @param weightFn the weight function
     * @param proofKeyFn the proof key function
     * @return the canonical hash
     */
    static Bytes computeHash(
            @NonNull final HistoryLibrary library,
            @NonNull final Set<Long> nodeIds,
            @NonNull final LongUnaryOperator weightFn,
            @NonNull final LongFunction<Bytes> proofKeyFn) {
        requireNonNull(nodeIds);
        requireNonNull(weightFn);
        requireNonNull(proofKeyFn);
        final var sortedNodeIds =
                nodeIds.stream().sorted().mapToLong(Long::longValue).toArray();
        final var targetWeights = Arrays.stream(sortedNodeIds).map(weightFn).toArray();
        final var proofKeysArray = Arrays.stream(sortedNodeIds)
                .mapToObj(proofKeyFn)
                .map(Bytes::toByteArray)
                .toArray(byte[][]::new);
        return Bytes.wrap(library.hashAddressBook(new AddressBook(targetWeights, proofKeysArray, sortedNodeIds)));
    }

    /**
     * The verification key for WRAPS proofs.
     */
    byte[] wrapsVerificationKey();

    /**
     * Returns a new Schnorr key pair.
     */
    SchnorrKeys newSchnorrKeyPair();

    /**
     * Computes the hash of the given address book with the same algorithm used by the SNARK circuit.
     *
     * @param addressBook the address book
     * @return the hash of the address book
     */
    byte[] hashAddressBook(@NonNull AddressBook addressBook);

    /**
     * Computes the message to be signed for a WRAPS proof.
     * @param addressBook the address book
     * @param hintsVerificationKey the hinTS verification key for the target address book
     * @return the message
     */
    byte[] computeWrapsMessage(AddressBook addressBook, byte[] hintsVerificationKey);

    /**
     * Runs the R1 phase of the signing protocol.
     * @param entropy the entropy (must be reused in remaining phases)
     * @param message the message to sign
     * @param privateKey the private key for R1
     * @return the R1 message
     */
    byte[] runWrapsPhaseR1(@NonNull byte[] entropy, @NonNull byte[] message, @NonNull byte[] privateKey);

    /**
     * Runs the R2 phase of the signing protocol.
     *
     * @param entropy the entropy (must be reused in remaining phases)
     * @param message the message to sign
     * @param r1Messages all participant's R1 messages
     * @param privateKey the private key
     * @param currentBook the current address book doing the rotation
     * @param r1NodeIds the node ids of the participants that contributed to the R1 messages
     * @return the R2 message
     */
    byte[] runWrapsPhaseR2(
            @NonNull byte[] entropy,
            @NonNull byte[] message,
            @NonNull byte[][] r1Messages,
            @NonNull byte[] privateKey,
            @NonNull AddressBook currentBook,
            @NonNull Set<Long> r1NodeIds);

    /**
     * Runs the R3 phase of the signing protocol.
     *
     * @param entropy the entropy (must be reused in remaining phases)
     * @param message the message to sign
     * @param r1Messages all participant's R1 messages
     * @param r2Messages all participant's R2 messages
     * @param privateKey the private key
     * @param currentBook the current address book doing the rotation
     * @param r1NodeIds the node ids of the participants that contributed to the R1 messages
     * @return the R3 message
     */
    byte[] runWrapsPhaseR3(
            @NonNull byte[] entropy,
            @NonNull byte[] message,
            @NonNull byte[][] r1Messages,
            @NonNull byte[][] r2Messages,
            @NonNull byte[] privateKey,
            @NonNull AddressBook currentBook,
            @NonNull Set<Long> r1NodeIds);

    /**
     * Runs the aggregation phase of the signing protocol.
     *
     * @param message the message to sign
     * @param r1Messages all participant's R1 messages
     * @param r2Messages all participant's R2 messages
     * @param r3Messages all participant's R3 messages
     * @param currentBook the current address book doing the rotation
     * @param r1NodeIds the node ids of the participants that contributed to the R1 messages
     * @return the aggregated signature
     */
    byte[] runAggregationPhase(
            @NonNull byte[] message,
            @NonNull byte[][] r1Messages,
            @NonNull byte[][] r2Messages,
            @NonNull byte[][] r3Messages,
            @NonNull AddressBook currentBook,
            @NonNull Set<Long> r1NodeIds);

    /**
     * Verifies an aggregated signature.
     *
     * @param message the message
     * @param nodeIds the node ids of full address book
     * @param publicKeys the full address book public keys
     * @param weights the weights of the full address book
     * @param signature the aggregated signature
     * @return true if the signature is valid; false otherwise
     */
    boolean verifyAggregateSignature(
            @NonNull byte[] message,
            @NonNull long[] nodeIds,
            @NonNull byte[][] publicKeys,
            @NonNull long[] weights,
            @NonNull byte[] signature);

    /**
     * Constructs a genesis WRAPS proof.
     *
     * @param genesisAddressBookHash the genesis address book hash
     * @param aggregatedSignature an aggregated signature from the genesis address book
     * @param genesisHintsVerificationKey the hinTS verification key for the genesis address book
     * @param signers the set of signers contributing to the aggregated signature
     * @param addressBook the genesis address book
     * @return the genesis WRAPS proof
     */
    Proof constructGenesisWrapsProof(
            @NonNull byte[] genesisAddressBookHash,
            @NonNull byte[] genesisHintsVerificationKey,
            @NonNull byte[] aggregatedSignature,
            @NonNull Set<Long> signers,
            @NonNull AddressBook addressBook);

    /**
     * Constructs an incremental WRAPS proof.
     *
     * @param genesisAddressBookHash the genesis address book hash
     * @param sourceProof the source proof
     * @param sourceAddressBook the source address book
     * @param targetAddressBook the target address book
     * @param targetHintsVerificationKey the hinTS verification key for the target address book
     * @param aggregatedSignature an aggregated signature from the target address book
     * @param signers the set of signers contributing to the aggregated signature
     * @return the incremental WRAPS proof
     */
    Proof constructIncrementalWrapsProof(
            @NonNull byte[] genesisAddressBookHash,
            @NonNull byte[] sourceProof,
            @NonNull AddressBook sourceAddressBook,
            @NonNull AddressBook targetAddressBook,
            @NonNull byte[] targetHintsVerificationKey,
            @NonNull byte[] aggregatedSignature,
            @NonNull Set<Long> signers);

    /**
     * Returns whether the library is ready to be used.
     */
    boolean wrapsProverReady();
}
