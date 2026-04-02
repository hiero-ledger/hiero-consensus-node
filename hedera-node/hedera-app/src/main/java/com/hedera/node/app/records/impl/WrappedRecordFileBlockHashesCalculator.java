// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.RecordFileItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.HashObject;
import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes {@link WrappedRecordFileBlockHashes} deterministically from a snapshot of record-block inputs.
 */
public final class WrappedRecordFileBlockHashesCalculator {
    private WrappedRecordFileBlockHashesCalculator() {}

    public static WrappedRecordFileBlockHashes compute(@NonNull final WrappedRecordFileBlockHashesComputationInput in) {
        requireNonNull(in);
        if (in.recordStreamItems().isEmpty()) {
            throw new IllegalArgumentException("recordStreamItems must not be empty");
        }

        final var firstItem = in.recordStreamItems().getFirst();
        final var firstConsensusTimestamp = requireNonNull(firstItem.record()).consensusTimestampOrThrow();
        final Bytes consensusTimestampHash =
                BlockImplUtils.hashLeaf(Timestamp.PROTOBUF.toBytes(firstConsensusTimestamp));

        final var sidecarBundles =
                WrappedRecordSidecarUtils.buildSidecarBundles(in.sidecarRecords(), in.maxSidecarSizeInBytes());

        final var recordFileContents = new RecordStreamFile(
                in.hapiProtoVersion(),
                new HashObject(
                        HashAlgorithm.SHA_384, (int) in.startRunningHash().length(), in.startRunningHash()),
                new ArrayList<>(in.recordStreamItems()),
                new HashObject(HashAlgorithm.SHA_384, (int) in.endRunningHash().length(), in.endRunningHash()),
                in.blockNumber(),
                sidecarBundles.sidecarMetadata());

        final var recordFileItem = RecordFileItem.newBuilder()
                .creationTime(in.blockCreationTime())
                .recordFileContents(recordFileContents)
                .sidecarFileContents(sidecarBundles.sidecarFiles())
                .build();

        final var header = BlockHeader.newBuilder()
                .hapiProtoVersion(in.hapiProtoVersion())
                .number(in.blockNumber())
                .blockTimestamp(in.blockCreationTime())
                .hashAlgorithm(BlockHashAlgorithm.SHA2_384);

        final var headerItem = BlockItem.newBuilder().blockHeader(header).build();
        final var recordFileBlockItem =
                BlockItem.newBuilder().recordFile(recordFileItem).build();

        final var hasher = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
        hasher.addLeaf(BlockItem.PROTOBUF.toBytes(headerItem).toByteArray());
        hasher.addLeaf(BlockItem.PROTOBUF.toBytes(recordFileBlockItem).toByteArray());
        final Bytes outputItemsTreeRootHash = Bytes.wrap(hasher.computeRootHash());

        return new WrappedRecordFileBlockHashes(in.blockNumber(), consensusTimestampHash, outputItemsTreeRootHash);
    }
}
