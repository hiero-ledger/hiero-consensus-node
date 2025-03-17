// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.task;

/**
 * An object capable of tracking the number of leaves received during a reconnect.
 */
public interface ReconnectNodeCount {

    void incrementLeafCount();

    void incrementRedundantLeafCount();

    void incrementInternalCount();

    void incrementRedundantInternalCount();
}
