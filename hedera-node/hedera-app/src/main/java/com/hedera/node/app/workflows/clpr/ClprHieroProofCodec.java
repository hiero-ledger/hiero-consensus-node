// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_256;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;

import com.hedera.hapi.block.stream.HieroBlockWitness;
import com.hedera.hapi.block.stream.HieroProof;
import com.hedera.hapi.block.stream.HintsKeyProvenanceChain;
import com.hedera.hapi.block.stream.HintsKeyProvenanceLink;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.node.app.history.impl.HintsKeyWitnessVerifier;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Encodes compact Hiero proofs for the Solidity verifier's version 1 envelope. */
final class ClprHieroProofCodec {
    static final int MAX_LINKS = 8;
    static final int MAX_STATE_PROOF_PATHS = 64;
    private static final int MAX_PATH_SIBLINGS = 64;
    private static final int FORMAT_VERSION = 1;
    private static final int FORMAT_VERSION_FIELD_SIZE = 2;
    private static final int COMPACT_SIGNATURE_LENGTH = VK_LENGTH + SIGNATURE_LENGTH;
    private static final int COMPRESSED_WRAPS_LENGTH = 11_368;
    private static final int NATIVE_SIGNATURE_LENGTH = COMPACT_SIGNATURE_LENGTH + COMPRESSED_WRAPS_LENGTH;
    private static final int SHA_256_LENGTH = 32;
    private static final int MAX_WITNESS_TIMESTAMP_LENGTH = 16;
    private static final long LINK_SIZE = bytesFieldSize(VK_LENGTH) + bytesFieldSize(SIGNATURE_LENGTH);

    private ClprHieroProofCodec() {}

    record CompactStateProof(
            @NonNull Bytes verificationKey, @NonNull Bytes payload) {}

    /**
     * Removes the WRAPS suffix of the block signature while retaining the Merkle paths and their opaque leaf bytes.
     *
     * @throws IllegalArgumentException if the proof has no paths, no signed block proof, or an unsupported signature
     *     layout
     */
    static @NonNull CompactStateProof compactStateProof(@NonNull final Bytes nativeProof) {
        final var proof = parseStateProof(nativeProof);
        validatePaths(proof);
        final var signature = compactSignature(blockSignature(proof));
        final var compact = proof.copyBuilder()
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(signature)
                        .build())
                .build();
        return new CompactStateProof(signature.slice(0, VK_LENGTH), StateProof.PROTOBUF.toBytes(compact));
    }

    static @NonNull Bytes compactSignature(@NonNull final Bytes tssSignature) {
        if (tssSignature.length() == COMPACT_SIGNATURE_LENGTH) {
            return tssSignature;
        }
        if (tssSignature.length() != NATIVE_SIGNATURE_LENGTH) {
            throw new IllegalArgumentException("Expected a compact or WRAPS-bearing hinTS block signature");
        }
        return tssSignature.slice(0, COMPACT_SIGNATURE_LENGTH);
    }

    static @NonNull Bytes encodeStateProof(
            @NonNull final CompactStateProof proof, @NonNull final List<HintsKeyProvenance> links) {
        final var builder =
                HieroProof.newBuilder().formatVersion(FORMAT_VERSION).stateProof(parseStateProof(proof.payload()));
        if (!links.isEmpty()) {
            builder.keyProvenance(provenanceChain(links));
        }
        return encodeEnvelope(builder.build());
    }

    static boolean supportsWitness(@Nullable final HintsKeyWitness witness) {
        return witness != null
                && witness.hashAlgorithm() == SHA2_256
                && HintsKeyWitnessVerifier.hasValidStructure(witness);
    }

    static @NonNull Bytes encodeWitness(
            @NonNull final List<HintsKeyProvenance> links, @NonNull final HintsKeyWitness witness) {
        if (!supportsWitness(witness)) {
            throw new IllegalArgumentException("The compact envelope requires a valid SHA-256 adoption witness");
        }
        return encodeEnvelope(HieroProof.newBuilder()
                .formatVersion(FORMAT_VERSION)
                .keyProvenance(provenanceChain(links))
                .blockWitness(HieroBlockWitness.newBuilder()
                        .timestamp(Timestamp.PROTOBUF.toBytes(witness.timestampOrThrow()))
                        .siblingHash(witness.siblingHash())
                        .signature(witness.signature())
                        .build())
                .build());
    }

    /**
     * Reads a locally produced envelope for the sender self-check. PBJ's strict parser rejects unknown fields,
     * and a byte-for-byte round trip additionally requires canonical order, varints and field presence.
     *
     * @throws IllegalArgumentException if the encoding or proof structure is invalid
     */
    static @NonNull HieroProof decodeEnvelope(@NonNull final Bytes wire) {
        try {
            final var proof = HieroProof.PROTOBUF.parseStrict(wire);
            if (!HieroProof.PROTOBUF.toBytes(proof).equals(wire)) {
                throw new IllegalArgumentException("Noncanonical HieroProof encoding");
            }
            validateEnvelope(proof);
            return proof;
        } catch (final ParseException e) {
            throw new IllegalArgumentException("Malformed HieroProof", e);
        }
    }

    static long stateProofEnvelopeSize(@NonNull final CompactStateProof proof, final int linkCount) {
        return stateProofEnvelopeSize(proof.payload().length(), linkCount);
    }

    static long stateProofEnvelopeSize(final long compactProofSize, final int linkCount) {
        return FORMAT_VERSION_FIELD_SIZE
                + bytesFieldSize(compactProofSize)
                + (linkCount == 0 ? 0 : bytesFieldSize(chainSize(linkCount)));
    }

    static long witnessEnvelopeSize(final int linkCount, @NonNull final HintsKeyWitness witness) {
        if (!supportsWitness(witness)) {
            throw new IllegalArgumentException("The compact envelope requires a valid SHA-256 adoption witness");
        }
        final int timestampSize = Timestamp.PROTOBUF.measureRecord(witness.timestampOrThrow());
        if (timestampSize == 0 || timestampSize > MAX_WITNESS_TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException("Unsupported adoption witness timestamp length");
        }
        return FORMAT_VERSION_FIELD_SIZE
                + bytesFieldSize(chainSize(linkCount))
                + bytesFieldSize(witnessSize(timestampSize));
    }

    private static long witnessSize(final long timestampSize) {
        return bytesFieldSize(timestampSize) + bytesFieldSize(SHA_256_LENGTH) + bytesFieldSize(SIGNATURE_LENGTH);
    }

    private static long chainSize(final int linkCount) {
        validateLinkCount(linkCount);
        return bytesFieldSize(VK_LENGTH) + linkCount * bytesFieldSize(LINK_SIZE);
    }

    private static void validateLinkCount(final int linkCount) {
        if (linkCount < 1 || linkCount > MAX_LINKS) {
            throw new IllegalArgumentException("Provenance chain must have between one and eight links");
        }
    }

    private static @NonNull HintsKeyProvenanceChain provenanceChain(@NonNull final List<HintsKeyProvenance> links) {
        validateLinkCount(links.size());
        final var head = links.getFirst().sourceVerificationKey();
        final var wireLinks = new ArrayList<HintsKeyProvenanceLink>(links.size());
        var predecessor = head;
        for (final var link : links) {
            if (!link.sourceVerificationKey().equals(predecessor)) {
                throw new IllegalArgumentException("Discontinuous provenance chain");
            }
            predecessor = link.targetVerificationKey();
            wireLinks.add(new HintsKeyProvenanceLink(predecessor, link.signature()));
        }
        return new HintsKeyProvenanceChain(head, wireLinks);
    }

    private static @NonNull Bytes encodeEnvelope(@NonNull final HieroProof proof) {
        validateEnvelope(proof);
        return HieroProof.PROTOBUF.toBytes(proof);
    }

    private static void validateEnvelope(@NonNull final HieroProof proof) {
        final var stateProof = proof.stateProof();
        final var witness = proof.blockWitness();
        final var chain = proof.keyProvenance();
        if (proof.formatVersion() != FORMAT_VERSION || (stateProof == null) == (witness == null)) {
            throw new IllegalArgumentException("Expected a version 1 envelope with exactly one proof form");
        }
        if (chain != null) {
            validateChain(chain);
        }
        if (stateProof != null) {
            validatePaths(stateProof);
            final var signature = blockSignature(stateProof);
            if (signature.length() != COMPACT_SIGNATURE_LENGTH) {
                throw new IllegalArgumentException("StateProof must carry a compact block signature");
            }
            if (chain != null && !chain.links().getLast().nextVerificationKey().equals(signature.slice(0, VK_LENGTH))) {
                throw new IllegalArgumentException("Provenance chain does not reach the state proof's signing key");
            }
        }
        if (witness != null
                && (chain == null
                        || witness.timestamp().length() == 0
                        || witness.timestamp().length() > MAX_WITNESS_TIMESTAMP_LENGTH
                        || witness.siblingHash().length() != SHA_256_LENGTH
                        || witness.signature().length() != SIGNATURE_LENGTH)) {
            throw new IllegalArgumentException("Invalid compact block witness");
        }
    }

    private static void validateChain(@NonNull final HintsKeyProvenanceChain chain) {
        validateLinkCount(chain.links().size());
        final var head = chain.initialVerificationKey();
        if (head.length() != VK_LENGTH) {
            throw new IllegalArgumentException("Invalid provenance chain head key length");
        }
        final var visited = new HashSet<Bytes>();
        visited.add(head);
        for (final var link : chain.links()) {
            if (link.nextVerificationKey().length() != VK_LENGTH
                    || link.signature().length() != SIGNATURE_LENGTH
                    || !visited.add(link.nextVerificationKey())) {
                throw new IllegalArgumentException("Invalid or repeated provenance chain link");
            }
        }
    }

    private static @NonNull StateProof parseStateProof(@NonNull final Bytes wire) {
        try {
            return StateProof.PROTOBUF.parseStrict(wire);
        } catch (final ParseException e) {
            throw new IllegalArgumentException("Malformed StateProof", e);
        }
    }

    private static @NonNull Bytes blockSignature(@NonNull final StateProof proof) {
        final var signedBlock = proof.signedBlockProof();
        if (signedBlock == null) {
            throw new IllegalArgumentException("StateProof has no signed block proof");
        }
        return signedBlock.blockSignature();
    }

    private static void validatePaths(@NonNull final StateProof proof) {
        if (proof.paths().isEmpty()
                || proof.paths().size() > MAX_STATE_PROOF_PATHS
                || proof.paths().stream().anyMatch(path -> path.siblings().size() > MAX_PATH_SIBLINGS)) {
            throw new IllegalArgumentException("StateProof exceeds compact verifier path bounds or has no paths");
        }
    }

    /** All field numbers in this encoding fit in a single tag byte. */
    private static long bytesFieldSize(final long payloadLength) {
        int lengthBytes = 1;
        for (long remaining = payloadLength; remaining >= 128; remaining >>>= 7) {
            lengthBytes++;
        }
        return 1 + lengthBytes + payloadLength;
    }
}
