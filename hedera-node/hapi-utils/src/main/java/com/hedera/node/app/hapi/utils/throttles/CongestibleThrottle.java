// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

/**
 * All throttles that can be used to determine congestion should implement this interface.
 * All congestion multipliers are read through this interface.
 */
public interface CongestibleThrottle {
    long used();

    long capacity();

    long mtps();

    String name();

    double instantaneousPercentUsed();
}
