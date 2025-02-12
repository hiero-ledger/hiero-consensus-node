/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
