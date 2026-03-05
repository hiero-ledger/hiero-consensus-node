// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl;

import static org.hiero.consensus.event.creator.impl.EventCreationStatus.ATTEMPTING_CREATION;
import static org.hiero.consensus.event.creator.impl.EventCreationStatus.IDLE;
import static org.hiero.consensus.event.creator.impl.EventCreationStatus.NO_ELIGIBLE_PARENTS;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.FloatFormats;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.event.FutureEventBuffer;
import org.hiero.consensus.event.FutureEventBufferingOption;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.impl.rules.AggregateEventCreationRules;
import org.hiero.consensus.event.creator.impl.rules.EventCreationRule;
import org.hiero.consensus.event.creator.impl.rules.MaximumRateRule;
import org.hiero.consensus.event.creator.impl.rules.PlatformHealthRule;
import org.hiero.consensus.event.creator.impl.rules.PlatformStatusRule;
import org.hiero.consensus.event.creator.impl.rules.QuiescenceRule;
import org.hiero.consensus.event.creator.impl.rules.SyncLagCalculator;
import org.hiero.consensus.event.creator.impl.rules.SyncLagRule;
import org.hiero.consensus.metrics.extensions.PhaseTimer;
import org.hiero.consensus.metrics.extensions.PhaseTimerBuilder;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.gossip.SyncProgress;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;

/**
 * Default implementation of the {@link EventCreationManager}.
 */
public class DefaultEventCreationManager implements EventCreationManager {

    private static final DoubleGauge.Config SYNC_ROUND_LAG_METRIC_CONFIG = new DoubleGauge.Config(
                    Metrics.PLATFORM_CATEGORY, "syncRoundLag")
            .withDescription("How many rounds on average are we lagging behind peers")
            .withFormat(FloatFormats.FORMAT_DECIMAL_3);
    /**
     * Creates events.
     */
    private final EventCreator creator;

    /**
     * Rules that say if event creation is permitted.
     */
    private final EventCreationRule eventCreationRules;

    /**
     * Tracks the current phase of event creation.
     */
    private final PhaseTimer<EventCreationStatus> phase;

    /**
     * The duration that the system has been unhealthy.
     */
    private Duration unhealthyDuration = Duration.ZERO;

    private final FutureEventBuffer futureEventBuffer;

    private final QuiescenceRule quiescenceRule;

    private final PlatformStatusRule platformStatusRule;

    private final DoubleGauge syncLagBehind;

    /**
     * Utility class for computing median lag for sync.
     */
    private final SyncLagCalculator syncLagCalculator;

    /**
     * Constructor of the event creation manager.
     *
     * @param configuration provides the configuration for the event creator
     * @param metrics provides the metrics for the event creator
     * @param time provides the time source for the event creator
     * @param signatureTransactionCheck checks for pending signature transactions
     * @param eventCreator creates events
     * @param roster current roster
     * @param selfId id of current node
     */
    public DefaultEventCreationManager(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final SignatureTransactionCheck signatureTransactionCheck,
            @NonNull final EventCreator eventCreator,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId) {
        this.creator = Objects.requireNonNull(eventCreator);
        this.syncLagCalculator = new SyncLagCalculator(selfId, roster);
        final EventCreationConfig config = configuration.getConfigData(EventCreationConfig.class);

        platformStatusRule = new PlatformStatusRule(signatureTransactionCheck);
        quiescenceRule = new QuiescenceRule();
        final List<EventCreationRule> rules = new ArrayList<>();
        rules.add(new MaximumRateRule(configuration, time));
        rules.add(platformStatusRule);
        rules.add(new PlatformHealthRule(config.maximumPermissibleUnhealthyDuration(), this::getUnhealthyDuration));
        rules.add(new SyncLagRule(config.maxAllowedSyncLag(), this::getSyncRoundLag));
        rules.add(quiescenceRule);

        eventCreationRules = AggregateEventCreationRules.of(rules);
        futureEventBuffer =
                new FutureEventBuffer(metrics, FutureEventBufferingOption.EVENT_BIRTH_ROUND, "eventCreator");

        phase = new PhaseTimerBuilder<>(metrics, time, "platform", EventCreationStatus.class)
                .enableFractionalMetrics()
                .setInitialPhase(IDLE)
                .setMetricsNamePrefix("eventCreation")
                .build();

        syncLagBehind = metrics.getOrCreate(SYNC_ROUND_LAG_METRIC_CONFIG);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public PlatformEvent maybeCreateEvent() {
        if (!eventCreationRules.isEventCreationPermitted()) {
            phase.activatePhase(eventCreationRules.getEventCreationStatus());
            return null;
        }

        phase.activatePhase(ATTEMPTING_CREATION);

        final PlatformEvent newEvent = creator.maybeCreateEvent();
        if (newEvent == null) {
            // The only reason why the event creator may choose not to create an event
            // is if there are no eligible parents.
            phase.activatePhase(NO_ELIGIBLE_PARENTS);
        } else {
            eventCreationRules.eventWasCreated();
            // After an event was created we check the status to update the right phase
            if (!eventCreationRules.isEventCreationPermitted()) {
                phase.activatePhase(eventCreationRules.getEventCreationStatus());
            } else {
                phase.activatePhase(IDLE);
            }
        }

        return newEvent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerEvent(@NonNull final PlatformEvent event) {
        final PlatformEvent nonFutureEvent = futureEventBuffer.addEvent(event);
        if (nonFutureEvent != null) {
            creator.registerEvent(event);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        creator.setEventWindow(eventWindow);
        futureEventBuffer.updateEventWindow(eventWindow).forEach(creator::registerEvent);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void quiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {
        Objects.requireNonNull(quiescenceCommand);
        quiescenceRule.quiescenceCommand(quiescenceCommand);
        creator.quiescenceCommand(quiescenceCommand);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        creator.clear();
        phase.activatePhase(IDLE);
        futureEventBuffer.clear();
        final EventWindow eventWindow = EventWindow.getGenesisEventWindow();
        futureEventBuffer.updateEventWindow(eventWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        this.platformStatusRule.setPlatformStatus(Objects.requireNonNull(platformStatus));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        unhealthyDuration = Objects.requireNonNull(duration);
    }

    @Override
    public void reportSyncProgress(@NonNull final SyncProgress syncProgress) {
        final long diff = syncProgress.peerWindow().getPendingConsensusRound()
                - syncProgress.localWindow().getPendingConsensusRound();
        syncLagCalculator.reportSyncLag(syncProgress.peerId(), diff);
    }

    /**
     * Get the duration that the system has been unhealthy.
     *
     * @return the duration that the system has been unhealthy
     */
    private Duration getUnhealthyDuration() {
        return unhealthyDuration;
    }

    /**
     * Get the lag behind the median of the network latest consensus round
     *
     * @return how many rounds are we behind the median of the network when comparing latest consensus round reported by
     * syncs
     */
    public double getSyncRoundLag() {
        final double clampedMedianLag = syncLagCalculator.getSyncRoundLag();
        syncLagBehind.set(clampedMedianLag);
        return clampedMedianLag;
    }
}
