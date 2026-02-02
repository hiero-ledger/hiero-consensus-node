// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
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

    /**
     * Creates a roster.
     *
     * @param size the size of the required roster
     */
    @NonNull
    public static RosterAndCerts genRosterLoadKeys(final int size) {
        final RandomRosterBuilder rosterBuilder = RandomRosterBuilder.create(Randotron.create())
                .withSize(size)
                .withRealKeysEnabled(true)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE);
        final Roster genRoster = rosterBuilder.build();
        final Map<NodeId, KeysAndCerts> genKac = genRoster.rosterEntries().stream()
                .map(RosterEntry::nodeId)
                .map(NodeId::of)
                .collect(Collectors.toMap(Function.identity(), rosterBuilder::getPrivateKeys));
        return new RosterAndCerts(genRoster, genKac);
    }
}
