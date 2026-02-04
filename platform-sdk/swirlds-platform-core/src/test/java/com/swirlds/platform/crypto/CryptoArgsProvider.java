// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.roster.test.fixtures.RosterWithKeys;
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
        final RosterAndCerts rosterAndCerts = genRosterLoadKeys(NUMBER_OF_ADDRESSES);
        return Stream.of(Arguments.of(rosterAndCerts.roster(), rosterAndCerts.nodeIdKeysAndCertsMap()));
    }

    /**
     * Creates a roster.
     *
     * @param size the size of the required roster
     */
    @NonNull
    public static RosterAndCerts genRosterLoadKeys(final int size) {
        final RosterWithKeys rosterWithKeys = RandomRosterBuilder.create(Randotron.create())
                .withSize(size)
                .withRealKeysEnabled(true)
                .withWeightGenerator(WeightGenerators.BALANCED_1000_PER_NODE)
                .buildWithKeys();
        return new RosterAndCerts(rosterWithKeys.getRoster(), rosterWithKeys.getAllKeysAndCerts());
    }
}
