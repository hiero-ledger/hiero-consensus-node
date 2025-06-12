// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

/**
 * Exception that indicates no Block Nodes were configured for connectivity.
 */
public class NoBlockNodesAvailableException extends RuntimeException {

    public NoBlockNodesAvailableException() {
        super("No block nodes were available to connect to");
    }
}
