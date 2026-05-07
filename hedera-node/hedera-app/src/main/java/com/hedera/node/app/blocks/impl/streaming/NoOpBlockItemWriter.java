// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link BlockItemWriter} that intentionally drops all items.
 */
public final class NoOpBlockItemWriter implements BlockItemWriter {
    public static final NoOpBlockItemWriter INSTANCE = new NoOpBlockItemWriter();

    private NoOpBlockItemWriter() {}

    @Override
    public void openBlock(final long blockNumber) {}

    @Override
    public void writePbjItemAndBytes(@NonNull final BlockItem item, @NonNull final Bytes bytes) {}

    @Override
    public void writePbjItem(@NonNull final BlockItem item) {}

    @Override
    public void closeCompleteBlock() {}

    @Override
    public void flushPendingBlock(@NonNull final PendingProof pendingProof) {}
}
