// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.node.internal.network.BlockNodeConfig;
import java.util.List;

public class NoOpBlockNodeConfigExtractor implements BlockNodeConfigExtractor {
    public NoOpBlockNodeConfigExtractor() {}

    @Override
    public List<BlockNodeConfig> getAllNodes() {
        return List.of();
    }
}
