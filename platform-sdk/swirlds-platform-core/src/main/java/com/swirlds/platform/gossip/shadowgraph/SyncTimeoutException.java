// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import com.swirlds.platform.gossip.SyncException;
import java.time.Duration;

public class SyncTimeoutException extends SyncException {
    public SyncTimeoutException(final Duration syncTime, final Duration maxSyncTime) {
        super(String.format(
                "Maximum sync time exceeded! Max time: %d sec, time elapsed: %d sec",
                maxSyncTime.toSeconds(), syncTime.toSeconds()));
    }
}
