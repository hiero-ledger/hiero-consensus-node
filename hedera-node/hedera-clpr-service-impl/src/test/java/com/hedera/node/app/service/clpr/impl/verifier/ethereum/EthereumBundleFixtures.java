// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.deterministicBytes;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.foldBranch;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.merkleizeIndependently;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.sha256;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.sparseBranch;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.sparseRoot;
import static com.hedera.node.app.service.clpr.impl.verifier.ethereum.EthereumTestUtils.uint64LeafLittleEndian;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.EthereumPosBeaconBlockHeader;
import com.hedera.hapi.node.state.clpr.EthereumPosCommitteeAnchor;
import com.hedera.hapi.node.state.clpr.EthereumPosLedgerConfigurationPayload;
import com.hedera.hapi.node.state.clpr.EthereumPosSyncAggregate;
import com.hedera.hapi.node.state.clpr.EthereumPosSyncCommittee;
import com.hedera.hapi.node.state.clpr.EthereumPosTrustAnchor;
import com.hedera.node.app.hapi.utils.MiscCryptoUtils;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Builders for consistent Ethereum verifier test payloads: committees of test keys, trust anchors, config payloads,
 * committee rotations and message deliveries whose SSZ branches, block header and Merkle-Patricia proofs all verify.
 *
 * <p>Keys are points on the BLS12-381 G1 curve, so a config payload's compressed keys decompress, but not keys of any
 * real validator, and signatures are zero bytes: the verifier's BLS check is a fake until real BLS lands. Each key's
 * uncompressed and compressed forms are consistent. Hashing, merkleization and the generalized indices are computed
 * here independently of {@link Ssz}.
 */
final class EthereumBundleFixtures {

    static final byte[] GENESIS_VALIDATORS_ROOT = deterministicBytes(32, 0x4B);
    static final byte[] FORK_VERSION = {0x08, 0x00, 0x00, 0x00};
    static final byte[] CHANNEL_ID = deterministicBytes(32, 0x3C);
    static final byte[] SERVICE_ADDRESS = deterministicBytes(20, 0x5D);
    static final byte[] CHANNEL_CONTEXT = concat(CHANNEL_ID, SERVICE_ADDRESS);

    /** The keccak256 of the CLPR service contract's code, as the account proofs and the anchors carry it. */
    static final byte[] CODE_HASH = deterministicBytes(32, 0xC0);

    /** The sync-committee period of the default trust anchor. */
    static final long ANCHOR_PERIOD = 1_000L;

    /** Length of an EIP-2537 uncompressed G2 signature. */
    static final int SIGNATURE_LENGTH = 256;

    /** Gloas generalized indices, from the consensus-specs light-client constants. */
    static final long FINALIZED_ROOT_GINDEX = 735L;

    static final long CURRENT_SYNC_COMMITTEE_GINDEX = 2945L;
    static final long NEXT_SYNC_COMMITTEE_GINDEX = 2946L;
    static final long EXECUTION_BLOCK_HASH_GINDEX = 2856L;

    /** keccak256(RLP("")): the root of an empty Merkle-Patricia trie. */
    static final byte[] EMPTY_TRIE_ROOT = MiscCryptoUtils.keccak256DigestOf(new byte[] {(byte) 0x80});

    private static final int CHANNELS_BASE_SLOT = 15;
    private static final int[] CHANNEL_SLOT_OFFSETS = {1, 2, 4, 5, 16};
    private static final byte MANIFEST_COMMITMENT_SLOT = 18;

    /** The BLS12-381 base field modulus. */
    private static final BigInteger FIELD_MODULUS = new BigInteger(
            "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);

    private EthereumBundleFixtures() {}

    // -----------------------------------------------------------------------------------
    // Committees and anchors
    // -----------------------------------------------------------------------------------

    /**
     * A committee of test keys: points of the curve {@code y² = x³ + 4} whose {@code x} is derived from the seed and
     * the member index. Members alternate between the smaller and the larger {@code y}, so both sign-flag values occur.
     */
    static final class Committee {
        final byte[][] uncompressedKeys;
        final byte[][] compressedKeys;
        final byte[] uncompressedAggregate;
        final byte[] compressedAggregate;
        final byte[] pubkeysRoot;
        private final byte[][] leaves;

        private Committee(final int size, final int seed) {
            uncompressedKeys = new byte[size][];
            compressedKeys = new byte[size][];
            leaves = new byte[size][];
            for (int i = 0; i < size; i++) {
                uncompressedKeys[i] = curvePoint(seed, i);
                compressedKeys[i] = compressIndependently(uncompressedKeys[i]);
                leaves[i] = pubkeyLeaf(compressedKeys[i]);
            }
            uncompressedAggregate = curvePoint(seed + 1, size);
            compressedAggregate = compressIndependently(uncompressedAggregate);
            pubkeysRoot = merkleizeIndependently(leaves);
        }

        static Committee of(final SyncCommitteePreset preset, final int seed) {
            return new Committee(preset.committeeSize(), seed);
        }

        /** {@code hash_tree_root(SyncCommittee)}: the value the next-committee branch commits to. */
        byte[] hashTreeRoot() {
            return sha256(pubkeysRoot, pubkeyLeaf(compressedAggregate));
        }

        /** The non-signer proof of member {@code index}: its uncompressed key followed by its bottom-up siblings. */
        byte[] nonSignerEntry(final int index) {
            final List<byte[]> parts = new ArrayList<>();
            parts.add(uncompressedKeys[index]);
            byte[][] level = leaves;
            int position = index;
            while (level.length > 1) {
                parts.add(level[position ^ 1]);
                final byte[][] next = new byte[level.length / 2][];
                for (int i = 0; i < next.length; i++) {
                    next[i] = sha256(level[2 * i], level[2 * i + 1]);
                }
                level = next;
                position >>= 1;
            }
            return concat(parts.toArray(new byte[0][]));
        }

        /**
         * The EIP-2537 encoding of the first curve point at or after an {@code x} unique per (seed, index): the first
         * {@code x} whose {@code x³ + 4} has a square root mod {@code p}.
         */
        private static byte[] curvePoint(final int seed, final int index) {
            final byte[] start = new byte[48];
            for (int j = 0; j < 48; j++) {
                start[j] = (byte) (seed * 13 + index * 7 + j);
            }
            // Below p (top byte 0x01) and unique per (seed, index).
            start[0] = 0x01;
            start[1] = (byte) (index >>> 8);
            start[2] = (byte) index;
            start[3] = (byte) seed;
            final BigInteger rootExponent = FIELD_MODULUS.add(BigInteger.ONE).shiftRight(2);
            BigInteger x = new BigInteger(1, start);
            while (true) {
                final BigInteger ySquared = x.pow(3).add(BigInteger.valueOf(4)).mod(FIELD_MODULUS);
                final BigInteger root = ySquared.modPow(rootExponent, FIELD_MODULUS);
                if (root.multiply(root).mod(FIELD_MODULUS).equals(ySquared)) {
                    final BigInteger otherRoot = FIELD_MODULUS.subtract(root);
                    final BigInteger y = (index % 2 == 0) == (root.compareTo(otherRoot) < 0) ? root : otherRoot;
                    final byte[] point = new byte[128];
                    System.arraycopy(toBytes48(x), 0, point, 16, 48);
                    System.arraycopy(toBytes48(y), 0, point, 80, 48);
                    return point;
                }
                x = x.add(BigInteger.ONE);
            }
        }

        /** ZCash compression: {@code x} with the compression flag and, when {@code y > (p-1)/2}, the sign flag. */
        private static byte[] compressIndependently(final byte[] uncompressed) {
            final byte[] x = Arrays.copyOfRange(uncompressed, 16, 64);
            final BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 80, 128));
            x[0] = (byte) (x[0] | 0x80 | (y.compareTo(FIELD_MODULUS.shiftRight(1)) > 0 ? 0x20 : 0));
            return x;
        }

        private static byte[] toBytes48(final BigInteger value) {
            final byte[] raw = value.toByteArray();
            final byte[] out = new byte[48];
            final int length = Math.min(raw.length, 48);
            System.arraycopy(raw, raw.length - length, out, 48 - length, length);
            return out;
        }
    }

    /** The SSZ leaf of a compressed key: {@code sha256(key ‖ 16 zero bytes)}. */
    static byte[] pubkeyLeaf(final byte[] compressedKey) {
        return sha256(compressedKey, new byte[16]);
    }

    /**
     * A trust anchor as the {@code EthereumPosTrustAnchor} protobuf, built here independently of {@link TrustAnchor}:
     * {@code current} for {@code period}, {@code next} for the period after it, and {@link #CODE_HASH}.
     */
    static byte[] anchor(
            final SyncCommitteePreset preset, final long period, final Committee current, final Committee next) {
        return EthereumPosTrustAnchor.PROTOBUF
                .toBytes(EthereumPosTrustAnchor.newBuilder()
                        .preset(preset == SyncCommitteePreset.MAINNET ? 1 : 2)
                        .genesisValidatorsRoot(Bytes.wrap(GENESIS_VALIDATORS_ROOT))
                        .forkVersion(Bytes.wrap(FORK_VERSION))
                        .period(period)
                        .current(trustedCommittee(current))
                        .next(trustedCommittee(next))
                        .codeHash(Bytes.wrap(CODE_HASH))
                        .build())
                .toByteArray();
    }

    private static EthereumPosCommitteeAnchor trustedCommittee(final Committee committee) {
        return EthereumPosCommitteeAnchor.newBuilder()
                .pubkeysRoot(Bytes.wrap(committee.pubkeysRoot))
                .aggregatePubkey(Bytes.wrap(committee.compressedAggregate))
                .build();
    }

    /** The first slot of {@code period} under {@code preset}. */
    static long firstSlotOf(final SyncCommitteePreset preset, final long period) {
        return period * (preset == SyncCommitteePreset.MAINNET ? 8192L : 64L);
    }

    // -----------------------------------------------------------------------------------
    // Beacon primitives
    // -----------------------------------------------------------------------------------

    /** Independent {@code hash_tree_root(BeaconBlockHeader)}. */
    static byte[] beaconHeaderRoot(
            final long slot,
            final long proposerIndex,
            final byte[] parentRoot,
            final byte[] stateRoot,
            final byte[] bodyRoot) {
        return merkleizeIndependently(new byte[][] {
            uint64LeafLittleEndian(slot),
            uint64LeafLittleEndian(proposerIndex),
            parentRoot,
            stateRoot,
            bodyRoot,
            new byte[32],
            new byte[32],
            new byte[32]
        });
    }

    /** Independent sync-committee signing root over {@code objectRoot} for the fixtures' domain. */
    static byte[] signingRoot(final byte[] objectRoot) {
        final byte[] forkDataRoot = sha256(Arrays.copyOf(FORK_VERSION, 32), GENESIS_VALIDATORS_ROOT);
        final byte[] domain = new byte[32];
        domain[0] = 0x07;
        System.arraycopy(forkDataRoot, 0, domain, 4, 28);
        return sha256(objectRoot, domain);
    }

    static byte[] headerRlp(
            final long slot,
            final long proposerIndex,
            final byte[] parentRoot,
            final byte[] stateRoot,
            final byte[] bodyRoot) {
        return Rlp.encodeList(List.of(
                Rlp.encodeUint(slot),
                Rlp.encodeUint(proposerIndex),
                Rlp.encodeBytes(parentRoot),
                Rlp.encodeBytes(stateRoot),
                Rlp.encodeBytes(bodyRoot)));
    }

    /** A participation bitvector with every member set except {@code nonSigners}. */
    static byte[] bitsWithout(final SyncCommitteePreset preset, final int... nonSigners) {
        final byte[] bits = new byte[preset.committeeSize() / 8];
        Arrays.fill(bits, (byte) 0xFF);
        for (final int index : nonSigners) {
            bits[index / 8] &= (byte) ~(1 << (index % 8));
        }
        return bits;
    }

    /** The non-signer entries of {@code committee} for the clear bits of {@code bits}, in ascending index order. */
    static List<byte[]> nonSignerEntries(final Committee committee, final byte[] bits) {
        final List<byte[]> entries = new ArrayList<>();
        for (int i = 0; i < bits.length * 8; i++) {
            if (((bits[i / 8] >>> (i % 8)) & 1) == 0) {
                entries.add(committee.nonSignerEntry(i));
            }
        }
        return entries;
    }

    // -----------------------------------------------------------------------------------
    // Bundle payloads
    // -----------------------------------------------------------------------------------

    /** The bundle payload {@code [ [rotation...], delivery ]}. */
    static byte[] bundle(final List<byte[]> rotations, final byte[] delivery) {
        return Rlp.encodeList(List.of(Rlp.encodeList(rotations), delivery));
    }

    /** The bundle payload with no rotations. */
    static byte[] bundle(final byte[] delivery) {
        return bundle(List.of(), delivery);
    }

    /**
     * A committee rotation of {@code period}: attested and finalized in that period, signed by {@code signer} (the
     * committee of {@code period}), proving {@code next} (the committee of the period after). Under an anchor of
     * period {@code P}, a rotation is of period {@code P + 1}. Every field can be overridden before {@link #build()}.
     */
    static final class RotationBuilder {
        final Committee signer;
        final Committee next;
        long attestedSlot;
        long finalizedSlot;
        long proposerIndex = 11;
        byte[] parentRoot = deterministicBytes(32, 0x61);
        byte[] bodyRoot = deterministicBytes(32, 0x62);
        byte[] bits;
        List<byte[]> nonSignerEntries; // null → derived from signer and bits
        byte[] signature = new byte[SIGNATURE_LENGTH];
        byte[] nextAggregate; // uncompressed, as on the wire
        byte[][] finalityBranchOverride;
        byte[][] nextCommitteeBranchOverride;

        RotationBuilder(
                final SyncCommitteePreset preset, final long period, final Committee signer, final Committee next) {
            this.signer = signer;
            this.next = next;
            attestedSlot = firstSlotOf(preset, period) + 40;
            finalizedSlot = firstSlotOf(preset, period) + 8;
            bits = bitsWithout(preset, 3);
            nextAggregate = next.uncompressedAggregate;
        }

        byte[] finalizedHeaderRlp() {
            return headerRlp(
                    finalizedSlot,
                    5,
                    deterministicBytes(32, 0x63),
                    deterministicBytes(32, 0x64),
                    deterministicBytes(32, 0x65));
        }

        byte[] finalizedRoot() {
            return beaconHeaderRoot(
                    finalizedSlot,
                    5,
                    deterministicBytes(32, 0x63),
                    deterministicBytes(32, 0x64),
                    deterministicBytes(32, 0x65));
        }

        /** The attested state, committing the finalized root and the next committee at their Gloas positions. */
        Map<Long, byte[]> attestedStateLeaves() {
            return Map.of(FINALIZED_ROOT_GINDEX, finalizedRoot(), NEXT_SYNC_COMMITTEE_GINDEX, next.hashTreeRoot());
        }

        byte[] build() {
            final Map<Long, byte[]> state = attestedStateLeaves();
            final byte[][] finalityBranch = finalityBranchOverride != null
                    ? finalityBranchOverride
                    : sparseBranch(state, FINALIZED_ROOT_GINDEX);
            final byte[][] nextCommitteeBranch = nextCommitteeBranchOverride != null
                    ? nextCommitteeBranchOverride
                    : sparseBranch(state, NEXT_SYNC_COMMITTEE_GINDEX);
            return Rlp.encodeList(List.of(
                    headerRlp(attestedSlot, proposerIndex, parentRoot, sparseRoot(state), bodyRoot),
                    syncAggregateRlp(bits, signature),
                    nonSignersRlp(nonSignerEntries != null ? nonSignerEntries : nonSignerEntries(signer, bits)),
                    finalizedHeaderRlp(),
                    branchRlp(finalityBranch),
                    Rlp.encodeList(List.of(Rlp.encodeBytes(next.pubkeysRoot), Rlp.encodeBytes(nextAggregate))),
                    branchRlp(nextCommitteeBranch)));
        }
    }

    /**
     * A message delivery signed by {@code signer} in {@code period}, in the slot after the attested one. By default the
     * CLPR service account carries {@link #CODE_HASH} and an empty storage trie, and the five Channel slots of
     * {@link #CHANNEL_ID} are proven absent; every field can be overridden before {@link #build()}.
     */
    static final class DeliveryBuilder {
        final Committee signer;
        long attestedSlot;
        Long signatureSlot; // null → attestedSlot + 1
        long finalizedSlot;
        long proposerIndex = 7;
        byte[] parentRoot = deterministicBytes(32, 0x21);
        byte[] attestedBodyRoot = deterministicBytes(32, 0x22);
        byte[] bits;
        List<byte[]> nonSignerEntries; // null → derived from signer and bits
        byte[] signature = new byte[SIGNATURE_LENGTH];
        byte[] accountCodeHash = CODE_HASH;
        byte[] storageRoot;
        byte[] executionStateRoot;
        byte[] blockHeaderRlpOverride;
        byte[][] executionBranchOverride;
        byte[] finalizedBodyRootOverride;
        byte[][] finalityBranchOverride;
        byte[][] accountProofNodes;
        List<byte[]> storageEntries;
        byte[] bundleContent = {0x01, 0x02};
        byte[] manifestProof = Rlp.encodeList(List.of());
        byte[] manifestPreimage = new byte[0];

        DeliveryBuilder(final SyncCommitteePreset preset, final long period, final Committee signer) {
            this.signer = signer;
            attestedSlot = firstSlotOf(preset, period) + 50;
            finalizedSlot = firstSlotOf(preset, period) + 16;
            bits = bitsWithout(preset, 1, 2);
            withStorage(EMPTY_TRIE_ROOT, new byte[0][], channelSlots(CHANNEL_ID));
        }

        /**
         * Proves {@code value} at {@code provenSlot} in a single-leaf storage trie and serves that leaf for each of
         * {@code entrySlots}; every other slot resolves absent (zero).
         */
        DeliveryBuilder withSingleSlotStorage(final byte[] provenSlot, final byte[] value, final byte[]... entrySlots) {
            final byte[][] storage = storageLeafProof(provenSlot, value);
            return withStorage(storage[0], new byte[][] {storage[1]}, entrySlots);
        }

        /** Uses {@code storageRoot} for the account and one entry per {@code entrySlots}, each serving {@code nodes}. */
        DeliveryBuilder withStorage(final byte[] storageRoot, final byte[][] nodes, final byte[]... entrySlots) {
            this.storageRoot = storageRoot;
            proveAccount();
            storageEntries = new ArrayList<>();
            for (final byte[] slot : entrySlots) {
                storageEntries.add(storageEntry(slot, nodes));
            }
            return this;
        }

        /** Proves the CLPR service account with {@code codeHash} instead of {@link #CODE_HASH}. */
        DeliveryBuilder withAccountCodeHash(final byte[] codeHash) {
            accountCodeHash = codeHash;
            proveAccount();
            return this;
        }

        private void proveAccount() {
            final byte[][] account = accountLeafProof(SERVICE_ADDRESS, storageRoot, accountCodeHash);
            executionStateRoot = account[0];
            accountProofNodes = new byte[][] {account[1]};
        }

        /**
         * Adds the manifest commitment of {@code preimage} at slot 18, proven under the same single-leaf storage trie
         * that serves the Channel slots.
         */
        DeliveryBuilder withManifest(final byte[] preimage, final byte[] commitment, final byte[] proofSlot) {
            final byte[][] storage = storageLeafProof(manifestCommitmentSlot(), commitment);
            withStorage(storage[0], new byte[][] {storage[1]}, channelSlots(CHANNEL_ID));
            manifestProof = Rlp.encodeList(List.of(storageEntry(proofSlot, new byte[][] {storage[1]})));
            manifestPreimage = preimage;
            return this;
        }

        byte[] blockHeaderRlp() {
            if (blockHeaderRlpOverride != null) {
                return blockHeaderRlpOverride;
            }
            return Rlp.encodeList(List.of(
                    Rlp.encodeBytes(deterministicBytes(32, 0x31)), // parentHash
                    Rlp.encodeBytes(deterministicBytes(32, 0x32)), // ommersHash
                    Rlp.encodeBytes(deterministicBytes(20, 0x33)), // coinbase
                    Rlp.encodeBytes(executionStateRoot), // stateRoot
                    Rlp.encodeBytes(deterministicBytes(32, 0x34)), // transactionsRoot
                    Rlp.encodeBytes(deterministicBytes(32, 0x35)))); // receiptsRoot
        }

        byte[] finalizedBodyRoot() {
            if (finalizedBodyRootOverride != null) {
                return finalizedBodyRootOverride;
            }
            return foldBranch(MiscCryptoUtils.keccak256DigestOf(blockHeaderRlp()), zeroBranch(11), (int)
                    (EXECUTION_BLOCK_HASH_GINDEX - 2048));
        }

        byte[] finalizedRoot() {
            return beaconHeaderRoot(
                    finalizedSlot, 3, deterministicBytes(32, 0x23), deterministicBytes(32, 0x24), finalizedBodyRoot());
        }

        byte[] attestedStateRoot() {
            return sparseRoot(Map.of(FINALIZED_ROOT_GINDEX, finalizedRoot()));
        }

        byte[] attestedRoot() {
            return beaconHeaderRoot(attestedSlot, proposerIndex, parentRoot, attestedStateRoot(), attestedBodyRoot);
        }

        byte[] build() {
            final byte[][] finalityBranch = finalityBranchOverride != null
                    ? finalityBranchOverride
                    : sparseBranch(Map.of(FINALIZED_ROOT_GINDEX, finalizedRoot()), FINALIZED_ROOT_GINDEX);
            final byte[] finalizedExecution = Rlp.encodeList(List.of(
                    headerRlp(
                            finalizedSlot,
                            3,
                            deterministicBytes(32, 0x23),
                            deterministicBytes(32, 0x24),
                            finalizedBodyRoot()),
                    branchRlp(executionBranchOverride != null ? executionBranchOverride : zeroBranch(11)),
                    Rlp.encodeBytes(blockHeaderRlp())));
            final List<byte[]> accountProof = new ArrayList<>();
            for (final byte[] node : accountProofNodes) {
                accountProof.add(Rlp.encodeBytes(node));
            }
            return Rlp.encodeList(List.of(
                    headerRlp(attestedSlot, proposerIndex, parentRoot, attestedStateRoot(), attestedBodyRoot),
                    Rlp.encodeUint(signatureSlot != null ? signatureSlot : attestedSlot + 1),
                    syncAggregateRlp(bits, signature),
                    nonSignersRlp(nonSignerEntries != null ? nonSignerEntries : nonSignerEntries(signer, bits)),
                    finalizedExecution,
                    branchRlp(finalityBranch),
                    Rlp.encodeList(accountProof),
                    Rlp.encodeList(storageEntries),
                    Rlp.encodeBytes(bundleContent),
                    manifestProof,
                    Rlp.encodeBytes(manifestPreimage)));
        }
    }

    // -----------------------------------------------------------------------------------
    // Config payloads
    // -----------------------------------------------------------------------------------

    /**
     * An {@code EthereumPosLedgerConfigurationPayload} of {@code period}: the bootstrap opens {@code current} under the
     * anchor header's state root, and an update header of the same period, signed by {@code current}, opens
     * {@code next}. Every field can be overridden before {@link #build()}.
     */
    static final class ConfigBuilder {
        final Committee current;
        final Committee next;
        long anchorSlot;
        Long slot; // null → anchorSlot
        long updateSlot;
        byte[] bits;
        byte[] signature = new byte[SIGNATURE_LENGTH];
        byte[] codeHash = CODE_HASH;
        byte[][] currentBranchOverride;
        byte[][] nextBranchOverride;

        ConfigBuilder(
                final SyncCommitteePreset preset, final long period, final Committee current, final Committee next) {
            this.current = current;
            this.next = next;
            anchorSlot = firstSlotOf(preset, period) + 33;
            updateSlot = firstSlotOf(preset, period) + 60;
            bits = bitsWithout(preset, 3, 17);
        }

        byte[] anchorStateRoot() {
            return sparseRoot(Map.of(CURRENT_SYNC_COMMITTEE_GINDEX, current.hashTreeRoot()));
        }

        byte[] updateStateRoot() {
            return sparseRoot(Map.of(NEXT_SYNC_COMMITTEE_GINDEX, next.hashTreeRoot()));
        }

        /** The root of the update header: the object of the current committee's signature. */
        byte[] updateRoot() {
            return beaconHeaderRoot(
                    updateSlot, 9, deterministicBytes(32, 0x71), updateStateRoot(), deterministicBytes(32, 0x72));
        }

        EthereumPosLedgerConfigurationPayload build() {
            return EthereumPosLedgerConfigurationPayload.newBuilder()
                    .syncCommittee(committee(current))
                    .ledgerConfiguration(ClprLedgerConfiguration.newBuilder()
                            .chainId("eip155:1")
                            .serviceAddress(Bytes.wrap(SERVICE_ADDRESS))
                            .build())
                    .genesisValidatorsRoot(Bytes.wrap(GENESIS_VALIDATORS_ROOT))
                    .forkVersion(Bytes.wrap(FORK_VERSION))
                    .slot(slot != null ? slot : anchorSlot)
                    .anchorHeader(header(
                            anchorSlot,
                            8,
                            deterministicBytes(32, 0x73),
                            anchorStateRoot(),
                            deterministicBytes(32, 0x74)))
                    .currentSyncCommitteeBranch(branch(
                            currentBranchOverride != null
                                    ? currentBranchOverride
                                    : sparseBranch(
                                            Map.of(CURRENT_SYNC_COMMITTEE_GINDEX, current.hashTreeRoot()),
                                            CURRENT_SYNC_COMMITTEE_GINDEX)))
                    .updateHeader(header(
                            updateSlot,
                            9,
                            deterministicBytes(32, 0x71),
                            updateStateRoot(),
                            deterministicBytes(32, 0x72)))
                    .syncAggregate(EthereumPosSyncAggregate.newBuilder()
                            .bits(Bytes.wrap(bits))
                            .signature(Bytes.wrap(signature))
                            .build())
                    .nextSyncCommittee(committee(next))
                    .nextSyncCommitteeBranch(branch(
                            nextBranchOverride != null
                                    ? nextBranchOverride
                                    : sparseBranch(
                                            Map.of(NEXT_SYNC_COMMITTEE_GINDEX, next.hashTreeRoot()),
                                            NEXT_SYNC_COMMITTEE_GINDEX)))
                    .codeHash(Bytes.wrap(codeHash))
                    .build();
        }

        byte[] serialize() {
            return EthereumPosLedgerConfigurationPayload.PROTOBUF
                    .toBytes(build())
                    .toByteArray();
        }

        private static EthereumPosSyncCommittee committee(final Committee committee) {
            final List<Bytes> keys = new ArrayList<>();
            for (final byte[] key : committee.compressedKeys) {
                keys.add(Bytes.wrap(key));
            }
            return EthereumPosSyncCommittee.newBuilder()
                    .pubkeys(keys)
                    .aggregatePubkey(Bytes.wrap(committee.compressedAggregate))
                    .build();
        }

        private static EthereumPosBeaconBlockHeader header(
                final long slot,
                final long proposerIndex,
                final byte[] parentRoot,
                final byte[] stateRoot,
                final byte[] bodyRoot) {
            return EthereumPosBeaconBlockHeader.newBuilder()
                    .slot(slot)
                    .proposerIndex(proposerIndex)
                    .parentRoot(Bytes.wrap(parentRoot))
                    .stateRoot(Bytes.wrap(stateRoot))
                    .bodyRoot(Bytes.wrap(bodyRoot))
                    .build();
        }

        private static List<Bytes> branch(final byte[][] siblings) {
            final List<Bytes> out = new ArrayList<>();
            for (final byte[] sibling : siblings) {
                out.add(Bytes.wrap(sibling));
            }
            return out;
        }
    }

    // -----------------------------------------------------------------------------------
    // Execution-layer primitives
    // -----------------------------------------------------------------------------------

    /**
     * The five Channel slot keys of {@code channelId}: {@code keccak256(channelId ‖ uint256(15)) + {1, 2, 4, 5, 16}}.
     */
    static byte[][] channelSlots(final byte[] channelId) {
        final byte[] mappingSlot = new byte[32];
        mappingSlot[31] = CHANNELS_BASE_SLOT;
        final BigInteger base = new BigInteger(1, MiscCryptoUtils.keccak256DigestOf(concat(channelId, mappingSlot)));
        final byte[][] slots = new byte[CHANNEL_SLOT_OFFSETS.length][];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = toBytes32(base.add(BigInteger.valueOf(CHANNEL_SLOT_OFFSETS[i])));
        }
        return slots;
    }

    /** The five Channel slot keys of {@code channelId} followed by {@code extraSlot}. */
    static byte[][] channelSlotsAnd(final byte[] channelId, final byte[] extraSlot) {
        final byte[][] slots = Arrays.copyOf(channelSlots(channelId), CHANNEL_SLOT_OFFSETS.length + 1);
        slots[CHANNEL_SLOT_OFFSETS.length] = extraSlot;
        return slots;
    }

    static byte[] manifestCommitmentSlot() {
        final byte[] slot = new byte[32];
        slot[31] = MANIFEST_COMMITMENT_SLOT;
        return slot;
    }

    /** A 32-byte slot key whose last byte is {@code value}. */
    static byte[] slotKey(final int value) {
        final byte[] slot = new byte[32];
        slot[31] = (byte) value;
        return slot;
    }

    /** RLP {@code [key, proofNodes[]]} storage-proof entry. */
    static byte[] storageEntry(final byte[] key, final byte[][] proofNodes) {
        final List<byte[]> encodedNodes = new ArrayList<>();
        for (final byte[] node : proofNodes) {
            encodedNodes.add(Rlp.encodeBytes(node));
        }
        return Rlp.encodeList(List.of(Rlp.encodeBytes(key), Rlp.encodeList(encodedNodes)));
    }

    /**
     * A single-leaf MPT proof of the account at {@code address}, carrying {@link #CODE_HASH}: {@code [stateRoot,
     * leafNode]}.
     */
    static byte[][] accountLeafProof(final byte[] address, final byte[] storageRoot) {
        return accountLeafProof(address, storageRoot, CODE_HASH);
    }

    /** A single-leaf MPT proof of the account at {@code address}: {@code [stateRoot, leafNode]}. */
    static byte[][] accountLeafProof(final byte[] address, final byte[] storageRoot, final byte[] codeHash) {
        final byte[] account = Rlp.encodeList(List.of(
                Rlp.encodeUint(0L), Rlp.encodeUint(0L), Rlp.encodeBytes(storageRoot), Rlp.encodeBytes(codeHash)));
        return leafProof(MiscCryptoUtils.keccak256DigestOf(address), account);
    }

    /** A single-leaf MPT proof of {@code value} at {@code slot}: {@code [storageRoot, leafNode]}. */
    static byte[][] storageLeafProof(final byte[] slot, final byte[] value) {
        // The leaf value is RLP(RLP(bytes32)); the verifier peels one layer.
        return leafProof(MiscCryptoUtils.keccak256DigestOf(slot), Rlp.encodeBytes(value));
    }

    private static byte[][] leafProof(final byte[] path32, final byte[] value) {
        // Hex-prefix for a 64-nibble leaf path: flag byte 0x20 (leaf, even) followed by the path.
        final byte[] leaf =
                Rlp.encodeList(List.of(Rlp.encodeBytes(concat(new byte[] {0x20}, path32)), Rlp.encodeBytes(value)));
        return new byte[][] {MiscCryptoUtils.keccak256DigestOf(leaf), leaf};
    }

    // -----------------------------------------------------------------------------------
    // Encoding helpers
    // -----------------------------------------------------------------------------------

    static byte[] syncAggregateRlp(final byte[] bits, final byte[] signature) {
        return Rlp.encodeList(List.of(Rlp.encodeBytes(bits), Rlp.encodeBytes(signature)));
    }

    static byte[] nonSignersRlp(final List<byte[]> entries) {
        final List<byte[]> encoded = new ArrayList<>();
        for (final byte[] entry : entries) {
            encoded.add(Rlp.encodeBytes(entry));
        }
        return Rlp.encodeList(encoded);
    }

    static byte[] branchRlp(final byte[][] branch) {
        final List<byte[]> encoded = new ArrayList<>();
        for (final byte[] node : branch) {
            encoded.add(Rlp.encodeBytes(node));
        }
        return Rlp.encodeList(encoded);
    }

    static byte[][] zeroBranch(final int depth) {
        final byte[][] branch = new byte[depth][];
        for (int i = 0; i < depth; i++) {
            branch[i] = new byte[32];
        }
        return branch;
    }

    static byte[] concat(final byte[]... parts) {
        int length = 0;
        for (final byte[] part : parts) {
            length += part.length;
        }
        final ByteBuffer out = ByteBuffer.allocate(length);
        for (final byte[] part : parts) {
            out.put(part);
        }
        return out.array();
    }

    private static byte[] toBytes32(final BigInteger value) {
        final byte[] raw = value.toByteArray();
        final byte[] out = new byte[32];
        final int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, out, 32 - length, length);
        return out;
    }
}
