// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.node.app.spi.records.BlockRecordInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.consensus.model.crypto.DigestType;

/**
 * A utility class that provides methods for getting information from the {@link BlockInfo} object in order to
 * satisfy the {@link BlockRecordInfo} interface. There are at least two classes ({@link BlockRecordInfoImpl} and
 * {@link BlockRecordManagerImpl} which implement {@link BlockRecordInfo} and need this information, but are not
 * otherwise suitable for a class hierarchy. So, utility methods FTW!
 */
public final class BlockRecordInfoUtils {
    public static final int HASH_SIZE = DigestType.SHA_384.digestLength();

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

    /**
     * Returns the hash of the given block number, or {@code null} if unavailable.
     *
     * @param blockNo the block number of interest, must be within range of (current_block - 1) -> (current_block - 254)
     * @return its hash, if available otherwise null
     */
    @Nullable
    public static Bytes blockHashByBlockNumber(@NonNull final BlockInfo blockInfo, final long blockNo) {
        return blockHashByBlockNumber(blockInfo.blockHashes(), blockInfo.lastBlockNumber(), blockNo);
    }

    /**
     * Given a concatenated sequence of 48-byte block hashes, where the rightmost hash was
     * for the given last block number, returns either the hash of the block at the given
     * block number, or null if the block number is out of range.
     *
     * @param blockHashes the concatenated sequence of block hashes
     * @param lastBlockNo the block number of the rightmost hash in the sequence
     * @param blockNo the block number of the hash to return
     * @return the hash of the block at the given block number if available, null otherwise
     */
    public static @Nullable Bytes blockHashByBlockNumber(
            @NonNull final Bytes blockHashes, final long lastBlockNo, final long blockNo) {
        final var blocksAvailable = blockHashes.length() / HASH_SIZE;

        // Smart contracts (and other services) call this API. Should a smart contract call this, we don't really
        // want to throw an exception. So we will just return null, which is also valid. Basically, if the block
        // doesn't exist, you get null.
        if (blockNo < 0) {
            return null;
        }
        final var firstAvailableBlockNo = lastBlockNo - blocksAvailable + 1;
        // If blocksAvailable == 0, then firstAvailable == blockNo; and all numbers are
        // either less than or greater than or equal to blockNo, so we return unavailable
        if (blockNo < firstAvailableBlockNo || blockNo > lastBlockNo) {
            return null;
        } else {
            long offset = (blockNo - firstAvailableBlockNo) * HASH_SIZE;
            return blockHashes.slice(offset, HASH_SIZE);
        }
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
            if (runningBlockHashes != null && runningBlockHashes.length() >= HASH_SIZE) {
                return runningBlockHashes.slice(runningBlockHashes.length() - HASH_SIZE, HASH_SIZE);
            }
        }
        return null;
    }
}
