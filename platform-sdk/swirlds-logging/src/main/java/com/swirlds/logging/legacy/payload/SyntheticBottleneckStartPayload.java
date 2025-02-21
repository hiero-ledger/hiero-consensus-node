// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.payload;

/**
 * This payload is logged when the PTT app starts a synthetic bottleneck session to slow
 * down the handling of transactions
 */
public class SyntheticBottleneckStartPayload extends AbstractLogPayload {
    public SyntheticBottleneckStartPayload(String message) {
        super(message);
    }
}
