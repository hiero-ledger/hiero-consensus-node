// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.failure;

import static com.hedera.hapi.util.HapiUtils.asAccountString;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.internal.BlockBytes;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.hapi.utils.blocks.BlockFileUtils;
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
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Locates, on local disk, the block that contains a given ISS round (plus optionally a window of preceding blocks for
 * lead-up context), so they can be staged for triage. This is the source for the detection path in
 * {@code FILE} / {@code FILE_AND_GRPC} mode, where closed blocks are durable {@code .blk.zstd} files on disk.
 *
 * <p>Block files are named by block number, not round number, so the round is found by searching: the node writes a
 * {@code RoundHeader} as the first item of every round, block files are named by their zero-padded block number, and a
 * block's first round increases monotonically with its block number. The search therefore reads each candidate's first
 * {@code RoundHeader}, scanning from the newest block inward — the ISS round is almost always recent — and skipping any
 * candidate whose first round cannot be read (e.g. a header-only {@code .open.zstd}) rather than aborting. It considers
 * all three on-disk content kinds: {@code .blk.zstd} (complete), {@code .pnd.zstd} (pending, with its {@code .pnd.json}
 * proof sidecar), and {@code .open.zstd} (the open block flushed at catastrophic failure). Legacy {@code .gz} files
 * left by an older version are read too.
 */
public class IssBlockResolver {
    private static final Logger log = LogManager.getLogger(IssBlockResolver.class);

    /** Suffixes of an open block flushed for triage: the current format, then the legacy one. */
    private static final List<String> INCOMPLETE_EXTS = List.of(".open.zstd", ".open.gz");

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

    /**
     * Block numbers whose contents could not be read (corrupt, or a header-only {@code .open.zstd}). Cached so the
     * detection poll (which re-invokes {@link #resolve} every 250ms) does not re-decompress the same unreadable file
     * each iteration. Unreadability is terminal for a given block number; the set is pruned to the retention window.
     */
    private final Set<Long> unreadableBlockNumbers = ConcurrentHashMap.newKeySet();

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
     * @param round the ISS round to locate
     * @param precedingBlocks how many preceding blocks to also include (clamped at the earliest retained block)
     * @return the ISS block plus any preceding blocks, ordered oldest→newest; empty if the ISS block is not on disk
     */
    @NonNull
    public List<IssBlockRef> resolve(final long round, final int precedingBlocks) {
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
        unreadableBlockNumbers.retainAll(currentNumbers);

        // Find the block containing the round: the rightmost block whose first round is <= round (first round
        // increases monotonically with block number). Scan from the newest end so a recent ISS — the common case — is
        // found quickly. A candidate whose first RoundHeader cannot be read (a header-only .open.zstd flushed after
        // writes were dropped at fatal shutdown, or a corrupt file) is SKIPPED, never fatal: one unreadable block must
        // not abort the resolve and drop an ISS block that is present on disk as a readable .blk.zstd.
        final int maxReadDepth = config.maxReadDepth();
        final int maxReadSize = config.maxReadBytesSize();
        int issIndex = -1;
        Long oldestReadableRound = null;
        // Whether the pick's direct successor is READABLE and bounds the round to the pick (its first round > round).
        // If not (the newest block(s) were unreadable and skipped, or a missing/unmarked block sits between them), the
        // pick needs the last-round confirmation below before it can be trusted.
        boolean boundedByReadableNewerBlock = false;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            final OptionalLong firstRound = cachedFirstRound(blocks.get(i), maxReadDepth, maxReadSize);
            if (firstRound.isEmpty()) {
                log.warn(
                        "Skipping block file {} while locating ISS round {}: first round not readable",
                        blocks.get(i).contents(),
                        round);
                // The round could be inside THIS unreadable block, so an already-seen readable newer block no longer
                // proves the round is in the pick; clear the bound to force the last-round confirmation below.
                boundedByReadableNewerBlock = false;
                continue;
            }
            oldestReadableRound = firstRound.getAsLong();
            if (firstRound.getAsLong() <= round) {
                issIndex = i;
                break;
            }
            // A missing or unmarked block between this block and the next candidate may hold the round.
            boundedByReadableNewerBlock =
                    i > 0 && blocks.get(i).blockNumber() == blocks.get(i - 1).blockNumber() + 1;
        }
        if (issIndex < 0) {
            log.warn(
                    "ISS round {} precedes the earliest retained block (first retained round {}); nothing to stage",
                    round,
                    oldestReadableRound);
            return List.of();
        }

        // The loop picks the rightmost block whose FIRST round <= the ISS round: proof only that the round is in that
        // block OR a newer one. A readable newer block bounds it (its first round > round) — but if NO readable newer
        // block does (the pick is the newest block, or every newer block was an unreadable/header-only ".open.zstd"
        // skipped above), the round may instead be in that newest, not-yet-durable/unreadable block. Returning the pick
        // would stage the WRONG block and let the coordinator mark the round done, so confirm the round is actually
        // within the pick (round <= its LAST round); if not, return empty so the caller keeps polling / surfaces the
        // loss rather than masking it with a preceding block.
        if (!boundedByReadableNewerBlock) {
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
            refs.add(new IssBlockRef(bf.blockNumber(), bf.files()));
        }
        log.info(
                "Located ISS round {} in block #{}; staging {} block(s) ({} preceding + the ISS block)",
                round,
                blocks.get(issIndex).blockNumber(),
                refs.size(),
                found);
        return refs;
    }

    /**
     * Clears the negative (unreadable) cache so the next {@link #resolve} re-reads every candidate. Called by the
     * authoritative {@code CATASTROPHIC_FAILURE} one-shot resolve, so a block that was only <i>transiently</i>
     * unreadable during the detection poll (e.g. a fleeting I/O error) is not skipped forever.
     */
    void forgetUnreadable() {
        unreadableBlockNumbers.clear();
    }

    /** Lists the content block files under the node dir, keyed by block number (best file per number). */
    private List<BlockFile> listBlocks(@NonNull final Path nodeDir) {
        final Map<Long, BlockFile> byNumber = new HashMap<>();
        try (final Stream<Path> entries = Files.list(nodeDir)) {
            entries.forEach(path -> {
                final String name = path.getFileName().toString();
                final BlockKind kind;
                if (BlockFileUtils.isCompleteBlockFile(path)) {
                    // A block file is a finished, readable block only once its .mf completion marker exists. Without
                    // it, this is the currently-open block still being written (a partial, unterminated stream that
                    // would fail to parse and abort the search), so skip it.
                    if (!Files.exists(BlockFileUtils.markerPathFor(path))) {
                        return;
                    }
                    kind = BlockKind.COMPLETE;
                } else if (BlockFileUtils.isPendingBlockFile(path)) {
                    kind = BlockKind.PENDING;
                } else if (INCOMPLETE_EXTS.stream().anyMatch(name::endsWith)) {
                    kind = BlockKind.INCOMPLETE;
                } else {
                    return; // .pnd.json, .mf, or anything else
                }
                final long blockNumber;
                try {
                    // Every content kind is named "<block number>.<kind>[.<compression>]".
                    blockNumber = Long.parseLong(name.substring(0, name.indexOf('.')));
                } catch (final NumberFormatException e) {
                    return;
                }
                final Path sidecar = (kind == BlockKind.PENDING) ? BlockFileUtils.pendingProofPathFor(path) : null;
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
        if (unreadableBlockNumbers.contains(block.blockNumber())) {
            return OptionalLong.empty();
        }
        final OptionalLong firstRound = firstRoundOf(block.contents(), maxReadDepth, maxReadSize);
        if (firstRound.isPresent()) {
            firstRoundByBlockNumber.put(block.blockNumber(), firstRound.getAsLong());
        } else {
            unreadableBlockNumbers.add(block.blockNumber());
        }
        return firstRound;
    }

    /**
     * Parses {@code contents} into raw per-item {@link BlockBytes} (wire-identical to a {@code Block}, as in
     * {@code IssBufferBlockReader}) rather than the full {@code Block} object graph, so callers can deserialize items
     * one at a time. The compressed file is streamed straight in (no intermediate decompressed {@code byte[]}), bounded
     * by {@code maxReadSize}; {@link BlockFileUtils#openInputStream} picks the decompressor from the file suffix and
     * closes the file if the decompressor cannot be built on an empty/truncated/corrupt file. Empty if the file cannot
     * be read or parsed.
     */
    private static Optional<BlockBytes> parseBlockBytes(
            @NonNull final Path contents, final int maxReadDepth, final int maxReadSize) {
        try (final var blockIn = BlockFileUtils.openInputStream(contents);
                final ReadableStreamingData in = new ReadableStreamingData(blockIn)) {
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
