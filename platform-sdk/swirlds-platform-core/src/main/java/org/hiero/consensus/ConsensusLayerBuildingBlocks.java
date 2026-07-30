// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.transformers.WireTransformer;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.iss.detection.IssDetectionModule;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.SavedStateController;
import org.hiero.consensus.state.StateModule;
import org.hiero.consensus.status.monitor.StatusMonitorModule;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;

public record ConsensusLayerBuildingBlocks(
        @NonNull WiringModel wiringModel,
        @NonNull Configuration configuration,
        @NonNull EventCreatorModule eventCreatorModule,
        @NonNull EventIntakeModule eventIntakeModule,
        @NonNull PcesModule pcesModule,
        @NonNull HashgraphModule hashgraphModule,
        @NonNull GossipModule gossipModule,
        @NonNull IssDetectionModule issDetectionModule,
        @NonNull TransactionHandlingModule transactionHandlingModule,
        @NonNull StateModule stateModule,
        @NonNull ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring,
        @NonNull RunningEventHashOverrideWiring runningEventHashOverrideWiring,
        @NonNull WireTransformer<EventWindow, EventWindow> initialEventWindowDispatcher,
        @NonNull ComponentWiring<AppNotifier, Void> notifierWiring,
        @NonNull StatusMonitorModule statusMonitorModule,
        @NonNull NotificationEngine notificationEngine,
        @NonNull SavedStateController savedStateController,
        @NonNull BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
        @NonNull FallenBehindMonitor fallenBehindMonitor,
        @NonNull IntakeEventCounter intakeEventCounter,
        @NonNull PipelineFlusher pipelineFlusher) {}
