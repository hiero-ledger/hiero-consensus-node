// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RosterWithKeys;
import edu.umd.cs.findbugs.annotations.NonNull;
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
    static Stream<Arguments> basicTestArgs() throws Exception {
        final RosterAndCerts rosterAndCerts = genRosterLoadKeys(NUMBER_OF_ADDRESSES);
        final RosterWithKeys rosterWithKeys = RandomRosterBuilder.create(Randotron.create())
                .withSize(NUMBER_OF_ADDRESSES)
                .withRealKeysEnabled(true)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
                .buildWithKeys();
        final Map<NodeId, KeysAndCerts> genKac = rosterWithKeys.getRoster().rosterEntries().stream()
                .map(RosterEntry::nodeId)
                .map(NodeId::of)
                .collect(Collectors.toMap(Function.identity(), rosterWithKeys::getKeysAndCerts));
        return Stream.of(
                Arguments.of(rosterAndCerts.roster(), rosterAndCerts.nodeIdKeysAndCertsMap()),
                Arguments.of(rosterWithKeys.getRoster(), genKac));
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
