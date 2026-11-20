// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static com.hedera.hapi.node.base.BlockHashAlgorithm.SHA2_256;
import static com.hedera.node.app.hapi.utils.MiscCryptoUtils.keccak256DigestOf;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.block.stream.HieroProof;
import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.node.app.hapi.utils.blocks.StateProofVerifier;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.history.impl.HintsKeyWitnessVerifier;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Test;

class ClprHieroProofSelfCheckTest {
    private static final Bytes INITIAL_KEY = bytes(VK_LENGTH, 1);
    private static final Bytes SIGNER = bytes(VK_LENGTH, 2);
    private static final Bytes CERT_SIG = bytes(SIGNATURE_LENGTH, 3);
    private static final Bytes BLOCK_SIG = bytes(SIGNATURE_LENGTH, 4);
    private static final Bytes ANCHOR_ID = keccak256DigestOf(INITIAL_KEY);
    private static final HintsKeyProvenance LINK = HintsKeyProvenance.newBuilder()
            .sourceVerificationKey(INITIAL_KEY)
            .targetVerificationKey(SIGNER)
            .signature(CERT_SIG)
            .build();
    private static final HintsKeyWitness WITNESS = HintsKeyWitness.newBuilder()
            .timestamp(new Timestamp(100, 0))
            .siblingHash(bytes(32, 5))
            .signature(BLOCK_SIG)
            .hashAlgorithm(SHA2_256)
            .build();

    private final HintsLibrary library = mock(HintsLibrary.class);
    private final ClprHieroProofSelfCheck subject =
            new ClprHieroProofSelfCheck(library, new HintsKeyWitnessVerifier(library), mock(Metrics.class));

    @Test
    void validatesWitnessAndCertificateAtEvmThreshold() {
        final var envelope = ClprHieroProofCodec.encodeWitness(List.of(LINK), WITNESS);
        given(library.verifyAggregate(CERT_SIG, SIGNER, INITIAL_KEY, 1, 3)).willReturn(true);
        given(library.verifyAggregate(BLOCK_SIG, HintsKeyWitnessVerifier.rootHash(WITNESS), SIGNER, 1, 3))
                .willReturn(true);

        assertThat(subject.verify(envelope, ANCHOR_ID)).isTrue();
        assertThat(subject.verify(envelope, keccak256DigestOf(SIGNER))).isFalse();
        assertThat(subject.verify(
                        ClprHieroProofCodec.encodeWitness(
                                List.of(LINK.copyBuilder()
                                        .signature(bytes(SIGNATURE_LENGTH, 6))
                                        .build()),
                                WITNESS),
                        ANCHOR_ID))
                .isFalse();
        assertThat(subject.verify(
                        ClprHieroProofCodec.encodeWitness(
                                List.of(LINK),
                                WITNESS.copyBuilder().siblingHash(bytes(32, 6)).build()),
                        ANCHOR_ID))
                .isFalse();
    }

    @Test
    void validatesTheSignerAndEveryStatePathAgainstTheSameRoot() {
        final var path = MerklePath.newBuilder()
                .stateItemLeaf(bytes(24, 7))
                .nextPathIndex(-1)
                .build();
        final var signedBlockProof = TssSignedBlockProof.newBuilder()
                .blockSignature(SIGNER.append(BLOCK_SIG))
                .build();
        final var root =
                Bytes.wrap(StateProofVerifier.computeBlockRootHashFromPath(path, DigestType.SHA_256.buildDigest()));
        given(library.verifyAggregate(CERT_SIG, SIGNER, INITIAL_KEY, 1, 3)).willReturn(true);
        given(library.verifyAggregate(BLOCK_SIG, root, SIGNER, 1, 3)).willReturn(true);
        final var state = StateProof.newBuilder()
                .paths(path, path)
                .signedBlockProof(signedBlockProof)
                .build();
        final var envelope = ClprHieroProofCodec.encodeStateProof(
                ClprHieroProofCodec.compactStateProof(StateProof.PROTOBUF.toBytes(state)), List.of(LINK));
        final var proof = ClprHieroProofCodec.decodeEnvelope(envelope);
        assertThat(subject.verify(envelope, ANCHOR_ID)).isTrue();
        assertThat(subject.verify(
                        HieroProof.PROTOBUF.toBytes(proof.copyBuilder()
                                .stateProof(state.copyBuilder()
                                        .paths(
                                                path,
                                                path.copyBuilder()
                                                        .stateItemLeaf(bytes(24, 8))
                                                        .build())
                                        .build())
                                .build()),
                        ANCHOR_ID))
                .isFalse();
        assertThat(subject.verify(
                        HieroProof.PROTOBUF.toBytes(proof.copyBuilder()
                                .stateProof(state.copyBuilder()
                                        .paths(
                                                path,
                                                path.copyBuilder()
                                                        .nextPathIndex(0)
                                                        .build())
                                        .build())
                                .build()),
                        ANCHOR_ID))
                .isFalse();
    }

    private static Bytes bytes(final int length, final int first) {
        final var data = new byte[length];
        data[0] = (byte) first;
        return Bytes.wrap(data);
    }
}
