// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with freeze state.
 */
public interface ReadableFreezeStore {
    /**
     * Get the hash of the prepared update file. If no prepared update file has been set
     * (i.e. if the network is not in the process of an upgrade), this method will return null.
     * @return the hash of the prepared update file, or null if no prepared update file has been set
     */
    @Nullable
    Bytes updateFileHash();

    /**
     * Returns the scheduled freeze time, or null if there is no freeze currently scheduled.
     */
    @Nullable
    Timestamp freezeTime();
}
