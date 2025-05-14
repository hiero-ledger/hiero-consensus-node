// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.hashgraph.EventWindow;

public class EventWindowUtils {
    public static EventWindow createEventWindow(
            @NonNull final ConsensusSnapshot snapshot, @NonNull Configuration configuration) {
        return createEventWindow(
                snapshot,
                configuration.getConfigData(EventConfig.class).getAncientMode(),
                configuration.getConfigData(ConsensusConfig.class).roundsNonAncient());
    }

    public static EventWindow createEventWindow(
            @NonNull final ConsensusSnapshot snapshot,
            @NonNull final AncientMode ancientMode,
            final int roundsNonAncient) {
        final long ancientThreshold = RoundCalculationUtils.getAncientThreshold(roundsNonAncient, snapshot);
        return new EventWindow(snapshot.round(), ancientThreshold, ancientThreshold, ancientMode);
    }
}
