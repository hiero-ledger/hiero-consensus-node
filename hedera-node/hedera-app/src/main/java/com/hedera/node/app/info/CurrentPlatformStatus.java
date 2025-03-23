// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;

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
