// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

/**
 * A type able to reclaim its throttle used capacity.
 */
public interface ThrottleUsage {
    void reclaimCapacity();
}
