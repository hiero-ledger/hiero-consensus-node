// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.node.internal.network.BlockNodeConfig;
import java.util.List;

/**
 * Extracts block node configuration from a JSON configuration file.
 */
public interface BlockNodeConfigExtractor {
    /**
     * @return the list of all block node configurations
     */
    List<BlockNodeConfig> getAllNodes();
}
