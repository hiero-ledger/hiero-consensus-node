// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.blocks.impl.streaming.FileBlockItemWriter;
import com.hedera.node.internal.network.PendingProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Path;

/**
 * Writes serialized block items to a destination stream.
 */
public interface BlockItemWriter {
    /**
     * Opens a block for writing.
     *
     * @param blockNumber the number of the block to open
     */
    void openBlock(final long blockNumber);

    /**
     * Writes an item and/or its serialized bytes to the destination stream.
     *
     * @param item the item to write
     * @param bytes the serialized item to write
     */
    void writePbjItemAndBytes(@NonNull final BlockItem item, @NonNull final Bytes bytes);

    /**
     * Writes a PBJ item to the destination stream.
     * @param item the item to write
     */
    void writePbjItem(@NonNull final BlockItem item);

    /**
     * Closes a block that is complete with a proof.
     */
    void closeCompleteBlock();

    /**
     * Flushes to disk a block that is still waiting for a complete proof.
     * @param pendingProof the proof pending a signature
     * @return the path of the flushed pending-block contents file ({@code .pnd.gz}), or {@code null} if nothing was
     * written (e.g. not in an OPEN state, no buffered items, or an I/O error)
     */
    @Nullable
    Path flushPendingBlock(@NonNull PendingProof pendingProof);

    /**
     * Flushes the current OPEN, unproven block to local disk for triage after a catastrophic failure (e.g. an ISS),
     * as an {@code .open.gz} artifact: the gzipped block items, parseable as a {@link
     * com.hedera.hapi.block.stream.Block} for analysis. Unlike {@link #flushPendingBlock(PendingProof)}, this is
     * deliberately NOT a recoverable pending block — it has no {@code .pnd.json} proof sidecar (so pending-block
     * recovery never picks it up) and no completion marker (so it is never mistaken for a finished/proven block).
     * Implementations that never persist to disk may no-op; implementations that buffer the block stream in memory
     * (e.g. {@code GrpcBlockItemWriter}) must persist the open block here, or its contents are lost when the node
     * stops. Best-effort; implementations must not throw.
     * <p>
     * The artifact contains only the items written so far and may therefore END WITH A PARTIAL ROUND: if the failure
     * arrived mid-round, trailing items (and the round's state-changes/footer) can be missing. Consumers must tolerate
     * an incomplete final round — e.g. read each round by its leading {@code RoundHeader} rather than assuming a clean
     * round boundary at end-of-file.
     *
     * @return the path of the flushed {@code .iss.gz} triage artifact, or {@code null} if nothing was written
     */
    @Nullable
    Path flushIncompleteBlock();

    /**
     * Flushes the current OPEN, unproven block to local disk for triage after a catastrophic failure (e.g. an ISS),
     * as an {@code .open.gz} artifact: the gzipped block items, parseable as a {@link
     * com.hedera.hapi.block.stream.Block} for analysis. Unlike {@link #flushPendingBlock(PendingProof)}, this is
     * deliberately NOT a recoverable pending block — it has no {@code .pnd.json} proof sidecar (so pending-block
     * recovery never picks it up) and no completion marker (so it is never mistaken for a finished/proven block).
     * Implementations that never persist to disk may no-op; implementations that buffer the block stream in memory
     * (e.g. {@code GrpcBlockItemWriter}) must persist the open block here, or its contents are lost when the node
     * stops. Best-effort; implementations must not throw.
     * <p>
     * The artifact contains only the items written so far and may therefore END WITH A PARTIAL ROUND: if the failure
     * arrived mid-round, trailing items (and the round's state-changes/footer) can be missing. Consumers must tolerate
     * an incomplete final round — e.g. read each round by its leading {@code RoundHeader} rather than assuming a clean
     * round boundary at end-of-file.
     */
    void flushIncompleteBlock();

    default Path pendingProofPath(@NonNull final Path blockDir, final long blockNumber) {
        final var baseName = FileBlockItemWriter.longToFileName(blockNumber);
        return blockDir.resolve(baseName + ".pnd.json");
    }
}
