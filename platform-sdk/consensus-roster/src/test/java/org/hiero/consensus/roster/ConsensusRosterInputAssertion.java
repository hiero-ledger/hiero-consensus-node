// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.roster.RosterUtils.hash;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.roster.ConsensusLayerRosterInputs;

/**
 * A utility class for asserting the correctness of {@link ConsensusLayerRosterInputs} in tests.
 */
public class ConsensusRosterInputAssertion {

    private ConsensusRosterInputAssertion() {}

    public static void assertConsensusLayerRosterInputs(
            @NonNull final ConsensusLayerRosterInputs rosterInputs,
            @NonNull final List<Long> expectedRounds,
            @NonNull final List<Roster> expectedRosters) {
        final Map<Bytes, Roster> expectedRostersByHash =
                expectedRosters.stream().collect(toMap(roster -> hash(roster).getBytes(), identity()));
        final List<Bytes> expectedHashes =
                expectedRosters.stream().map(roster -> hash(roster).getBytes()).toList();

        assertThat(rosterInputs.history())
                .extracting(RoundRosterPair::roundNumber)
                .containsExactlyElementsOf(expectedRounds);
        assertThat(rosterInputs.history())
                .extracting(RoundRosterPair::activeRosterHash)
                .containsExactlyElementsOf(expectedHashes);
        assertThat(rosterInputs.rosters()).containsExactlyInAnyOrderEntriesOf(expectedRostersByHash);
    }
}
