// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockItem.ItemOneOfType;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.BlockProof.ProofOneOfType;
import com.hedera.hapi.block.stream.RecordFileItem;
import com.hedera.hapi.block.stream.RecordFileSignature;
import com.hedera.hapi.block.stream.SignedRecordFileProof;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BlockContentsValidatorTest {

    private static final Timestamp TIMESTAMP = new Timestamp(1_000_000L, 0);
    // Wrapped record blocks use hash(0x00) for the empty state root, not an all-zero byte array.
    private static final Bytes EMPTY_HASH = Bytes.fromHex(
            "bec021b4f368e3069134e012c2b4307083d3a9bdd206e24e5f0d86e13d6636655933ec2b413465966817a9c208a11717");
    private static final Bytes EMPTY_HASH_SHA256 =
            Bytes.fromHex("6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d");

    private static BlockItem headerItem(long blockNumber) {
        return headerItem(blockNumber, BlockHashAlgorithm.SHA2_384);
    }

    private static BlockItem headerItem(long blockNumber, BlockHashAlgorithm algorithm) {
        return new BlockItem(new OneOf<>(
                ItemOneOfType.BLOCK_HEADER,
                new BlockHeader(new SemanticVersion(0, 58, 0, "", ""), null, blockNumber, TIMESTAMP, algorithm)));
    }

    private static BlockItem recordFileItem() {
        return new BlockItem(new OneOf<>(
                ItemOneOfType.RECORD_FILE,
                new RecordFileItem(
                        TIMESTAMP,
                        new RecordStreamFile(
                                new SemanticVersion(0, 58, 0, "", ""), null, List.of(), null, 1L, List.of()),
                        List.of(),
                        List.of())));
    }

    private static BlockItem footerItem() {
        return footerItem(EMPTY_HASH);
    }

    private static BlockItem footerItem(Bytes emptyHash) {
        return new BlockItem(new OneOf<>(ItemOneOfType.BLOCK_FOOTER, new BlockFooter(emptyHash, emptyHash, emptyHash)));
    }

    private static BlockItem wrbProofItem(long blockNumber, int version) {
        return new BlockItem(new OneOf<>(
                ItemOneOfType.BLOCK_PROOF,
                new BlockProof(
                        blockNumber,
                        new OneOf<>(
                                ProofOneOfType.SIGNED_RECORD_FILE_PROOF,
                                new SignedRecordFileProof(
                                        version, List.of(new RecordFileSignature(Bytes.wrap(new byte[256]), 0L)))))));
    }

    private static BlockItem stateChangesItem() {
        return new BlockItem(new OneOf<>(ItemOneOfType.STATE_CHANGES, new StateChanges(TIMESTAMP, List.of())));
    }

    private static BlockItem roundHeaderItem() {
        return new BlockItem(
                new OneOf<>(ItemOneOfType.ROUND_HEADER, new com.hedera.hapi.block.stream.input.RoundHeader(1L)));
    }

    private static BlockItem eventHeaderItem() {
        return new BlockItem(
                new OneOf<>(ItemOneOfType.EVENT_HEADER, com.hedera.hapi.block.stream.input.EventHeader.DEFAULT));
    }

    // --- WRB Positive Tests ---

    @ParameterizedTest
    @EnumSource(
            value = BlockHashAlgorithm.class,
            names = {"SHA2_256", "SHA2_384"})
    void validWrbNonGenesis(BlockHashAlgorithm algorithm) {
        final var emptyHash = algorithm == BlockHashAlgorithm.SHA2_256 ? EMPTY_HASH_SHA256 : EMPTY_HASH;
        final var block = new Block(
                List.of(headerItem(42, algorithm), recordFileItem(), footerItem(emptyHash), wrbProofItem(42, 6)));
        assertDoesNotThrow(() -> new BlockContentsValidator().validateBlocks(List.of(block)));
    }

    @Test
    void validWrbGenesis() {
        final var block = new Block(List.of(headerItem(0), recordFileItem(), footerItem(), wrbProofItem(0, 2)));
        assertDoesNotThrow(() -> new BlockContentsValidator().validateBlocks(List.of(block)));
    }

    @Test
    void validWrbAllFormatVersions() {
        for (int version : new int[] {2, 5, 6}) {
            final var block = new Block(
                    List.of(headerItem(version), recordFileItem(), footerItem(), wrbProofItem(version, version)));
            assertDoesNotThrow(
                    () -> new BlockContentsValidator().validateBlocks(List.of(block)),
                    "Should accept WRB with record file format version " + version);
        }
    }

    // --- WRB Negative Tests ---

    @Test
    void wrbMissingRecordFileItem() {
        final var block = new Block(List.of(headerItem(1), footerItem(), wrbProofItem(1, 6)));
        // No RECORD_FILE item means it looks like a normal block, which will fail round validation
        assertThrows(AssertionError.class, () -> new BlockContentsValidator().validateBlocks(List.of(block)));
    }

    @Test
    void wrbDuplicateRecordFileItem() {
        final var block =
                new Block(List.of(headerItem(1), recordFileItem(), recordFileItem(), footerItem(), wrbProofItem(1, 6)));
        final var err =
                assertThrows(AssertionError.class, () -> new BlockContentsValidator().validateBlocks(List.of(block)));
        assertTrue(err.getMessage().contains("more than one RecordFileItem"));
    }

    @Test
    void wrbWithRoundHeader() {
        final var block = new Block(
                List.of(headerItem(1), roundHeaderItem(), recordFileItem(), footerItem(), wrbProofItem(1, 6)));
        final var err =
                assertThrows(AssertionError.class, () -> new BlockContentsValidator().validateBlocks(List.of(block)));
        assertTrue(err.getMessage().contains("unexpected item type"));
    }

    @Test
    void wrbWithEventHeader() {
        final var block = new Block(
                List.of(headerItem(1), eventHeaderItem(), recordFileItem(), footerItem(), wrbProofItem(1, 6)));
        final var err =
                assertThrows(AssertionError.class, () -> new BlockContentsValidator().validateBlocks(List.of(block)));
        assertTrue(err.getMessage().contains("unexpected item type"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 5})
    void wrbWithStateChanges(long blockNumber) {
        final var block = new Block(List.of(
                headerItem(blockNumber),
                stateChangesItem(),
                recordFileItem(),
                footerItem(),
                wrbProofItem(blockNumber, 6)));
        final var err =
                assertThrows(AssertionError.class, () -> new BlockContentsValidator().validateBlocks(List.of(block)));
        assertTrue(err.getMessage().contains("WRB StateChanges found"));
    }

    @Test
    void wrbStateChangesAfterRecordFile() {
        final var block = new Block(
                List.of(headerItem(1), recordFileItem(), stateChangesItem(), footerItem(), wrbProofItem(1, 6)));
        final var err =
                assertThrows(AssertionError.class, () -> new BlockContentsValidator().validateBlocks(List.of(block)));
        assertTrue(err.getMessage().contains("WRB StateChanges found"));
    }

    @Test
    void wrbWithInvalidProofVersion() {
        final var block = new Block(List.of(headerItem(1), recordFileItem(), footerItem(), wrbProofItem(1, 3)));
        final var err =
                assertThrows(AssertionError.class, () -> new BlockContentsValidator().validateBlocks(List.of(block)));
        assertTrue(err.getMessage().contains("invalid version"));
    }

    @Test
    void wrbWithTssProofInsteadOfSignedRecordFileProof() {
        final var tssProof = new BlockItem(new OneOf<>(
                ItemOneOfType.BLOCK_PROOF,
                new BlockProof(
                        1L,
                        new OneOf<>(
                                ProofOneOfType.SIGNED_BLOCK_PROOF,
                                new com.hedera.hapi.block.stream.TssSignedBlockProof(Bytes.EMPTY)))));
        final var block = new Block(List.of(headerItem(1), recordFileItem(), footerItem(), tssProof));
        final var err =
                assertThrows(AssertionError.class, () -> new BlockContentsValidator().validateBlocks(List.of(block)));
        assertTrue(err.getMessage().contains("SignedRecordFileProof"));
    }

    @Test
    void wrbWithNoSignatures() {
        final var emptySignaturesProof = new BlockItem(new OneOf<>(
                ItemOneOfType.BLOCK_PROOF,
                new BlockProof(
                        1L,
                        new OneOf<>(
                                ProofOneOfType.SIGNED_RECORD_FILE_PROOF, new SignedRecordFileProof(6, List.of())))));
        final var block = new Block(List.of(headerItem(1), recordFileItem(), footerItem(), emptySignaturesProof));
        final var err =
                assertThrows(AssertionError.class, () -> new BlockContentsValidator().validateBlocks(List.of(block)));
        assertTrue(err.getMessage().contains("no signatures"));
    }

    @Test
    void wrbFooterBeforeRecordFile() {
        final var block = new Block(List.of(headerItem(1), footerItem(), recordFileItem(), wrbProofItem(1, 6)));
        final var err =
                assertThrows(AssertionError.class, () -> new BlockContentsValidator().validateBlocks(List.of(block)));
        assertTrue(err.getMessage().contains("BlockFooter found before RecordFileItem"));
    }

    // --- Normal Block Regression ---

    @Test
    void normalBlockStillValidates() {
        final var items = new ArrayList<BlockItem>();
        items.add(headerItem(1));
        items.add(roundHeaderItem());
        items.add(stateChangesItem());
        items.add(new BlockItem(new OneOf<>(
                ItemOneOfType.BLOCK_PROOF,
                new BlockProof(
                        1L,
                        new OneOf<>(
                                ProofOneOfType.SIGNED_BLOCK_PROOF,
                                new com.hedera.hapi.block.stream.TssSignedBlockProof(Bytes.wrap(new byte[64])))))));
        final var block = new Block(items);
        assertDoesNotThrow(() -> new BlockContentsValidator().validateBlocks(List.of(block)));
    }

    // --- isWrappedRecordBlock Detection ---

    @Test
    void detectsWrappedRecordBlock() {
        assertTrue(BlockStreamValidator.isWrappedRecordBlock(List.of(headerItem(1), recordFileItem(), footerItem())));
    }

    @Test
    void detectsNormalBlock() {
        assertTrue(!BlockStreamValidator.isWrappedRecordBlock(
                List.of(headerItem(1), roundHeaderItem(), stateChangesItem())));
    }
}
