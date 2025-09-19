// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.app.hapi.utils.records.BlockRecordUtils;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * A utility class that provides methods for getting information from the {@link BlockInfo} object in order to
 * satisfy the {@link BlockRecordInfo} interface. There are at least two classes ({@link BlockRecordInfoImpl} and
 * {@link BlockRecordManagerImpl} which implement {@link BlockRecordInfo} and need this information, but are not
 * otherwise suitable for a class hierarchy. So, utility methods FTW!
 */
public final class BlockRecordInfoUtils {

    private BlockRecordInfoUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Get the consensus time of the first transaction of the last block, this is the last completed immutable block.
     *
     * @return the consensus time of the first transaction of the last block, null if there was no previous block
     */
    @Nullable
    public static Instant firstConsTimeOfLastBlock(@NonNull final BlockInfo blockInfo) {
        final var firstConsTimeOfLastBlock = blockInfo.firstConsTimeOfLastBlock();
        return firstConsTimeOfLastBlock != null
                ? Instant.ofEpochSecond(firstConsTimeOfLastBlock.seconds(), firstConsTimeOfLastBlock.nanos())
                : null;
    }

    /**
     * Gets the hash of the last block
     *
     * @return the last block hash, null if no blocks have been created
     */
    @Nullable
    public static Bytes lastBlockHash(@NonNull final BlockInfo blockInfo) {
        return getLastBlockHash(blockInfo);
    }

    // ========================================================================================================
    // Private Methods

    /**
     * Get the last block hash from the block info. This is the last block hash in the block hashes byte array.
     *
     * @param blockInfo The block info
     * @return The last block hash, or null if there are no blocks yet
     */
    @Nullable
    private static Bytes getLastBlockHash(@Nullable final BlockInfo blockInfo) {
        if (blockInfo != null) {
            Bytes runningBlockHashes = blockInfo.blockHashes();
            if (runningBlockHashes != null && runningBlockHashes.length() >= BlockRecordUtils.HASH_SIZE) {
                return runningBlockHashes.slice(
                        runningBlockHashes.length() - BlockRecordUtils.HASH_SIZE, BlockRecordUtils.HASH_SIZE);
            }
        }
        return null;
    }
}
