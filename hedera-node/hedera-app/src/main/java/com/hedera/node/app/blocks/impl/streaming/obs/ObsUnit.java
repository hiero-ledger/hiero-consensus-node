// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

/**
 * Measurement unit attached to every {@link Statistics} and probe, used for labelling log output.
 */
public enum ObsUnit {
    NANOS,
    MICROS,
    BYTES,
    COUNT
}
