// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import com.hedera.node.config.data.BlockNodeConnectionConfig;

/**
 * Enumeration of different types of cool down to apply. More specifically, this determines the amount of time the cool
 * down should be.
 */
public enum CoolDownType {
    /**
     * No cool down should be applied.
     */
    NONE,
    /**
     * A basic cool down should be applied. See {@link BlockNodeConnectionConfig#basicNodeCoolDownSeconds()} for the amount
     * of time a basic cool down will induce.
     */
    BASIC,
    /**
     * An extended cool down should be applied. See {@link BlockNodeConnectionConfig#extendedNodeCoolDownSeconds()} for the
     * amount of time an extended cool down will induce.
     */
    EXTENDED
}
