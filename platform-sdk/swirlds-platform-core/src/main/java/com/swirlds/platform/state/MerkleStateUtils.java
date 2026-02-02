// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.base.formatting.TextTable;
import com.swirlds.common.utility.Mnemonics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.base.crypto.Hash;

/**
 * A utility class for the Merkle state.
 */
public class MerkleStateUtils {
    public static final String HASH_INFO_TEMPLATE = "(root) VirtualMap    state    /    %s";

    /**
     * Generate a string that describes this state.
     *
     * @param platformState current platform state
     *
     */
    @NonNull
    public static String createInfoString(
            @NonNull final PlatformStateAccessor platformState, @NonNull final Hash rootHash) {
        final Hash hashEventsCons = platformState.getLegacyRunningEventHash();

        final ConsensusSnapshot snapshot = platformState.getSnapshot();
        final List<MinimumJudgeInfo> minimumJudgeInfo = snapshot == null ? null : snapshot.minimumJudgeInfoList();

        final StringBuilder sb = new StringBuilder();

        new TextTable()
                .setBordersEnabled(false)
                .addRow("Round:", platformState.getRound())
                .addRow("Timestamp:", platformState.getConsensusTimestamp())
                .addRow("Next consensus number:", snapshot == null ? "null" : snapshot.nextConsensusNumber())
                .addRow("Legacy running event hash:", hashEventsCons)
                .addRow(
                        "Legacy running event mnemonic:",
                        hashEventsCons == null ? "null" : Mnemonics.generateMnemonic(hashEventsCons))
                .addRow("Rounds non-ancient:", platformState.getRoundsNonAncient())
                .addRow("Creation version:", platformState.getCreationSoftwareVersion())
                .addRow("Minimum judge hash code:", minimumJudgeInfo == null ? "null" : minimumJudgeInfo.hashCode())
                .addRow("Root hash:", rootHash)
                .render(sb);

        sb.append("\n");
        sb.append(String.format(HASH_INFO_TEMPLATE, Mnemonics.generateMnemonic(rootHash)));
        sb.append("\n");

        return sb.toString();
    }
}
