// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.platform.reconnect.api.ReservedSignedStateResult;
import com.swirlds.platform.scratchpad.Scratchpad;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.iss.IssScratchpad;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.wiring.PlatformComponents;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.hashgraph.FreezePeriodChecker;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.roster.RosterHistory;

/**
 * This record contains core utilities and basic objects needed to build a platform. It should not contain any platform
 * components.
 *
 * @param platformComponents                     the wiring for this platform
 * @param platformContext                        the context for this platform
 * @param model                                  the wiring model for this platform
 * @param keysAndCerts                           an object holding all the public/private key pairs and the CSPRNG state
 *                                               for this member
 * @param selfId                                 the ID for this node
 * @param mainClassName                          the name of the app class inheriting from SwirldMain
 * @param swirldName                             the name of the swirld being run
 * @param appVersion                             the current version of the running application
 * @param initialState                           the initial state of the platform
 * @param rosterHistory                          the roster history provided by the application to use at startup
 * @param applicationCallbacks                   the callbacks that the platform will call when certain events happen
 * @param preconsensusEventConsumer              the consumer for preconsensus events, null if publishing this data has
 *                                               not been enabled
 * @param snapshotOverrideConsumer               the consumer for snapshot overrides, null if publishing this data has
 *                                               not been enabled
 * @param intakeEventCounter                     counts events that have been received by gossip but not yet inserted
 *                                               into gossip event storage, per peer
 * @param secureRandomSupplier                   a source of secure random number generator instances
 * @param freezeChecker                          a predicate that determines if a timestamp is in the freeze period
 * @param latestImmutableStateProviderReference  a reference to a method that supplies the latest immutable state. Input
 *                                               argument is a string explaining why we are getting this state (for
 *                                               debugging). Return value may be null (implementation detail of
 *                                               underlying data source), this indirection can be removed once states
 *                                               are passed within the wiring framework
 * @param consensusEventStreamName               a part of the name of the directory where the consensus event stream is
 *                                               written
 * @param issScratchpad                          scratchpad storage for ISS recovery
 * @param notificationEngine                     for sending notifications to the application (legacy pattern)
 * @param statusActionSubmitterReference         a reference to the status action submitter, this can be deleted once
 *                                               platform status management is handled by the wiring framework
 * @param stateLifecycleManager                  responsible for the mutable state, this is exposed here due to
 *                                               reconnect
 * @param getLatestCompleteStateReference        a reference to a supplier that supplies the latest immutable state,
 *                                               this is exposed here due to reconnect, can be removed once reconnect is
 *                                               made compatible with the wiring framework
 * @param firstPlatform                          if this is the first platform being built (there is static setup that
 *                                               needs to be done, long term plan is to stop using static variables)
 * @param execution                              the instance of the execution layer, which allows consensus to interact
 *                                               with the execution layer
 * @param fallenBehindMonitor                    an instance of the fallenBehind Monitor which tracks if the node has fallen behind
 * @param reservedSignedStateResultPromise             a shared data structure that Gossip and the ReconnectController will use provide
 *                                               and obtain a reference to a ReservedSignedState
 */
public record PlatformBuildingBlocks(
        @NonNull PlatformComponents platformComponents,
        @NonNull PlatformContext platformContext,
        @NonNull WiringModel model,
        @NonNull KeysAndCerts keysAndCerts,
        @NonNull NodeId selfId,
        @NonNull String mainClassName,
        @NonNull String swirldName,
        @NonNull SemanticVersion appVersion,
        @NonNull ReservedSignedState initialState,
        @NonNull RosterHistory rosterHistory,
        @NonNull ApplicationCallbacks applicationCallbacks,
        @Nullable Consumer<PlatformEvent> preconsensusEventConsumer,
        @Nullable Consumer<ConsensusSnapshot> snapshotOverrideConsumer,
        @NonNull IntakeEventCounter intakeEventCounter,
        @NonNull Supplier<SecureRandom> secureRandomSupplier,
        @NonNull FreezePeriodChecker freezeChecker,
        @NonNull AtomicReference<Function<String, ReservedSignedState>> latestImmutableStateProviderReference,
        @NonNull String consensusEventStreamName,
        @NonNull Scratchpad<IssScratchpad> issScratchpad,
        @NonNull NotificationEngine notificationEngine,
        @NonNull AtomicReference<StatusActionSubmitter> statusActionSubmitterReference,
        @NonNull StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
        @NonNull AtomicReference<Supplier<ReservedSignedState>> getLatestCompleteStateReference,
        boolean firstPlatform,
        @NonNull ConsensusStateEventHandler consensusStateEventHandler,
        @NonNull ExecutionLayer execution,
        @NonNull FallenBehindMonitor fallenBehindMonitor,
        @NonNull BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise) {
    public PlatformBuildingBlocks {
        requireNonNull(platformComponents);
        requireNonNull(platformContext);
        requireNonNull(model);
        requireNonNull(keysAndCerts);
        requireNonNull(selfId);
        requireNonNull(mainClassName);
        requireNonNull(swirldName);
        requireNonNull(appVersion);
        requireNonNull(initialState);
        requireNonNull(rosterHistory);
        requireNonNull(applicationCallbacks);
        requireNonNull(intakeEventCounter);
        requireNonNull(secureRandomSupplier);
        requireNonNull(freezeChecker);
        requireNonNull(latestImmutableStateProviderReference);
        requireNonNull(consensusEventStreamName);
        requireNonNull(issScratchpad);
        requireNonNull(notificationEngine);
        requireNonNull(statusActionSubmitterReference);
        requireNonNull(stateLifecycleManager);
        requireNonNull(getLatestCompleteStateReference);
        requireNonNull(consensusStateEventHandler);
        requireNonNull(execution);
        requireNonNull(fallenBehindMonitor);
        requireNonNull(reservedSignedStateResultPromise);
    }
}
