// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.bytesField;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.concat;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.messageField;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.varint;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.varintField;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.SeiBlockRef;
import com.hedera.hapi.node.state.clpr.SeiCommit;
import com.hedera.hapi.node.state.clpr.SeiCommitSig;
import com.hedera.hapi.node.state.clpr.SeiHeader;
import com.hedera.hapi.node.state.clpr.SeiSignedHeader;
import com.hedera.hapi.node.state.clpr.SeiStateProof;
import com.hedera.hapi.node.state.clpr.SeiStorageProofEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorSet;
import com.hedera.hapi.node.state.clpr.SeiValidatorSetUpdate;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * Builds a fully synthetic but cryptographically consistent CometBFT chain for verifier tests:
 * deterministic Ed25519 validators, a hand-built IAVL-shaped tree with ICS-23 existence proofs,
 * a two-store multistore commitment, and a signed header whose {@code app_hash} commits it all.
 * Inverts exactly the verification rules in {@link SeiIcs23}/{@link SeiHashing}, so it can also
 * produce targeted invalid variants for negative tests.
 */
final class SyntheticSeiChain {

    static final String CHAIN_ID = "clpr-sei-test";
    static final long HEIGHT = 1000;
    static final byte[] SERVICE_ADDRESS = sha20("clpr-service-contract");

    /** IAVL-ish inner-node domain prefix (must be 3 bytes, not starting with 0x00). */
    private static final byte[] IAVL_INNER_BASE = {0x02, 0x04, 0x06};

    /** Varint length prefix for a 32-byte child hash (ics23 childSize 33 = 0x20 + hash). */
    private static final byte[] CHILD_LEN = {0x20};

    /** IAVL leaf prefix: starts with 0x00 like real iavl leaf prefixes (height 0, size 1, version). */
    private static final byte[] IAVL_LEAF_PREFIX = {0x00, 0x02, 0x02};

    /** Tendermint simple-tree leaf prefix. */
    private static final byte[] SIMPLE_LEAF_PREFIX = {0x00};

    private SyntheticSeiChain() {}

    record Validator(
            @NonNull Ed25519PrivateKeyParameters privateKey,
            @NonNull byte[] publicKey,
            long votingPower) {}

    /** Deterministic validators with the given voting powers. */
    @NonNull
    static List<Validator> validators(final long... powers) {
        final List<Validator> validators = new ArrayList<>();
        for (int i = 0; i < powers.length; i++) {
            final byte[] seed = SeiMerkle.sha256(("validator-seed-" + i).getBytes(StandardCharsets.UTF_8));
            final var priv = new Ed25519PrivateKeyParameters(seed, 0);
            final var pub = priv.generatePublicKey().getEncoded();
            validators.add(new Validator(priv, pub, powers[i]));
        }
        return validators;
    }

    @NonNull
    static SeiValidatorSet toProtoSet(@NonNull final List<Validator> validators) {
        return SeiValidatorSet.newBuilder()
                .validators(validators.stream()
                        .map(v -> SeiValidatorEntry.newBuilder()
                                .ed25519PubKey(Bytes.wrap(v.publicKey()))
                                .votingPower(v.votingPower())
                                .build())
                        .toList())
                .build();
    }

    /** A built state proof plus the loose parts tests may want to tamper with. */
    record Chain(
            @NonNull SeiStateProof stateProof,
            @NonNull SeiHeader header,
            @NonNull SeiCommit commit,
            @NonNull byte[] headerHash,
            @NonNull byte[] appHash) {}

    /**
     * Builds a signed state proof for the given storage slots (key suffix -> 32-byte value),
     * signed by {@code signingValidators} (a subset of {@code validatorSet}), with
     * {@code next_validators_hash} taken from {@code nextSet} (pass the same set for no rotation).
     */
    @NonNull
    static Chain stateProof(
            @NonNull final List<Validator> validatorSet,
            @NonNull final List<Validator> signingValidators,
            @NonNull final List<Validator> nextSet,
            @NonNull final byte[][] slots32,
            @NonNull final byte[][] values32) {
        // ── IAVL layer: left-leaning chain over the slot leaves ──
        final byte[][] keys = new byte[slots32.length][];
        for (int i = 0; i < slots32.length; i++) {
            keys[i] = concat(new byte[] {0x03}, SERVICE_ADDRESS, slots32[i]);
        }
        final byte[][] leafHashes = new byte[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            leafHashes[i] = iavlLeafHash(keys[i], values32[i]);
        }
        // running[i] = fold of leaves 0..i
        final byte[][] running = new byte[keys.length][];
        running[0] = leafHashes[0];
        for (int i = 1; i < keys.length; i++) {
            running[i] = iavlInnerHash(running[i - 1], leafHashes[i]);
        }
        final byte[] storeRoot = running[keys.length - 1];

        // ── Multistore layer: two stores, "evm" then "zest" ──
        final byte[] otherStoreRoot = SeiMerkle.sha256("zest-store".getBytes(StandardCharsets.UTF_8));
        final byte[] evmLeaf = simpleLeafHash("evm".getBytes(StandardCharsets.UTF_8), storeRoot);
        final byte[] otherLeaf = simpleLeafHash("zest".getBytes(StandardCharsets.UTF_8), otherStoreRoot);
        final byte[] appHash = SeiMerkle.sha256(new byte[] {0x01}, evmLeaf, otherLeaf);
        final byte[] multistoreProof = commitmentProof(
                "evm".getBytes(StandardCharsets.UTF_8),
                storeRoot,
                SIMPLE_LEAF_PREFIX,
                List.of(new SeiIcs23.InnerOp(1, new byte[] {0x01}, otherLeaf)));

        // ── Header + commit signed by the requested validators ──
        final var headerProto = header(HEIGHT, appHash, toProtoSet(validatorSet), toProtoSet(nextSet));
        final byte[] headerHash = SeiHashing.headerHash(headerProto);
        final byte[] partsHash = SeiMerkle.sha256("parts".getBytes(StandardCharsets.UTF_8));
        final var blockId = SeiBlockRef.newBuilder()
                .hash(Bytes.wrap(headerHash))
                .partSetTotal(1)
                .partSetHash(Bytes.wrap(partsHash))
                .build();
        final var commit = commit(HEIGHT, blockId, validatorSet, signingValidators);

        // ── Per-slot ICS-23 proofs ──
        final List<SeiStorageProofEntry> entries = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            final List<SeiIcs23.InnerOp> path = new ArrayList<>();
            if (i > 0) {
                // right child of running[i-1]
                path.add(new SeiIcs23.InnerOp(
                        1, concat(IAVL_INNER_BASE, CHILD_LEN, running[i - 1], CHILD_LEN), new byte[0]));
            }
            for (int sibling = i + 1; sibling < keys.length; sibling++) {
                // left child with leaf `sibling` to the right
                path.add(new SeiIcs23.InnerOp(
                        1, concat(IAVL_INNER_BASE, CHILD_LEN), concat(CHILD_LEN, leafHashes[sibling])));
            }
            entries.add(SeiStorageProofEntry.newBuilder()
                    .key(Bytes.wrap(keys[i]))
                    .value(Bytes.wrap(values32[i]))
                    .iavlProof(Bytes.wrap(commitmentProof(keys[i], values32[i], IAVL_LEAF_PREFIX, path)))
                    .build());
        }

        final var stateProof = SeiStateProof.newBuilder()
                .signedHeader(SeiSignedHeader.newBuilder()
                        .header(headerProto)
                        .commit(commit)
                        .build())
                .storeKey(Bytes.wrap("evm".getBytes(StandardCharsets.UTF_8)))
                .multistoreProof(Bytes.wrap(multistoreProof))
                .storageProofs(entries)
                .build();
        return new Chain(stateProof, headerProto, commit, headerHash, appHash);
    }

    /**
     * Builds a signed header (header + commit, no storage proofs) at {@code height}, signed by
     * {@code signingValidators} out of {@code validatorSet}, announcing {@code nextSet} as the
     * next validator set. Used to construct {@link #validatorSetUpdate} rotation chains.
     */
    @NonNull
    static SeiSignedHeader signedHeader(
            final long height,
            @NonNull final List<Validator> validatorSet,
            @NonNull final List<Validator> signingValidators,
            @NonNull final List<Validator> nextSet) {
        final byte[] appHash = SeiMerkle.sha256(("app-hash-" + height).getBytes(StandardCharsets.UTF_8));
        final var headerProto = header(height, appHash, toProtoSet(validatorSet), toProtoSet(nextSet));
        final byte[] headerHash = SeiHashing.headerHash(headerProto);
        final byte[] partsHash = SeiMerkle.sha256("parts".getBytes(StandardCharsets.UTF_8));
        final var blockId = SeiBlockRef.newBuilder()
                .hash(Bytes.wrap(headerHash))
                .partSetTotal(1)
                .partSetHash(Bytes.wrap(partsHash))
                .build();
        return SeiSignedHeader.newBuilder()
                .header(headerProto)
                .commit(commit(height, blockId, validatorSet, signingValidators))
                .build();
    }

    /**
     * Builds one {@link SeiValidatorSetUpdate}: a signed header at {@code height} proving, against
     * {@code validatorSet}, a rotation to {@code nextSet}.
     */
    @NonNull
    static SeiValidatorSetUpdate validatorSetUpdate(
            final long height,
            @NonNull final List<Validator> validatorSet,
            @NonNull final List<Validator> signingValidators,
            @NonNull final List<Validator> nextSet) {
        return SeiValidatorSetUpdate.newBuilder()
                .signedHeader(signedHeader(height, validatorSet, signingValidators, nextSet))
                .nextValidatorSet(toProtoSet(nextSet))
                .build();
    }

    @NonNull
    private static SeiHeader header(
            final long height,
            final byte[] appHash,
            final SeiValidatorSet validators,
            final SeiValidatorSet nextValidators) {
        return SeiHeader.newBuilder()
                .versionBlock(11)
                .chainId(CHAIN_ID)
                .height(height)
                .time(Timestamp.newBuilder()
                        .seconds(1_750_000_000L)
                        .nanos(123_456_789)
                        .build())
                .lastBlockId(SeiBlockRef.newBuilder()
                        .hash(Bytes.wrap(SeiMerkle.sha256("prev".getBytes(StandardCharsets.UTF_8))))
                        .partSetTotal(1)
                        .partSetHash(Bytes.wrap(SeiMerkle.sha256("prev-parts".getBytes(StandardCharsets.UTF_8))))
                        .build())
                .lastCommitHash(hash32("last-commit"))
                .dataHash(hash32("data"))
                .validatorsHash(Bytes.wrap(SeiHashing.validatorSetHash(validators)))
                .nextValidatorsHash(Bytes.wrap(SeiHashing.validatorSetHash(nextValidators)))
                .consensusHash(hash32("consensus"))
                .appHash(Bytes.wrap(appHash))
                .lastResultsHash(hash32("results"))
                .evidenceHash(hash32("evidence"))
                .proposerAddress(Bytes.wrap(sha20("proposer")))
                .build();
    }

    @NonNull
    private static SeiCommit commit(
            final long height,
            final SeiBlockRef blockId,
            final List<Validator> validatorSet,
            final List<Validator> signers) {
        final byte[] signersBits = new byte[(validatorSet.size() + 7) / 8];
        final List<SeiCommitSig> signatures = new ArrayList<>();
        for (int i = 0; i < validatorSet.size(); i++) {
            final Validator validator = validatorSet.get(i);
            if (!containsValidator(signers, validator)) {
                continue;
            }
            signersBits[i / 8] |= (byte) (0x80 >>> (i % 8));
            final long seconds = 1_750_000_001L;
            final int nanos = 1_000 * (i + 1); // every validator signs its own timestamp
            final var timestamp =
                    Timestamp.newBuilder().seconds(seconds).nanos(nanos).build();
            final byte[] signBytes = SeiHashing.precommitSignBytes(CHAIN_ID, height, 0, blockId, timestamp);
            final var signer = new Ed25519Signer();
            signer.init(true, validator.privateKey());
            signer.update(signBytes, 0, signBytes.length);
            signatures.add(SeiCommitSig.newBuilder()
                    .timestamp(timestamp)
                    .signature(Bytes.wrap(signer.generateSignature()))
                    .build());
        }
        return SeiCommit.newBuilder()
                .round(0)
                .partSetTotal(blockId.partSetTotal())
                .partSetHash(blockId.partSetHash())
                .signersBits(Bytes.wrap(signersBits))
                .signatures(signatures)
                .build();
    }

    private static boolean containsValidator(final List<Validator> validators, final Validator target) {
        for (final Validator validator : validators) {
            if (Arrays.equals(validator.publicKey(), target.publicKey())) {
                return true;
            }
        }
        return false;
    }

    // ── proof-byte builders (encode the ics23 protobuf wire form with SeiProto) ──

    @NonNull
    private static byte[] commitmentProof(
            final byte[] key, final byte[] value, final byte[] leafPrefix, final List<SeiIcs23.InnerOp> path) {
        final byte[] leafOp = concat(
                varintField(1, 1), // hash = SHA256
                varintField(3, 1), // prehash_value = SHA256
                varintField(4, 1), // length = VAR_PROTO
                bytesField(5, leafPrefix));
        byte[] exist = concat(bytesField(1, key), bytesField(2, value), messageField(3, leafOp));
        for (final SeiIcs23.InnerOp op : path) {
            final byte[] innerOp =
                    concat(varintField(1, op.hashOp()), bytesField(2, op.prefix()), bytesField(3, op.suffix()));
            exist = concat(exist, messageField(4, innerOp));
        }
        return messageField(1, exist);
    }

    @NonNull
    private static byte[] iavlLeafHash(final byte[] key, final byte[] value) {
        final byte[] hashedValue = SeiMerkle.sha256(value);
        return SeiMerkle.sha256(IAVL_LEAF_PREFIX, varint(key.length), key, varint(hashedValue.length), hashedValue);
    }

    @NonNull
    private static byte[] iavlInnerHash(final byte[] left, final byte[] right) {
        return SeiMerkle.sha256(IAVL_INNER_BASE, CHILD_LEN, left, CHILD_LEN, right);
    }

    @NonNull
    private static byte[] simpleLeafHash(final byte[] key, final byte[] value) {
        final byte[] hashedValue = SeiMerkle.sha256(value);
        return SeiMerkle.sha256(SIMPLE_LEAF_PREFIX, varint(key.length), key, varint(hashedValue.length), hashedValue);
    }

    @NonNull
    static byte[] sha20(final String tag) {
        final byte[] full = SeiMerkle.sha256(tag.getBytes(StandardCharsets.UTF_8));
        final byte[] out = new byte[20];
        System.arraycopy(full, 0, out, 0, 20);
        return out;
    }

    @NonNull
    static Bytes hash32(final String tag) {
        return Bytes.wrap(SeiMerkle.sha256(tag.getBytes(StandardCharsets.UTF_8)));
    }
}
