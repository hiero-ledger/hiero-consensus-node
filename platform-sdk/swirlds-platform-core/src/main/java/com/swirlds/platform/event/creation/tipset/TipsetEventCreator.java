// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.creation.tipset;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.platform.event.creation.tipset.TipsetAdvancementWeight.ZERO_ADVANCEMENT_WEIGHT;
import static org.hiero.consensus.model.event.EventConstants.CREATOR_ID_UNDEFINED;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.stream.Signer;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import com.swirlds.platform.components.transaction.TransactionSupplier;
import com.swirlds.platform.event.EventUtils;
import com.swirlds.platform.event.creation.EventCreator;
import com.swirlds.platform.event.hashing.PbjStreamHasher;
import com.swirlds.platform.event.hashing.UnsignedEventHasher;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.roster.RosterUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.event.creator.impl.EventCreationConfig;

/**
 * Responsible for creating new events using the tipset algorithm.
 */
public class TipsetEventCreator implements EventCreator {

    private static final Logger logger = LogManager.getLogger(TipsetEventCreator.class);

    private final Time time;
    private final Random random;
    private final Signer signer;
    private final NodeId selfId;
    private final TipsetTracker tipsetTracker;
    private final TipsetWeightCalculator tipsetWeightCalculator;
    private final ChildlessEventTracker childlessOtherEventTracker;
    private final TransactionSupplier transactionSupplier;
    private final SemanticVersion softwareVersion;
    private EventWindow eventWindow;

    /**
     * The address book for the current network.
     */
    private final Roster roster;

    /**
     * The size of the current address book.
     */
    private final int networkSize;

    /**
     * The selfishness score is divided by this number to get the probability of creating an event that reduces the
     * selfishness score. The higher this number is, the lower the probability is that an event will be created that
     * reduces the selfishness score.
     */
    private final double antiSelfishnessFactor;

    /**
     * The metrics for the tipset algorithm.
     */
    private final TipsetMetrics tipsetMetrics;

    /**
     * The last event created by this node.
     */
    private EventDescriptorWrapper lastSelfEvent;

    /**
     * The timestamp of the last event created by this node.
     */
    private Instant lastSelfEventCreationTime;

    /**
     * The number of transactions in the last event created by this node.
     */
    private int lastSelfEventTransactionCount;

    /**
     * Defines the current ancient mode.
     */
    private final AncientMode ancientMode;

    private final RateLimitedLogger zeroAdvancementWeightLogger;
    private final RateLimitedLogger noParentFoundLogger;

    /**
     * Event hasher for unsigned events.
     */
    private final UnsignedEventHasher eventHasher;

    /**
     * Create a new tipset event creator.
     *
     * @param platformContext     the platform context
     * @param random              a source of randomness, does not need to be cryptographically secure
     * @param signer              used for signing things with this node's private key
     * @param roster              the current roster
     * @param selfId              this node's ID
     * @param softwareVersion     the current software version of the application
     * @param transactionSupplier provides transactions to be included in new events
     */
    public TipsetEventCreator(
            @NonNull final PlatformContext platformContext,
            @NonNull final Random random,
            @NonNull final Signer signer,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final SemanticVersion softwareVersion,
            @NonNull final TransactionSupplier transactionSupplier) {

        this.time = platformContext.getTime();
        this.random = Objects.requireNonNull(random);
        this.signer = Objects.requireNonNull(signer);
        this.selfId = Objects.requireNonNull(selfId);
        this.transactionSupplier = Objects.requireNonNull(transactionSupplier);
        this.softwareVersion = Objects.requireNonNull(softwareVersion);
        this.roster = Objects.requireNonNull(roster);

        final EventCreationConfig eventCreationConfig =
                platformContext.getConfiguration().getConfigData(EventCreationConfig.class);

        antiSelfishnessFactor = Math.max(1.0, eventCreationConfig.antiSelfishnessFactor());
        tipsetMetrics = new TipsetMetrics(platformContext, roster);
        ancientMode = platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode();
        tipsetTracker = new TipsetTracker(time, roster, ancientMode);
        childlessOtherEventTracker = new ChildlessEventTracker();
        tipsetWeightCalculator =
                new TipsetWeightCalculator(platformContext, roster, selfId, tipsetTracker, childlessOtherEventTracker);
        networkSize = roster.rosterEntries().size();

        zeroAdvancementWeightLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));
        noParentFoundLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));

        this.eventWindow = EventWindow.getGenesisEventWindow(ancientMode);
        this.eventHasher = new PbjStreamHasher();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerEvent(@NonNull final PlatformEvent event) {
        if (eventWindow.isAncient(event)) {
            return;
        }

        final NodeId eventCreator = event.getCreatorId();
        if (RosterUtils.getIndex(roster, eventCreator.id()) == -1) {
            return;
        }
        final boolean selfEvent = eventCreator.equals(selfId);

        if (selfEvent) {
            if (lastSelfEvent == null || lastSelfEvent.eventDescriptor().generation() < event.getGeneration()) {
                // Normally we will ingest self events before we get to this point, but it's possible
                // to learn of self events for the first time here if we are loading from a restart or reconnect.
                lastSelfEvent = event.getDescriptor();
                lastSelfEventCreationTime = event.getTimeCreated();
                lastSelfEventTransactionCount = event.getTransactionCount();
                childlessOtherEventTracker.registerSelfEventParents(event.getOtherParents());
            } else {
                // We already ingested this self event (when it was created),
                return;
            }
        }

        final EventDescriptorWrapper descriptor = event.getDescriptor();
        final List<EventDescriptorWrapper> parentDescriptors = event.getAllParents();

        tipsetTracker.addEvent(descriptor, parentDescriptors);

        if (!selfEvent) {
            childlessOtherEventTracker.addEvent(descriptor, parentDescriptors);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);
        tipsetTracker.setEventWindow(eventWindow);
        childlessOtherEventTracker.pruneOldEvents(eventWindow);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public UnsignedEvent maybeCreateEvent() {
        if (networkSize == 1) {
            // Special case: network of size 1.
            // We can always create a new event, no need to run the tipset algorithm.
            return createEventForSizeOneNetwork();
        }

        final long selfishness = tipsetWeightCalculator.getMaxSelfishnessScore();
        tipsetMetrics.getSelfishnessMetric().update(selfishness);

        // Never bother with anti-selfishness techniques if we have a selfishness score of 1.
        // We are pretty much guaranteed to be selfish to ~1/3 of other nodes by a score of 1.
        final double beNiceChance = (selfishness - 1) / antiSelfishnessFactor;

        if (beNiceChance > 0 && random.nextDouble() < beNiceChance) {
            return createEventToReduceSelfishness();
        } else {
            return createEventByOptimizingAdvancementWeight();
        }
    }

    /**
     * Create the next event for a network of size 1 (i.e. where we are the only member). We don't use the tipset
     * algorithm like normal, since we will never have a real other parent.
     *
     * @return the new event
     */
    private UnsignedEvent createEventForSizeOneNetwork() {
        // There is a quirk in size 1 networks where we can only
        // reach consensus if the self parent is also the other parent.
        // Unexpected, but harmless. So just use the same event
        // as both parents until that issue is resolved.
        return buildAndProcessEvent(lastSelfEvent);
    }

    /**
     * Create an event using the other parent with the best tipset advancement weight.
     *
     * @return the new event, or null if it is not legal to create a new event
     */
    @Nullable
    private UnsignedEvent createEventByOptimizingAdvancementWeight() {
        final List<EventDescriptorWrapper> possibleOtherParents = childlessOtherEventTracker.getChildlessEvents();
        Collections.shuffle(possibleOtherParents, random);

        EventDescriptorWrapper bestOtherParent = null;
        TipsetAdvancementWeight bestAdvancementWeight = ZERO_ADVANCEMENT_WEIGHT;
        for (final EventDescriptorWrapper otherParent : possibleOtherParents) {

            final List<EventDescriptorWrapper> parents = new ArrayList<>(2);
            parents.add(otherParent);
            if (lastSelfEvent != null) {
                parents.add(lastSelfEvent);
            }

            final TipsetAdvancementWeight advancementWeight =
                    tipsetWeightCalculator.getTheoreticalAdvancementWeight(parents);
            if (advancementWeight.isGreaterThan(bestAdvancementWeight)) {
                bestOtherParent = otherParent;
                bestAdvancementWeight = advancementWeight;
            }
        }

        if (bestOtherParent == null) {
            // If there are no available other parents, it is only legal to create a new event if we are
            // creating a genesis event. In order to create a genesis event, we must have never created
            // an event before and the current event window must have never been advanced.
            if (!eventWindow.isGenesis() || lastSelfEvent != null) {
                // event creation isn't legal
                return null;
            }

            // we are creating a genesis event, so we can use a null other parent
            return buildAndProcessEvent(null);
        }

        tipsetMetrics.getTipsetParentMetric(bestOtherParent.creator()).cycle();
        return buildAndProcessEvent(bestOtherParent);
    }

    /**
     * Create an event that reduces the selfishness score.
     *
     * @return the new event, or null if it is not legal to create a new event
     */
    @Nullable
    private UnsignedEvent createEventToReduceSelfishness() {
        final List<EventDescriptorWrapper> possibleOtherParents = childlessOtherEventTracker.getChildlessEvents();
        final List<EventDescriptorWrapper> ignoredNodes = new ArrayList<>(possibleOtherParents.size());

        // Choose a random ignored node, weighted by how much it is currently being ignored.

        // First, figure out who is an ignored node and sum up all selfishness scores.
        int selfishnessSum = 0;
        final List<Integer> selfishnessScores = new ArrayList<>(possibleOtherParents.size());
        for (final EventDescriptorWrapper possibleIgnoredNode : possibleOtherParents) {
            final int selfishness = tipsetWeightCalculator.getSelfishnessScoreForNode(possibleIgnoredNode.creator());

            final List<EventDescriptorWrapper> theoreticalParents = new ArrayList<>(2);
            theoreticalParents.add(possibleIgnoredNode);
            if (lastSelfEvent == null) {
                throw new IllegalStateException("lastSelfEvent is null");
            }
            theoreticalParents.add(lastSelfEvent);

            final TipsetAdvancementWeight advancementWeight =
                    tipsetWeightCalculator.getTheoreticalAdvancementWeight(theoreticalParents);

            if (selfishness > 1) {
                if (advancementWeight.isNonZero()) {
                    ignoredNodes.add(possibleIgnoredNode);
                    selfishnessScores.add(selfishness);
                    selfishnessSum += selfishness;
                } else {
                    // Note: if selfishness score is greater than 1, it is mathematically not possible
                    // for the advancement score to be zero. But in the interest in extreme caution,
                    // we check anyway, since it is very important never to create events with
                    // an advancement score of zero.
                    zeroAdvancementWeightLogger.error(
                            EXCEPTION.getMarker(),
                            "selfishness score is {} but advancement score is zero for {}.\n{}",
                            selfishness,
                            possibleIgnoredNode,
                            this);
                }
            }
        }

        if (ignoredNodes.isEmpty()) {
            // Note: this should be impossible, since we will not enter this method in the first
            // place if there are no ignored nodes. But better to be safe than sorry, and returning null
            // is an acceptable way of saying "I can't create an event right now".
            noParentFoundLogger.error(
                    EXCEPTION.getMarker(), "failed to locate eligible ignored node to use as a parent");
            return null;
        }

        // Choose a random ignored node.
        final int choice = random.nextInt(selfishnessSum);
        int runningSum = 0;
        for (int i = 0; i < ignoredNodes.size(); i++) {
            runningSum += selfishnessScores.get(i);
            if (choice < runningSum) {
                final EventDescriptorWrapper ignoredNode = ignoredNodes.get(i);
                tipsetMetrics.getPityParentMetric(ignoredNode.creator()).cycle();
                return buildAndProcessEvent(ignoredNode);
            }
        }

        // This should be impossible.
        throw new IllegalStateException("Failed to find an other parent");
    }

    /**
     * Given an other parent, build the next self event and process it.
     *
     * @param otherParent the other parent, or null if there is no other parent
     * @return the new event
     */
    private UnsignedEvent buildAndProcessEvent(@Nullable final EventDescriptorWrapper otherParent) {
        final List<EventDescriptorWrapper> parentDescriptors = new ArrayList<>(2);
        if (lastSelfEvent != null) {
            parentDescriptors.add(lastSelfEvent);
        }
        if (otherParent != null) {
            parentDescriptors.add(otherParent);
        }

        final UnsignedEvent event = assembleEventObject(otherParent);

        final EventDescriptorWrapper descriptor = event.getDescriptor();
        tipsetTracker.addEvent(descriptor, parentDescriptors);
        final TipsetAdvancementWeight advancementWeight =
                tipsetWeightCalculator.addEventAndGetAdvancementWeight(descriptor);
        final double weightRatio = advancementWeight.advancementWeight()
                / (double) tipsetWeightCalculator.getMaximumPossibleAdvancementWeight();
        tipsetMetrics.getTipsetAdvancementMetric().update(weightRatio);

        if (otherParent != null) {
            childlessOtherEventTracker.registerSelfEventParents(List.of(otherParent));
        }

        lastSelfEvent = descriptor;
        lastSelfEventCreationTime = event.getTimeCreated();
        lastSelfEventTransactionCount = event.getTransactions().size();

        return event;
    }

    /**
     * Given the parents, assemble the event object.
     *
     * @param otherParent the other parent
     * @return the event
     */
    @NonNull
    private UnsignedEvent assembleEventObject(@Nullable final EventDescriptorWrapper otherParent) {
        final Instant now = time.now();
        final Instant timeCreated;
        if (lastSelfEvent == null) {
            timeCreated = now;
        } else {
            timeCreated = EventUtils.calculateNewEventCreationTime(
                    now, lastSelfEventCreationTime, lastSelfEventTransactionCount);
        }

        final UnsignedEvent event = new UnsignedEvent(
                softwareVersion,
                selfId,
                lastSelfEvent,
                otherParent == null ? Collections.emptyList() : Collections.singletonList(otherParent),
                eventWindow.getAncientMode() == AncientMode.BIRTH_ROUND_THRESHOLD
                        ? eventWindow.getPendingConsensusRound()
                        : ConsensusConstants.ROUND_FIRST,
                timeCreated,
                transactionSupplier.getTransactions());
        eventHasher.hashUnsignedEvent(event);

        return event;
    }

    /**
     * Get the creator of a descriptor, handle null appropriately.
     */
    @Nullable
    private static NodeId getCreator(@Nullable final EventDescriptorWrapper descriptor) {
        if (descriptor == null) {
            return CREATOR_ID_UNDEFINED;
        } else {
            return descriptor.creator();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        tipsetTracker.clear();
        childlessOtherEventTracker.clear();
        tipsetWeightCalculator.clear();
        eventWindow = EventWindow.getGenesisEventWindow(ancientMode);
        lastSelfEvent = null;
        lastSelfEventCreationTime = null;
        lastSelfEventTransactionCount = 0;
    }

    @NonNull
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Event window: ").append(tipsetTracker.getEventWindow()).append("\n");
        sb.append("Latest self event: ").append(lastSelfEvent).append("\n");
        sb.append(tipsetWeightCalculator);

        sb.append("Childless events:");
        final List<EventDescriptorWrapper> childlessEvents = childlessOtherEventTracker.getChildlessEvents();
        if (childlessEvents.isEmpty()) {
            sb.append(" none\n");
        } else {
            sb.append("\n");
            for (final EventDescriptorWrapper event : childlessEvents) {
                final Tipset tipset = tipsetTracker.getTipset(event);
                sb.append("  - ").append(event).append(" ").append(tipset).append("\n");
            }
        }

        return sb.toString();
    }
}
