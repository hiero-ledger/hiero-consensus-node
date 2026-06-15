// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import java.util.List;

/**
 * Represents a versioned block node configuration.
 *
 * @param versionNumber the version number associated with the configuration
 * @param configs list of configurations for one or more block nodes
 */
public record VersionedBlockNodeConfigurationSet(long versionNumber, List<BlockNodeConfiguration> configs) {}
