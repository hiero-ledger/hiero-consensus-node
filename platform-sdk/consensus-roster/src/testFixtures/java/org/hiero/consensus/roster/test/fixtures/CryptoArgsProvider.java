// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster.test.fixtures;

import java.util.stream.Stream;
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
        final RosterWithKeys rosterWithKeys = RosterFactory.randomRosterWithKeys(
                Randotron.create(), NUMBER_OF_ADDRESSES, WeightGenerators.BALANCED_1000_PER_NODE);
        return Stream.of(Arguments.of(rosterWithKeys.getRoster(), rosterWithKeys.getAllKeysAndCerts()));
    }
}
