// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when the PTT app ends a synthetic bottleneck session to slow
 * down the handling of transactions
 */
public class SyntheticBottleneckFinishPayload extends AbstractLogPayload {
    public SyntheticBottleneckFinishPayload(String message) {
        super(message);
    }
}
