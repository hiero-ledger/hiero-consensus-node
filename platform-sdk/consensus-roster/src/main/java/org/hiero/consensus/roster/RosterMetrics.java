// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.metrics.FunctionGauge;

/**
 * A utility class to encapsulate the metrics for the address book.
 */
public final class RosterMetrics {

    private RosterMetrics() {}

    /**
     * Register the metrics for the address book.
     *
     * @param metrics     the metrics engine
     * @param roster      the roster to register metrics for
     * @param selfId      the ID of the node
     */
    public static void registerRosterMetrics(
            @NonNull final Metrics metrics, @NonNull final Roster roster, @NonNull final NodeId selfId) {

        metrics.getOrCreate(new FunctionGauge.Config<>(Metrics.INFO_CATEGORY, "memberID", Long.class, selfId::id)
                .withUnit("nodeID")
                .withDescription("The node ID number of this member"));

        metrics.getOrCreate(new FunctionGauge.Config<>(
                        Metrics.INFO_CATEGORY, "members", Integer.class, roster.rosterEntries()::size)
                .withUnit("count")
                .withDescription("total number of nodes currently in the roster"));
    }
}
