// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.blockstream.BlockStreamRecoveryWorkflow.applyBlocks;
import static com.hedera.statevalidation.gcp.GcpPathHelper.blockFileName;

import com.hedera.statevalidation.gcp.BlockRangeResolver;
import com.hedera.statevalidation.gcp.GcpPathHelper;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.state.BinaryState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pcli.utility.ParameterizedClass;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "apply-blocks", description = "Update the state by applying blocks from a block stream.")
public class ApplyBlocksCommand extends ParameterizedClass implements Runnable {

    private static final Logger log = LogManager.getLogger(ApplyBlocksCommand.class);

    @ParentCommand
    private StateOperatorCommand parent;

    private Path blockStreamDirectory;
    private String gcpBlockStreamPath;
    private Path outputPath = Path.of("./out");
    private NodeId selfId;
    public static final long DEFAULT_TARGET_ROUND = Long.MAX_VALUE;
    private long targetRound = DEFAULT_TARGET_ROUND;
    private String expectedHash = "";
    private int roundsPerSecond = Integer.MAX_VALUE;
    private String billingProject;
    private int downloadThreads = 32;

    private ApplyBlocksCommand() {}

    @Option(
            names = {"-d", "--block-stream-dir"},
            required = true,
            description = "The path to a directory tree containing block stream files. "
                    + "Accepts a local path or a GCS URI (gs://...). "
                    + "When a GCS path is provided, --target-round is required.")
    private void setBlockStreamDirectory(final String blockStreamDir) {
        if (GcpPathHelper.isGcpPath(blockStreamDir)) {
            this.gcpBlockStreamPath = blockStreamDir;
            // Don't call pathMustExist — the path is remote
        } else {
            this.blockStreamDirectory = pathMustExist(Path.of(blockStreamDir).toAbsolutePath());
        }
    }

    @Option(
            names = {"-o", "--out"},
            description =
                    "The location where output is written. Default = './out'. " + "Must not exist prior to invocation.")
    private void setOutputPath(final Path outputPath) {
        this.outputPath = outputPath;
    }

    @Option(
            names = {"-id", "--node-id"},
            required = true,
            description = "The ID of the node that is being used to recover the state. "
                    + "This node's keys should be available locally.")
    private void setSelfId(final long selfId) {
        this.selfId = NodeId.of(selfId);
    }

    @Option(
            names = {"-t", "--target-round"},
            defaultValue = "9223372036854775807",
            description = "The last round that should be applied to the state, any higher rounds are ignored. "
                    + "Default = apply all available rounds. Required when --block-stream-dir is a GCS path.")
    private void setTargetRound(final long targetRound) {
        this.targetRound = targetRound;
    }

    @Option(
            names = {"-h", "--expected-hash"},
            defaultValue = "",
            description = "Expected hash of the resulting state.")
    private void setExpectedHash(final String expectedHash) {
        this.expectedHash = expectedHash;
    }

    @Option(
            names = {"-r", "--rate"},
            defaultValue = "2147483647",
            description = "Maximum rounds to apply per second. Controls CPU/IO load independently of state size. "
                    + "For example, 10 means at most 10 rounds/s. Default = unlimited (apply as fast as possible)")
    private void setRoundsPerSecond(final int roundsPerSecond) {
        this.roundsPerSecond = roundsPerSecond;
    }

    @Option(
            names = {"-bp", "--billing-project"},
            description = "GCP billing project for requester-pays buckets. "
                    + "Applies to the block stream directory download.")
    private void setBillingProject(final String billingProject) {
        this.billingProject = billingProject;
    }

    @Option(
            names = {"-dt", "--download-threads"},
            defaultValue = "32",
            description = "Number of parallel workers for downloading block files from GCP. Default = 32.")
    private void setDownloadThreads(final int downloadThreads) {
        this.downloadThreads = downloadThreads;
    }

    @Override
    public void run() {
        // Step 1: Resolve and initialize the state directory (handles GCS download if needed)
        parent.resolveAndGetStateDir();

        try {
            // Step 2: If block stream directory is on GCS, resolve the block range and download
            if (gcpBlockStreamPath != null) {
                blockStreamDirectory = resolveGcpBlockStream();
            }

            // Step 3: Apply blocks using the (now local) block stream directory
            applyBlocks(blockStreamDirectory, selfId, targetRound, outputPath, expectedHash, roundsPerSecond);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Resolves a GCS block stream path by:
     * <ol>
     *   <li>Validating that {@code --target-round} is specified (required for GCS)</li>
     *   <li>Reading {@link com.hedera.hapi.node.state.blockstream.BlockStreamInfo} from the loaded
     *       state to determine the left block boundary</li>
     *   <li>Running the scatter-gather binary search to find the right boundary block</li>
     *   <li>Downloading the resolved block range in parallel to a temp directory</li>
     * </ol>
     *
     * @return the local path to the downloaded block files
     * @throws IOException if any step fails
     */
    private Path resolveGcpBlockStream() throws IOException {
        // Validate: --target-round is mandatory for GCS paths
        if (targetRound == DEFAULT_TARGET_ROUND) {
            throw new IllegalArgumentException(
                    "--target-round is required when --block-stream-dir is a GCS path (gs://). "
                            + "Cannot download the entire block stream from GCS.");
        }

        GcpPathHelper.ensureGcloudAvailable();

        // Read BlockStreamInfo from the state to get the left boundary
        final BinaryState state = (BinaryState) StateUtils.getDefaultState();
        final long leftBlock = BlockRangeResolver.extractLeftBoundary(state);

        // Use a deterministic directory name containing the source round (from the state path)
        // and the target round, so identical runs reuse cached block files.
        final String sourceRound = GcpPathHelper.extractLastPathElement(parent.getRawStateDir());
        final String cacheName = "state-validator-blocks-" + sourceRound + "-to-" + targetRound;
        final Path tempBlockDir = Path.of(".", cacheName);
        Files.createDirectories(tempBlockDir);
        parent.trackTempDirectory(tempBlockDir);

        // Resolve the block range (probe files are downloaded into tempBlockDir as a side effect)
        final BlockRangeResolver resolver = new BlockRangeResolver(gcpBlockStreamPath, billingProject, tempBlockDir);
        final BlockRangeResolver.BlockRange range = resolver.resolve(leftBlock, targetRound);

        log.info(
                "Block range resolved: [{}, {}] ({} files). Starting bulk download...",
                range.leftBlock(),
                range.rightBlock(),
                range.fileCount());

        // Build the list of file names to download
        final List<String> fileNames = new ArrayList<>();
        for (long blockNum = range.leftBlock(); blockNum <= range.rightBlock(); blockNum++) {
            final String fileName = blockFileName(blockNum);
            // Skip files already present (from probing or a previous cached run)
            final Path localFile = tempBlockDir.resolve(fileName);
            if (localFile.toFile().exists() && localFile.toFile().length() > 0) {
                continue;
            }
            fileNames.add(fileName);
        }

        final long preCached = range.fileCount() - fileNames.size();
        if (preCached == range.fileCount()) {
            System.out.printf("All %d block files already cached locally.%n", range.fileCount());
            log.info("All {} block files already cached in {}", range.fileCount(), tempBlockDir);
        } else {
            log.info("{} files already cached. {} files remaining to download.", preCached, fileNames.size());

            // Bulk download the remaining files
            GcpPathHelper.downloadFiles(gcpBlockStreamPath, fileNames, tempBlockDir, billingProject, downloadThreads);
        }

        // Final validation: ensure all files in the range are present and non-empty
        long missingCount = 0;
        for (long blockNum = range.leftBlock(); blockNum <= range.rightBlock(); blockNum++) {
            final Path localFile = tempBlockDir.resolve(blockFileName(blockNum));
            if (!localFile.toFile().exists() || localFile.toFile().length() == 0) {
                missingCount++;
                if (missingCount <= 10) {
                    log.error("Missing or empty block file after download: {}", localFile.getFileName());
                }
            }
        }
        if (missingCount > 0) {
            throw new IOException(missingCount + " block file(s) missing or empty after download. "
                    + "The block stream may be incomplete in GCS, or downloads failed.");
        }

        log.info("All {} block files ready in {}", range.fileCount(), tempBlockDir);
        return tempBlockDir;
    }
}
