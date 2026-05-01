// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

/**
 * Utility methods for dealing with signed states on disk.
 */
public final class SignedStateFileUtils {

    public static final String SIGNATURE_SET_FILE_NAME = "signatureSet.pbj";

    public static final String HASH_INFO_FILE_NAME = "hashInfo.txt";

    /**
     * The name of the file that contains the human-readable address book in the saved state
     */
    public static final String CURRENT_ROSTER_FILE_NAME = "currentRoster.json";

    /**
     * The name of the file that contains the human-readable consensus snapshot in the saved state
     */
    public static final String CONSENSUS_SNAPSHOT_FILE_NAME = "consensusSnapshot.json";

    private SignedStateFileUtils() {}
}
