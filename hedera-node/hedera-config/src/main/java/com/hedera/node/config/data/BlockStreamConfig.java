// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;

/**
 * Configuration for the block stream.
 * @param streamMode Value of RECORDS disables the block stream; BOTH enables it
 * @param writerMode if we are writing to a file or gRPC stream
 * @param blockFileDir directory to store block files
 * @param compressFilesOnCreation whether to compress files on creation
 * @param grpcAddress the address of the gRPC server
 * @param grpcPort the port of the gRPC server
 */
@ConfigData("blockStream")
public record BlockStreamConfig(
        @ConfigProperty(defaultValue = "BOTH") @NetworkProperty StreamMode streamMode,
        @ConfigProperty(defaultValue = "FILE") @NodeProperty BlockStreamWriterMode writerMode,
        @ConfigProperty(defaultValue = "/opt/hgcapp/blockStreams") @NodeProperty String blockFileDir,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean compressFilesOnCreation,
        @ConfigProperty(defaultValue = "32") @NetworkProperty int hashCombineBatchSize,
        @ConfigProperty(defaultValue = "1") @NetworkProperty int roundsPerBlock,
        @ConfigProperty(defaultValue = "localhost") String grpcAddress,
        @ConfigProperty(defaultValue = "8080") @Min(0) @Max(65535) int grpcPort) {}
