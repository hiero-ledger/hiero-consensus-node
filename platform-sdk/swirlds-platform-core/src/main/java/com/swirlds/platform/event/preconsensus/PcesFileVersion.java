// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import java.util.Arrays;

/**
 * The version of the file format used to serialize preconsensus events.
 */
public enum PcesFileVersion {
    /** The version of the file format that serializes events as protobuf. */
    PROTOBUF_EVENTS(2);

    private final int versionNumber;

    PcesFileVersion(final int versionNumber) {
        this.versionNumber = versionNumber;
    }

    /**
     * @return the version number of the file format
     */
    public int getVersionNumber() {
        return versionNumber;
    }

    /**
     * @return the version number of the current file format
     */
    public static int currentVersionNumber() {
        return PROTOBUF_EVENTS.getVersionNumber();
    }

    /**
     * Get the file format version with the given version number.
     *
     * @param versionNumber the version number of the file format
     * @return the file format version with the given version number
     */
    public static PcesFileVersion fromVersionNumber(final int versionNumber) {
        return Arrays.stream(PcesFileVersion.values())
                .filter(v -> v.getVersionNumber() == versionNumber)
                .findFirst()
                .orElse(null);
    }
}
