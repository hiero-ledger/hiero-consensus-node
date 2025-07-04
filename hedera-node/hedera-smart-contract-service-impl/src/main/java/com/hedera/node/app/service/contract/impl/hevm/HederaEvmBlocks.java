// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.hedera.node.app.service.contract.impl.exec.TransactionProcessor;
import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Provides block information as context for a {@link TransactionProcessor}.
 */
public interface HederaEvmBlocks {
    Hash UNAVAILABLE_BLOCK_HASH = org.hyperledger.besu.datatypes.Hash.wrap(Bytes32.wrap(new byte[32]));

    /**
     * Returns the hash of the given block number, or {@link HederaEvmBlocks#UNAVAILABLE_BLOCK_HASH}
     * if unavailable.
     *
     * @param blockNo the block number of interest
     * @return its hash, if available
     */
    Hash blockHashOf(MessageFrame frame, long blockNo);

    /**
     * Returns the in-scope block values, given an effective gas limit.
     *
     * @param gasLimit the effective gas limit
     * @return the scoped block values
     */
    BlockValues blockValuesOf(long gasLimit);
}
