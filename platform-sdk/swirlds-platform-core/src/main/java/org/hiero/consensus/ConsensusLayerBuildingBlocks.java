// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.transformers.WireTransformer;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.hashgraph.FreezePeriodChecker;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.status.StatusMonitorModule;

public record ConsensusLayerBuildingBlocks(
        @NonNull WiringModel wiringModel,
        @NonNull Configuration configuration,
        @NonNull EventCreatorModule eventCreatorModule,
        @NonNull EventIntakeModule eventIntakeModule,
        @NonNull PcesModule pcesModule,
        @NonNull HashgraphModule hashgraphModule,
        @NonNull GossipModule gossipModule,
        @NonNull WireTransformer<EventWindow, EventWindow> initialEventWindowDispatcher,
        @NonNull StatusMonitorModule statusMonitorModule,
        @NonNull BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
        @NonNull FallenBehindMonitor fallenBehindMonitor,
        @NonNull IntakeEventCounter intakeEventCounter,
        @NonNull FreezePeriodChecker freezePeriodChecker) {}
