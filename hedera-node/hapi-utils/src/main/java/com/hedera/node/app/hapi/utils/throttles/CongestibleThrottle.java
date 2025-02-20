// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.throttles;

public interface CongestibleThrottle {
    long used();

    long capacity();

    long mtps();

    String name();

    double instantaneousPercentUsed();
}
