// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.sei;

import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.bytesField;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.concat;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.delimited;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.messageField;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.sfixed64Field;
import static com.hedera.node.app.service.clpr.impl.verifier.sei.SeiProto.varintField;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.SeiBlockRef;
import com.hedera.hapi.node.state.clpr.SeiHeader;
import com.hedera.hapi.node.state.clpr.SeiValidatorEntry;
import com.hedera.hapi.node.state.clpr.SeiValidatorSet;
import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * CometBFT hash, encoding, and signature computations the Sei verifier authenticates headers
 * with. All encodings were validated byte-for-byte against Sei testnet ({@code atlantic-2})
 * data; sei-tendermint is wire-compatible with upstream Tendermint for all of them.
 *
 * <ol>
 *   <li>{@link #headerHash}: simple Merkle root over the 14 cdc-encoded header fields,
 *       which must equal the {@code BlockID} hash that validators sign;</li>
 *   <li>{@link #validatorSetHash}: simple Merkle root over encoded {@code SimpleValidator}
 *       leaves, which must equal the header's {@code validators_hash};</li>
 *   <li>{@link #precommitSignBytes}: the length-delimited canonical vote each validator
 *       signs with Ed25519 — note every signature in a commit covers a <i>different</i>
 *       message, because each carries that validator's own timestamp.</li>
 * </ol>
 */
public final class SeiHashing {

    /** {@code SignedMsgType.SIGNED_MSG_TYPE_PRECOMMIT}. */
    private static final int PRECOMMIT_TYPE = 2;

    /** Length in bytes of a CometBFT validator address: first 20 bytes of SHA256(pubkey). */
    public static final int ADDRESS_LENGTH = 20;

    /** Length in bytes of an Ed25519 public key. */
    public static final int ED25519_PUB_KEY_LENGTH = 32;

    /** Length in bytes of an Ed25519 signature. */
    private static final int SIGNATURE_LENGTH = 64;

    private SeiHashing() {}

    /**
     * Computes the block-header hash: the simple Merkle root of the 14 header fields, each
     * cdc-encoded (primitives wrapped in gogo wrapper messages, messages marshaled directly).
     */
    @NonNull
    public static byte[] headerHash(@NonNull final SeiHeader header) {
        Objects.requireNonNull(header, "header");
        final var time = header.timeOrElse(Timestamp.DEFAULT);
        final List<byte[]> fields = List.of(
                // tmversion.Consensus{block, app}
                concat(varintField(1, header.versionBlock()), varintField(2, header.versionApp())),
                // gogo StringValue / Int64Value wrappers for primitives
                bytesField(1, header.chainId().getBytes(StandardCharsets.UTF_8)),
                varintField(1, header.height()),
                encodeTimestamp(time),
                encodeBlockId(header.lastBlockIdOrElse(SeiBlockRef.DEFAULT)),
                bytesField(1, header.lastCommitHash().toByteArray()),
                bytesField(1, header.dataHash().toByteArray()),
                bytesField(1, header.validatorsHash().toByteArray()),
                bytesField(1, header.nextValidatorsHash().toByteArray()),
                bytesField(1, header.consensusHash().toByteArray()),
                bytesField(1, header.appHash().toByteArray()),
                bytesField(1, header.lastResultsHash().toByteArray()),
                bytesField(1, header.evidenceHash().toByteArray()),
                bytesField(1, header.proposerAddress().toByteArray()));
        return SeiMerkle.root(fields);
    }

    /**
     * Computes the validator-set hash: the simple Merkle root over
     * {@code SimpleValidator{pub_key{ed25519}, voting_power}} leaves, in the given order
     * (CometBFT canonical order: descending voting power, ties by address).
     */
    @NonNull
    public static byte[] validatorSetHash(@NonNull final SeiValidatorSet validatorSet) {
        Objects.requireNonNull(validatorSet, "validatorSet");
        final List<SeiValidatorEntry> validators = validatorSet.validators();
        if (validators.isEmpty()) {
            throw ProofException.sei("validator set is empty");
        }
        return SeiMerkle.root(
                validators.stream().map(SeiHashing::encodeValidator).toList());
    }

    /**
     * Builds the exact bytes a validator signs for a precommit on a block: the
     * varint-length-delimited canonical vote
     * {@code CanonicalVote{type=PRECOMMIT, height(sfixed64), round(sfixed64), block_id,
     * timestamp, chain_id}}. The timestamp is the individual {@code CommitSig} timestamp,
     * not the block time.
     */
    @NonNull
    public static byte[] precommitSignBytes(
            @NonNull final String chainId,
            final long height,
            final long round,
            @NonNull final SeiBlockRef blockId,
            @NonNull final Timestamp timestamp) {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(timestamp, "timestamp");
        final byte[] canonicalPartSetHeader = concat(
                varintField(1, blockId.partSetTotal()),
                bytesField(2, blockId.partSetHash().toByteArray()));
        final byte[] canonicalBlockId =
                concat(bytesField(1, blockId.hash().toByteArray()), messageField(2, canonicalPartSetHeader));
        final byte[] canonicalVote = concat(
                varintField(1, PRECOMMIT_TYPE),
                sfixed64Field(2, height),
                sfixed64Field(3, round),
                messageField(4, canonicalBlockId),
                messageField(5, encodeTimestamp(timestamp)),
                bytesField(6, chainId.getBytes(StandardCharsets.UTF_8)));
        return delimited(canonicalVote);
    }

    /** Derives a validator's address: the first 20 bytes of SHA-256 of its public key. */
    @NonNull
    public static byte[] validatorAddress(@NonNull final byte[] ed25519PubKey) {
        Objects.requireNonNull(ed25519PubKey, "ed25519PubKey");
        return Arrays.copyOf(SeiMerkle.sha256(ed25519PubKey), ADDRESS_LENGTH);
    }

    /**
     * Verifies an Ed25519 signature; returns {@code false} for malformed keys or signatures so
     * callers can treat invalid input as an unsigned vote.
     */
    public static boolean verifyEd25519(
            @NonNull final byte[] publicKey32, @NonNull final byte[] message, @NonNull final byte[] signature64) {
        Objects.requireNonNull(publicKey32, "publicKey32");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(signature64, "signature64");
        if (publicKey32.length != ED25519_PUB_KEY_LENGTH || signature64.length != SIGNATURE_LENGTH) {
            return false;
        }
        try {
            final var signer = new Ed25519Signer();
            signer.init(false, new Ed25519PublicKeyParameters(publicKey32, 0));
            signer.update(message, 0, message.length);
            return signer.verifySignature(signature64);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    @NonNull
    private static byte[] encodeValidator(@NonNull final SeiValidatorEntry validator) {
        final byte[] publicKey = validator.ed25519PubKey().toByteArray();
        if (publicKey.length != ED25519_PUB_KEY_LENGTH) {
            throw ProofException.sei(
                    "Ed25519 public key must be " + ED25519_PUB_KEY_LENGTH + " bytes, got " + publicKey.length);
        }
        if (validator.votingPower() <= 0) {
            throw ProofException.sei("validator voting power must be positive, got " + validator.votingPower());
        }
        return concat(messageField(1, bytesField(1, publicKey)), varintField(2, validator.votingPower()));
    }

    /** {@code google.protobuf.Timestamp{seconds, nanos}}. */
    @NonNull
    private static byte[] encodeTimestamp(@NonNull final Timestamp timestamp) {
        return concat(varintField(1, timestamp.seconds()), varintField(2, timestamp.nanos()));
    }

    /**
     * {@code tmproto.BlockID{hash, part_set_header}} — the part-set header is a
     * non-nullable embedded message, so it is written even when empty.
     */
    @NonNull
    private static byte[] encodeBlockId(@NonNull final SeiBlockRef blockId) {
        final byte[] partSetHeader = concat(
                varintField(1, blockId.partSetTotal()),
                bytesField(2, blockId.partSetHash().toByteArray()));
        return concat(bytesField(1, blockId.hash().toByteArray()), messageField(2, partSetHeader));
    }
}
