// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The per-event fields a graph declares once in its header, so that a row carries only what it changes.
 * <p>
 * Every field here is hashed content (D1), so each has to have a definite value on every event or the graph's hashes
 * do not reproduce. Writing all of them on all thirty rows would bury the structure the file exists to show, which is
 * why they are defaults rather than columns.
 *
 * @param coin             the coin value, whose parity is read only if an election reaches a coin round
 * @param birthRound       the birth round of an event with no parents; every other event derives it from its parents
 * @param transactionCount the number of transactions per event
 */
public record GraphDefaults(long coin, long birthRound, int transactionCount) {

    /** What a graph gets when its header declares nothing: no transactions, no coin, everything in round one. */
    public static final GraphDefaults STANDARD = new GraphDefaults(0, 1, 0);

    @NonNull
    public GraphDefaults withCoin(final long newCoin) {
        return new GraphDefaults(newCoin, birthRound, transactionCount);
    }

    @NonNull
    public GraphDefaults withBirthRound(final long newBirthRound) {
        return new GraphDefaults(coin, newBirthRound, transactionCount);
    }

    @NonNull
    public GraphDefaults withTransactionCount(final int newTransactionCount) {
        return new GraphDefaults(coin, birthRound, newTransactionCount);
    }
}
