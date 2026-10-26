// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.services.bdd.junit.support.validators.block.IndirectProofSequenceValidator.assertMatchingStateProof;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.MerklePath;
import com.hedera.hapi.block.stream.StateProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndirectProofSequenceValidatorTest {
    private static final long BLOCK_NUMBER = 16L;
    private static final Bytes SIGNED_BLOCK_SIGNATURE = Bytes.wrap(new byte[] {1, 2, 3});
    // Another publisher's aggregate signature on the same signed block hash
    private static final Bytes OTHER_PUBLISHER_SIGNATURE = Bytes.wrap(new byte[] {1, 2, 4});
    private static final List<MerklePath> PATHS = List.of(timestampPath((byte) 7));

    @Test
    void identicalProofsMatchWithoutCheckingTheSignature() {
        final var expected = proof(PATHS, SIGNED_BLOCK_SIGNATURE);
        assertDoesNotThrow(() -> assertMatchingStateProof(BLOCK_NUMBER, expected, expected, signature -> {
            throw new AssertionError("Signature checked for an identical proof");
        }));
    }

    @Test
    void differentSignatureMatchesIfItVerifies() {
        final List<Bytes> checked = new ArrayList<>();
        assertMatchingStateProof(
                BLOCK_NUMBER,
                proof(PATHS, SIGNED_BLOCK_SIGNATURE),
                proof(PATHS, OTHER_PUBLISHER_SIGNATURE),
                signature -> {
                    checked.add(signature);
                    return true;
                });
        assertEquals(List.of(OTHER_PUBLISHER_SIGNATURE), checked);
    }

    @Test
    void differentSignatureFailsIfItDoesNotVerify() {
        assertThrows(
                AssertionError.class,
                () -> assertMatchingStateProof(
                        BLOCK_NUMBER,
                        proof(PATHS, SIGNED_BLOCK_SIGNATURE),
                        proof(PATHS, OTHER_PUBLISHER_SIGNATURE),
                        signature -> false));
    }

    @Test
    void differentAggregateUnderTheSameVerificationKeyMatchesIfItVerifies() {
        assertDoesNotThrow(() -> assertMatchingStateProof(
                BLOCK_NUMBER,
                proof(PATHS, hintsSignature((byte) 1, (byte) 1)),
                proof(PATHS, hintsSignature((byte) 1, (byte) 2)),
                signature -> true));
    }

    @Test
    void differentVerificationKeyFailsEvenIfTheSignatureVerifies() {
        final var error = assertThrows(
                AssertionError.class,
                () -> assertMatchingStateProof(
                        BLOCK_NUMBER,
                        proof(PATHS, hintsSignature((byte) 1, (byte) 1)),
                        proof(PATHS, hintsSignature((byte) 2, (byte) 1)),
                        signature -> true));
        assertTrue(error.getMessage().contains("different verification key"));
    }

    @Test
    void differentPathsFailEvenWithAValidSignature() {
        assertThrows(
                AssertionError.class,
                () -> assertMatchingStateProof(
                        BLOCK_NUMBER,
                        proof(PATHS, SIGNED_BLOCK_SIGNATURE),
                        proof(List.of(timestampPath((byte) 8)), SIGNED_BLOCK_SIGNATURE),
                        signature -> true));
    }

    @Test
    void proofWithoutSignedBlockProofFails() {
        assertThrows(
                AssertionError.class,
                () -> assertMatchingStateProof(
                        BLOCK_NUMBER,
                        proof(PATHS, SIGNED_BLOCK_SIGNATURE),
                        StateProof.newBuilder().paths(PATHS).build(),
                        signature -> true));
    }

    @Test
    void missingProofFails() {
        assertThrows(
                AssertionError.class,
                () -> assertMatchingStateProof(
                        BLOCK_NUMBER, proof(PATHS, SIGNED_BLOCK_SIGNATURE), null, signature -> true));
    }

    private static StateProof proof(final List<MerklePath> paths, final Bytes signature) {
        return StateProof.newBuilder()
                .paths(paths)
                .signedBlockProof(TssSignedBlockProof.newBuilder()
                        .blockSignature(signature)
                        .build())
                .build();
    }

    // A verificationKey || aggregateSignature signature, as the node writes it
    private static Bytes hintsSignature(final byte verificationKey, final byte aggregate) {
        final var bytes = new byte[HintsLibraryImpl.VK_LENGTH + HintsLibraryImpl.SIGNATURE_LENGTH];
        Arrays.fill(bytes, 0, HintsLibraryImpl.VK_LENGTH, verificationKey);
        Arrays.fill(bytes, HintsLibraryImpl.VK_LENGTH, bytes.length, aggregate);
        return Bytes.wrap(bytes);
    }

    private static MerklePath timestampPath(final byte leaf) {
        return MerklePath.newBuilder()
                .timestampLeaf(Bytes.wrap(new byte[] {leaf}))
                .build();
    }
}
