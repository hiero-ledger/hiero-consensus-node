// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.tools;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.app.OtterApp.SWIRLD_NAME;
import static org.hiero.otter.fixtures.tools.GenerateStateTool.PCES_DIRECTORY;
import static org.hiero.otter.fixtures.tools.GenerateStateTool.SAVE_STATE_DIRECTORY;
import static org.hiero.otter.fixtures.util.OtterSavedStateUtils.fetchApplicationVersion;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.base.file.FileUtils;
import org.hiero.consensus.PathsConfig;
import org.hiero.consensus.state.config.StateConfig_;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.app.OtterApp;
import org.hiero.otter.fixtures.turtle.TurtleTestEnvironment;

/**
 * Generates the {@code post-freeze-4-node-state} saved-state artifact consumed by
 * {@code PostFreezeStakeThresholdFlipTest}.
 *
 * <p>Differs from {@link GenerateStateTool} in two ways:
 * <ul>
 *   <li>Uses {@link WeightGenerators#BALANCED_REAL_WEIGHT} so 2/4 online nodes ≈ 50 % stake
 *       (below the 2/3 threshold) and 3/4 ≈ 75 % (above), matching the assertions of the
 *       consuming test.</li>
 *   <li>Preserves the top-level {@code preconsensus-events/} directory in the produced
 *       artifact. The consuming test relies on the PCES containing events from all four
 *       creators (accumulated via gossip before freeze) so that some rounds can advance on
 *       restart from freeze even when only a subset of the roster is online.</li>
 * </ul>
 *
 * <p>Intended to be run manually when the artifact needs to be (re)generated. From the repo
 * root:
 * <pre>
 *   ./gradlew :consensus-otter-tests:generatePostFreezeSavedState
 * </pre>
 * The output is written under
 * {@code platform-sdk/consensus-otter-tests/saved-states/post-freeze-4-node-state/}.
 */
public class GeneratePostFreezeStateTool {

    /** Sub-directory under {@link GenerateStateTool#SAVE_STATE_DIRECTORY} where the artifact is installed. */
    public static final String ARTIFACT_NAME = "post-freeze-4-node-state";

    /**
     * Self-ID of the node whose state + PCES is kept as the artifact. Must be a survivor of
     * Phase 2 (i.e. {@code >= FUTURE_ONLINE_COUNT}) so its PCES retains the events written
     * after the future-online subset is killed.
     */
    private static final long SELF_ID = 6L;

    /** Test environment used to create and control the ephemeral network. */
    private final TestEnvironment environment;

    /** Software version for the state. */
    private final SemanticVersion version;

    /**
     * Create a new tool bound to the given test environment.
     *
     * @param environment the test environment to use; must not be {@code null}
     * @param version the version in which the state should be written
     */
    public GeneratePostFreezeStateTool(
            @NonNull final TestEnvironment environment, @NonNull final SemanticVersion version) {
        this.environment = requireNonNull(environment, "environment cannot be null");
        this.version = requireNonNull(version, "version cannot be null");
    }

    /**
     * Retrieves a node from the test environment's network by its node ID.
     *
     * @param nodeId the ID of the node to retrieve
     * @return the node with the specified ID
     */
    @NonNull
    public Node getNode(final int nodeId) {
        return environment.network().nodes().get(nodeId);
    }

    /**
     * Number of nodes in the fixture roster.
     */
    private static final int TOTAL_NODES = 10;

    /**
     * How many of the {@link #TOTAL_NODES nodes} will be online in the consuming test. These
     * nodes are killed early during fixture generation so that the remaining {@code TOTAL_NODES
     * - FUTURE_ONLINE_COUNT} nodes (with sub-supermajority stake) keep gossiping and writing
     * events to PCES without ever finalizing a new round. That leaves the fixture's PCES with
     * events past the last-finalized round from <em>exactly the creators the consuming test
     * will leave offline</em> — which is the shape the mainnet incident exhibited.
     */
    private static final int FUTURE_ONLINE_COUNT = 6;

    /**
     * Generate the fixture. Phased:
     * <ol>
     *   <li>Start all 10 nodes and let them run long enough to persist several signed states
     *       and accumulate PCES events from all creators.</li>
     *   <li>Kill the {@link #FUTURE_ONLINE_COUNT} nodes that will be the online subset in the
     *       consuming test. This leaves {@code TOTAL_NODES - FUTURE_ONLINE_COUNT} nodes
     *       running with stake below the 2/3 supermajority.</li>
     *   <li>Let those remaining nodes gossip for a bit longer. They cannot finalize any new
     *       round (insufficient stake) but they keep <em>writing events to PCES</em>, so
     *       PCES ends up with events at birth rounds beyond the last finalized round from
     *       exactly the creators that will be offline in the consuming test.</li>
     *   <li>Kill everything.</li>
     * </ol>
     * On test restart with the 6 nodes online, replay finalizes rounds up through the PCES's
     * last "all-10-creators" round; then live gossip from the online 6 fills in the missing
     * self-events for the "only-4-creators-in-PCES" rounds, and fame decisions complete for
     * those rounds using PCES witness weight from the 4 offline creators.
     */
    public void generateState() {
        final Network network = environment.network();
        final TimeManager timeManager = environment.timeManager();

        network.weightGenerator(WeightGenerators.BALANCED_REAL_WEIGHT);
        network.addNodes(TOTAL_NODES);
        network.version(version);
        // Force frequent signed-state saves and keep many of them on disk so the fixture
        // cleanup step has a much-earlier round available to keep. Defaults would leave us
        // with just the freeze state on disk (saveStatePeriod = 900 s, signedStateDisk = 2).
        network.withConfigValue(StateConfig_.SAVE_STATE_PERIOD, 1);
        network.withConfigValue(StateConfig_.SIGNED_STATE_DISK, 200);
        network.start();

        // Phase 1: all 10 nodes gossip → PCES accumulates all-creator events, signed states
        // are persisted at various rounds.
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Phase 2: kill the future-online subset. Only the future-offline nodes keep running.
        for (int i = 0; i < FUTURE_ONLINE_COUNT; i++) {
            network.nodes().get(i).killImmediately();
        }

        // Phase 3: let the 4 future-offline nodes keep gossiping. With 40 % stake they cannot
        // advance any new round, but they keep writing events into their PCES.
        timeManager.waitFor(Duration.ofSeconds(15L));

        network.shutdown();
    }

    /**
     * Cleans up the produced saved-state directory, keeping the top-level
     * {@link GenerateStateTool#PCES_DIRECTORY preconsensus-events} directory and the latest
     * round of the {@link OtterApp#APP_NAME OtterApp} state. Anything else is discarded.
     *
     * <p>Unlike {@link GenerateStateTool}, we do <b>not</b> delete a {@code preconsensus-events}
     * directory nested inside the latest round — this test's fixture is entirely about PCES
     * survival across the freeze boundary.
     */
    public void cleanUpDirectory(@NonNull final Path rootOutputDirectory) throws IOException {
        requireNonNull(rootOutputDirectory, "root output directory cannot be null");

        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(rootOutputDirectory)) {
            for (final Path path : stream) {
                if (Files.isDirectory(path)) {
                    if (path.getFileName().toString().equals(OtterApp.APP_NAME)) {
                        removeAllButTargetState(path);
                    } else if (!path.getFileName().toString().equals(PCES_DIRECTORY)) {
                        FileUtils.deleteDirectory(path);
                    }
                } else {
                    Files.delete(path);
                }
            }
        }
    }

    /**
     * How many rounds before the last saved state to keep the loaded state at. Chosen small
     * so that PCES replay finalizes 0 rounds (matching the mainnet incident's shape) and
     * the flip only fires later during live-gossip OBSERVING via witness weight from the
     * offline creators' PCES-retained events for the boundary rounds.
     */
    private static final int LOADED_STATE_ROUND_OFFSET_FROM_LATEST = 15;

    /**
     * Keep the signed-state snapshot at {@code freezeRound - LOADED_STATE_ROUND_OFFSET_FROM_FREEZE}
     * (or the closest earlier snapshot) and delete every other saved round. PCES is untouched
     * so it still covers the full run up through the freeze round.
     */
    private void removeAllButTargetState(@NonNull final Path path) throws IOException {
        final Path dir = path.resolve(String.valueOf(SELF_ID)).resolve(SWIRLD_NAME);
        try (final Stream<Path> list = Files.list(dir)) {
            final List<Path> roundDirectories = list.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().matches("\\d+"))
                    .sorted((a, b) -> Integer.compare(
                            Integer.parseInt(a.getFileName().toString()),
                            Integer.parseInt(b.getFileName().toString())))
                    .toList();

            if (roundDirectories.isEmpty()) {
                throw new IOException("No round directory found for " + path);
            }

            System.out.printf(
                    "Signed-state saves observed: %d rounds spanning %s..%s%n",
                    roundDirectories.size(),
                    roundDirectories.get(0).getFileName(),
                    roundDirectories.get(roundDirectories.size() - 1).getFileName());

            final int latestRound = Integer.parseInt(roundDirectories
                    .get(roundDirectories.size() - 1)
                    .getFileName()
                    .toString());
            final int targetRound = latestRound - LOADED_STATE_ROUND_OFFSET_FROM_LATEST;

            Path chosen = roundDirectories.get(0);
            for (final Path candidate : roundDirectories) {
                final int r = Integer.parseInt(candidate.getFileName().toString());
                if (r <= targetRound) {
                    chosen = candidate;
                } else {
                    break;
                }
            }

            for (final Path roundDirectory : roundDirectories) {
                if (!roundDirectory.equals(chosen)) {
                    FileUtils.deleteDirectory(roundDirectory);
                }
            }

            System.out.printf(
                    "Kept state at round %s (latest saved was %d, %d rounds of PCES ahead); PCES retained over full range%n",
                    chosen.getFileName(),
                    latestRound,
                    latestRound - Integer.parseInt(chosen.getFileName().toString()));
        }
    }

    /**
     * Install the produced state at {@code <module>/saved-states/post-freeze-4-node-state/},
     * replacing any prior artifact. Resolved relative to the current working directory, which
     * is expected to be the {@code consensus-otter-tests} module directory (the Gradle
     * {@code generatePostFreezeSavedState} task runs from there by default).
     */
    public void copyFilesInPlace(@NonNull final Path rootOutputDirectory) throws IOException {
        final Path savedStateDirectory = Path.of(SAVE_STATE_DIRECTORY, ARTIFACT_NAME);

        if (Files.exists(savedStateDirectory)) {
            FileUtils.deleteDirectory(savedStateDirectory);
        }
        Files.createDirectories(savedStateDirectory);

        FileUtils.moveDirectory(rootOutputDirectory, savedStateDirectory);
    }

    /**
     * Command-line entry point.
     *
     * <p>Exit code {@code 0} on success, {@code -1} on failure.
     */
    public static void main(final String[] args) {
        try {
            final Path turtleDir = Path.of("build", "turtle");
            if (Files.exists(turtleDir)) {
                FileUtils.deleteDirectory(turtleDir);
            }

            final SemanticVersion version = fetchApplicationVersion();
            final GeneratePostFreezeStateTool tool =
                    new GeneratePostFreezeStateTool(new TurtleTestEnvironment(0L, false), version);
            tool.generateState();

            final Node node = tool.getNode((int) SELF_ID);
            final Configuration configuration = node.configuration().current();
            final Path outputDirectory =
                    configuration.getConfigData(PathsConfig.class).savedStateDir();

            tool.cleanUpDirectory(outputDirectory);
            tool.copyFilesInPlace(outputDirectory);
        } catch (final RuntimeException | IOException exp) {
            System.err.println(exp.getMessage());
            System.exit(-1);
        }

        System.exit(0);
    }
}
