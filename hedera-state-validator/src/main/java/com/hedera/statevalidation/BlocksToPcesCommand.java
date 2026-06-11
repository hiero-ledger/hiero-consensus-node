// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.blockstream.BlocksToPcesWorkflow.convert;
import static com.hedera.statevalidation.gcp.GcpPathHelper.blockFileName;

import com.hedera.statevalidation.gcp.BlockRangeResolver;
import com.hedera.statevalidation.gcp.GcpPathHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.pcli.utility.ParameterizedClass;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Reconstructs an (unsigned) preconsensus event stream from block stream files and writes it as PCES
 * files.
 *
 * <p>Unlike the other state-operator commands, this command does <b>not</b> operate on a state
 * snapshot — it neither loads state nor needs the node's keys. The reconstructed events are a
 * function of the block stream alone and are not node-specific, so it is a standalone command rather
 * than a subcommand of {@code StateOperatorCommand}.
 *
 * <p>The produced PCES files can later be replayed on a node started from a state snapshot at
 * {@code --origin-round} to reproduce the original block stream. Applying the files to a state is out
 * of scope for this command.
 *
 * <p><b>Limitations:</b> redacted or filtered block streams are not supported, and the full block
 * range is held in memory during reconstruction.
 */
@Command(
        name = "blocks-to-pces",
        mixinStandardHelpOptions = true,
        description = "Reconstruct an unsigned preconsensus event stream (PCES files) from block stream files.")
public class BlocksToPcesCommand extends ParameterizedClass implements Runnable {

    @CommandLine.ParentCommand
    @SuppressWarnings("unused")
    private StateOperatorCommand parent;

    private static final Logger log = LogManager.getLogger(BlocksToPcesCommand.class);

    private Path blockStreamDirectory;
    private String gcpBlockStreamPath;
    private Path outputPath = Path.of("./out");
    private long originRound = -1;
    private long targetRound;
    private String billingProject;
    private int downloadThreads = 32;

    @Option(
            names = {"-d", "--block-stream-dir"},
            required = true,
            description = "The path to a directory tree containing block stream files. "
                    + "Accepts a local path or a GCS URI (gs://...). "
                    + "When a GCS path is provided, --target-round is required.")
    private void setBlockStreamDirectory(final String blockStreamDir) {
        if (GcpPathHelper.isGcpPath(blockStreamDir)) {
            this.gcpBlockStreamPath = blockStreamDir;
        } else {
            this.blockStreamDirectory = pathMustExist(Path.of(blockStreamDir).toAbsolutePath());
        }
    }

    @Option(
            names = {"-or", "--origin-round"},
            required = true,
            description = "The starting round of the state snapshot these PCES files are intended to be "
                    + "replayed against. Stamped as the PCES stream origin and used as the left block "
                    + "boundary when downloading from GCS.")
    private void setOriginRound(final long originRound) {
        this.originRound = originRound;
    }

    @Option(
            names = {"-o", "--out"},
            description = "The directory under which the PCES files are written, in a "
                    + "'pces-<originRound>-<targetRound>' subdirectory. Default = './out'.")
    private void setOutputPath(final Path outputPath) {
        this.outputPath = outputPath;
    }

    @Option(
            names = {"-t", "--target-round"},
            required = true,
            description = "The last round whose events are extracted; events from higher rounds are ignored. "
                    + "Default = extract all available rounds. Required when --block-stream-dir is a GCS path.")
    private void setTargetRound(final long targetRound) {
        this.targetRound = targetRound;
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
        try {
            // If the block stream directory is on GCS, resolve the range and download.
            if (gcpBlockStreamPath != null) {
                blockStreamDirectory = resolveGcpBlockStream();
            }

            final Path pcesDir = outputPath.resolve("pces-" + originRound + "-" + targetRound);
            if (Files.exists(pcesDir)) {
                throw new IllegalArgumentException("Output directory already exists: " + pcesDir);
            }

            final long count = convert(blockStreamDirectory, pcesDir, originRound);
            log.info("blocks-to-pces complete: {} event(s) written to {}", count, pcesDir);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Resolves a GCS block stream path. Unlike {@code ApplyBlocksCommand}, the left boundary comes
     * from {@code --origin-round} rather than {@code BlockStreamInfo} in a loaded state, since this
     * command does not load state.
     *
     * @return the local path to the downloaded block files
     */
    private Path resolveGcpBlockStream() throws IOException {
        GcpPathHelper.ensureGcloudAvailable();

        final String streamId = GcpPathHelper.extractLastPathElement(gcpBlockStreamPath);
        final String cacheName = "state-validator-blocks-" + originRound + "-to-" + targetRound + "-s" + streamId;
        final Path tempBlockDir = Path.of(".", cacheName);
        Files.createDirectories(tempBlockDir);

        // The left boundary is the first block containing a round >= originRound. BlockRangeResolver
        // expects a left block number; we use originRound as the left bound for the search.
        final BlockRangeResolver resolver = new BlockRangeResolver(gcpBlockStreamPath, billingProject, tempBlockDir);
        final BlockRangeResolver.BlockRange range = resolver.resolveByRounds(originRound, targetRound);

        log.info(
                "Block range resolved: [{}, {}] ({} files). Starting download...",
                range.leftBlock(),
                range.rightBlock(),
                range.fileCount());

        final List<String> fileNames = new ArrayList<>();
        for (long blockNum = range.leftBlock(); blockNum <= range.rightBlock(); blockNum++) {
            final Path localFile = tempBlockDir.resolve(blockFileName(blockNum));
            if (localFile.toFile().exists() && localFile.toFile().length() > 0) {
                continue;
            }
            fileNames.add(blockFileName(blockNum));
        }

        if (!fileNames.isEmpty()) {
            GcpPathHelper.downloadFiles(gcpBlockStreamPath, fileNames, tempBlockDir, billingProject, downloadThreads);
        }

        long missingCount = 0;
        for (long blockNum = range.leftBlock(); blockNum <= range.rightBlock(); blockNum++) {
            final Path localFile = tempBlockDir.resolve(blockFileName(blockNum));
            if (!localFile.toFile().exists() || localFile.toFile().length() == 0) {
                missingCount++;
            }
        }
        if (missingCount > 0) {
            throw new IOException(missingCount + " block file(s) missing or empty after download.");
        }

        return tempBlockDir;
    }
}
