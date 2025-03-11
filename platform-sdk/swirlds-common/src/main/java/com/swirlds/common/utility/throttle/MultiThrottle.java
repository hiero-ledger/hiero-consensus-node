// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility.throttle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Each instance of this class can be used to throttle some kind of flow, to allow only a certain number of
 * transactions per second for the union of a set of throttles.
 *
 * For each transaction all throttles will be sorted by capacity before allowing for each throttle contained
 * in the MultiThrottle
 *
 * and then when a transaction is received, do this:
 *
 * assemble either a List of throttles or individually add throttles related to the current transaction
 *
 * <pre>{@code
 * if (multiThrottle.allow()) {
 *    //accept the transaction
 * } else {
 *    //reject the transaction because BUSY
 * }
 * }</pre>
 */
public class MultiThrottle {
    private final List<Throttle> throttles;

    /**
     * Create a new set of ANDed throttles that must allow and increment all throttles synchronously
     */
    public MultiThrottle() {
        this.throttles = new ArrayList<>();
    }

    /**
     * Create a new set of ANDed throttles that must allow and increment all throttles synchronously
     *
     * @param throttles
     * 		a list of throttles including that need to all allow additional traffic to be incremented
     */
    public MultiThrottle(final List<Throttle> throttles) {
        if (throttles == null) {
            throw new IllegalArgumentException("throttles cannot be null");
        }

        this.throttles = new ArrayList<>(throttles);

        sortThrottles();
    }

    /**
     * add a throttle to the ArrayList of throttles in the current MultiThrottle
     *
     * @param throttle
     * 		a throttles to be added to the list of Throttles
     */
    public synchronized void addThrottle(final Throttle throttle) {
        if (throttle == null) {
            throw new IllegalArgumentException("throttle cannot be null");
        }

        throttles.add(throttle);

        sortThrottles();
    }

    /**
     * Can 1 transactions be allowed right now?  If so, return true, and record that it was allowed
     * (which will reduce the number allowed in the near future)
     *
     * @return can this number of transactions be allowed right now?
     */
    public synchronized boolean allow() {
        return allow(1);
    }

    /**
     * Can the given number of transactions be allowed right now?  If so, return true, and record that they were allowed
     * (which will reduce the number allowed in the near future)
     *
     * @param amount
     * 		the number of transactions in the block (must be nonnegative)
     * @return can this number of transactions be allowed right now?
     */
    public synchronized boolean allow(final double amount) {
        for (Throttle throttle : throttles) {
            if (!throttle.allow(amount)) {
                return false;
            }
        }

        return true;
    }

    private void sortThrottles() {
        throttles.sort(Comparator.comparing(Throttle::getCapacity));
    }
}
