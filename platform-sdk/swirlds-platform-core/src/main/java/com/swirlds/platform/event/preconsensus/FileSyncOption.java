// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

/**
 * Options for syncing PCES files to disk.
 */
public enum FileSyncOption {
    /**
     * Sync the file after every event.
     */
    EVERY_EVENT,
    /**
     * Sync the file after every self event.
     */
    EVERY_SELF_EVENT,
    /**
     * Never sync the file. The data will be guaranteed to be written to disk when the file is closed.
     */
    DONT_SYNC
}
