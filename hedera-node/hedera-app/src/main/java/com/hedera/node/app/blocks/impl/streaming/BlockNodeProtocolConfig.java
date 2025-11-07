// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Configuration for block node protocols.
 * @param blockNodeConfig the block node configuration
 * @param maxMessageSizeBytes the maximum message size in bytes
 */
public record BlockNodeProtocolConfig(
        @NonNull BlockNodeConfig blockNodeConfig, @Nullable Integer maxMessageSizeBytes) {}
