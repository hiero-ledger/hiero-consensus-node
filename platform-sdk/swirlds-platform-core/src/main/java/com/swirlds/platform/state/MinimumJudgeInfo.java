// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the minimum ancient indicator for all judges in a particular round.
 *
 * @param round                        the round number
 * @param minimumJudgeAncientThreshold the minimum ancient threshold for all judges for a given round. Will be a
 *                                     generation if the birth round migration has not yet happened, will be a birth
 *                                     round otherwise.
 */
public record MinimumJudgeInfo(long round, long minimumJudgeAncientThreshold) {

    /**
     * The maximum permissible size of a list of {@link MinimumJudgeInfo} lists as deserialized by
     * {@link #deserializeList(SerializableDataInputStream)}. Is an upper bound for
     * {@link com.swirlds.platform.consensus.ConsensusConfig#roundsNonAncient()}, choices for this config that exceed
     * this value will result in an exception being thrown when deserializeList is called.
     */
    public static final int MAX_MINIMUM_JUDGE_INFO_SIZE = 32;

    /**
     * Serialize a list of {@link MinimumJudgeInfo} objects
     *
     * @param minimumJudgeInfo the list of {@link MinimumJudgeInfo} objects to serialize
     * @param out              the stream to write to
     * @throws IOException thrown if an IO error occurs
     */
    public static void serializeList(
            @NonNull final List<MinimumJudgeInfo> minimumJudgeInfo, @NonNull final SerializableDataOutputStream out)
            throws IOException {
        out.writeInt(minimumJudgeInfo.size());
        for (final MinimumJudgeInfo info : minimumJudgeInfo) {
            out.writeLong(info.round());
            out.writeLong(info.minimumJudgeAncientThreshold());
        }
    }

    /**
     * Deserialize a list of {@link MinimumJudgeInfo} objects
     *
     * @param in the stream to read from
     * @return the list of {@link MinimumJudgeInfo} objects
     * @throws IOException thrown if an IO error occurs
     */
    public static List<MinimumJudgeInfo> deserializeList(@NonNull final SerializableDataInputStream in)
            throws IOException {
        final int size = in.readInt();
        if (size > MAX_MINIMUM_JUDGE_INFO_SIZE) {
            throw new IOException("Deserializing a list of MinimumJudgeInfo objects with size " + size
                    + " exceeds the maximum permissible size of " + MAX_MINIMUM_JUDGE_INFO_SIZE);
        }
        final List<MinimumJudgeInfo> minimumJudgeInfo = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            minimumJudgeInfo.add(new MinimumJudgeInfo(in.readLong(), in.readLong()));
        }
        return minimumJudgeInfo;
    }
}
