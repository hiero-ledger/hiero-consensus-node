// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.gui;

import com.hedera.hapi.platform.event.GossipEvent;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.consensus.SyntheticSnapshot;
import com.swirlds.platform.event.orphan.DefaultOrphanBuffer;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.gui.BranchedEventMetadata;
import com.swirlds.platform.gui.GuiEventStorage;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import com.swirlds.platform.gui.hashgraph.internal.StandardGuiSource;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.source.ForkingEventSource;
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
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;

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
     * @param addressBook     the address book
     * @param eventProvider   the event provider
     */
    public TestGuiSource(
            @NonNull final PlatformContext platformContext,
            @NonNull final AddressBook addressBook,
            @NonNull final GuiEventProvider eventProvider) {
        this.eventStorage = new GuiEventStorage(platformContext.getConfiguration(), addressBook);
        this.guiSource = new StandardGuiSource(addressBook, eventStorage);
        this.eventProvider = eventProvider;
        this.orphanBuffer = new DefaultOrphanBuffer(
                platformContext.getConfiguration(), platformContext.getMetrics(), new NoOpIntakeEventCounter());
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

    public @NonNull JPanel controls() {
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
            eventStorage.handleSnapshotOverride(SyntheticSnapshot.getGenesisSnapshot());
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
    public GuiEventStorage getEventStorage() {
        return eventStorage;
    }

    /**
     * Get a map between events and their branch index in case there are {@link ForkingEventSource instances}
     * that produce branched events.
     *
     * @return the constructed map
     */
    private Map<PlatformEvent, Integer> getEventToBranchIndex() {
        final Map<PlatformEvent, Integer> eventToBranchIndex = new HashMap<>();

        if (eventProvider instanceof GeneratorEventProvider) {
            final List<ForkingEventSource> forkingEventSources = new ArrayList<>();
            for (final NodeId nodeId : guiSource.getAddressBook().getNodeIdSet()) {
                if (((GeneratorEventProvider) eventProvider).getNodeSource(nodeId)
                        instanceof ForkingEventSource forkingEventSource) {
                    forkingEventSources.add(forkingEventSource);

                    final List<LinkedList<EventImpl>> branches = forkingEventSource.getBranches();

                    for (int i = 0; i < branches.size(); i++) {
                        final List<EventImpl> branch = branches.get(i);
                        for (final EventImpl event : branch) {
                            eventToBranchIndex.put(event.getBaseEvent(), i);
                        }
                    }
                }
            }
        }

        return eventToBranchIndex;
    }
}
