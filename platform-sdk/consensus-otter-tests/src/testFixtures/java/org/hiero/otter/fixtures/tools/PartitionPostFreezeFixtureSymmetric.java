// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.tools;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.hiero.base.file.FileUtils;

/**
 * Post-processes the base {@code post-freeze-4-node-state} fixture produced by
 * {@link GeneratePostFreezeStateTool} into a single symmetric variant: every event whose
 * creator is one of the "will-be-online" creators AND whose birth round is strictly
 * greater than the loaded state's max round is dropped. All other events are kept
 * verbatim; every node loads the same PCES.
 *
 * <p>Used by {@code PostFreezeStakeThresholdFlipTest} as the regression fixture for
 * incident-20260821 (post-freeze stake-threshold flip). Pre-fix, this shape reproduced the
 * spurious {@code CHECKING → ACTIVE} transition: with online-creator events past R dropped,
 * replay finalises 0 rounds past R (matching mainnet), then live gossip in the online
 * subset merged with the offline-creator PCES tail let a round past R decide fame during
 * CHECKING — the replayed self events in that round triggered
 * {@code SelfEventReachedConsensusAction} and drove the flip. Post-fix, the transition is
 * gated on the triggering round containing a self event with
 * {@link org.hiero.consensus.model.event.EventOrigin#RUNTIME} origin, so those replayed
 * self events no longer promote the platform to ACTIVE.
 *
 * <p>Run from the repo root:
 * <pre>
 *   ./gradlew :consensus-otter-tests:partitionPostFreezeFixtureSymmetric
 * </pre>
 * Output at
 * {@code platform-sdk/consensus-otter-tests/saved-states/post-freeze-4-node-state-symmetric/}.
 */
public class PartitionPostFreezeFixtureSymmetric {

    private static final Path BASE_FIXTURE_DIR =
            Path.of(GenerateStateTool.SAVE_STATE_DIRECTORY, GeneratePostFreezeStateTool.ARTIFACT_NAME);

    /** Output artifact directory (created by this tool). */
    public static final String ARTIFACT_NAME = GeneratePostFreezeStateTool.ARTIFACT_NAME + "-symmetric";

    private static final Path OUTPUT_DIR = Path.of(GenerateStateTool.SAVE_STATE_DIRECTORY, ARTIFACT_NAME);

    private static final int TOTAL_NODES = 10;

    /**
     * Number of creators from the top of the node-ID range that are considered "will-be-online"
     * in the consuming test and whose post-checkpoint events get dropped. Must match the
     * consuming test's online subset size and the base fixture's Phase-2 kill count.
     */
    private static final int N_ONLINE = 6;

    private static final String OTTER_APP_DIR_NAME = "OtterApp";
    private static final String PCES_DIR_NAME = "preconsensus-events";

    private static long readLoadedRound(@NonNull final Path baseFixtureRoot) throws IOException {
        try (final Stream<Path> stream = Files.walk(baseFixtureRoot, 6)) {
            final Path metadataFile = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("stateMetadata.txt"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("stateMetadata.txt not found in " + baseFixtureRoot));

            try (final BufferedReader reader = Files.newBufferedReader(metadataFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String trimmed = line.trim();
                    if (trimmed.startsWith("ROUND:")) {
                        return Long.parseLong(
                                trimmed.substring("ROUND:".length()).trim());
                    }
                }
            }
        }
        throw new IOException("ROUND field not found in stateMetadata.txt under " + baseFixtureRoot);
    }

    public static void main(final String[] args) {
        try {
            requireNonNull(BASE_FIXTURE_DIR);
            if (!Files.isDirectory(BASE_FIXTURE_DIR)) {
                throw new IOException(
                        "Base fixture not found at " + BASE_FIXTURE_DIR + ". Run generatePostFreezeSavedState first.");
            }

            final long loadedRound = readLoadedRound(BASE_FIXTURE_DIR);
            System.out.printf("Base fixture loaded-round = %d%n", loadedRound);

            if (Files.exists(OUTPUT_DIR)) {
                FileUtils.deleteDirectory(OUTPUT_DIR);
            }
            Files.createDirectories(OUTPUT_DIR);

            // Copy state directory verbatim.
            final Path srcStateDir = BASE_FIXTURE_DIR.resolve(OTTER_APP_DIR_NAME);
            final Path dstStateDir = OUTPUT_DIR.resolve(OTTER_APP_DIR_NAME);
            FileUtils.copyDirectory(srcStateDir, dstStateDir);

            // Filter PCES: drop online-creator events past R. Offline creators (>=N_ONLINE)
            // keep all their events; online creators (<N_ONLINE) only keep events with
            // BR<=R. The dropped events are what the test-run online nodes will recreate
            // live post-restart.
            final Set<Long> onlineCreators = new HashSet<>();
            LongStream.range(0, N_ONLINE).forEach(onlineCreators::add);
            final Path srcPcesRoot = BASE_FIXTURE_DIR.resolve(PCES_DIR_NAME);
            final Path dstPcesRoot = OUTPUT_DIR.resolve(PCES_DIR_NAME);

            final PcesFilter.FilterResult result = PcesFilter.filter(srcPcesRoot, dstPcesRoot, event -> {
                if (event.getBirthRound() <= loadedRound) {
                    return true;
                }
                return !onlineCreators.contains(event.getCreatorId().id());
            });

            System.out.printf(
                    "Symmetric variant: dropped online creators %s past round %d; kept %d/%d events (BR %d..%d)%n",
                    onlineCreators.stream().sorted().toList(),
                    loadedRound,
                    result.eventsKept(),
                    result.eventsRead(),
                    result.minBirthRoundKept(),
                    result.maxBirthRoundKept());
        } catch (final RuntimeException | IOException exp) {
            System.err.println(exp.getMessage());
            System.exit(-1);
        }
        System.exit(0);
    }
}
