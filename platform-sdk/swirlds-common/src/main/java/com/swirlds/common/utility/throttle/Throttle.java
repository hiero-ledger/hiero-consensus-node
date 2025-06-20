// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility.throttle;

import static com.swirlds.base.units.UnitConstants.NANOSECONDS_TO_SECONDS;

/**
 * Each instance of this class can be used to throttle some kind of flow, to allow only a certain number of transactions
 * per second or bytes per second or events per second etc.
 * <p>
 * For throttling transactions per second, the instance remembers how many transactions have occurred recently, and will
 * then answer whether a new transaction is allowed, or should be blocked.  This also works for sets of transactions,
 * where the entire set is either accepted or rejected.  So, to limit a flow of bytes per second, each byte can be
 * treated as a "transaction", and a block of 1kB can be considered a set of 1024 transactions.
 * <p>
 * Given the number of transactions per second (tps) and the max number of seconds worth of transactions that could ever
 * come in a single burst (burstPeriod), this uses a leaky bucket model to throttle it. Each allowed transaction
 * increases the contents of the bucket by 1. Each nanosecond decreases the contents of the bucket by a billionth of
 * tps.  If the next transaction or block of transactions would fill the bucket to more than tps * burstPeriod, then
 * that transaction or block is disallowed.
 * <p>
 * For example, to throttle smart contract calls to 1.5 per second on this computer, it would be instantiated as:
 *
 * <pre>{@code
 * Throttle contractThrottle = new Throttle(1.5);   //throttle to 1.5 tps for this node
 * }</pre>
 * <p>
 * and then when a transaction is received, do this:
 *
 * <pre>{@code
 * if (contractThrottle.allow()) {
 *    //accept the transaction
 * } else {
 *     //reject the transaction because BUSY
 * }
 * }</pre>
 */
public class Throttle {
    // allow a max of tps transactions per second, on average
    private volatile double tps;
    // after a long time of no transactions, allow a burst of tps * burstPeriod transactions all at once. This is how
    // long it takes the bucket to leak empty.
    private volatile double burstPeriod;
    // the size of the bucket
    private volatile double capacity;
    // amount of transaction traffic we have had recently. This is always in the range [0, capacity].
    private volatile double traffic;
    // the last time a transaction was received
    private volatile long lastTime;

    /**
     * Start throttling a new flow, allowing tps transactions per second, and bursts of at most tps transactions at
     * once.
     *
     * @param tps the max transactions per second (on average) that is allowed (negative values treated as 0)
     */
    public Throttle(double tps) {
        this(tps, 1);
    }

    /**
     * Start throttling a new flow, allowing tps transactions per second, and bursts of at most tps * burstPeriod
     * transactions at once.
     *
     * @param tps         the max transactions per second (on average) that is allowed (negative values treated as 0)
     * @param burstPeriod bursts can allow at most this many seconds' worth of transactions at once (negative values
     *                    treated as 0)
     */
    public Throttle(double tps, double burstPeriod) {
        this.tps = Math.max(0, tps);
        this.burstPeriod = Math.max(0, burstPeriod);
        this.capacity = tps * burstPeriod;
        this.traffic = 0;
        this.lastTime = System.nanoTime();
    }

    /**
     * Gets the computed capacity of this {@link Throttle} based on the formula {@code tps * burstPeriod}.
     *
     * @return the computed capacity
     */
    public synchronized double getCapacity() {
        return capacity;
    }

    /** get max transactions per second allowed, on average */
    public synchronized double getTps() {
        return tps;
    }

    /**
     * Set max transactions per second allowed, on average.
     *
     * @param tps max transactions per second (negative values will be treated as 0)
     */
    public synchronized void setTps(double tps) {
        this.tps = Math.max(0, tps);
        capacity = this.tps * this.burstPeriod;
    }

    /** get the number of seconds worth of traffic that can occur in a single burst */
    public synchronized double getBurstPeriod() {
        return burstPeriod;
    }

    /**
     * set the number of seconds worth of traffic that can occur in a single burst
     *
     * @param burstPeriod bursts can allow at most this many seconds' worth of transactions at once (negative values
     *                    treated as 0)
     */
    public synchronized void setBurstPeriod(double burstPeriod) {
        this.burstPeriod = Math.max(0, burstPeriod);
        this.capacity = this.tps * this.burstPeriod;
    }

    /**
     * Can one more transaction be allowed right now?  If so, return true, and record that it was allowed (which will
     * reduce the number allowed in the near future)
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
     * @param amount the number of transactions in the block (must be nonnegative)
     * @return can this number of transactions be allowed right now?
     */
    public synchronized boolean allow(double amount) {
        // when a new transaction comes in, do this:
        long t = System.nanoTime();
        traffic = Math.min(
                capacity, Math.max(0, traffic - (t - lastTime) * tps * NANOSECONDS_TO_SECONDS)); // make the bucket
        // leak
        lastTime = t;
        if (amount < 0 || traffic + amount > capacity) {
            return false;
        }
        traffic += amount;
        return true;
    }
}
