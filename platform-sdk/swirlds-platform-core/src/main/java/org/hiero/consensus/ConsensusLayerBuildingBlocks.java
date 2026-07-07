package org.hiero.consensus;

import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.reconnect.ReconnectModule;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.PlatformMonitor;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.event.stream.ConsensusEventStream;
import org.hiero.consensus.gossip.GossipModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.iss.detection.IssDetectionModule;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.state.management.SavedStateController;
import org.hiero.consensus.state.management.StateManagementModule;
import org.hiero.consensus.state.signed.StateGarbageCollector;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;

public record ConsensusLayerBuildingBlocks(@NonNull WiringModel wiringModel,
                                           @NonNull Configuration configuration,
                                           @NonNull EventCreatorModule eventCreatorModule,
                                           @NonNull EventIntakeModule eventIntakeModule,
                                           @NonNull PcesModule pcesModule,
                                           @NonNull HashgraphModule hashgraphModule,
                                           @NonNull GossipModule gossipModule,
                                           @NonNull IssDetectionModule issDetectionModule,
                                           @NonNull TransactionHandlingModule transactionHandlingModule,
                                           @NonNull StateManagementModule stateManagementModule,
                                           @NonNull ReconnectModule reconnectModule,
                                           @NonNull ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring,
                                           @NonNull RunningEventHashOverrideWiring runningEventHashOverrideWiring,
                                           @NonNull ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring,
                                           @NonNull ComponentWiring<AppNotifier, Void> notifierWiring,
                                           @NonNull ComponentWiring<StateGarbageCollector, Void> stateGarbageCollectorWiring,
                                           @NonNull ComponentWiring<SignedStateSentinel, Void> signedStateSentinelWiring,
                                           @NonNull ComponentWiring<PlatformMonitor, PlatformStatus> platformMonitorWiring,
                                           @NonNull NotificationEngine notificationEngine,
                                           @NonNull SavedStateController savedStateController,
                                           @NonNull AtomicReference<Platform> platformReference) {
}
