// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.hashgraph.FreezeCheckHolder;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * The default implementation of the {@link HashgraphModule}
 */
public class DefaultHashgraphModule implements HashgraphModule {

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final WiringModel model,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final FreezeCheckHolder freezeChecker) {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public InputWire<PlatformEvent> eventInputWire() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OutputWire<ConsensusRound> consensusRoundOutputWire() {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OutputWire<PlatformEvent> preconsensusEventOutputWire() {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OutputWire<PlatformEvent> staleEventOutputWire() {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public InputWire<PlatformStatus> platformStatusInputWire() {
        throw new UnsupportedOperationException("Not yet implemented.");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public InputWire<ConsensusSnapshot> consensusSnapshotInputWire() {
        throw new UnsupportedOperationException("Not yet implemented.");
    }
}
