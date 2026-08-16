// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gui;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.fakes.noop.NoOpRecycleBin;
import org.hiero.consensus.gui.api.TestGuiSource;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.model.hashgraph.GenesisSnapshotFactory;
import org.hiero.consensus.pcli.graph.PcesEventGraphSource;
import org.hiero.consensus.pcli.graph.PcesEventGraphSource.HashOption;
import org.hiero.consensus.round.EventWindowUtils;

/**
 * Main class for running the hashgraph GUI with PCES files.
 */
public class HashgraphGuiFromPcesMain {

    /**
     * The root directory containing the roster file and PCES directory to display. Update this path to point to your
     * local test data.
     */
    private static final String ROOT_DIR = "/path/to/root/";

    /**
     * The name of the directory containing the PCES files to display. The PCES files within can be in nested
     * subdirectories or in this directory directly. Must be located in {@link #ROOT_DIR}
     */
    private static final String PCES_DIR = "preconsensus-events";

    /**
     * The name of the JSON file containing the {@link Roster} used to create the events in the PCES directory. Must be
     * located in {@link #ROOT_DIR}.
     */
    private static final String ROSTER_FILE = "currentRoster.json";

    /**
     * The name of the JSON file containing the {@link com.hedera.hapi.platform.state.ConsensusSnapshot} to start the
     * PCES replay from. Must be located in {@link #ROOT_DIR}. To start from genesis, set this to {@code null}.
     */
    private static final String CONSENSUS_SNAPSHOT_FILE = null;

    /**
     * The number of events to show in the GUI when it starts up.
     */
    private static final int NUM_INITIAL_EVENTS_TO_SHOW = 10;

    /**
     * The main method that runs the GUI, showing the graph from PCES files on disk.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) throws FileNotFoundException, ParseException {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final Metrics metrics = new NoOpMetrics();
        final RecycleBin recycleBin = new NoOpRecycleBin();

        final Path resourceDir = Path.of(ROOT_DIR);
        final Path rosterPath = resourceDir.resolve(ROSTER_FILE);
        final Path pcesPath = resourceDir.resolve(PCES_DIR);
        final Roster roster = Roster.JSON.parse(new ReadableStreamingData(new FileInputStream(rosterPath.toFile())));

        final long startingRound;
        final long minimumNonAncientRound;
        final ConsensusSnapshot consensusSnapshot;

        if (CONSENSUS_SNAPSHOT_FILE != null) {
            /*
             * Note that if starting from a consensus snapshot, all non-ancient events must be replayed into consensus
             * before consensus calculations and data will be available. This can be a lot of events. The GUI will show
             * all of these events as gray until the judges are received. Then consensus data will be displayed
             * in the GUI.
             */
            final Path consensusSnapshotPath = resourceDir.resolve(CONSENSUS_SNAPSHOT_FILE);
            consensusSnapshot = ConsensusSnapshot.JSON.parse(
                    new ReadableStreamingData(new FileInputStream(consensusSnapshotPath.toFile())));
            startingRound = consensusSnapshot.round();
            final ConsensusConfig configData = configuration.getConfigData(ConsensusConfig.class);
            final int roundsNonAncient = configData.roundsNonAncient();
            final boolean useDABAlgorithm = configData.useDABConsensusAlgorithm();
            minimumNonAncientRound = EventWindowUtils.createEventWindow(
                            consensusSnapshot, roundsNonAncient, useDABAlgorithm)
                    .ancientThreshold();
        } else {
            startingRound = 0;
            minimumNonAncientRound = 0;
            consensusSnapshot = GenesisSnapshotFactory.newGenesisSnapshot();
        }

        final PcesEventGraphSource source = new PcesEventGraphSource(
                pcesPath, configuration, recycleBin, HashOption.HASH, startingRound, minimumNonAncientRound);
        final TestGuiSource guiSource = new TestGuiSource(metrics, configuration, roster, source);

        guiSource.loadSnapshot(consensusSnapshot);
        guiSource.generateEvents(NUM_INITIAL_EVENTS_TO_SHOW);
        guiSource.runGui();
    }
}
