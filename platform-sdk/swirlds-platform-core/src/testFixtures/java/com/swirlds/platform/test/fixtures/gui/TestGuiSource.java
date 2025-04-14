// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.gui;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.consensus.SyntheticSnapshot;
import com.swirlds.platform.gui.GuiEventStorage;
import com.swirlds.platform.gui.hashgraph.HashgraphGuiSource;
import com.swirlds.platform.gui.hashgraph.internal.StandardGuiSource;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

public class TestGuiSource {
    private final GuiEventProvider eventProvider;
    private final HashgraphGuiSource guiSource;
    private ConsensusSnapshot savedSnapshot;
    private final GuiEventStorage eventStorage;
    private final AncientMode ancientMode;

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
        this.ancientMode = platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode();
    }

    public void runGui() {
        HashgraphGuiRunner.runHashgraphGui(guiSource, controls());
    }

    public void generateEvents(final int numEvents) {
        final List<PlatformEvent> events = eventProvider.provideEvents(numEvents);
        for (final PlatformEvent event : events) {
            eventStorage.handlePreconsensusEvent(event);
        }
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
            final List<PlatformEvent> events = eventProvider.provideEvents(
                    numEvents.getValue() instanceof final Integer value ? value : defaultNumEvents);
            for (final PlatformEvent event : events) {
                eventStorage.handlePreconsensusEvent(event);
            }

            updateFameDecidedBelow.run();
        });
        // Reset
        final JButton reset = new JButton("Reset");
        reset.addActionListener(e -> {
            eventProvider.reset();
            eventStorage.handleSnapshotOverride(SyntheticSnapshot.getGenesisSnapshot(ancientMode));
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
}
