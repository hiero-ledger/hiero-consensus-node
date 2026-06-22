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
 * <p><b>Target-round semantics (whole-block).</b> {@code --target-round} identifies the <i>last
 * block</i> to extract: the block that <i>contains</i> that round. That entire block is included —
 * all of its rounds, including any after the target round — not truncated at the target round. This
 * is the same whole-block meaning {@code replay-pces} uses, and it is the inverse of
 * {@code apply-blocks}, where {@code --target-round} means "stop exactly at this round" and the last
 * block may be applied only partially. Whole-block extraction is required so that {@code replay-pces}
 * can drive the block's closing round and finalize the last block, rather than leaving it open. The
 * resulting state therefore corresponds to the <i>last round of the block containing the target
 * round</i> (>= the target round), not to the target round exactly.
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

    /**
     * Default number of rounds to extend the extraction window backward beyond the origin round, to
     * include the non-ancient "tail" of events whose descendants near the origin reference them as
     * parents. Matches the platform's default {@code consensus.roundsNonAncient} (26). The replaying
     * node's orphan buffer will hold any event whose parents are non-ancient until those parents
     * arrive; extending the window back by this many rounds ensures those parents are present in the
     * stream rather than missing, so the earliest extracted events can leave the orphan buffer.
     */
    private static final long DEFAULT_ROUNDS_NON_ANCIENT = 26;

    private Path blockStreamDirectory;
    private String gcpBlockStreamPath;
    private Path outputPath = Path.of("./out");
    private long originRound = -1;
    private long targetRound;
    private String billingProject;
    private int downloadThreads = Runtime.getRuntime().availableProcessors();
    private long roundsNonAncient = DEFAULT_ROUNDS_NON_ANCIENT;

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
            names = {"-tr", "--target-round"},
            required = true,
            description = "Identifies the last block to extract: the block that CONTAINS this round. The entire "
                    + "block is extracted (all its rounds, including any after this round) — it is NOT truncated "
                    + "at this round. This whole-block meaning matches replay-pces and is the inverse of "
                    + "apply-blocks (where --target-round stops exactly at the round, applying the last block "
                    + "partially). The replayed state will correspond to the last round of the block containing "
                    + "this round, which may be greater than this round.")
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

    @Option(
            names = {"-rna", "--rounds-non-ancient"},
            defaultValue = "" + DEFAULT_ROUNDS_NON_ANCIENT,
            description = "Number of rounds to extend the extraction window backward before --origin-round, "
                    + "to include the non-ancient tail of events that events near the origin reference as "
                    + "parents. Without this tail, the earliest extracted events reference parents that are "
                    + "non-ancient but missing from the stream, and the replaying node's orphan buffer holds "
                    + "them forever (consensus never advances). Should be >= the replaying node's "
                    + "consensus.roundsNonAncient. Default = 26.")
    private void setRoundsNonAncient(final long roundsNonAncient) {
        this.roundsNonAncient = roundsNonAncient;
    }

    @Override
    public void run() {
        // This tool writes millions of events sequentially with no concurrent readers and no need
        // for per-event durability. The default PCES writer on Linux (FILE_CHANNEL) issues one write
        // syscall per event, which dominates runtime; OUTPUT_STREAM batches writes through a
        // BufferedOutputStream. Set this before the (lazily built) platform Configuration is created
        // so CommonPcesWriter picks it up. Scoped to this standalone command's process only.
        System.setProperty("event.preconsensus.pcesFileWriterType", "OUTPUT_STREAM");
        try {
            // If the block stream directory is on GCS, resolve the range and download.
            if (gcpBlockStreamPath != null) {
                blockStreamDirectory = resolveGcpBlockStream();
            }

            final Path pcesDir = outputPath.resolve("pces-" + originRound + "-" + targetRound);
            if (Files.exists(pcesDir)) {
                throw new IllegalArgumentException("Output directory already exists: " + pcesDir);
            }

            // The extraction window, in consensus rounds, matched to the GCS resolver's semantics:
            //  - left bound extends roundsNonAncient before originRound (non-ancient parent tail; see option doc)
            //  - right bound is the target round
            // convert() applies this window with whole-block granularity (a block is included if its round
            // span intersects [leftRound, targetRound]), identical to what the GCS BlockRangeResolver downloads,
            // so a local directory and a GCS source yield the same block set — and thus the same PCES output.
            final long leftRound = Math.max(1L, originRound - roundsNonAncient);

            final long count = convert(blockStreamDirectory, pcesDir, originRound, leftRound, targetRound);
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
        final String cacheName = "state-validator-blocks-" + originRound + "-to-" + targetRound + "-rna"
                + roundsNonAncient + "-s" + streamId;
        final Path tempBlockDir = Path.of(".", cacheName);
        Files.createDirectories(tempBlockDir);

        // The left boundary is extended backward by roundsNonAncient before the origin round so that the
        // non-ancient tail of events (parents of events near the origin) is included. Without this, the
        // earliest extracted events reference non-ancient parents that are absent from the stream, and the
        // replaying node's orphan buffer never releases them. The origin stamp itself remains originRound.
        final long leftSearchRound = Math.max(1L, originRound - roundsNonAncient);

        // The left boundary is the first block containing a round >= leftSearchRound. BlockRangeResolver
        // expects a left block number; we use leftSearchRound as the left bound for the search.
        final BlockRangeResolver resolver = new BlockRangeResolver(gcpBlockStreamPath, billingProject, tempBlockDir);
        final BlockRangeResolver.BlockRange range = resolver.resolveByRounds(leftSearchRound, targetRound);

        // Download one block PAST the resolved right boundary. replay-pces needs the events in the block
        // after the target-containing block to decide consensus on (and thus close) the target block —
        // see filterByRoundWindow. The resolver returns the block containing targetRound; the convert step
        // selects that block plus the next one, so the next one must be downloaded too. The trailing block
        // is best-effort: if the target is at the very end of the available stream it may not exist, in
        // which case the target block simply cannot be closed (the caller must extend --target-round).
        final long trailingBlock = range.rightBlock() + 1;

        log.info(
                "Block range resolved: [{}, {}] (+1 trailing block {}). Starting download...",
                range.leftBlock(),
                range.rightBlock(),
                trailingBlock);

        final List<String> fileNames = new ArrayList<>();
        for (long blockNum = range.leftBlock(); blockNum <= range.rightBlock(); blockNum++) {
            final Path localFile = tempBlockDir.resolve(blockFileName(blockNum));
            if (localFile.toFile().exists() && localFile.toFile().length() > 0) {
                continue;
            }
            fileNames.add(blockFileName(blockNum));
        }
        // Add the trailing block to the download list (best-effort; verified separately below).
        final Path trailingLocal = tempBlockDir.resolve(blockFileName(trailingBlock));
        if (!trailingLocal.toFile().exists() || trailingLocal.toFile().length() == 0) {
            fileNames.add(blockFileName(trailingBlock));
        }

        if (!fileNames.isEmpty()) {
            GcpPathHelper.downloadFiles(gcpBlockStreamPath, fileNames, tempBlockDir, billingProject, downloadThreads);
        }

        // The resolved range [leftBlock, rightBlock] must be fully present.
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
        // The trailing block is optional: warn if absent so the user knows the last block may not close.
        if (!trailingLocal.toFile().exists() || trailingLocal.toFile().length() == 0) {
            log.warn(
                    "Trailing block {} (needed to close the target block during replay) is not available; "
                            + "the last block may remain open. Extend --target-round to include more blocks.",
                    trailingBlock);
        }

        return tempBlockDir;
    }
}
