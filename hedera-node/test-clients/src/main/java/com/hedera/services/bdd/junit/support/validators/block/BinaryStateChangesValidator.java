// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SAVED_STATES_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SWIRLDS_LOG;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.STATE_METADATA_FILE;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.junit.support.validators.block.RootHashUtils.extractRootMnemonic;
import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.node.app.ServicesMain;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.state.BinaryState;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Mnemonics;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.config.PathsConfig;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.Assertions;

/**
 * A validator that replays {@link StateChanges} through the {@link BinaryState} API, without going through
 * service-specific writable state adapters.
 *
 * <p>After applying all state changes from the block stream, the resulting root hash is compared
 * to the expected hash from the latest saved state. This validates that the block stream contains a complete and
 * correct record of all state mutations.
 */
public class BinaryStateChangesValidator implements BlockStreamValidator {

    private static final Logger logger = LogManager.getLogger(BinaryStateChangesValidator.class);
    private static final int HASH_SIZE = 48;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    private final Bytes expectedRootHashBytes;
    private final Path pathToNode0SwirldsLog;

    @Nullable
    private final Path preservedPreviewBlocksDir;

    private final BinaryStateChangeSummary stateChangesSummary = new BinaryStateChangeSummary();

    private Instant lastStateChangesTime;
    private final VirtualMapState state;

    static void main() {
        final var node0Dir = Paths.get("hedera-node/test-clients")
                .resolve(workingDirFor(0, "hapi"))
                .toAbsolutePath()
                .normalize();
        final long shard = 11;
        final long realm = 12;
        final var validator = new BinaryStateChangesValidator(
                Bytes.fromHex(
                        "50ea5c2588457b952dba215bcefc5f54a1b87c298e5c0f2a534a8eb7177354126c55ee5c23319187e964443e4c17c007"),
                node0Dir.resolve("output/swirlds.log"));
        final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(
                node0Dir.resolve("data/blockStreams/block-%d.%d.3".formatted(shard, realm)));
        validator.validateBlocks(blocks);
    }

    public static final Factory FACTORY = new Factory() {
        @NonNull
        @Override
        public BlockStreamValidator create(@NonNull final HapiSpec spec) {
            return newValidatorFor(spec);
        }

        @Override
        public boolean appliesTo(@NonNull final HapiSpec spec) {
            return spec.targetNetworkOrThrow().type() == SUBPROCESS_NETWORK;
        }
    };

    /**
     * Constructs a validator that will replay the state changes in the block stream through the {@link BinaryState} API
     * and compare the resulting root hash to the latest saved state hash.
     *
     * @param spec the spec
     * @return the validator
     */
    public static BinaryStateChangesValidator newValidatorFor(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        final var latestStateDir = findMaybeLatestSavedStateFor(spec);
        if (latestStateDir == null) {
            throw new AssertionError("No saved state directory found");
        }
        final var rootHash = findRootHashFrom(latestStateDir.resolve(STATE_METADATA_FILE));
        if (rootHash == null) {
            throw new AssertionError("No root hash found in state metadata file");
        }
        if (!(spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork)) {
            throw new IllegalArgumentException("Cannot validate state changes for an embedded network");
        }

        final var node0 = subProcessNetwork.getRequiredNode(byNodeId(0));
        final var preservedDir =
                node0.metadata().workingDir().resolve("data").resolve("cutover").resolve("preservedPreviewBlocks");
        final var preservedPreviewBlocksDir = Files.isDirectory(preservedDir) ? preservedDir : null;
        return new BinaryStateChangesValidator(rootHash, node0.getExternalPath(SWIRLDS_LOG), preservedPreviewBlocksDir);
    }

    public BinaryStateChangesValidator(
            @NonNull final Bytes expectedRootHashBytes, @NonNull final Path pathToNode0SwirldsLog) {
        this(expectedRootHashBytes, pathToNode0SwirldsLog, null);
    }

    public BinaryStateChangesValidator(
            @NonNull final Bytes expectedRootHashBytes,
            @NonNull final Path pathToNode0SwirldsLog,
            @Nullable final Path preservedPreviewBlocksDir) {
        this.expectedRootHashBytes = requireNonNull(expectedRootHashBytes);
        this.pathToNode0SwirldsLog = requireNonNull(pathToNode0SwirldsLog);
        this.preservedPreviewBlocksDir = preservedPreviewBlocksDir;

        final var platformConfig = ServicesMain.buildPlatformConfig();
        final var pathsConfig = platformConfig.getConfigData(PathsConfig.class);
        final var metrics = new NoOpMetrics();
        final var merkleDbConfig = platformConfig.getConfigData(MerkleDbConfig.class);
        final var fileSystemManager = new FileSystemManager(pathsConfig.savedStateDir(), pathsConfig.tmpDir());
        final var dsBuilder =
                new MerkleDbDataSourceBuilder(platformConfig, fileSystemManager, merkleDbConfig.initialCapacity());
        final var virtualMap = new VirtualMap(dsBuilder, platformConfig);
        this.state = new VirtualMapStateImpl(virtualMap, metrics);
    }

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info("Beginning binary replay validation of expected root hash {}", expectedRootHashBytes);
        if (preservedPreviewBlocksDir != null) {
            logger.info("Cutover detected — replaying preserved preview blocks from {}", preservedPreviewBlocksDir);
            final var previewBlocks =
                    BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocksIgnoringMarkers(preservedPreviewBlocksDir);
            logger.info(
                    "Replaying {} preserved preview blocks before {} post-cutover blocks",
                    previewBlocks.size(),
                    blocks.size());
            applyBlocks(previewBlocks);
        }
        applyBlocks(blocks);
        logger.info("Summary of binary-applied changes by state:\n{}", stateChangesSummary);

        // Make the underlying VirtualMap immutable by creating a mutable copy, then hash the original
        final VirtualMap virtualMap = state.getRoot();
        virtualMap.copy().release();
        final Bytes rootHashBytes = requireNonNull(virtualMap.getHash()).getBytes();
        logger.info("Validating binary replay root hash {}", rootHashBytes);

        if (!expectedRootHashBytes.equals(rootHashBytes)) {
            final var expectedRootMnemonic = getMaybeLastHashMnemonics(pathToNode0SwirldsLog);
            final var actualRootMnemonic = Mnemonics.generateMnemonic(virtualMap.getHash());
            final var errorMsg = new StringBuilder("Binary replay hash mismatch");
            errorMsg.append("\n    * root hash - expected ")
                    .append(expectedRootHashBytes)
                    .append(", was ")
                    .append(rootHashBytes);
            if (expectedRootMnemonic != null) {
                errorMsg.append("\n    * root mnemonic - expected ")
                        .append(expectedRootMnemonic)
                        .append(", was ")
                        .append(actualRootMnemonic);
            }
            Assertions.fail(errorMsg.toString());
        }
    }

    private void applyBlocks(@NonNull final List<Block> blocks) {
        for (final var block : blocks) {
            for (final var item : block.items()) {
                if (!item.hasStateChanges()) {
                    continue;
                }
                final var changes = item.stateChangesOrThrow();
                final var at = asInstant(changes.consensusTimestampOrThrow());
                // (FUTURE) Re-enable after state change ordering is fixed
                if (false && lastStateChangesTime != null && at.isBefore(lastStateChangesTime)) {
                    Assertions.fail("Binary validation – state changes are not in chronological order at " + at);
                }
                lastStateChangesTime = at;
                BinaryStateChangeParser.applyStateChanges(
                        state, StateChanges.PROTOBUF.toBytes(changes), stateChangesSummary);
            }
        }
    }

    private static @Nullable Bytes findRootHashFrom(@NonNull final Path stateMetadataPath) {
        try (final var lines = Files.lines(stateMetadataPath)) {
            return lines.filter(line -> line.startsWith("HASH:"))
                    .map(line -> line.substring(line.length() - 2 * HASH_SIZE))
                    .map(Bytes::fromHex)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            logger.error("Failed to read state metadata file {}", stateMetadataPath, e);
            return null;
        }
    }

    private static @Nullable Path findMaybeLatestSavedStateFor(@NonNull final HapiSpec spec) {
        final var savedStateDirs = spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(SAVED_STATES_DIR))
                .map(Path::toAbsolutePath)
                .toList();
        for (final var savedStatesDir : savedStateDirs) {
            try {
                final var latestRoundPath = findLargestNumberDirectory(savedStatesDir);
                if (latestRoundPath != null) {
                    return latestRoundPath;
                }
            } catch (IOException e) {
                logger.error("Failed to find the latest saved state directory in {}", savedStatesDir, e);
            }
        }
        return null;
    }

    private static @Nullable Path findLargestNumberDirectory(@NonNull final Path savedStatesDir) throws IOException {
        long latestRound = -1;
        Path latestRoundPath = null;
        try (final var stream =
                Files.newDirectoryStream(savedStatesDir, BinaryStateChangesValidator::isNumberDirectory)) {
            for (final var numberDirectory : stream) {
                final var round = Long.parseLong(numberDirectory.getFileName().toString());
                if (round > latestRound) {
                    latestRound = round;
                    latestRoundPath = numberDirectory;
                }
            }
        }
        return latestRoundPath;
    }

    private static boolean isNumberDirectory(@NonNull final Path path) {
        return path.toFile().isDirectory()
                && NUMBER_PATTERN.matcher(path.getFileName().toString()).matches();
    }

    private static @Nullable String getMaybeLastHashMnemonics(final Path path) {
        String rootMnemonicLine = null;
        try {
            final var lines = Files.readAllLines(path);
            for (final var line : lines) {
                if (line.startsWith("(root)")) {
                    rootMnemonicLine = line;
                    break;
                }
            }
        } catch (IOException e) {
            logger.error("Could not read root mnemonic from {}", path, e);
            return null;
        }
        logger.info("Read root mnemonic:\n{}", rootMnemonicLine);
        return rootMnemonicLine == null ? null : extractRootMnemonic(rootMnemonicLine);
    }
}
