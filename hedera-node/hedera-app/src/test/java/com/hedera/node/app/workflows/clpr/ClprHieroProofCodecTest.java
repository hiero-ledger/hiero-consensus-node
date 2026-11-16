// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_256;
import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_384;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.block.stream.HieroBlockWitness;
import com.hedera.hapi.block.stream.HieroProof;
import com.hedera.hapi.block.stream.HintsKeyProvenanceChain;
import com.hedera.hapi.block.stream.HintsKeyProvenanceLink;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClprHieroProofCodecTest {
    @Test
    void compactionRetainsMerklePathsAndOpaqueLeafBytes() {
        final var paths = List.of(
                MerklePath.newBuilder()
                        .nextPathIndex(-1)
                        .stateItemLeaf(Bytes.fromHex("1a02100112020802"))
                        .build(),
                MerklePath.newBuilder().nextPathIndex(-1).hash(bytes(32, 7)).build());
        final var key = bytes(VK_LENGTH, 1);
        final var compactSignature = key.append(bytes(SIGNATURE_LENGTH, 2));
        final var nativeSignature = compactSignature.append(bytes(11_368, 3));
        final var wire = StateProof.PROTOBUF.toBytes(stateProof(paths, nativeSignature));
        final var expectedProof = stateProof(paths, compactSignature);
        final var expected = StateProof.PROTOBUF.toBytes(expectedProof);

        final var compact = ClprHieroProofCodec.compactStateProof(wire);
        assertEquals(key, compact.verificationKey());
        assertEquals(expected, compact.payload());
        assertEquals(compact, ClprHieroProofCodec.compactStateProof(expected));

        final var envelope = ClprHieroProofCodec.encodeStateProof(compact, List.of());
        assertEquals(ClprHieroProofCodec.stateProofEnvelopeSize(compact, 0), envelope.length());
        final var input = envelope.toReadableSequentialData();
        assertVersion(input);
        assertEquals(expected, readBytesField(input, 2));
        assertFalse(input.hasRemaining());
        final var expectedEnvelope = HieroProof.newBuilder()
                .formatVersion(1)
                .stateProof(expectedProof)
                .build();
        assertEquals(expectedEnvelope, ClprHieroProofCodec.decodeEnvelope(envelope));
        assertEquals(HieroProof.PROTOBUF.toBytes(expectedEnvelope), envelope);
        assertEquals(envelope, ClprHieroProofCodec.encodeStateProof(compact, List.of()));
    }

    @Test
    void witnessUsesContractFieldsAndExactSize() {
        final var head = bytes(VK_LENGTH, 1);
        final var successor = bytes(VK_LENGTH, 2);
        final var linkSignature = bytes(SIGNATURE_LENGTH, 3);
        final var link = HintsKeyProvenance.newBuilder()
                .sourceVerificationKey(head)
                .targetVerificationKey(successor)
                .signature(linkSignature)
                .build();
        final var timestamp = new Timestamp(1_783_000_000, 987_654_321);
        final var sibling = bytes(32, 4);
        final var signature = bytes(SIGNATURE_LENGTH, 5);
        final var witness = HintsKeyWitness.newBuilder()
                .timestamp(timestamp)
                .siblingHash(sibling)
                .signature(signature)
                .hashAlgorithm(SHA2_256)
                .build();
        final var envelope = ClprHieroProofCodec.encodeWitness(List.of(link), witness);
        assertEquals(ClprHieroProofCodec.witnessEnvelopeSize(1, witness), envelope.length());

        final var input = envelope.toReadableSequentialData();
        assertVersion(input);
        final var chainInput = readBytesField(input, 3).toReadableSequentialData();
        assertEquals(head, readBytesField(chainInput, 1));
        final var linkInput = readBytesField(chainInput, 2).toReadableSequentialData();
        assertEquals(successor, readBytesField(linkInput, 1));
        assertEquals(linkSignature, readBytesField(linkInput, 2));
        assertFalse(linkInput.hasRemaining());
        assertFalse(chainInput.hasRemaining());
        final var witnessInput = readBytesField(input, 4).toReadableSequentialData();
        assertEquals(Timestamp.PROTOBUF.toBytes(timestamp), readBytesField(witnessInput, 1));
        assertEquals(sibling, readBytesField(witnessInput, 2));
        assertEquals(signature, readBytesField(witnessInput, 3));
        assertFalse(witnessInput.hasRemaining());
        assertFalse(input.hasRemaining());
        final var expectedEnvelope = HieroProof.newBuilder()
                .formatVersion(1)
                .keyProvenance(new HintsKeyProvenanceChain(
                        head, List.of(new HintsKeyProvenanceLink(successor, linkSignature))))
                .blockWitness(new HieroBlockWitness(Timestamp.PROTOBUF.toBytes(timestamp), sibling, signature))
                .build();
        assertEquals(expectedEnvelope, ClprHieroProofCodec.decodeEnvelope(envelope));
        assertEquals(HieroProof.PROTOBUF.toBytes(expectedEnvelope), envelope);
        assertEquals(envelope, ClprHieroProofCodec.encodeWitness(List.of(link), witness));

        final var sha384Witness = witness.copyBuilder()
                .hashAlgorithm(SHA2_384)
                .siblingHash(bytes(48, 4))
                .build();
        assertFalse(ClprHieroProofCodec.supportsWitness(sha384Witness));
        assertThrows(
                IllegalArgumentException.class, () -> ClprHieroProofCodec.encodeWitness(List.of(link), sha384Witness));
    }

    @Test
    void rejectsNoncanonicalEnvelopeEncoding() {
        final var proof = stateProof(
                List.of(MerklePath.newBuilder().hash(bytes(32, 1)).build()), bytes(VK_LENGTH + SIGNATURE_LENGTH, 2));
        final var valid = HieroProof.PROTOBUF.toBytes(
                HieroProof.newBuilder().formatVersion(1).stateProof(proof).build());
        final var stateField = valid.slice(2, valid.length() - 2);
        final var invalid = List.of(
                stateField.append(Bytes.fromHex("0801")), // Out of order
                Bytes.fromHex("088100").append(stateField), // Nonminimal version varint
                Bytes.fromHex("0801").append(valid), // Duplicate version
                valid.append(Bytes.fromHex("1a00")), // Empty chain
                valid.append(Bytes.fromHex("2801")), // Unknown field
                valid.append(Bytes.fromHex("00"))); // Trailing byte

        for (final var wire : invalid) {
            assertThrows(IllegalArgumentException.class, () -> ClprHieroProofCodec.decodeEnvelope(wire));
        }
    }

    @Test
    void rejectsRepeatedKeyAndMismatchedStateProofSigner() {
        final var head = bytes(VK_LENGTH, 1);
        final var linkSignature = bytes(SIGNATURE_LENGTH, 2);
        final var proof = stateProof(
                List.of(MerklePath.newBuilder().hash(bytes(32, 3)).build()), bytes(VK_LENGTH + SIGNATURE_LENGTH, 4));
        final var envelope = HieroProof.newBuilder().formatVersion(1).stateProof(proof);
        for (final var badSuccessor : List.of(head, bytes(VK_LENGTH, 5))) {
            final var chain =
                    new HintsKeyProvenanceChain(head, List.of(new HintsKeyProvenanceLink(badSuccessor, linkSignature)));
            final var wire =
                    HieroProof.PROTOBUF.toBytes(envelope.keyProvenance(chain).build());
            assertThrows(IllegalArgumentException.class, () -> ClprHieroProofCodec.decodeEnvelope(wire));
        }
    }

    private static StateProof stateProof(final List<MerklePath> paths, final Bytes signature) {
        return StateProof.newBuilder()
                .paths(paths)
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(signature)
                        .build())
                .build();
    }

    private static Bytes bytes(final int length, final int value) {
        final var data = new byte[length];
        Arrays.fill(data, (byte) value);
        return Bytes.wrap(data);
    }

    private static void assertVersion(final ReadableSequentialData input) {
        assertEquals(8, input.readVarInt(false));
        assertEquals(1, input.readVarInt(false));
    }

    private static Bytes readBytesField(final ReadableSequentialData input, final int fieldNumber) {
        assertEquals((fieldNumber << 3) | 2, input.readVarInt(false));
        return input.readBytes(input.readVarInt(false));
    }
}
