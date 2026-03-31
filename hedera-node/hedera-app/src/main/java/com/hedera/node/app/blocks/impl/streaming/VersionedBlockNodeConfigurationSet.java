// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import java.util.List;

public record VersionedBlockNodeConfigurationSet(long versionNumber, List<BlockNodeConfiguration> configs) {}
