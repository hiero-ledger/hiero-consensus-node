// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.iss.detection.IssDetectionModule;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.state.SavedStateController;
import org.hiero.consensus.state.StateModule;
import org.hiero.consensus.state.nexus.SignedStateNexus;
import org.hiero.consensus.transaction.handling.TransactionHandlingModule;

public record ConsensusLayerAdapterBuildingBlocks(
        @NonNull WiringModel wiringModel,
        @NonNull Configuration configuration,
        @NonNull ConsensusLayerLifecycleManager consensusLayerLifecycleManager,
        @NonNull IssDetectionModule issDetectionModule,
        @NonNull TransactionHandlingModule transactionHandlingModule,
        @NonNull StateModule stateModule,
        @NonNull RunningEventHashOverrideWiring runningEventHashOverrideWiring,
        @NonNull ComponentWiring<AppNotifier, Void> notifierWiring,
        @NonNull NotificationEngine notificationEngine,
        @NonNull SavedStateController savedStateController,
        // TODO figure out what to do with this - does it get shared between gossip and reconnect? Does it go away?
        @NonNull FallenBehindMonitor fallenBehindMonitor,
        @NonNull AtomicReference<PlatformStatus> platformStatusReference,
        @NonNull SignedStateNexus lastCompleteSignedState) {}
