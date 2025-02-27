// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

/**
 * Whether and how network metadata should be exported to disk for use in a later network transplant.
 */
public enum DiskNetworkExport {
    /**
     * Never export network metadata to disk.
     */
    NEVER,
    /**
     * Export network metadata to disk every block, convenient for getting a file as quickly as possible in a
     * development network that will serve as the target of a state transplant from a production network.
     */
    EVERY_BLOCK,
    /**
     * Export network metadata to disk only when the network is frozen.
     */
    ONLY_FREEZE_BLOCK,
}
