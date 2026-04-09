// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import com.hedera.hapi.block.internal.WrappedRecordFileBlockHashes;
import com.hedera.hapi.block.stream.BlockItem;

/**
 * Result of {@link WrappedRecordFileBlockHashesCalculator#computeWithItems}: the computed
 * {@link WrappedRecordFileBlockHashes} together with the {@link BlockItem}s (block header and
 * record-file item) that were hashed to produce them. Exposing the items lets callers forward
 * them to a {@code BlockItemWriter} without rebuilding them.
 */
public record WrappedRecordFileBlockResult(
        WrappedRecordFileBlockHashes hashes, BlockItem headerItem, BlockItem recordFileItem) {}
