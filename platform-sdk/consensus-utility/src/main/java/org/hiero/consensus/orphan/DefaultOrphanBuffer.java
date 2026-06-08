// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.orphan;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static org.hiero.consensus.model.event.NonDeterministicGeneration.assignNGen;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.metrics.FunctionGauge;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.sequence.map.SequenceMap;
import org.hiero.consensus.model.sequence.map.StandardSequenceMap;

/**
 * Takes as input an unordered stream of {@link PlatformEvent}s and emits a stream of {@link PlatformEvent}s in
 * topological order.
 */
public class DefaultOrphanBuffer implements OrphanBuffer {
    /**
     * Initial capacity of {@link #eventsWithParents} and {@link #missingParentMap}.
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * Avoid the creation of lambdas for Map.computeIfAbsent() by reusing this lambda.
     */
    private static final Function<EventDescriptorWrapper, List<OrphanedEvent>> EMPTY_LIST =
            ignored -> new ArrayList<>();

    /**
     * The current event window.
     */
    private EventWindow eventWindow;

    /**
     * The number of orphans currently in the buffer.
     */
    private int currentOrphanCount;

    /**
     * Keeps track of the number of events in the intake pipeline from each peer
     */
    private final IntakeEventCounter intakeEventCounter;

    /**
     * A map of descriptors to events for all non-ancient events that have found their parents (or whose parents have
     * become ancient).
     */
    private final SequenceMap<EventDescriptorWrapper, PlatformEvent> eventsWithParents;

    /**
     * A map where the key is the descriptor of a missing parent, and the value is a list of orphans that are missing
     * that parent.
     */
    private final SequenceMap<EventDescriptorWrapper, List<OrphanedEvent>> missingParentMap;

    /**
     * Hashes of "stale" parent events: events that were referenced as a parent by some consensus event but that never
     * reached consensus themselves (they went ancient before consensus) and are therefore absent from the block stream.
     * <p>
     * This set is <b>empty in normal operation</b> and is only populated when replaying a historical block stream from
     * which such events cannot be reconstructed (there is no production PCES to supply them). When non-empty, a parent
     * whose hash is in this set is treated as permanently absent: it is never recorded as a missing parent, so a child
     * whose only otherwise-missing parents are stale is released immediately rather than waiting forever for an event
     * that will never arrive.
     * <p>
     * This is safe with respect to resulting state: an event that goes ancient before reaching consensus can be omitted
     * from the hashgraph during replay without changing consensus (it contributed nothing to consensus in production).
     * The child's own hash is unaffected — it still carries the stale parent's descriptor verbatim — and the downstream
     * {@code ConsensusLinker} already drops any parent link with no matching event (see RUL-004), so consensus proceeds
     * on a clean graph. This special case exists only for replays of old streams; in normal operation the parent would
     * be gossiped and required before the child, and this set is empty so the behavior is unchanged.
     */
    private final Set<Hash> staleParentHashes;

    /**
     * Constructor. Normal-operation entry point: no stale parents.
     *
     * @param metrics the metrics instance to use
     * @param intakeEventCounter keeps track of the number of events in the intake pipeline from each peer
     */
    public DefaultOrphanBuffer(@NonNull final Metrics metrics, @NonNull final IntakeEventCounter intakeEventCounter) {
        this(metrics, intakeEventCounter, Set.of());
    }

    /**
     * Constructor.
     *
     * @param metrics the metrics instance to use
     * @param intakeEventCounter keeps track of the number of events in the intake pipeline from each peer
     * @param staleParentHashes hashes of parents known to be stale (never reached consensus, absent from the stream);
     *                          must be empty for normal operation, populated only for historical block-stream replay
     */
    public DefaultOrphanBuffer(
            @NonNull final Metrics metrics,
            @NonNull final IntakeEventCounter intakeEventCounter,
            @NonNull final Set<Hash> staleParentHashes) {

        this.intakeEventCounter = Objects.requireNonNull(intakeEventCounter);
        this.staleParentHashes = Set.copyOf(Objects.requireNonNull(staleParentHashes));
        this.currentOrphanCount = 0;

        metrics.getOrCreate(new FunctionGauge.Config<>(
                PLATFORM_CATEGORY, "orphanBufferSize", Integer.class, this::getCurrentOrphanCount)
                .withDescription("number of orphaned events currently in the orphan buffer")
                .withUnit("events"));
        this.eventWindow = EventWindow.getGenesisEventWindow();
        missingParentMap = new StandardSequenceMap<>(0, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);
        eventsWithParents = new StandardSequenceMap<>(0, INITIAL_CAPACITY, true, EventDescriptorWrapper::birthRound);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<PlatformEvent> handleEvent(@NonNull final PlatformEvent event) {
        if (eventWindow.isAncient(event)) {
            // Ancient events can be safely ignored.
            intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
            return List.of();
        }

        currentOrphanCount++;

        final List<EventDescriptorWrapper> missingParents = getMissingParents(event);
        if (missingParents.isEmpty()) {
            return eventIsNotAnOrphan(event);
        } else {
            final OrphanedEvent orphanedEvent = new OrphanedEvent(event, missingParents);
            for (final EventDescriptorWrapper missingParent : missingParents) {
                this.missingParentMap.computeIfAbsent(missingParent, EMPTY_LIST).add(orphanedEvent);
            }

            return List.of();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<PlatformEvent> setEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);

        eventsWithParents.shiftWindow(eventWindow.ancientThreshold());

        // As the map is cleared out, we need to gather the ancient parents and their orphans. We can't
        // modify the data structure as the window is being shifted, so we collect that data and act on
        // it once the window has finished shifting.
        final List<ParentAndOrphans> ancientParents = new ArrayList<>();
        missingParentMap.shiftWindow(
                eventWindow.ancientThreshold(),
                (parent, orphans) -> ancientParents.add(new ParentAndOrphans(parent, orphans)));

        final List<PlatformEvent> unorphanedEvents = new ArrayList<>();
        ancientParents.forEach(
                parentAndOrphans -> unorphanedEvents.addAll(missingParentBecameAncient(parentAndOrphans)));

        return unorphanedEvents;
    }

    /**
     * Called when a parent becomes ancient.
     * <p>
     * Accounts for events potentially becoming un-orphaned as a result of the parent becoming ancient.
     *
     * @param parentAndOrphans the parent that became ancient, along with its orphans
     * @return the list of events that are no longer orphans as a result of the parent becoming ancient
     */
    @NonNull
    private List<PlatformEvent> missingParentBecameAncient(@NonNull final ParentAndOrphans parentAndOrphans) {
        final List<PlatformEvent> unorphanedEvents = new ArrayList<>();

        final EventDescriptorWrapper parentDescriptor = parentAndOrphans.parent();

        for (final OrphanedEvent orphan : parentAndOrphans.orphans()) {
            orphan.missingParents().remove(parentDescriptor);

            if (orphan.missingParents().isEmpty()) {
                unorphanedEvents.addAll(eventIsNotAnOrphan(orphan.orphan()));
            }
        }

        return unorphanedEvents;
    }

    /**
     * Get the parents of an event that are currently missing.
     *
     * @param event the event whose missing parents to find
     * @return the list of missing parents, empty if no parents are missing
     */
    @NonNull
    private List<EventDescriptorWrapper> getMissingParents(@NonNull final PlatformEvent event) {
        final List<EventDescriptorWrapper> missingParents = new ArrayList<>();

        for (final EventDescriptorWrapper parent : event.getAllParents()) {
            if (!eventsWithParents.containsKey(parent)
                    && !eventWindow.isAncient(parent)
                    && !isStaleParent(parent)) {
                missingParents.add(parent);
            }
        }

        return missingParents;
    }

    /**
     * Determine whether the given parent is a known stale event — one that never reached consensus and is therefore
     * absent from the block stream being replayed. Such a parent will never arrive, so it must not be treated as a
     * missing parent; the referencing child is released as if the parent did not exist, which is safe because a
     * pre-consensus-ancient event can be omitted from the replayed hashgraph without changing consensus, and the
     * downstream linker already drops parent links with no matching event.
     * <p>
     * Returns {@code false} for every parent in normal operation, where {@link #staleParentHashes} is empty.
     *
     * @param parent the parent descriptor to test
     * @return {@code true} if the parent is a known stale event that will never arrive
     */
    private boolean isStaleParent(@NonNull final EventDescriptorWrapper parent) {
        return !staleParentHashes.isEmpty() && staleParentHashes.contains(parent.hash());
    }

    /**
     * Signal that an event is not an orphan.
     * <p>
     * Accounts for events potentially becoming un-orphaned as a result of this event not being an orphan.
     *
     * @param event the event that is not an orphan
     * @return the list of events that are no longer orphans as a result of this event not being an orphan
     */
    @NonNull
    private List<PlatformEvent> eventIsNotAnOrphan(@NonNull final PlatformEvent event) {
        final List<PlatformEvent> unorphanedEvents = new ArrayList<>();

        final Deque<PlatformEvent> nonOrphanStack = new LinkedList<>();
        nonOrphanStack.push(event);

        // When a missing parent is found, there may be many descendants of that parent who end up
        // being un-orphaned. This loop frees all such orphans non-recursively (recursion yields pretty
        // code but can thrash the stack).
        while (!nonOrphanStack.isEmpty()) {
            currentOrphanCount--;

            final PlatformEvent nonOrphan = nonOrphanStack.pop();
            final EventDescriptorWrapper nonOrphanDescriptor = nonOrphan.getDescriptor();

            if (eventWindow.isAncient(nonOrphan)) {
                // Although it doesn't cause harm to pass along ancient events, it is unnecessary to do so.
                intakeEventCounter.eventExitedIntakePipeline(event.getSenderId());
                continue;
            }

            unorphanedEvents.add(nonOrphan);
            eventsWithParents.put(nonOrphanDescriptor, nonOrphan);
            assignNGen(nonOrphan, eventsWithParents);

            // since this event is no longer an orphan, we need to recheck all of its children to see if any might
            // not be orphans anymore
            final List<OrphanedEvent> children = missingParentMap.remove(nonOrphanDescriptor);
            if (children == null) {
                continue;
            }

            for (final OrphanedEvent child : children) {
                child.missingParents().remove(nonOrphanDescriptor);
                if (child.missingParents().isEmpty()) {
                    nonOrphanStack.push(child.orphan());
                }
            }
        }

        return unorphanedEvents;
    }

    /**
     * Gets the number of orphans currently in the buffer. Exposed for testing.
     *
     * @return the number of orphans currently in the buffer
     */
    @NonNull
    Integer getCurrentOrphanCount() {
        return currentOrphanCount;
    }

    /**
     * Clears the orphan buffer.
     */
    public void clear() {
        eventsWithParents.clear();

        // clearing this map here is safe, under the assumption that the intake event counter will be reset
        // before gossip starts back up
        missingParentMap.clear();
        currentOrphanCount = 0;
    }
}
