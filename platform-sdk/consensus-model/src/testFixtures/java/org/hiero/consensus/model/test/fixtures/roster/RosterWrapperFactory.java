// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.test.fixtures.roster;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.roster.test.fixtures.RosterFactory;
import org.hiero.consensus.test.fixtures.WeightGenerator;

public class RosterWrapperFactory {

    /**
     * Create a random roster with the given size and weight generator with pre-generated keys for each node.
     *
     * @param random the source of randomness
     * @param size the number of entries in the roster
     * @param weightGenerator the weight generator to use
     * @return a {@link RosterWrapper} instance
     */
    @NonNull
    public static RosterWrapper randomRoster(
            @NonNull final Random random, final int size, @NonNull final WeightGenerator weightGenerator) {
        final Roster pbjRoster = RosterFactory.randomRoster(random, size, weightGenerator);
        return RosterWrapper.of(pbjRoster);
    }

    /**
     * Create a random roster with the given size and pre-generated keys for each node.
     *
     * @param random the source of randomness
     * @param size the number of entries in the roster
     * @return a {@link RosterWrapper} instance
     */
    @NonNull
    public static RosterWrapper randomRoster(@NonNull final Random random, final int size) {
        final Roster pbjRoster = RosterFactory.randomRoster(random, size);
        return RosterWrapper.of(pbjRoster);
    }

    /**
     * Create a random roster with the given size and weight generator, generating real keys for each node.
     *
     * @param random the source of randomness
     * @param size the number of entries in the roster
     * @param weightGenerator the weight generator to use
     * @return a {@link RosterWrapper} instance
     */
    @NonNull
    public static RosterWrapper randomRosterWithKeys(
            @NonNull final Random random, final int size, @NonNull final WeightGenerator weightGenerator) {
        final Roster pbjRoster = RosterFactory.randomRosterWithKeys(random, size, weightGenerator)
                .getRoster();
        return RosterWrapper.of(pbjRoster);
    }

    /**
     * Create a RosterWrapper from a list of RosterEntry instances.
     *
     * @param rosterEntries the list of RosterEntry instances
     * @return a {@link RosterWrapper} instance
     */
    @NonNull
    public static RosterWrapper createRosterWrapper(@NonNull final List<RosterEntry> rosterEntries) {
        return RosterWrapper.of(new Roster(rosterEntries));
    }
}
