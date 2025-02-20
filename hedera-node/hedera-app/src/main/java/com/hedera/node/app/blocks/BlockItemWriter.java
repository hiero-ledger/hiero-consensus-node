// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Writes serialized block items to a destination stream.
 */
public interface BlockItemWriter {
    /**
     * Opens a block for writing.
     *
     * @param blockNumber the number of the block to open
     */
    void openBlock(long blockNumber);

    /**
     * Writes a serialized item to the destination stream.
     *
     * @param bytes the serialized item to write
     * @return the block item writer
     */
    default BlockItemWriter writePbjItem(@NonNull final Bytes bytes) {
        requireNonNull(bytes);
        return writeItem(bytes.toByteArray());
    }

    /**
     * Writes a serialized item to the destination stream.
     *
     * @param bytes the serialized item to write
     * @return the block item writer
     */
    BlockItemWriter writeItem(@NonNull byte[] bytes);

    /**
     * Writes a pre-serialized sequence of items to the destination stream.
     *
     * @param data the serialized item to write
     * @return the block item writer
     */
    BlockItemWriter writeItems(@NonNull BufferedData data);

    /**
     * Closes the block.
     */
    void closeBlock();
}
