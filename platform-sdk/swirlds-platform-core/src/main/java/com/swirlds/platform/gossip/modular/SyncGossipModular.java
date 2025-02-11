/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.gossip.modular;

import com.google.common.collect.ImmutableList;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.gossip.FallenBehindManagerImpl;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.ProtocolConfig;
import com.swirlds.platform.gossip.permits.SyncPermitProvider;
import com.swirlds.platform.gossip.shadowgraph.Shadowgraph;
import com.swirlds.platform.gossip.sync.SyncManagerImpl;
import com.swirlds.platform.gossip.sync.config.SyncConfig;
import com.swirlds.platform.network.communication.handshake.VersionCompareHandshake;
import com.swirlds.platform.network.protocol.*;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.status.PlatformStatus;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.wiring.NoInput;
import com.swirlds.platform.wiring.components.Gossip;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class used during refactoring; with time, it should disappear, as all things will move to main wiring as all shared state is resolved
 */
public class SyncGossipModular implements Gossip {

    private static final Logger logger = LogManager.getLogger(SyncGossipModular.class);

    private final SyncGossipController controller;
    private final PeerCommunication network;
    private SyncGossipSharedProtocolState sharedState;

    // this is not a nice dependency, should be removed as well as the sharedState
    private Consumer<PlatformEvent> receivedEventHandler;

    /**
     * Builds the gossip engine, depending on which flavor is requested in the configuration.
     *
     * @param platformContext               the platform context
     * @param threadManager                 the thread manager
     * @param keysAndCerts                  private keys and public certificates
     * @param roster                        the current roster
     * @param selfId                        this node's ID
     * @param appVersion                    the version of the app
     * @param swirldStateManager            manages the mutable state
     * @param latestCompleteState           holds the latest signed state that has enough signatures to be verifiable
     * @param statusActionSubmitter         for submitting updates to the platform status manager
     * @param loadReconnectState            a method that should be called when a state from reconnect is obtained
     * @param clearAllPipelinesForReconnect this method should be called to clear all pipelines prior to a reconnect
     * @param intakeEventCounter            keeps track of the number of events in the intake pipeline from each peer
     */
    public SyncGossipModular(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final SoftwareVersion appVersion,
            @NonNull final SwirldStateManager swirldStateManager,
            @NonNull final Supplier<ReservedSignedState> latestCompleteState,
            @NonNull final StatusActionSubmitter statusActionSubmitter,
            @NonNull final Consumer<SignedState> loadReconnectState,
            @NonNull final Runnable clearAllPipelinesForReconnect,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final PlatformStateFacade platformStateFacade) {

        this.network = new PeerCommunication(platformContext, roster, selfId, keysAndCerts);

        var shadowgraph =
                new Shadowgraph(platformContext, roster.rosterEntries().size(), intakeEventCounter);

        var fallenBehindManager = new FallenBehindManagerImpl(
                selfId,
                this.network.getTopology(),
                statusActionSubmitter,
                () -> sharedState.fallenBehindCallback().get().run(),
                platformContext.getConfiguration().getConfigData(ReconnectConfig.class));

        var syncManager = new SyncManagerImpl(platformContext, fallenBehindManager);

        var syncConfig = platformContext.getConfiguration().getConfigData(SyncConfig.class);
        final int permitCount;
        if (syncConfig.onePermitPerPeer()) {
            permitCount = roster.rosterEntries().size() - 1;
        } else {
            permitCount = syncConfig.syncProtocolPermitCount();
        }

        var syncPermitProvider = new SyncPermitProvider(platformContext, permitCount);

        sharedState = new SyncGossipSharedProtocolState(
                this.network.getNetworkMetrics(),
                syncPermitProvider,
                shadowgraph,
                syncManager,
                new AtomicBoolean(false),
                new CachedPoolParallelExecutor(threadManager, "node-sync"),
                new AtomicReference<>(PlatformStatus.STARTING_UP),
                event -> this.receivedEventHandler.accept(event),
                new AtomicReference<>());

        this.controller = new SyncGossipController(intakeEventCounter, sharedState);

        final List<Protocol> protocols = ImmutableList.of(
                HeartbeatProtocol.create(platformContext, sharedState),
                ReconnectProtocol.create(
                        platformContext,
                        sharedState,
                        threadManager,
                        latestCompleteState,
                        roster,
                        loadReconnectState,
                        clearAllPipelinesForReconnect,
                        swirldStateManager,
                        selfId,
                        controller,
                        platformStateFacade),
                SyncProtocol.create(platformContext, sharedState, intakeEventCounter, roster));

        final ProtocolConfig protocolConfig = platformContext.getConfiguration().getConfigData(ProtocolConfig.class);
        final VersionCompareHandshake versionCompareHandshake =
                new VersionCompareHandshake(appVersion, !protocolConfig.tolerateMismatchedVersion());
        final List<ProtocolRunnable> handshakeProtocols = List.of(versionCompareHandshake);

        var threads = network.buildProtocolThreads(threadManager, selfId, handshakeProtocols, protocols);

        controller.registerThingToStartButNotStop(sharedState.shadowgraphExecutor());
        controller.registerThingsToStart(threads);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bind(
            @NonNull final WiringModel model,
            @NonNull final BindableInputWire<PlatformEvent, Void> eventInput,
            @NonNull final BindableInputWire<EventWindow, Void> eventWindowInput,
            @NonNull final StandardOutputWire<PlatformEvent> eventOutput,
            @NonNull final BindableInputWire<NoInput, Void> startInput,
            @NonNull final BindableInputWire<NoInput, Void> stopInput,
            @NonNull final BindableInputWire<NoInput, Void> clearInput,
            @NonNull final BindableInputWire<Duration, Void> systemHealthInput,
            @NonNull final BindableInputWire<PlatformStatus, Void> platformStatusInput) {

        startInput.bindConsumer(ignored -> controller.start());
        stopInput.bindConsumer(ignored -> controller.stop());
        clearInput.bindConsumer(ignored -> controller.clear());

        eventInput.bindConsumer(sharedState.shadowgraph()::addEvent);
        eventWindowInput.bindConsumer(sharedState.shadowgraph()::updateEventWindow);

        systemHealthInput.bindConsumer(sharedState.syncPermitProvider()::reportUnhealthyDuration);
        platformStatusInput.bindConsumer(sharedState.currentPlatformStatus()::set);

        receivedEventHandler = eventOutput::forward;
    }
}
