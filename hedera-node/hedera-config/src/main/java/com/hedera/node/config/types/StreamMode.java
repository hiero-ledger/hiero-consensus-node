// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.types;

/**
 * Enumerates the choices of what stream to produce; currently only records or both records and blocks are supported,
 * because record queries are not yet supported by translating from blocks.
 */
public enum StreamMode {
    /**
     * Stream records only.
     */
    RECORDS,
    /**
     * Stream blocks only.
     */
    BLOCKS,
    /**
     * Stream both blocks and records.
     */
    BOTH
}
