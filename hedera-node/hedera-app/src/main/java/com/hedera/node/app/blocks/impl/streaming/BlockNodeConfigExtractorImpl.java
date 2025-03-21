// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.node.internal.network.BlockNodeConfig;
import com.hedera.node.internal.network.BlockNodeConnectionInfo;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Extracts block node configuration from a JSON configuration file.
 */
public class BlockNodeConfigExtractorImpl implements BlockNodeConfigExtractor {
    private static final Logger logger = LogManager.getLogger(BlockNodeConfigExtractorImpl.class);
    private final List<BlockNodeConfig> allNodes;
    private final int blockItemBatchSize;

    /**
     * @param blockNodeConfigPath the path to the block node configuration file
     */
    public BlockNodeConfigExtractorImpl(String blockNodeConfigPath) {
        final var configPath = Paths.get(blockNodeConfigPath, "block-nodes.json");

        try {
            byte[] jsonConfig = Files.readAllBytes(configPath);
            BlockNodeConnectionInfo protoConfig = BlockNodeConnectionInfo.JSON.parse(Bytes.wrap(jsonConfig));

            // Convert proto config to internal config objects
            this.allNodes = protoConfig.nodes().stream()
                    .map(node -> new BlockNodeConfig(node.address(), node.port()))
                    .collect(Collectors.toList());

            logger.info("Loaded block node configuration from {}", configPath);
            logger.info("Block node configuration: {}", allNodes);

            this.blockItemBatchSize = protoConfig.blockItemBatchSize();

        } catch (IOException | ParseException e) {
            logger.error("Failed to read block node configuration from {}", configPath, e);
            throw new RuntimeException("Failed to read block node configuration from " + configPath, e);
        }
    }

    /**
     * @return the list of all block node configurations
     */
    @Override
    public List<BlockNodeConfig> getAllNodes() {
        return allNodes;
    }

    /**
     * @return the block items batch size to send to the block nodes
     */
    @Override
    public int getBlockItemBatchSize() {
        return blockItemBatchSize;
    }
}
