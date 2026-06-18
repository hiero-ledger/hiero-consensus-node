// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.notification.IssNotification.IssType;

/**
 * Locates, on local disk, the block that contains a given ISS round (plus optionally a window of preceding blocks for
 * lead-up context), so they can be uploaded for triage. This is the source for the detection path in
 * {@code FILE} / {@code FILE_AND_GRPC} mode, where closed blocks are durable {@code .blk.gz} files on disk.
 *
 * <p>Block files are named by block number, not round number, so the round is found by searching: the node writes a
 * {@code RoundHeader} as the first item of every round, block files are 36-digit zero-padded (lexicographic order ==
 * numeric order), and a block's first round increases monotonically with its block number. The search therefore reads
 * each candidate's first {@code RoundHeader} and uses an exponential ("gallop") probe inward from the newest block —
 * the ISS round is almost always recent — narrowing to a binary search. It considers all three on-disk content kinds:
 * {@code .blk.gz} (complete), {@code .pnd.gz} (pending, with its {@code .pnd.json} proof sidecar), and {@code .open.gz}
 * (the open block flushed at catastrophic failure).
 */
public class IssBlockResolver {
    private static final Logger log = LogManager.getLogger(IssBlockResolver.class);

    private static final String COMPLETE_EXT = ".blk.gz";
    private static final String PENDING_EXT = ".pnd.gz";
    private static final String INCOMPLETE_EXT = ".open.gz";
    private static final String PENDING_PROOF_EXT = ".pnd.json";

    private final ConfigProvider configProvider;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;
    private final FileSystem fileSystem;

    @Inject
    public IssBlockResolver(
            @NonNull final ConfigProvider configProvider,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final FileSystem fileSystem) {
        this.configProvider = requireNonNull(configProvider);
        this.selfNodeAccountIdManager = requireNonNull(selfNodeAccountIdManager);
        this.fileSystem = requireNonNull(fileSystem);
    }

    /**
     * Resolves the block containing {@code round}, plus up to {@code precedingBlocks} blocks immediately before it.
     *
     * @param issType the ISS type (carried into each returned {@link IssBlockRef} for object-key context)
     * @param round the ISS round to locate
     * @param precedingBlocks how many preceding blocks to also include (clamped at the earliest retained block)
     * @return the ISS block plus any preceding blocks, ordered oldest→newest; empty if the ISS block is not on disk
     */
    @NonNull
    public List<IssBlockRef> resolve(@NonNull final IssType issType, final long round, final int precedingBlocks) {
        requireNonNull(issType);
        final var config = configProvider.getConfiguration().getConfigData(BlockStreamConfig.class);
        final Path nodeDir = fileSystem
                .getPath(config.blockFileDir())
                .resolve("block-" + asAccountString(selfNodeAccountIdManager.getSelfNodeAccountId()));
        if (!Files.isDirectory(nodeDir)) {
            log.warn("ISS block dir {} does not exist; cannot locate block for round {}", nodeDir, round);
            return List.of();
        }

        final List<BlockFile> blocks = listBlocks(nodeDir);
        if (blocks.isEmpty()) {
            log.warn("No block files under {}; cannot locate block for ISS round {}", nodeDir, round);
            return List.of();
        }

        final var firstRounds = new FirstRoundReader(blocks, config.maxReadDepth(), config.maxReadBytesSize());
        final int issIndex;
        try {
            issIndex = indexOfBlockContaining(round, blocks.size(), firstRounds);
        } catch (final ResolutionException e) {
            log.warn("Could not read a block's first round while locating ISS round {}: {}", round, e.getMessage());
            return List.of();
        }
        if (issIndex < 0) {
            log.warn(
                    "ISS round {} precedes the earliest retained block (first retained round {}); nothing to upload",
                    round,
                    firstRounds.safeGet(0));
            return List.of();
        }

        final int firstIndex = Math.max(0, issIndex - Math.max(0, precedingBlocks));
        final int found = issIndex - firstIndex; // preceding blocks actually available
        if (found < Math.max(0, precedingBlocks)) {
            log.info(
                    "Requested {} preceding context block(s) for ISS round {} but only {} are retained on disk",
                    precedingBlocks,
                    round,
                    found);
        }
        final List<IssBlockRef> refs = new ArrayList<>();
        for (int i = firstIndex; i <= issIndex; i++) {
            final BlockFile bf = blocks.get(i);
            refs.add(new IssBlockRef(issType, round, bf.blockNumber(), bf.files()));
        }
        log.info(
                "Located ISS round {} in block #{}; uploading {} block(s) ({} preceding + the ISS block)",
                round,
                blocks.get(issIndex).blockNumber(),
                refs.size(),
                found);
        return refs;
    }

    /** Lists the content block files under the node dir, keyed by block number (best file per number). */
    private List<BlockFile> listBlocks(@NonNull final Path nodeDir) {
        final Map<Long, BlockFile> byNumber = new HashMap<>();
        try (final Stream<Path> entries = Files.list(nodeDir)) {
            entries.forEach(path -> {
                final String name = path.getFileName().toString();
                final BlockKind kind;
                final String numberPart;
                if (name.endsWith(COMPLETE_EXT)) {
                    kind = BlockKind.COMPLETE;
                    numberPart = name.substring(0, name.length() - COMPLETE_EXT.length());
                } else if (name.endsWith(PENDING_EXT)) {
                    kind = BlockKind.PENDING;
                    numberPart = name.substring(0, name.length() - PENDING_EXT.length());
                } else if (name.endsWith(INCOMPLETE_EXT)) {
                    kind = BlockKind.INCOMPLETE;
                    numberPart = name.substring(0, name.length() - INCOMPLETE_EXT.length());
                } else {
                    return; // .pnd.json, .mf, or anything else
                }
                final long blockNumber;
                try {
                    blockNumber = Long.parseLong(numberPart);
                } catch (final NumberFormatException e) {
                    return;
                }
                final Path sidecar =
                        (kind == BlockKind.PENDING) ? nodeDir.resolve(numberPart + PENDING_PROOF_EXT) : null;
                final BlockFile candidate = new BlockFile(
                        blockNumber, kind, path, (sidecar != null && Files.exists(sidecar)) ? sidecar : null);
                byNumber.merge(blockNumber, candidate, (a, b) -> a.kind().priority <= b.kind().priority ? a : b);
            });
        } catch (final IOException e) {
            log.warn("Failed to list block files under {}", nodeDir, e);
            return List.of();
        }
        return byNumber.values().stream()
                .sorted((a, b) -> Long.compareUnsigned(a.blockNumber(), b.blockNumber()))
                .toList();
    }

    /**
     * Exponential (gallop-from-newest) + binary search for the index of the block containing {@code round}: the
     * rightmost block whose first round is {@code <= round}. Returns -1 if {@code round} precedes the earliest block.
     */
    private static int indexOfBlockContaining(
            final long round, final int n, @NonNull final FirstRoundReader firstRounds) throws ResolutionException {
        if (firstRounds.get(0) > round) {
            return -1; // older than anything retained
        }
        if (firstRounds.get(n - 1) <= round) {
            return n - 1; // in (or after) the newest block
        }
        // firstRound(0) <= round < firstRound(n-1): gallop left from the newest end to bracket the answer.
        int off = 1;
        while (n - 1 - off >= 0 && firstRounds.get(n - 1 - off) > round) {
            off <<= 1;
        }
        int lo = Math.max(0, n - 1 - off); // firstRound(lo) <= round
        int hi = n - 1 - (off >> 1); // firstRound(hi) > round
        while (lo + 1 < hi) {
            final int mid = lo + (hi - lo) / 2;
            if (firstRounds.get(mid) <= round) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Reads (and memoizes) the first round number of each candidate block, parsed from its first {@code RoundHeader}. */
    private static final class FirstRoundReader {
        private final List<BlockFile> blocks;
        private final int maxReadDepth;
        private final int maxReadSize;
        private final Long[] cache;

        FirstRoundReader(@NonNull final List<BlockFile> blocks, final int maxReadDepth, final int maxReadSize) {
            this.blocks = blocks;
            this.maxReadDepth = maxReadDepth;
            this.maxReadSize = maxReadSize;
            this.cache = new Long[blocks.size()];
        }

        long get(final int index) throws ResolutionException {
            Long cached = cache[index];
            if (cached == null) {
                final Path contents = blocks.get(index).contents();
                final OptionalLong firstRound = firstRoundOf(contents, maxReadDepth, maxReadSize);
                if (firstRound.isEmpty()) {
                    throw new ResolutionException("no round header in " + contents);
                }
                cached = firstRound.getAsLong();
                cache[index] = cached;
            }
            return cached;
        }

        @Nullable
        Long safeGet(final int index) {
            try {
                return get(index);
            } catch (final ResolutionException e) {
                return null;
            }
        }
    }

    /** Parses {@code contents} and returns the round number of its first {@code RoundHeader} item, if present. */
    static OptionalLong firstRoundOf(@NonNull final Path contents, final int maxReadDepth, final int maxReadSize) {
        final byte[] bytes;
        try (final GZIPInputStream in = new GZIPInputStream(Files.newInputStream(contents))) {
            bytes = in.readAllBytes();
        } catch (final IOException e) {
            log.warn("Failed to read block file {}", contents, e);
            return OptionalLong.empty();
        }
        final Block block;
        try {
            block = Block.PROTOBUF.parse(
                    Bytes.wrap(bytes).toReadableSequentialData(), false, false, maxReadDepth, maxReadSize);
        } catch (final ParseException e) {
            log.warn("Failed to parse block file {}", contents, e);
            return OptionalLong.empty();
        }
        for (final BlockItem item : block.items()) {
            if (item.hasRoundHeader()) {
                return OptionalLong.of(item.roundHeaderOrThrow().roundNumber());
            }
        }
        return OptionalLong.empty();
    }

    private enum BlockKind {
        COMPLETE(0),
        PENDING(1),
        INCOMPLETE(2);

        private final int priority; // lower wins when multiple files exist for one block number

        BlockKind(final int priority) {
            this.priority = priority;
        }
    }

    private record BlockFile(
            long blockNumber,
            @NonNull BlockKind kind,
            @NonNull Path contents,
            @Nullable Path sidecar) {
        List<Path> files() {
            return sidecar == null ? List.of(contents) : List.of(contents, sidecar);
        }
    }

    /** Thrown when a candidate block's first round cannot be read, aborting the search. */
    private static final class ResolutionException extends Exception {
        ResolutionException(@NonNull final String message) {
            super(message);
        }
    }
}
