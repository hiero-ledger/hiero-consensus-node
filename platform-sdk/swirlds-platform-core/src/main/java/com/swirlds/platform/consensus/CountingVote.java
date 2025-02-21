// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

/**
 * The outcome of the counting vote in consensus. Since there are only 4 outcomes, there is no need
 * to instantiate a new object every time, so less work for the garbage collector.
 */
public enum CountingVote {
    YES_MAJORITY(true, true),
    YES_MINORITY(true, false),
    NO_MAJORITY(false, true),
    NO_MINORITY(false, false);

    private final boolean vote;
    private final boolean supermajority;

    CountingVote(final boolean vote, final boolean supermajority) {
        this.vote = vote;
        this.supermajority = supermajority;
    }

    /**
     * Get an instance of the counting vote based on the outcome supplied
     *
     * @param vote true if it's a yes vote, false if no
     * @param supermajority true if a supermajority voted yes, false if there was no supermajority
     * @return an instance representing this outcome
     */
    public static CountingVote get(final boolean vote, final boolean supermajority) {
        if (vote) {
            if (supermajority) {
                return YES_MAJORITY;
            } else {
                return YES_MINORITY;
            }
        } else {
            if (supermajority) {
                return NO_MAJORITY;
            } else {
                return NO_MINORITY;
            }
        }
    }

    /**
     * @return true if it's a yes vote, false if no
     */
    public boolean getVote() {
        return vote;
    }

    /**
     * @return true if a supermajority voted yes, false if there was no supermajority
     */
    public boolean isSupermajority() {
        return supermajority;
    }
}
