// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.blocks.impl.BlockImplUtils.hashLeaf;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.SIGNATURE_LENGTH;
import static com.hedera.node.app.hints.impl.HintsLibraryImpl.VK_LENGTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.history.HintsKeyProvenance;
import com.hedera.hapi.node.state.history.HintsKeyWitness;
import com.hedera.node.app.blocks.impl.BlockRootTree;
import com.hedera.node.app.blocks.impl.BlockRootTreeHasher;
import com.hedera.node.app.blocks.impl.StreamingBlockRootTreeHasher;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsKeyWitnessVerifierTest {
    private static final Bytes VK = Bytes.wrap(new byte[VK_LENGTH]);
    private static final Bytes SIGNATURE = Bytes.wrap(new byte[SIGNATURE_LENGTH]);
    private static final Timestamp TIMESTAMP = new Timestamp(1_700_000_000L, 123);

    @Mock
    private HintsLibrary library;

    private HintsKeyWitnessVerifier subject;

    @BeforeEach
    void setUp() {
        subject = new HintsKeyWitnessVerifier(library);
    }

    @ParameterizedTest
    @EnumSource(
            value = BlockHashAlgorithm.class,
            names = {"SHA2_256", "SHA2_384"})
    void reconstructsExactlyTheProductionBlockRoot(final BlockHashAlgorithm algorithm) {
        final var digest = algorithm == BlockHashAlgorithm.SHA2_256 ? DigestType.SHA_256 : DigestType.SHA_384;
        final var branches = new Bytes[BlockRootTreeHasher.SLOT_COUNT];
        for (int i = 0; i < BlockRootTreeHasher.ASSIGNED_SLOT_COUNT; i++) {
            branches[i] = hashLeaf(digest.buildDigest(), Bytes.wrap("branch " + i));
        }
        Arrays.fill(
                branches,
                BlockRootTreeHasher.ASSIGNED_SLOT_COUNT,
                branches.length,
                BlockRootTreeHasher.emptySubtreeFor(digest));
        final var witness = witness(algorithm)
                .copyBuilder()
                .siblingHash(StreamingBlockRootTreeHasher.streamedRootOf(digest::buildDigest, branches))
                .build();
        final var expected = BlockRootTree.computeBlockRootHash(
                digest, TIMESTAMP, Arrays.copyOf(branches, BlockRootTreeHasher.ASSIGNED_SLOT_COUNT));

        assertEquals(expected, HintsKeyWitnessVerifier.rootHash(witness));
        given(library.verifyAggregate(SIGNATURE, expected, VK, 1, 3)).willReturn(true);
        assertTrue(subject.verify(witness, VK));
        verify(library).verifyAggregate(SIGNATURE, expected, VK, 1, 3);
    }

    @Test
    void malformedStructureIsRejectedBeforeNativeVerification() {
        final var valid = witness(BlockHashAlgorithm.SHA2_256);
        final var malformed = List.of(
                valid.copyBuilder().targetHintsConstructionId(0L).build(),
                valid.copyBuilder().timestamp((Timestamp) null).build(),
                valid.copyBuilder().timestamp(new Timestamp(0, 0)).build(),
                valid.copyBuilder().timestamp(new Timestamp(-1, 0)).build(),
                valid.copyBuilder().timestamp(new Timestamp(Long.MAX_VALUE, 0)).build(),
                valid.copyBuilder().timestamp(new Timestamp(1, -1)).build(),
                valid.copyBuilder().timestamp(new Timestamp(1, 1_000_000_000)).build(),
                valid.copyBuilder().siblingHash(Bytes.EMPTY).build(),
                valid.copyBuilder().siblingHash(Bytes.wrap(new byte[48])).build(),
                valid.copyBuilder().signature(Bytes.EMPTY).build(),
                valid.copyBuilder()
                        .signature(Bytes.wrap(new byte[SIGNATURE_LENGTH + 1]))
                        .build(),
                valid.copyBuilder().hashAlgorithm(BlockHashAlgorithm.SHA2_384).build());
        for (final var witness : malformed) {
            assertFalse(subject.verify(witness, VK));
        }
        assertFalse(subject.verify(null, VK));
        assertFalse(subject.verify(valid, null));
        assertFalse(subject.verify(valid, Bytes.EMPTY));
        verifyNoInteractions(library);
    }

    @Test
    void malformedGroupEncodingFailsClosed() {
        final var witness = witness(BlockHashAlgorithm.SHA2_256);
        given(library.verifyAggregate(SIGNATURE, HintsKeyWitnessVerifier.rootHash(witness), VK, 1, 3))
                .willThrow(new IllegalArgumentException("Malformed group point"));
        assertFalse(subject.verify(witness, VK));
    }

    @Test
    void rootCanBeConstructedBeforeSigningButNotWithInvalidProofInputs() {
        final var witness = witness(BlockHashAlgorithm.SHA2_384);
        assertEquals(
                HintsKeyWitnessVerifier.rootHash(witness),
                HintsKeyWitnessVerifier.rootHash(
                        witness.copyBuilder().signature(Bytes.EMPTY).build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> HintsKeyWitnessVerifier.rootHash(
                        witness.copyBuilder().siblingHash(Bytes.EMPTY).build()));
    }

    @Test
    void certificateMustStartAtTheActuallyTrustedKeyAndSignTheExactNextKey() {
        final var target = Bytes.wrap(new byte[VK_LENGTH - 1]).append(Bytes.wrap(new byte[] {1}));
        final var certificate = HintsKeyProvenance.newBuilder()
                .sourceVerificationKey(VK)
                .targetVerificationKey(target)
                .signature(SIGNATURE)
                .build();
        assertFalse(subject.verifyCertificate(certificate, target));
        assertFalse(subject.verifyCertificate(null, VK));
        assertFalse(subject.verifyCertificate(certificate, Bytes.EMPTY));
        assertFalse(subject.verifyCertificate(
                certificate.copyBuilder().signature(Bytes.EMPTY).build(), VK));
        verifyNoInteractions(library);

        given(library.verifyAggregate(SIGNATURE, target, VK, 1, 3)).willReturn(true);
        assertTrue(subject.verifyCertificate(certificate, VK));
        // IDs are outside the signed message and cannot supply an authenticated ordering rule.
        assertTrue(subject.verifyCertificate(
                certificate
                        .copyBuilder()
                        .sourceHintsConstructionId(900)
                        .targetHintsConstructionId(7)
                        .historyConstructionId(42)
                        .build(),
                VK));
    }

    private static HintsKeyWitness witness(final BlockHashAlgorithm algorithm) {
        return HintsKeyWitness.newBuilder()
                .targetHintsConstructionId(2)
                .timestamp(TIMESTAMP)
                .siblingHash(Bytes.wrap(new byte[algorithm == BlockHashAlgorithm.SHA2_256 ? 32 : 48]))
                .signature(SIGNATURE)
                .hashAlgorithm(algorithm)
                .build();
    }
}
