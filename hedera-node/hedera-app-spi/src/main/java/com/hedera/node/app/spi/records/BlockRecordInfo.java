// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.records;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Exposes information about, or derived from, the block stream. This includes information needed by the
 * {@link com.hedera.hapi.node.base.HederaFunctionality#UTIL_PRNG} operation and the EVM opcodes {@code BLOCKHASH},
 * {@code TIMESTAMP}, and {@code NUMBER}.
 * <ol>
 *     <li>A PRNG seed derived from the items in the stream.</li>
 *     <li>The current block number.</li>
 *     <li>The current block timestamp.</li>
 *     <li>A mapping from a trailing 256 block numbers and block hash.</li>
 * </ol>
 */
public interface BlockRecordInfo {
    /**
     * Returns a pseudorandom seed for use in the operations that need deterministic but unpredictable behavior.
     *
     * @return a pseudorandom seed, or null if not available
     */
    @Nullable
    Bytes prngSeed();

    /**
     * Get the number of the current block.
     *
     * @return the number of the current block
     */
    long blockNo();

    /**
     * The current block timestamp. Its seconds is the value returned by {@code block.timestamp} for a contract
     * executing in this block.
     *
     * @return the current block timestamp
     */
    @NonNull
    Timestamp blockTimestamp();

    /**
     * Returns the hash of the given block number, or {@code null} if unavailable.
     *
     * @param blockNo the block number of interest, must be within range of (current_block - 1) -> (current_block - 256)
     * @return its hash, if available otherwise null. If the {@code blockNo} is negative, then null is also returned.
     */
    @Nullable
    Bytes blockHashByBlockNumber(long blockNo);
}
