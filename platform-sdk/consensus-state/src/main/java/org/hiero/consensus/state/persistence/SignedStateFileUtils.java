// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.persistence;

import static com.swirlds.logging.legacy.LogMarker.SIGNED_STATE;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.state.saved.SavedStateInfo;

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

    /**
     * Get the consensus snapshot from a given directory. If the consensus snapshot cannot be read, return null.
     *
     * @param dir the path to the directory containing the consensus snapshot
     * @return the consensus snapshot, or null if it cannot be read
     * @throws IOException if there is an I/O error reading the consensus snapshot
     * @throws ParseException if there is an error parsing the consensus snapshot
     */
    @NonNull
    public static ConsensusSnapshot getConsensusSnapshot(@NonNull final Path dir) throws IOException, ParseException{
        final Path consensusSnapshotFile = dir.resolve(CONSENSUS_SNAPSHOT_FILE_NAME);
        return ConsensusSnapshot.JSON.parse(
                new ReadableStreamingData(new FileInputStream(consensusSnapshotFile.toFile())));
    }
}
