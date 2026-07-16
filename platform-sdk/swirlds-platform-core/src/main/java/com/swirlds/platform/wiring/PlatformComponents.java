// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.platform.SwirldsPlatform;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.monitor.StatusMonitorModule;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.iss.detection.IssDetectionModule;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.StateModule;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;

/**
 * Encapsulates wiring for {@link SwirldsPlatform}.
 */
public record PlatformComponents(
        WiringModel model,
        EventCreatorModule eventCreatorModule,
        EventIntakeModule eventIntakeModule,
        PcesModule pcesModule,
        HashgraphModule hashgraphModule,
        GossipModule gossipModule,
        IssDetectionModule issDetectionModule,
        TransactionHandlingModule transactionHandlingModule,
        StateModule stateModule,
        ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring,
        RunningEventHashOverrideWiring runningEventHashOverrideWiring,
        ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring,
        ComponentWiring<AppNotifier, Void> notifierWiring,
        StatusMonitorModule statusMonitorModule) {}
