// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import org.hiero.consensus.model.status.PlatformStatus;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides the current platform status.
 */
public interface CurrentPlatformStatus {

    /**
     * Returns the current platform status.
     *
     * @return the current platform status
     */
    @NonNull
    PlatformStatus get();
}
