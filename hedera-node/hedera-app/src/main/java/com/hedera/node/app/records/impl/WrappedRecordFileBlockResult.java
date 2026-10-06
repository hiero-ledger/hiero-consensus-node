// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.pbj.runtime.io.buffer.Bytes;

/**
 * Result of {@link WrappedRecordFileBlockHashesCalculator#computeWithItems}: the computed
 * {@link WrappedRecordFileBlockHashes} together with the {@link BlockItem}s (block header and
 * record-file item) that were hashed to produce them, plus their already-serialized bytes.
 * Exposing the items lets callers forward them to a {@code BlockItemWriter} without rebuilding them,
 * and exposing the serialized bytes (which were computed while hashing) lets callers forward them
 * without re-serializing.
 *
 * @param hashes the computed wrapped-record-file block hashes
 * @param headerItem the block header item
 * @param recordFileItem the record-file item
 * @param headerItemBytes the serialized bytes of {@code headerItem} (the exact bytes hashed)
 * @param recordFileItemBytes the serialized bytes of {@code recordFileItem} (the exact bytes hashed)
 */
public record WrappedRecordFileBlockResult(
        WrappedRecordFileBlockHashes hashes,
        BlockItem headerItem,
        BlockItem recordFileItem,
        Bytes headerItemBytes,
        Bytes recordFileItemBytes) {}
