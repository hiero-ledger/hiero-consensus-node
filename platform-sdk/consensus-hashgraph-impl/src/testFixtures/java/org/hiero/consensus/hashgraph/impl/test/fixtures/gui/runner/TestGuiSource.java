// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.gui.runner;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import org.hiero.consensus.event.EventGraphSource;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.BranchingEventSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.BranchedEventMetadata;
import org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.GuiEventStorage;
import org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.hashgraph.HashgraphGuiSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.hashgraph.util.StandardGuiSource;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.GenesisSnapshotFactory;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;

public class TestGuiSource {
    private final GuiEventProvider eventProvider;
    private final HashgraphGuiSource guiSource;
    private ConsensusSnapshot savedSnapshot;
    private final GuiEventStorage eventStorage;
    private final Map<GossipEvent, BranchedEventMetadata> eventsToBranchMetadata = new HashMap<>();
    private final OrphanBuffer orphanBuffer;

    /**
     * Construct a {@link TestGuiSource} with the given platform context, address book, and event provider.
     *
     * @param platformContext the platform context
     * @param roster     the roster
     * @param eventSource   the source of events
     */
    public TestGuiSource(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final EventGraphSource eventSource) {
        this(platformContext, roster, wrapEventGraphSource(eventSource));
    }

    private static @NonNull GuiEventProvider wrapEventGraphSource(@NonNull final EventGraphSource eventSource) {
        return new GuiEventProvider() {
            @NonNull
            @Override
            public List<PlatformEvent> provideEvents(final int numberOfEvents) {
                return eventSource.nextEvents(numberOfEvents);
            }

            @Override
            public void reset() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * Construct a {@link TestGuiSource} with the given platform context, address book, and event provider.
     *
     * @param platformContext the platform context
     * @param roster     the roster
     * @param eventProvider   the event provider
     */
    public TestGuiSource(
            @NonNull final PlatformContext platformContext,
            @NonNull final Roster roster,
            @NonNull final GuiEventProvider eventProvider) {
        this.eventStorage = new GuiEventStorage(platformContext.getConfiguration(), roster);
        this.guiSource = new StandardGuiSource(roster, eventStorage);
        this.eventProvider = eventProvider;
        this.orphanBuffer = new DefaultOrphanBuffer(platformContext.getMetrics(), new NoOpIntakeEventCounter());
    }

    public void runGui() {
        HashgraphGuiRunner.runHashgraphGui(guiSource, controls());
    }

    public void generateEvents(final int numEvents) {
        final List<PlatformEvent> rawEvents = eventProvider.provideEvents(numEvents);
        final List<PlatformEvent> events = rawEvents.stream()
                .map(orphanBuffer::handleEvent)
                .flatMap(Collection::stream)
                .toList();
        final Map<PlatformEvent, Integer> eventToBranchIndex = getEventToBranchIndex();
        for (final PlatformEvent event : events) {
            if (!eventToBranchIndex.isEmpty() && eventToBranchIndex.containsKey(event)) {
                final BranchedEventMetadata branchedEventMetadata =
                        new BranchedEventMetadata(eventToBranchIndex.get(event), event.getNGen());
                eventsToBranchMetadata.put(event.getGossipEvent(), branchedEventMetadata);
            }

            eventStorage.handlePreconsensusEvent(event);
        }

        eventStorage.setBranchedEventsMetadata(eventsToBranchMetadata);
    }

    private @NonNull JPanel controls() {
        // Fame decided below
        final JLabel fameDecidedBelow = new JLabel("N/A");
        final Runnable updateFameDecidedBelow = () -> fameDecidedBelow.setText(
                "fame decided below: " + eventStorage.getConsensus().getFameDecidedBelow());
        updateFameDecidedBelow.run();
        // Next events
        final JButton nextEvent = new JButton("Next events");
        final int defaultNumEvents = 10;
        final int numEventsMinimum = 1;
        final int numEventsStep = 1;
        final JSpinner numEvents = new JSpinner(new SpinnerNumberModel(
                Integer.valueOf(defaultNumEvents),
                Integer.valueOf(numEventsMinimum),
                Integer.valueOf(Integer.MAX_VALUE),
                Integer.valueOf(numEventsStep)));
        nextEvent.addActionListener(e -> {
            final List<PlatformEvent> rawEvents = eventProvider.provideEvents(
                    numEvents.getValue() instanceof final Integer value ? value : defaultNumEvents);

            final List<PlatformEvent> events = rawEvents.stream()
                    .map(orphanBuffer::handleEvent)
                    .flatMap(Collection::stream)
                    .toList();

            final Map<PlatformEvent, Integer> eventToBranchIndex = getEventToBranchIndex();
            for (final PlatformEvent event : events) {
                if (!eventToBranchIndex.isEmpty() && eventToBranchIndex.containsKey(event)) {
                    final BranchedEventMetadata branchedEventMetadata =
                            new BranchedEventMetadata(eventToBranchIndex.get(event), event.getNGen());
                    eventsToBranchMetadata.put(event.getGossipEvent(), branchedEventMetadata);
                }

                eventStorage.handlePreconsensusEvent(event);
            }
            eventStorage.setBranchedEventsMetadata(eventsToBranchMetadata);

            updateFameDecidedBelow.run();
        });
        // Reset
        final JButton reset = new JButton("Reset");
        reset.addActionListener(e -> {
            eventProvider.reset();
            eventStorage.handleSnapshotOverride(GenesisSnapshotFactory.newGenesisSnapshot());
            updateFameDecidedBelow.run();
        });
        // snapshots
        final JButton printLastSnapshot = new JButton("Print last snapshot");
        printLastSnapshot.addActionListener(e -> {
            final ConsensusRound round = eventStorage.getLastConsensusRound();
            if (round == null) {
                System.out.println("No consensus rounds");
            } else {
                System.out.println(round.getSnapshot());
            }
        });
        final JButton saveLastSnapshot = new JButton("Save last snapshot");
        saveLastSnapshot.addActionListener(e -> {
            final ConsensusRound round = eventStorage.getLastConsensusRound();
            if (round == null) {
                System.out.println("No consensus rounds");
            } else {
                savedSnapshot = round.getSnapshot();
            }
        });
        final JButton loadSavedSnapshot = new JButton("Load saved snapshot");
        loadSavedSnapshot.addActionListener(e -> {
            if (savedSnapshot == null) {
                System.out.println("No saved snapshot");
                return;
            }
            eventStorage.handleSnapshotOverride(savedSnapshot);
        });

        // create JPanel
        final JPanel controls = new JPanel(new FlowLayout());
        controls.add(nextEvent);
        controls.add(numEvents);
        controls.add(reset);
        controls.add(fameDecidedBelow);
        controls.add(printLastSnapshot);
        controls.add(saveLastSnapshot);
        controls.add(loadSavedSnapshot);

        return controls;
    }

    /**
     * Load a snapshot into consensus
     * @param snapshot the snapshot to load
     */
    @SuppressWarnings("unused") // useful for debugging
    public void loadSnapshot(final ConsensusSnapshot snapshot) {
        System.out.println("Loading snapshot for round: " + snapshot.round());
        eventStorage.handleSnapshotOverride(snapshot);
    }

    /**
     * Get the {@link GuiEventStorage} used by this {@link TestGuiSource}
     *
     * @return the {@link GuiEventStorage}
     */
    @SuppressWarnings("unused") // useful for debugging
    GuiEventStorage getEventStorage() {
        return eventStorage;
    }

    /**
     * Get a map between events and their branch index in case there are {@link BranchingEventSource instances}
     * that produce branched events.
     *
     * @return the constructed map
     */
    private Map<PlatformEvent, Integer> getEventToBranchIndex() {
        final Map<PlatformEvent, Integer> eventToBranchIndex = new HashMap<>();

        if (eventProvider instanceof GeneratorEventProvider) {
            final List<BranchingEventSource> branchingEventSources = new ArrayList<>();
            for (final NodeId nodeId : guiSource.getRoster().rosterEntries().stream()
                    .map(RosterEntry::nodeId)
                    .map(NodeId::of)
                    .toList()) {
                if (((GeneratorEventProvider) eventProvider).getNodeSource(nodeId)
                        instanceof BranchingEventSource branchingEventSource) {
                    branchingEventSources.add(branchingEventSource);

                    final List<LinkedList<PlatformEvent>> branches = branchingEventSource.getBranches();

                    for (int i = 0; i < branches.size(); i++) {
                        final List<PlatformEvent> branch = branches.get(i);
                        for (final PlatformEvent event : branch) {
                            eventToBranchIndex.put(event, i);
                        }
                    }
                }
            }
        }

        return eventToBranchIndex;
    }
}
