// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WrappedRecordFileBlockHashesCalculatorTest {

    @Test
    void blockHeaderUsesFirstConsensusTimeAndRecordFileItemUsesBlockCreationTime() {
        final var blockCreationTime = new Timestamp(1_000L, 0);
        final var firstConsensusTime = new Timestamp(2_000L, 0);

        final var firstItem = new RecordStreamItem(
                Transaction.DEFAULT,
                TransactionRecord.newBuilder()
                        .consensusTimestamp(firstConsensusTime)
                        .build());

        final var input = new WrappedRecordFileBlockHashesComputationInput(
                1L,
                blockCreationTime,
                SemanticVersion.DEFAULT,
                Bytes.wrap(new byte[48]),
                Bytes.wrap(new byte[48]),
                List.of(firstItem),
                List.of(),
                1024 * 1024);

        final var result =
                WrappedRecordFileBlockHashesCalculator.computeWithItems(input, CommonUtils::sha384DigestOrThrow);

        final var actualCreationTime =
                result.recordFileItem().recordFileOrThrow().creationTime();
        final var actualBlockTimestamp =
                result.headerItem().blockHeaderOrThrow().blockTimestamp();

        assertEquals(blockCreationTime, actualCreationTime, "RecordFileItem.creationTime must equal blockCreationTime");
        assertEquals(
                firstConsensusTime,
                actualBlockTimestamp,
                "BlockHeader.blockTimestamp must equal first consensus timestamp of the first item");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    void headerDeclaresTheDigestTypeAndHashesHaveItsLength(final DigestType digestType) {
        final var result =
                WrappedRecordFileBlockHashesCalculator.computeWithItems(inputAt(2_000L), digestType::buildDigest);

        final var expectedAlgorithm =
                digestType == DigestType.SHA_256 ? BlockHashAlgorithm.SHA2_256 : BlockHashAlgorithm.SHA2_384;
        assertEquals(expectedAlgorithm, result.headerItem().blockHeaderOrThrow().hashAlgorithm());
        assertEquals(
                digestType.digestLength(),
                result.hashes().consensusTimestampHash().length());
        assertEquals(
                digestType.digestLength(),
                result.hashes().outputItemsTreeRootHash().length());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = DigestType.class,
            names = {"SHA_384", "SHA_256"})
    void hashesAreTheTimestampLeafAndTheTreeOfBothItems(final DigestType digestType) {
        final var firstConsensusTime = new Timestamp(2_000L, 0);
        final var result =
                WrappedRecordFileBlockHashesCalculator.computeWithItems(inputAt(2_000L), digestType::buildDigest);

        final var expectedTimestampHash =
                BlockImplUtils.hashLeaf(digestType.buildDigest(), Timestamp.PROTOBUF.toBytes(firstConsensusTime));
        final var expectedOutputRoot = BlockImplUtils.hashInternalNode(
                digestType.buildDigest(),
                BlockImplUtils.hashLeaf(digestType.buildDigest(), result.headerItemBytes()),
                BlockImplUtils.hashLeaf(digestType.buildDigest(), result.recordFileItemBytes()));
        assertEquals(expectedTimestampHash, result.hashes().consensusTimestampHash());
        assertEquals(expectedOutputRoot, result.hashes().outputItemsTreeRootHash());
    }

    @Test
    void recordFileKeepsItsSha384RunningHashesUnderSha256() {
        final var result = WrappedRecordFileBlockHashesCalculator.computeWithItems(
                inputAt(2_000L), DigestType.SHA_256::buildDigest);

        final var contents = result.recordFileItem().recordFileOrThrow().recordFileContentsOrThrow();
        assertEquals(
                HashAlgorithm.SHA_384, contents.startObjectRunningHashOrThrow().algorithm());
        assertEquals(48, contents.startObjectRunningHashOrThrow().hash().length());
        assertEquals(
                HashAlgorithm.SHA_384, contents.endObjectRunningHashOrThrow().algorithm());
        assertEquals(48, contents.endObjectRunningHashOrThrow().hash().length());
    }

    private static WrappedRecordFileBlockHashesComputationInput inputAt(final long firstConsensusSeconds) {
        final var firstItem = new RecordStreamItem(
                Transaction.DEFAULT,
                TransactionRecord.newBuilder()
                        .consensusTimestamp(new Timestamp(firstConsensusSeconds, 0))
                        .build());
        return new WrappedRecordFileBlockHashesComputationInput(
                1L,
                new Timestamp(1_000L, 0),
                SemanticVersion.DEFAULT,
                Bytes.wrap(new byte[48]),
                Bytes.wrap(new byte[48]),
                List.of(firstItem),
                List.of(),
                1024 * 1024);
    }
}
