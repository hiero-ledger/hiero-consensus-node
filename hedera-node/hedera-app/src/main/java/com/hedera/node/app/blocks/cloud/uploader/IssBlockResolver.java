// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.cloud.uploader;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.BlockBytes;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * each candidate's first {@code RoundHeader}, scanning from the newest block inward — the ISS round is almost always
 * recent — and skipping any candidate whose first round cannot be read (e.g. a header-only {@code .open.gz}) rather
 * than aborting. It considers all three on-disk content kinds: {@code .blk.gz} (complete), {@code .pnd.gz} (pending,
 * with its {@code .pnd.json} proof sidecar), and {@code .open.gz} (the open block flushed at catastrophic failure).
 */
public class IssBlockResolver {
    private static final Logger log = LogManager.getLogger(IssBlockResolver.class);

    private static final String COMPLETE_EXT = ".blk.gz";
    private static final String PENDING_EXT = ".pnd.gz";
    private static final String INCOMPLETE_EXT = ".open.gz";
    private static final String PENDING_PROOF_EXT = ".pnd.json";
    /** Written by {@code FileBlockItemWriter} only after a block is fully written and closed. */
    private static final String MARKER_EXT = ".mf";

    private final ConfigProvider configProvider;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;
    private final FileSystem fileSystem;

    /**
     * Memoized first-round-per-block-number so the detection path's poll loop (resolveWithWait re-invokes resolve
     * every 250ms) does not re-decompress and re-parse the same block files each iteration. A block's first round is
     * invariant, so entries never go stale; the map is pruned to the currently-retained blocks on each resolve to stay
     * bounded by the retention window.
     */
    private final Map<Long, Long> firstRoundByBlockNumber = new ConcurrentHashMap<>();

    /** Memoized last-round-per-block-number, used only to confirm the newest block actually contains the ISS round. */
    private final Map<Long, Long> lastRoundByBlockNumber = new ConcurrentHashMap<>();

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

        // Prune cached first-rounds to the currently-retained blocks so the cache stays bounded to the window.
        final Set<Long> currentNumbers = new HashSet<>();
        for (final BlockFile bf : blocks) {
            currentNumbers.add(bf.blockNumber());
        }
        firstRoundByBlockNumber.keySet().retainAll(currentNumbers);
        lastRoundByBlockNumber.keySet().retainAll(currentNumbers);

        // Find the block containing the round: the rightmost block whose first round is <= round (first round
        // increases monotonically with block number). Scan from the newest end so a recent ISS — the common case — is
        // found quickly. A candidate whose first RoundHeader cannot be read (a header-only .open.gz flushed after
        // writes were dropped at fatal shutdown, or a corrupt gzip) is SKIPPED, never fatal: one unreadable block must
        // not abort the resolve and drop an ISS block that is present on disk as a readable .blk.gz.
        final int maxReadDepth = config.maxReadDepth();
        final int maxReadSize = config.maxReadBytesSize();
        int issIndex = -1;
        Long oldestReadableRound = null;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            final OptionalLong firstRound = cachedFirstRound(blocks.get(i), maxReadDepth, maxReadSize);
            if (firstRound.isEmpty()) {
                log.warn(
                        "Skipping block file {} while locating ISS round {}: first round not readable",
                        blocks.get(i).contents(),
                        round);
                continue;
            }
            oldestReadableRound = firstRound.getAsLong();
            if (firstRound.getAsLong() <= round) {
                issIndex = i;
                break;
            }
        }
        if (issIndex < 0) {
            log.warn(
                    "ISS round {} precedes the earliest retained block (first retained round {}); nothing to upload",
                    round,
                    oldestReadableRound);
            return List.of();
        }

        // The loop picks the rightmost block whose FIRST round <= the ISS round: proof only that the round is in that
        // block OR a later one. A later LISTED block would have bounded it (its first round > round), but when the pick
        // is the NEWEST listed block the round may instead be in the still-open block — written as an unmarked
        // ".blk.gz" and excluded by the ".mf" gate above. Returning this preceding block would upload the wrong one and
        // let the coordinator mark the round done, so confirm the round is actually within the newest block
        // (round <= its LAST round); if not, keep polling (return empty) until the open block closes or is flushed. An
        // unreadable ".open.gz" skipped above stays in the list, leaving issIndex < size-1, so the best-effort fallback
        // for a dropped-writes open block is untouched.
        if (issIndex == blocks.size() - 1) {
            final OptionalLong lastRound = cachedLastRound(blocks.get(issIndex), maxReadDepth, maxReadSize);
            if (lastRound.isEmpty() || lastRound.getAsLong() < round) {
                log.info(
                        "ISS round {} is beyond the newest durable block #{} (last round {}); it is still in the open "
                                + "block — waiting for it to become durable before uploading",
                        round,
                        blocks.get(issIndex).blockNumber(),
                        lastRound.isEmpty() ? "unreadable" : String.valueOf(lastRound.getAsLong()));
                return List.of();
            }
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
                    numberPart = name.substring(0, name.length() - COMPLETE_EXT.length());
                    // A .blk.gz is a finished, readable block only once its .mf completion marker exists. Without it,
                    // this is the currently-open block still being written (a partial, unterminated gzip that would
                    // fail to parse and abort the search), so skip it.
                    if (!Files.exists(nodeDir.resolve(numberPart + MARKER_EXT))) {
                        return;
                    }
                    kind = BlockKind.COMPLETE;
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

    /** {@link #firstRoundOf} with per-block-number memoization (see {@link #firstRoundByBlockNumber}). */
    private OptionalLong cachedFirstRound(
            @NonNull final BlockFile block, final int maxReadDepth, final int maxReadSize) {
        final Long cached = firstRoundByBlockNumber.get(block.blockNumber());
        if (cached != null) {
            return OptionalLong.of(cached);
        }
        final OptionalLong firstRound = firstRoundOf(block.contents(), maxReadDepth, maxReadSize);
        firstRound.ifPresent(r -> firstRoundByBlockNumber.put(block.blockNumber(), r));
        return firstRound;
    }

    /**
     * Parses {@code contents} into raw per-item {@link BlockBytes} (wire-identical to a {@code Block}, as in
     * {@code IssBufferBlockReader}) rather than the full {@code Block} object graph, so callers can deserialize items
     * one at a time. The gzip is streamed straight in (no intermediate decompressed {@code byte[]}), bounded by
     * {@code maxReadSize}. Each stream stage is its own resource: {@link GZIPInputStream}'s constructor eagerly reads
     * the gzip header and can throw on an empty/truncated/corrupt file, so nesting the constructors would leak the
     * already-opened {@link Files#newInputStream} descriptor. Empty if the file cannot be read or parsed.
     */
    private static Optional<BlockBytes> parseBlockBytes(
            @NonNull final Path contents, final int maxReadDepth, final int maxReadSize) {
        try (final var fileIn = Files.newInputStream(contents);
                final var gzipIn = new GZIPInputStream(fileIn);
                final ReadableStreamingData in = new ReadableStreamingData(gzipIn)) {
            return Optional.of(BlockBytes.PROTOBUF.parse(in, false, false, maxReadDepth, maxReadSize));
        } catch (final IOException e) {
            log.warn("Failed to read block file {}", contents, e);
        } catch (final ParseException e) {
            log.warn("Failed to parse block file {}", contents, e);
        }
        return Optional.empty();
    }

    /** Returns the round number of {@code contents}'s first {@code RoundHeader} — usually its second item — if present. */
    static OptionalLong firstRoundOf(@NonNull final Path contents, final int maxReadDepth, final int maxReadSize) {
        final Optional<BlockBytes> parsed = parseBlockBytes(contents, maxReadDepth, maxReadSize);
        if (parsed.isEmpty()) {
            return OptionalLong.empty();
        }
        // Deserialize items one at a time only until the first RoundHeader, so a large block is not fully materialized.
        for (final Bytes itemBytes : parsed.get().items()) {
            try {
                final BlockItem item = BlockItem.PROTOBUF.parse(itemBytes);
                if (item.hasRoundHeader()) {
                    return OptionalLong.of(item.roundHeaderOrThrow().roundNumber());
                }
            } catch (final ParseException e) {
                log.warn("Failed to parse a block item in {}", contents, e);
                return OptionalLong.empty();
            }
        }
        return OptionalLong.empty();
    }

    /** {@link #lastRoundOf} with per-block-number memoization (a durable block's last round is invariant too). */
    private OptionalLong cachedLastRound(
            @NonNull final BlockFile block, final int maxReadDepth, final int maxReadSize) {
        final Long cached = lastRoundByBlockNumber.get(block.blockNumber());
        if (cached != null) {
            return OptionalLong.of(cached);
        }
        final OptionalLong lastRound = lastRoundOf(block.contents(), maxReadDepth, maxReadSize);
        lastRound.ifPresent(r -> lastRoundByBlockNumber.put(block.blockNumber(), r));
        return lastRound;
    }

    /**
     * Returns the round number of {@code contents}'s last {@code RoundHeader}, if present. Unlike {@link #firstRoundOf}
     * this scans every item, so it is used only to confirm the newest block contains the ISS round. A truncated final
     * item in a flushed open block is tolerated: the last fully-parsed round is returned (a round's {@code RoundHeader}
     * is written before its body, so even a partial final round is counted).
     */
    static OptionalLong lastRoundOf(@NonNull final Path contents, final int maxReadDepth, final int maxReadSize) {
        final Optional<BlockBytes> parsed = parseBlockBytes(contents, maxReadDepth, maxReadSize);
        if (parsed.isEmpty()) {
            return OptionalLong.empty();
        }
        long lastRound = 0;
        boolean found = false;
        for (final Bytes itemBytes : parsed.get().items()) {
            try {
                final BlockItem item = BlockItem.PROTOBUF.parse(itemBytes);
                if (item.hasRoundHeader()) {
                    lastRound = item.roundHeaderOrThrow().roundNumber();
                    found = true;
                }
            } catch (final ParseException e) {
                break;
            }
        }
        return found ? OptionalLong.of(lastRound) : OptionalLong.empty();
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
}
