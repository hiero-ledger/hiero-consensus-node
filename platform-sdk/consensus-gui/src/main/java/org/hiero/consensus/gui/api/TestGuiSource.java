// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gui.api;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.FlowLayout;
import java.util.Collection;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import org.hiero.consensus.event.EventGraphSource;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.gui.internal.GuiEventStorage;
import org.hiero.consensus.gui.internal.HashgraphGuiRunner;
import org.hiero.consensus.gui.internal.hashgraph.HashgraphGuiSource;
import org.hiero.consensus.gui.internal.hashgraph.util.StandardGuiSource;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.GenesisSnapshotFactory;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;

public class TestGuiSource {
    private final GuiEventProvider eventProvider;
    private final HashgraphGuiSource guiSource;
    private ConsensusSnapshot savedSnapshot;
    private final GuiEventStorage eventStorage;
    private final OrphanBuffer orphanBuffer;

    /**
     * Construct a {@link TestGuiSource} with the given platform context, address book, and event provider.
     *
     * @param metrics the metrics system
     * @param configuration the platform configuration
     * @param roster     the roster
     * @param eventSource   the source of events
     */
    public TestGuiSource(
            @NonNull final Metrics metrics,
            @NonNull final Configuration configuration,
            @NonNull final Roster roster,
            @NonNull final EventGraphSource eventSource) {
        this(metrics, configuration, roster, wrapEventGraphSource(eventSource));
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
     * @param metrics the metrics system
     * @param configuration the platform configuration
     * @param roster     the roster
     * @param eventProvider   the event provider
     */
    public TestGuiSource(
            @NonNull final Metrics metrics,
            @NonNull final Configuration configuration,
            @NonNull final Roster roster,
            @NonNull final GuiEventProvider eventProvider) {
        this.eventStorage = new GuiEventStorage(configuration, roster);
        this.guiSource = new StandardGuiSource(roster, eventStorage);
        this.eventProvider = eventProvider;
        this.orphanBuffer = new DefaultOrphanBuffer(metrics, new NoOpIntakeEventCounter());
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
        for (final PlatformEvent event : events) {
            eventStorage.handlePreconsensusEvent(event);
        }
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

            for (final PlatformEvent event : events) {
                eventStorage.handlePreconsensusEvent(event);
            }
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
}
