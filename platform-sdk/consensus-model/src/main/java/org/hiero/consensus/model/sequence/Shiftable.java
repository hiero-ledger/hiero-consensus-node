// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.sequence;

/**
 * A data structure with a shifting window of acceptable values. When a new value is added, if the value
 * falls outside the currently accepted window then it is ignored.
 */
public interface Shiftable {
    /**
     * Purge all data with a generation older (lower number) than the specified generation
     *
     * @param firstSequenceNumberInWindow
     * 		the first sequence number in the window after this operation completes, all data older than
     * 		this value is removed
     */
    void shiftWindow(long firstSequenceNumberInWindow);
}
