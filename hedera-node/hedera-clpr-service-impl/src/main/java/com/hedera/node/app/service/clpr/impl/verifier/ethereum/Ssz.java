// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.checkedCopy;
import static com.hedera.node.app.service.clpr.impl.verifier.evm.ProofBytes.requireLength;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Simple serialize (SSZ) is the serialization method used on the Beacon Chain. It replaces the RLP serialization used
 * on the execution layer everywhere across the consensus layer except the peer discovery protocol.
 * <p>
 * This class contains the SSZ ({@code hash_tree_root}) primitives and the Ethereum consensus constants this verifier
 * needs: balanced-tree merkleization, the {@code BLS pubkey} / {@code uint64} leaf encodings, the sync-committee
 * signing domain/root, and Merkle-branch verification. Also holds the protocol size constants (committee size, BLS
 * lengths, fork-version length) and the SSZ generalized indices for the branches the verifier walks.
 */
final class Ssz {

    private Ssz() {}

    // ── Sync committee / BLS sizes (Altair, unchanged through Electra) ──
    static final int SYNC_COMMITTEE_SIZE = 512;
    static final int SYNC_COMMITTEE_BITS_LENGTH = SYNC_COMMITTEE_SIZE / 8;
    static final int BLS_PUBKEY_LENGTH = 48;
    static final int BLS_SIGNATURE_LENGTH = 96;
    static final int FORK_VERSION_LENGTH = 4;

    /**
     * BeaconBlockHeader SSZ container fields: slot, proposerIndex, parentRoot, stateRoot, bodyRoot.
     */
    static final int BEACON_HEADER_FIELDS = 5;

    /**
     * {@code DOMAIN_SYNC_COMMITTEE} domain type from the consensus spec.
     */
    private static final byte[] DOMAIN_SYNC_COMMITTEE = {0x07, 0x00, 0x00, 0x00};

    /**
     * SSZ generalized index of {@code BeaconBlockBody.execution_payload.state_root} relative to the block body root:
     * body has 12 (Deneb) / 13 (Electra) fields padded to 16 with {@code execution_payload} at field 9 (gindex 25), and
     * ExecutionPayloadHeader has 17 fields padded to 32 with {@code state_root} at field 2 (gindex 34); composed: 25*32
     * + 2 = 802, i.e. depth 9, leaf index 290.
     */
    static final int EXECUTION_STATE_ROOT_BRANCH_DEPTH = 9;

    static final int EXECUTION_STATE_ROOT_LEAF_INDEX = 290;

    /**
     * SSZ generalized index of {@code BeaconState.next_sync_committee} relative to the state root: the state has 37
     * fields padded to 64 with {@code next_sync_committee} at field 23, i.e. gindex 87, depth 6, leaf index 23.
     * (Pre-Electra forks used gindex 55 — depth 5 — because the state had fewer fields.)
     */
    static final int NEXT_SYNC_COMMITTEE_BRANCH_DEPTH = 6;

    static final int NEXT_SYNC_COMMITTEE_LEAF_INDEX = 23;

    /**
     * SSZ generalized index of {@code BeaconState.current_sync_committee} relative to the state root: field 22 of the
     * state padded to 64 fields, i.e. gindex 86, depth 6, leaf index 22. (Pre-Electra forks used gindex 54 — depth 5.)
     */
    static final int CURRENT_SYNC_COMMITTEE_BRANCH_DEPTH = 6;

    static final int CURRENT_SYNC_COMMITTEE_LEAF_INDEX = 22;

    /**
     * Per-thread reusable SHA-256 digest. {@link MessageDigest} is stateful and not thread-safe, so it cannot be shared
     * across threads via a plain static field; a {@link ThreadLocal} gives each thread its own instance while still
     * avoiding a fresh {@code getInstance} provider lookup and allocation on every hash. A single verify call performs
     * ~1k hashes (dominated by {@link SyncCommittee#hashTreeRoot()}), so reuse matters.
     */
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    });

    @NonNull
    static byte[] sha256(@NonNull final byte[]... inputs) {
        // digest() resets the instance, so the thread-local is always clean on entry; reset()
        // defensively in case a prior call threw between update() and digest().
        final MessageDigest digest = SHA_256.get();
        digest.reset();
        for (final byte[] input : inputs) {
            digest.update(input);
        }
        return digest.digest();
    }

    /**
     * Computes the SHA-256 hash of a 64-byte padded version of the given 48-byte BLS public key.
     * <p>
     * The method validates that the input public key is exactly 48 bytes, pads it to 64 bytes with zeroes,
     * and then computes the SHA-256 hash of the padded content.
     *
     * @param pubkey48 the BLS public key, a 48-byte array; must not be null
     * @return a 32-byte array containing the SHA-256 hash of the padded public key
     * @throws IllegalArgumentException if the input array length is not exactly 48 bytes
     * @throws NullPointerException if the input array is null
     */
    @NonNull
    static byte[] pubkeyHash64(@NonNull final byte[] pubkey48) {
        requireLength(pubkey48, BLS_PUBKEY_LENGTH, "pubkey");
        final byte[] padded = new byte[64];
        System.arraycopy(pubkey48, 0, padded, 0, BLS_PUBKEY_LENGTH);
        return sha256(padded);
    }

    /**
     * Computes the root of a balanced binary Merkle tree (SHA-256) over the given leaves — the core of SSZ
     * {@code hash_tree_root} for fixed-size containers and vectors.
     *
     * <p><b>Input:</b> {@code chunks}, an array of 32-byte leaves whose length is a power of two.
     * Callers are responsible for arranging the leaves into the SSZ layout before calling this: each scalar field is
     * its little-endian value in a 32-byte chunk, each 32-byte field is the chunk itself, and the chunk count is padded
     * up to the next power of two with all-zero chunks (e.g. a 5-field {@code BeaconBlockHeader} is padded to 8). This
     * method does no padding or encoding of its own — it only hashes.
     *
     * <p><b>What it does:</b> treats {@code chunks} as the bottom level of a complete binary tree
     * and folds it upward one level at a time. Each level halves the node count by hashing every adjacent pair
     * left-to-right: {@code parent = sha256(left || right)} (a 64-byte input). After {@code log2(chunks.length)} rounds
     * a single node remains.
     *
     * <p><b>Output:</b> that final 32-byte node — the Merkle root binding every leaf in order. Any
     * change to any leaf, or to leaf ordering, changes the root. For a single leaf ({@code chunks.length == 1}) the
     * loop runs zero times and the leaf is returned unchanged, as the SSZ spec requires.
     *
     * @param chunks the 32-byte leaves, in tree order; length must be a power of two
     * @return the 32-byte Merkle root
     * @throws IllegalArgumentException if {@code chunks.length} is not a power of two
     */
    @NonNull
    static byte[] merkleize(@NonNull final byte[][] chunks) {
        if (Integer.bitCount(chunks.length) != 1) {
            throw new IllegalArgumentException("chunk count must be a power of two, got " + chunks.length);
        }
        // Fold the current level into the next by hashing adjacent pairs, until one node is left.
        byte[][] level = chunks;
        while (level.length > 1) {
            final byte[][] next = new byte[level.length / 2][];
            for (int i = 0; i < next.length; i++) {
                next[i] = sha256(level[2 * i], level[2 * i + 1]);
            }
            level = next;
        }
        return level[0];
    }

    /**
     * Encodes a 64-bit unsigned integer as a 32-byte SSZ-encoded leaf node,
     * where the lower 8 bytes represent the integer in little-endian order,
     * and the remaining 24 bytes are zero-padded.
     *
     * @param value the 64-bit unsigned integer to encode
     * @return a 32-byte array containing the SSZ-encoded leaf node
     */
    @NonNull
    static byte[] uint64Leaf(final long value) {
        final byte[] leaf = new byte[32];
        long v = value;
        for (int i = 0; i < 8; i++) {
            leaf[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        return leaf;
    }

    /**
     * Computes the Sync Committee domain, which is used in the Ethereum consensus for cryptographic
     * domain separation to bind a specific fork version and genesis validators root to Sync Committee
     * operations.
     *
     * @param forkVersion4 a 4-byte fork version indicating the current version of the protocol; must not be null
     * @param genesisValidatorsRoot32 a 32-byte root of the genesis validators tree; must not be null
     * @return a 32-byte array representing the Sync Committee domain
     */
    @NonNull
    static byte[] computeSyncCommitteeDomain(
            @NonNull final byte[] forkVersion4, @NonNull final byte[] genesisValidatorsRoot32) {
        final byte[] paddedVersion = new byte[32];
        System.arraycopy(forkVersion4, 0, paddedVersion, 0, FORK_VERSION_LENGTH);
        final byte[] forkDataRoot = sha256(paddedVersion, genesisValidatorsRoot32);
        final byte[] domain = new byte[32];
        System.arraycopy(DOMAIN_SYNC_COMMITTEE, 0, domain, 0, 4);
        System.arraycopy(forkDataRoot, 0, domain, 4, 28);
        return domain;
    }

    /**
     * Computes the signing root by hashing the 32-byte object root and the 32-byte domain.
     * The result is a 32-byte hash used in cryptographic signing operations.
     *
     * @param objectRoot32 the 32-byte root of the object to be signed; must not be null
     * @param domain32 the 32-byte domain used for domain separation; must not be null
     * @return a 32-byte array representing the hash of the combined object root and domain
     */
    @NonNull
    static byte[] computeSigningRoot(@NonNull final byte[] objectRoot32, @NonNull final byte[] domain32) {
        return sha256(checkedCopy(objectRoot32, 32, "objectRoot"), checkedCopy(domain32, 32, "domain"));
    }

    /**
     * A single SSZ Merkle inclusion proof: the sibling hashes along one leaf-to-root path bundled with the leaf's fixed
     * position in the tree ({@code depth} and {@code index}). Each branch type the verifier walks — execution state
     * root, current/next sync committee — has a constant depth and index (see the generalized-index constants above);
     * only the {@code siblings} vary per proof.
     *
     * <p>This is the SSZ analogue of
     * {@link com.hedera.node.app.service.clpr.impl.verifier.evm.MerklePatriciaTrie}: both wrap proof data behind a
     * single verification call. They differ in kind — the trie reconstructs nodes and <em>discovers</em> the value
     * bound to a key, whereas an inclusion proof checks that a leaf the caller already holds sits at a known position
     * under a known root.
     *
     * @param siblings the sibling hashes along the leaf-to-root path, ordered bottom-up: {@code siblings[0]} is the
     *                 leaf's direct sibling, {@code siblings[depth-1]} the topmost; each must be exactly 32 bytes
     * @param depth    the height of the tree, i.e. the expected number of sibling nodes
     * @param index    the leaf's position within the {@code 2^depth} leaves at the bottom level (equivalently,
     *                 generalized index minus {@code 2^depth})
     */
    record SszMerkleBranch(@NonNull byte[][] siblings, int depth, int index) {

        /**
         * Checks this inclusion proof: does {@code leaf32} really sit at {@link #index} of the depth-{@link #depth}
         * binary hash tree whose root is {@code root32}? This is a direct port of {@code is_valid_merkle_branch} from
         * the Ethereum consensus spec.
         *
         * <p><b>What it does:</b> recomputes the root from the leaf. Starting with
         * {@code value = leaf32}, it climbs the tree one level per sibling, combining the running value with the
         * sibling hash for that level: {@code sha256(value || sibling)} when our node is a left child,
         * {@code sha256(sibling || value)} when it is a right child. Which side is decided by the bits of
         * {@link #index}, least-significant first — bit {@code i} is the left/right position at level {@code i}, so bit
         * set (odd position) means right child, sibling goes on the left. After {@link #depth} folds the running value
         * is what the tree's root <em>must</em> be if the leaf and path are genuine; it is compared against
         * {@code root32}.
         *
         * @param leaf32 the 32-byte leaf value being proven (e.g. a committee root or the execution state root); must
         *               be exactly 32 bytes
         * @param root32 the trusted 32-byte tree root the recomputation must reproduce
         * @return true if the recomputed root equals {@code root32} (the proof is valid); false if it differs or
         * {@code siblings.length != depth}
         * @throws IllegalArgumentException if the leaf or any sibling is not 32 bytes
         */
        boolean proves(@NonNull final byte[] leaf32, @NonNull final byte[] root32) {
            if (siblings.length != depth) {
                return false;
            }
            byte[] value = checkedCopy(leaf32, 32, "leaf");
            for (int i = 0; i < depth; i++) {
                final byte[] sibling = checkedCopy(siblings[i], 32, "branch[" + i + "]");
                value = ((index >>> i) & 1) == 1 ? sha256(sibling, value) : sha256(value, sibling);
            }
            return Arrays.equals(value, root32);
        }
    }
}
