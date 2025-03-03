// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import java.util.Set;

/**
 * Utility methods for dealing with signed states on disk.
 */
public final class SignedStateFileUtils {

    public static final String SIGNATURE_SET_FILE_NAME = "signatureSet.bin";

    public static final String HASH_INFO_FILE_NAME = "hashInfo.txt";

    /**
     * The name of the file that contains the human-readable address book in the saved state
     */
    public static final String CURRENT_ROSTER_FILE_NAME = "currentRoster.json";

    /**
     * The initial version of the signature set file
     */
    public static final int INIT_SIG_SET_FILE_VERSION = 1;

    /**
     * The supported versions of the signature set file
     */
    public static final Set<Integer> SUPPORTED_SIGSET_VERSIONS = Set.of(INIT_SIG_SET_FILE_VERSION);

    private SignedStateFileUtils() {}
}
