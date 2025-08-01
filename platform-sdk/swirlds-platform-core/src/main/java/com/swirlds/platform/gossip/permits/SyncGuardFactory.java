// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.permits;

/**
 * Factory class for SyncGuard implementations
 */
public class SyncGuardFactory {

    /**
     * Create new SyncGuard configured with parameters passed into the method.
     *
     * @param fairMaxConcurrentSyncs    maximum number of concurrent syncs running after which we won't initiate any
     *                                  more outgoing syncs (but can accept incoming ones) if set &lt;= 0, disabled
     *                                  entire fair sync logic (syncs will always be initiated if no other reasons block
     *                                  them) if set &gt; 0 and &lt;= 1, this number is set as a ratio of total number
     *                                  of nodes in the network if &gt; 1, ceiling of that number is used as limit of
     *                                  concurrent syncs
     * @param fairMinimalRoundRobinSize minimal number of synchronizations which happened in the past and are not
     *                                  currently running which has to be breached before sync against same peer can be
     *                                  considered if set &gt; 0 and &lt;= 1, this number is set as a ratio of total
     *                                  number of nodes in the network if &gt; 1, ceiling of that number is used as
     *                                  minimal round robin size
     * @param numberOfNodes             amount of nodes in entire network
     * @return new SyncGuard instance with some kind of fairness, or no-op implementation in case fairMaxConcurrentSyncs
     * is &lt;= 0
     */
    public static SyncGuard create(
            final double fairMaxConcurrentSyncs, final double fairMinimalRoundRobinSize, final int numberOfNodes) {

        final int maxConcurrentSyncs = (int)
                Math.ceil(fairMaxConcurrentSyncs > 1 ? fairMaxConcurrentSyncs : fairMaxConcurrentSyncs * numberOfNodes);
        final int minimalRoundRobinSize = (int) Math.ceil(
                fairMinimalRoundRobinSize > 1 ? fairMinimalRoundRobinSize : fairMinimalRoundRobinSize * numberOfNodes);

        if (maxConcurrentSyncs <= 0) {
            return new NoopSyncGuard();
        } else {
            return new LruSyncGuard(maxConcurrentSyncs, minimalRoundRobinSize);
        }
    }
}
