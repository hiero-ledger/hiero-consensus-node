// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;

/**
 * Configuration for the cutover jumpstart file
 * @param blockNum the last block number processed
 * @param previousWrappedRecordBlockHash the hash of the last block processed
 * @param streamingHasherLeafCount the number of leaves in the streaming hasher state
 * @param streamingHasherHashCount the number of hashes in the streaming hasher state
 * @param streamingHasherSubtreeHashes the list of subtree hashes in the streaming hasher state
 */
@ConfigData("blockStream.jumpstart")
public record BlockStreamJumpstartConfig(
        @ConfigProperty(defaultValue = "-1") @NetworkProperty
        long blockNum,

        @ConfigProperty(defaultValue = "") @NetworkProperty Bytes previousWrappedRecordBlockHash,

        @ConfigProperty(defaultValue = "-1") @NetworkProperty
        long streamingHasherLeafCount,

        @ConfigProperty(defaultValue = "-1") @NetworkProperty
        int streamingHasherHashCount,

        @ConfigProperty(defaultValue = "") @NetworkProperty List<Bytes> streamingHasherSubtreeHashes) {}
