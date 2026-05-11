// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockItem.ItemOneOfType;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.BlockProof.ProofOneOfType;
import com.hedera.hapi.block.stream.RecordFileItem;
import com.hedera.hapi.block.stream.RecordFileSignature;
import com.hedera.hapi.block.stream.SignedRecordFileProof;
import com.hedera.hapi.block.stream.TssSignedBlockProof;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.ArrayList;
import java.util.List;

final class BlockTestHelpers {

    static final Timestamp TIMESTAMP = new Timestamp(1_000_000L, 0);
    static final Bytes EMPTY_HASH = Bytes.wrap(new byte[48]);
    static final SemanticVersion VERSION = new SemanticVersion(0, 58, 0, "", "");

    private BlockTestHelpers() {}

    static BlockItem headerItem(long blockNumber) {
        return headerItem(blockNumber, TIMESTAMP);
    }

    static BlockItem headerItem(long blockNumber, Timestamp timestamp) {
        return new BlockItem(new OneOf<>(
                ItemOneOfType.BLOCK_HEADER,
                new BlockHeader(VERSION, null, blockNumber, timestamp, BlockHashAlgorithm.SHA2_384)));
    }

    static BlockItem recordFileItemWithTimestamp(Timestamp timestamp) {
        final var txnRecord = com.hedera.hapi.node.transaction.TransactionRecord.newBuilder()
                .consensusTimestamp(timestamp)
                .build();
        final var streamItem = new RecordStreamItem(null, txnRecord);
        return new BlockItem(new OneOf<>(
                ItemOneOfType.RECORD_FILE,
                new RecordFileItem(
                        timestamp,
                        new RecordStreamFile(VERSION, null, List.of(streamItem), null, 1L, List.of()),
                        List.of(),
                        List.of())));
    }

    static BlockItem recordFileItem() {
        return recordFileItemWithTimestamp(TIMESTAMP);
    }

    static BlockItem emptyRecordFileItem() {
        return new BlockItem(new OneOf<>(
                ItemOneOfType.RECORD_FILE,
                new RecordFileItem(
                        TIMESTAMP,
                        new RecordStreamFile(VERSION, null, List.of(), null, 1L, List.of()),
                        List.of(),
                        List.of())));
    }

    static BlockItem footerItem() {
        return new BlockItem(
                new OneOf<>(ItemOneOfType.BLOCK_FOOTER, new BlockFooter(EMPTY_HASH, EMPTY_HASH, EMPTY_HASH)));
    }

    static BlockItem wrbProofItem(long blockNumber, int version) {
        return wrbProofItem(blockNumber, version, List.of(new RecordFileSignature(Bytes.wrap(new byte[256]), 0L)));
    }

    static BlockItem wrbProofItem(long blockNumber, int version, List<RecordFileSignature> signatures) {
        return new BlockItem(new OneOf<>(
                ItemOneOfType.BLOCK_PROOF,
                new BlockProof(
                        blockNumber,
                        new OneOf<>(
                                ProofOneOfType.SIGNED_RECORD_FILE_PROOF,
                                new SignedRecordFileProof(version, signatures)))));
    }

    static BlockItem tssProofItem(long blockNumber) {
        return new BlockItem(new OneOf<>(
                ItemOneOfType.BLOCK_PROOF,
                new BlockProof(
                        blockNumber,
                        new OneOf<>(
                                ProofOneOfType.SIGNED_BLOCK_PROOF,
                                new TssSignedBlockProof(Bytes.wrap(new byte[64]))))));
    }

    static BlockItem stateChangesItem() {
        return new BlockItem(new OneOf<>(ItemOneOfType.STATE_CHANGES, new StateChanges(TIMESTAMP, List.of())));
    }

    static BlockItem roundHeaderItem() {
        return new BlockItem(
                new OneOf<>(ItemOneOfType.ROUND_HEADER, new com.hedera.hapi.block.stream.input.RoundHeader(1L)));
    }

    static Block wrbBlock(long blockNumber) {
        return new Block(
                List.of(headerItem(blockNumber), recordFileItem(), footerItem(), wrbProofItem(blockNumber, 6)));
    }

    static Block wrbBlockWithTimestamp(long blockNumber, Timestamp timestamp) {
        return new Block(List.of(
                headerItem(blockNumber, timestamp),
                recordFileItemWithTimestamp(timestamp),
                footerItem(),
                wrbProofItem(blockNumber, 6)));
    }

    static Block normalBlock(long blockNumber) {
        final var items = new ArrayList<BlockItem>();
        items.add(headerItem(blockNumber));
        items.add(roundHeaderItem());
        items.add(stateChangesItem());
        items.add(tssProofItem(blockNumber));
        return new Block(items);
    }
}
