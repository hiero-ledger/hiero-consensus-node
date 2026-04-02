// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Immutable snapshot of everything needed to compute a {@code WrappedRecordFileBlockHashes} entry
 * for a just-finished record-stream block.
 */
public record WrappedRecordFileBlockHashesComputationInput(
        long blockNumber,
        @NonNull Timestamp blockCreationTime,
        @NonNull SemanticVersion hapiProtoVersion,
        @NonNull Bytes startRunningHash,
        @NonNull Bytes endRunningHash,
        @NonNull List<RecordStreamItem> recordStreamItems,
        @NonNull List<TransactionSidecarRecord> sidecarRecords,
        int maxSidecarSizeInBytes) {

    public WrappedRecordFileBlockHashesComputationInput {
        requireNonNull(blockCreationTime);
        requireNonNull(hapiProtoVersion);
        requireNonNull(startRunningHash);
        requireNonNull(endRunningHash);
        requireNonNull(recordStreamItems);
        requireNonNull(sidecarRecords);
    }
}
