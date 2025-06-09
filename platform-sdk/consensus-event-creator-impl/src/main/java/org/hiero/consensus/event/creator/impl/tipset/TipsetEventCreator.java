// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetAdvancementWeight.ZERO_ADVANCEMENT_WEIGHT;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.throttle.RateLimitedLogger;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.event.creator.impl.TransactionSupplier;
import org.hiero.consensus.event.creator.impl.config.EventCreationConfig;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterUtils;

/**
 * Responsible for creating new events using the tipset algorithm.
 */
public class TipsetEventCreator implements EventCreator {

    private static final Logger logger = LogManager.getLogger(TipsetEventCreator.class);

    private final Time time;
    private final Random random;
    private final HashSigner signer;
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
    private PlatformEvent lastSelfEvent;

    private final RateLimitedLogger zeroAdvancementWeightLogger;
    private final RateLimitedLogger noParentFoundLogger;

    /**
     * Event hasher for unsigned events.
     */
    private final PbjStreamHasher eventHasher;

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
            @NonNull final HashSigner signer,
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
        tipsetTracker = new TipsetTracker(time, selfId, roster);
        childlessOtherEventTracker = new ChildlessEventTracker();
        tipsetWeightCalculator =
                new TipsetWeightCalculator(platformContext, roster, selfId, tipsetTracker, childlessOtherEventTracker);
        networkSize = roster.rosterEntries().size();

        zeroAdvancementWeightLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));
        noParentFoundLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));

        eventWindow = EventWindow.getGenesisEventWindow();
        eventHasher = new PbjStreamHasher();
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
            if (this.lastSelfEvent == null
                    || (this.lastSelfEvent.hasNGen() && this.lastSelfEvent.getNGen() < event.getNGen())) {
                // Normally we will ingest self events before we get to this point, but it's possible
                // to learn of self events for the first time here if we are loading from a restart (via PCES)
                // or reconnect (via gossip). In either of these cases, the self event passed to this method
                // will have an nGen value assigned by the orphan buffer.
                lastSelfEvent = event;
                childlessOtherEventTracker.registerSelfEventParents(event.getOtherParents());
                tipsetTracker.addSelfEvent(event.getDescriptor(), event.getAllParents());
            } else {
                // We already ingested this self event (when it was created),
                // or it is older than the event we are already tracking.
                return;
            }
        } else {
            tipsetTracker.addPeerEvent(event);
            childlessOtherEventTracker.addEvent(event);
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
    @Nullable
    @Override
    public PlatformEvent maybeCreateEvent() {
        final UnsignedEvent event = maybeCreateUnsignedEvent();
        if (event != null) {
            lastSelfEvent = signEvent(event);
            return lastSelfEvent;
        }
        return null;
    }

    @Nullable
    private UnsignedEvent maybeCreateUnsignedEvent() {
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

    private PlatformEvent signEvent(final UnsignedEvent event) {
        final Signature signature = signer.sign(event.getHash());
        return new PlatformEvent(event, signature.getBytes());
    }

    /**
     * Create the next event for a network of size 1 (i.e. where we are the only member). We don't use the tipset
     * algorithm like normal, since we will never have a real other parent.
     *
     * @return the new event
     */
    @NonNull
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
        final List<PlatformEvent> possibleOtherParents =
                new ArrayList<>(childlessOtherEventTracker.getChildlessEvents());
        Collections.shuffle(possibleOtherParents, random);

        PlatformEvent bestOtherParent = null;
        TipsetAdvancementWeight bestAdvancementWeight = ZERO_ADVANCEMENT_WEIGHT;
        for (final PlatformEvent otherParent : possibleOtherParents) {
            final List<EventDescriptorWrapper> parents = new ArrayList<>(2);
            parents.add(otherParent.getDescriptor());
            if (lastSelfEvent != null) {
                parents.add(lastSelfEvent.getDescriptor());
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

        tipsetMetrics.getTipsetParentMetric(bestOtherParent.getCreatorId()).cycle();
        return buildAndProcessEvent(bestOtherParent);
    }

    /**
     * Create an event that reduces the selfishness score.
     *
     * @return the new event, or null if it is not legal to create a new event
     */
    @Nullable
    private UnsignedEvent createEventToReduceSelfishness() {
        final Collection<PlatformEvent> possibleOtherParents = childlessOtherEventTracker.getChildlessEvents();
        final List<PlatformEvent> ignoredNodes = new ArrayList<>(possibleOtherParents.size());

        // Choose a random ignored node, weighted by how much it is currently being ignored.

        // First, figure out who is an ignored node and sum up all selfishness scores.
        int selfishnessSum = 0;
        final List<Integer> selfishnessScores = new ArrayList<>(possibleOtherParents.size());
        for (final PlatformEvent possibleIgnoredNode : possibleOtherParents) {
            final int selfishness =
                    tipsetWeightCalculator.getSelfishnessScoreForNode(possibleIgnoredNode.getCreatorId());

            final List<EventDescriptorWrapper> theoreticalParents = new ArrayList<>(2);
            theoreticalParents.add(possibleIgnoredNode.getDescriptor());
            if (lastSelfEvent == null) {
                throw new IllegalStateException("lastSelfEvent is null");
            }
            theoreticalParents.add(lastSelfEvent.getDescriptor());

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
                final PlatformEvent ignoredNode = ignoredNodes.get(i);
                tipsetMetrics.getPityParentMetric(ignoredNode.getCreatorId()).cycle();
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
    private UnsignedEvent buildAndProcessEvent(@Nullable final PlatformEvent otherParent) {
        final EventDescriptorWrapper otherParentDescriptor = otherParent == null ? null : otherParent.getDescriptor();
        final UnsignedEvent event = assembleEventObject(otherParentDescriptor);

        tipsetTracker.addSelfEvent(event.getDescriptor(), event.getMetadata().getAllParents());
        final TipsetAdvancementWeight advancementWeight =
                tipsetWeightCalculator.addEventAndGetAdvancementWeight(event.getDescriptor());
        final double weightRatio = advancementWeight.advancementWeight()
                / (double) tipsetWeightCalculator.getMaximumPossibleAdvancementWeight();
        tipsetMetrics.getTipsetAdvancementMetric().update(weightRatio);

        if (otherParent != null) {
            childlessOtherEventTracker.registerSelfEventParents(List.of(otherParentDescriptor));
        }

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
            timeCreated = calculateNewEventCreationTime(
                    now, lastSelfEvent.getTimeCreated(), lastSelfEvent.getTransactionCount());
        }

        final UnsignedEvent event = new UnsignedEvent(
                softwareVersion,
                selfId,
                lastSelfEvent == null ? null : lastSelfEvent.getDescriptor(),
                otherParent == null ? Collections.emptyList() : Collections.singletonList(otherParent),
                eventWindow.newEventBirthRound(),
                timeCreated,
                transactionSupplier.getTransactions());
        eventHasher.hashUnsignedEvent(event);

        return event;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        tipsetTracker.clear();
        childlessOtherEventTracker.clear();
        tipsetWeightCalculator.clear();
        eventWindow = EventWindow.getGenesisEventWindow();
        lastSelfEvent = null;
    }

    @NonNull
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Event window: ").append(tipsetTracker.getEventWindow()).append("\n");
        sb.append("Latest self event: ").append(lastSelfEvent).append("\n");
        sb.append(tipsetWeightCalculator);

        sb.append("Childless events:");
        final Collection<PlatformEvent> childlessEvents = childlessOtherEventTracker.getChildlessEvents();
        if (childlessEvents.isEmpty()) {
            sb.append(" none\n");
        } else {
            sb.append("\n");
            for (final PlatformEvent event : childlessEvents) {
                final Tipset tipset = tipsetTracker.getTipset(event.getDescriptor());
                sb.append("  - ").append(event).append(" ").append(tipset).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Calculate the creation time for a new event.
     * <p>
     * Regardless of whatever the host computer's clock says, the event creation time must always advance from self
     * parent to child. Further, the time in between the self parent and the child must be large enough so that every
     * transaction in the parent can be assigned a unique timestamp at nanosecond precision.
     *
     * @param now                        the current time
     * @param selfParentCreationTime     the creation time of the self parent
     * @param selfParentTransactionCount the number of transactions in the self parent
     * @return the creation time for the new event
     */
    @NonNull
    private static Instant calculateNewEventCreationTime(
            @NonNull final Instant now,
            @NonNull final Instant selfParentCreationTime,
            final int selfParentTransactionCount) {

        final int minimumIncrement = Math.max(1, selfParentTransactionCount);
        final Instant minimumNextEventTime = selfParentCreationTime.plusNanos(minimumIncrement);
        if (now.isBefore(minimumNextEventTime)) {
            return minimumNextEventTime;
        } else {
            return now;
        }
    }

    /**
     * Capable of signing a {@link Hash}
     */
    @FunctionalInterface
    public interface HashSigner {
        /**
         * @param hash
         * 		the hash to sign
         * @return the signature for the hash provided
         */
        Signature sign(Hash hash);
    }
}
