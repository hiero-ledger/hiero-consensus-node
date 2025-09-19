// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.records;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.base.crypto.DigestType;

public class BlockRecordUtils {
    /**
     * The epoch timestamp, a placeholder for time of an event that has never happened.
     */
    public static final Timestamp EPOCH = new Timestamp(0, 0);

    public static final int HASH_SIZE = DigestType.SHA_384.digestLength();

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
}
