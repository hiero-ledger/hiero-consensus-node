// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.freeze.FreezeCheckHolder;
import org.hiero.consensus.model.event.PlatformEvent;

public interface HashgraphModule {

    void initialize(
            @NonNull Configuration configuration,
            @NonNull Metrics metrics,
            @NonNull Time time,
            @NonNull Roster roster,
            @NonNull NodeId selfId,
            @NonNull FreezeCheckHolder freezeChecker);

    InputWire<PlatformEvent> addEventInputWire();

    OutputWire<ConsensusEngineOutput> consensusEngineOutputWire();
}
