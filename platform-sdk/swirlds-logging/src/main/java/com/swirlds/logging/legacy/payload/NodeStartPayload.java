// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when a node starts.
 */
public class NodeStartPayload extends AbstractLogPayload {

    public NodeStartPayload() {
        super("main() started");
    }
}
