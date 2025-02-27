// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import java.util.LongSummaryStatistics;

/**
 * This interface indicates that the implementing class can provide statistics for the sizes of the files it uses.
 */
public interface FileStatisticAware {
    /**
     * Get statistics for sizes of the files it uses.
     *
     * @return statistics for sizes of all fully written files, in bytes
     */
    LongSummaryStatistics getFilesSizeStatistics();
}
