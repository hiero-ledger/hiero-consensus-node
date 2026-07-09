// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.internal;

import static org.hiero.consensus.platformstate.PlatformStateUtils.bulkUpdateOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.state.State;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.crypto.Hash;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.platformstate.PlatformStateModifier;
import org.hiero.consensus.platformstate.PlatformStateUtils;
import org.hiero.consensus.platformstate.PlatformStateValueAccumulator;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.test.fixtures.RandomSignedStateGenerator;
import org.hiero.consensus.status.StatusActionSubmitter;
import org.hiero.consensus.status.actions.PlatformStatusAction;

/**
 * A helper class for testing the {@link DefaultTransactionHandler}.
 */
public class TransactionHandlerTester implements AutoCloseable {
    private final PlatformStateModifier platformState;
    private final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;
    private final DefaultTransactionHandler defaultTransactionHandler;
    private final List<PlatformStatusAction> submittedActions = new ArrayList<>();
    private final List<Round> handledRounds = new ArrayList<>();
    private final ConsensusStateEventHandler consensusStateEventHandler;
    private final Instant freezeTime;
    private final Instant consensusTimestamp;

    /**
     * Constructs a new {@link TransactionHandlerTester} with the given {@link Roster}.
     *
     */
    public TransactionHandlerTester() {

        freezeTime = Instant.now();
        consensusTimestamp = freezeTime.minusMillis(1);

        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final FileSystemManager fileSystemManager = new FileSystemManager();
        platformState = new PlatformStateValueAccumulator();
        final RandomSignedStateGenerator randomSignedStateGenerator = new RandomSignedStateGenerator();
        final SignedState state = randomSignedStateGenerator.build();

        consensusStateEventHandler = mock(ConsensusStateEventHandler.class);

        when(consensusStateEventHandler.onSealConsensusRound(any(), any())).thenReturn(true);
        stateLifecycleManager = new VirtualMapStateLifecycleManager(metrics, time, configuration, fileSystemManager);
        stateLifecycleManager.initWithState(state.getState());
        doAnswer(i -> {
                    handledRounds.add(i.getArgument(0));
                    return null;
                })
                .when(consensusStateEventHandler)
                .onHandleConsensusRound(any(), same(stateLifecycleManager.getMutableState()), any());
        final AtomicReference<StatusActionSubmitter> statusActionSubmitterReference =
                new AtomicReference<>(submittedActions::add);
        defaultTransactionHandler = new DefaultTransactionHandler(
                time,
                configuration,
                metrics,
                stateLifecycleManager,
                statusActionSubmitterReference,
                SemanticVersion.DEFAULT,
                consensusStateEventHandler,
                NodeId.of(1),
                0L);
    }

    /**
     * @return the {@link DefaultTransactionHandler} used by this tester
     */
    public DefaultTransactionHandler getTransactionHandler() {
        return defaultTransactionHandler;
    }

    /**
     * @return the {@link PlatformStateModifier} used by this tester
     */
    public PlatformStateModifier getPlatformState() {
        return platformState;
    }

    /**
     * @return a list of all {@link PlatformStatusAction}s that have been submitted by the transaction handler
     */
    public List<PlatformStatusAction> getSubmittedActions() {
        return submittedActions;
    }

    /**
     * @return a list of all {@link Round}s that have been provided to the {@link State} for handling
     */
    public List<Round> getHandledRounds() {
        return handledRounds;
    }

    /**
     * @return the {@link StateLifecycleManager} used by this tester
     */
    public StateLifecycleManager<VirtualMapState, VirtualMap> getStateLifecycleManager() {
        return stateLifecycleManager;
    }

    /**
     * @return the {@link ConsensusStateEventHandler} used by this tester
     */
    public ConsensusStateEventHandler getStateEventHandler() {
        return consensusStateEventHandler;
    }

    /**
     * @return the list of legacy running hashes that were set on the state
     */
    public Hash getLegacyRunningHash() {
        return PlatformStateUtils.legacyRunningEventHashOf(stateLifecycleManager.getMutableState());
    }

    public void enableFreezePeriod() {
        bulkUpdateOf(stateLifecycleManager.getMutableState(), platformStateModifier -> {
            platformStateModifier.setConsensusTimestamp(consensusTimestamp);
            platformStateModifier.setFreezeTime(freezeTime);
        });
    }

    @Override
    public void close() {
        while (stateLifecycleManager.getLatestImmutableState().getRoot().getReservationCount() != -1) {
            stateLifecycleManager.getLatestImmutableState().release();
        }
        while (stateLifecycleManager.getMutableState().getRoot().getReservationCount() != -1) {
            stateLifecycleManager.getMutableState().release();
        }
    }
}
