// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster.test.fixtures;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.params.provider.Arguments;

/**
 * This class is used for generating unit test method parameters, even though IntelliJ says it is not used.
 */
public class CryptoArgsProvider {
    public static final int NUMBER_OF_ADDRESSES = 10;

    /**
     * @return 1 set of arguments (generated)
     */
    static Stream<Arguments> basicTestArgs() {
        final RandomRosterBuilder rosterBuilder = RandomRosterBuilder.create(Randotron.create())
                .withSize(NUMBER_OF_ADDRESSES)
                .withRealKeysEnabled(true)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE);
        final Roster genRoster = rosterBuilder.build();
        final Map<NodeId, KeysAndCerts> genKac = genRoster.rosterEntries().stream()
                .map(RosterEntry::nodeId)
                .map(NodeId::of)
                .collect(Collectors.toMap(Function.identity(), rosterBuilder::getPrivateKeys));
        return Stream.of(Arguments.of(genRoster, genKac));
    }
}
