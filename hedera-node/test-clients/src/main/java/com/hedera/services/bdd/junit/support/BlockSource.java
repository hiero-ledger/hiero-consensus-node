// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import com.hedera.hapi.block.stream.Block;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A pluggable source of {@link Block} objects for block-stream assertions.
 *
 * <p>The block-stream assertion framework consumes {@link Block}s through a
 * {@link StreamDataListener#onNewBlock(Block)} callback. Historically the only source was the
 * on-disk block-stream directory (watched by {@link StreamFileAccess}). Under
 * {@code blockStream.writerMode=GRPC} no {@code .blk} files are written to disk, so blocks must be
 * obtained from the active block node over gRPC instead. This interface abstracts the difference so
 * the assertion logic stays source-agnostic.
 *
 * @see FileSystemBlockSource
 * @see BlockNodeBlockSource
 * @see BlockSourceFactory
 */
public interface BlockSource {
    /**
     * Subscribes the given listener to receive blocks from this source. Each {@link Block} that
     * becomes available is delivered to {@link StreamDataListener#onNewBlock(Block)} in ascending
     * block-number order.
     *
     * @param listener the listener to receive blocks
     * @return a {@link Runnable} that, when run, stops/unsubscribes this source
     */
    @NonNull
    Runnable subscribe(@NonNull StreamDataListener listener);
}
