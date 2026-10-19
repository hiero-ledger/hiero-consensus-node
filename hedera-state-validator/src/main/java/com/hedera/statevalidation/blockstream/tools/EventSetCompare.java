// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream.tools;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.statevalidation.blockstream.BlockStreamEventBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.event.PlatformEvent;

/// Compares the set of consensus events, and the round each one reached consensus in, between two block streams
/// (original vs re-minted) over a round range. Events are reconstructed with {@link BlockStreamEventBuilder} and
/// matched by event hash, which covers EventCore, parents and transactions, so a match means the same event.
///
/// Reports events present in only one stream, events that reached consensus in a different round, and events in
/// the same round but at a different position in consensus order. Used to locate the first point where a replay's
/// consensus diverges from the original (e.g. a judge missing from the replayed hashgraph).
///
/// Events are collected over the requested range widened by {@link #MARGIN_ROUNDS} on each side, so an event that
/// moved just across a range edge is reported as moved rather than as missing. Only events whose round (on either
/// side) falls inside the requested range are reported.
final class EventSetCompare {

    /** Extra rounds collected on each side of the requested range so events that moved across an edge still match. */
    private static final long MARGIN_ROUNDS = 50;

    /** Maximum number of events printed per category. */
    private static final int MAX_PRINTED = 50;

    /**
     * One consensus event as it appears in a block stream.
     *
     * @param hash event hash (hex)
     * @param creator creator node id
     * @param birthRound birth round
     * @param timeCreated creator's timestamp
     * @param round consensus round the event appears in
     * @param position 0-based position of the event within its round's consensus order
     * @param block block number the event appears in
     * @param txCount number of transactions in the event
     */
    private record EventInfo(
            String hash,
            long creator,
            long birthRound,
            Instant timeCreated,
            long round,
            int position,
            long block,
            int txCount) {
        String describe() {
            return String.format(
                    "%s creator=%d birthRound=%d timeCreated=%s txs=%d",
                    hash.substring(0, 16), creator, birthRound, timeCreated, txCount);
        }
    }

    static void run(final Path originalDir, final Path remintedDir, final long fromRound, final long toRound)
            throws IOException {
        final long lo = fromRound - MARGIN_ROUNDS;
        final long hi = toRound + MARGIN_ROUNDS;
        System.out.printf(
                "Comparing consensus events in rounds [%d, %d] (collected over [%d, %d])%n", fromRound, toRound, lo, hi);

        final Map<String, EventInfo> original = collect(originalDir, lo, hi);
        final Map<String, EventInfo> reminted = collect(remintedDir, lo, hi);
        System.out.printf("Original: %d event(s); reminted: %d event(s)%n", original.size(), reminted.size());

        final List<EventInfo> onlyOriginal = new ArrayList<>();
        final List<EventInfo[]> movedRound = new ArrayList<>();
        final List<EventInfo[]> movedPosition = new ArrayList<>();
        for (final EventInfo o : original.values()) {
            final EventInfo r = reminted.get(o.hash());
            if (r == null) {
                if (inRange(o.round(), fromRound, toRound)) {
                    onlyOriginal.add(o);
                }
            } else if (inRange(o.round(), fromRound, toRound) || inRange(r.round(), fromRound, toRound)) {
                if (o.round() != r.round()) {
                    movedRound.add(new EventInfo[] {o, r});
                } else if (o.position() != r.position()) {
                    movedPosition.add(new EventInfo[] {o, r});
                }
            }
        }
        final List<EventInfo> onlyReminted = new ArrayList<>();
        for (final EventInfo r : reminted.values()) {
            if (!original.containsKey(r.hash()) && inRange(r.round(), fromRound, toRound)) {
                onlyReminted.add(r);
            }
        }

        final Comparator<EventInfo> byRound =
                Comparator.comparingLong(EventInfo::round).thenComparingInt(EventInfo::position);
        onlyOriginal.sort(byRound);
        onlyReminted.sort(byRound);
        final Comparator<EventInfo[]> byEarlierRound =
                Comparator.comparingLong(p -> Math.min(p[0].round(), p[1].round()));
        movedRound.sort(byEarlierRound.thenComparingInt(p -> p[0].position()));
        movedPosition.sort(byEarlierRound.thenComparingInt(p -> p[0].position()));

        System.out.printf("%n=== Only in original (%d) ===%n", onlyOriginal.size());
        onlyOriginal.stream()
                .limit(MAX_PRINTED)
                .forEach(e -> System.out.printf(
                        "  round %d pos %d block %d: %s%n", e.round(), e.position(), e.block(), e.describe()));

        System.out.printf("%n=== Only in reminted (%d) ===%n", onlyReminted.size());
        onlyReminted.stream()
                .limit(MAX_PRINTED)
                .forEach(e -> System.out.printf(
                        "  round %d pos %d block %d: %s%n", e.round(), e.position(), e.block(), e.describe()));

        System.out.printf("%n=== Different consensus round (%d) ===%n", movedRound.size());
        movedRound.stream()
                .limit(MAX_PRINTED)
                .forEach(p -> System.out.printf(
                        "  round %d -> %d: %s%n", p[0].round(), p[1].round(), p[0].describe()));

        System.out.printf("%n=== Same round, different consensus order (%d) ===%n", movedPosition.size());
        movedPosition.stream()
                .limit(MAX_PRINTED)
                .forEach(p -> System.out.printf(
                        "  round %d pos %d -> %d: %s%n",
                        p[0].round(), p[0].position(), p[1].position(), p[0].describe()));

        if (onlyOriginal.isEmpty() && onlyReminted.isEmpty() && movedRound.isEmpty() && movedPosition.isEmpty()) {
            System.out.printf("%nNo event differences in rounds [%d, %d].%n", fromRound, toRound);
        }
    }

    private static boolean inRange(final long round, final long fromRound, final long toRound) {
        return round >= fromRound && round <= toRound;
    }

    /** Collects every consensus event whose round is in [lo, hi], keyed by event hash, in stream order. */
    private static Map<String, EventInfo> collect(final Path dir, final long lo, final long hi) throws IOException {
        final Map<Long, Path> byNumber = OutputRecordCompare.indexByBlockNumber(dir);
        final List<Long> numbers = new ArrayList<>(byNumber.keySet());
        final Map<String, EventInfo> events = new LinkedHashMap<>();
        final Map<Long, Integer> nextPositionInRound = new HashMap<>();
        final BlockStreamEventBuilder builder = new BlockStreamEventBuilder();

        for (int i = firstBlockEndingAtOrAfter(numbers, byNumber, lo); i < numbers.size(); i++) {
            final Block block = readBlock(byNumber.get(numbers.get(i)));
            if (block == null) {
                continue;
            }
            // The event builder emits events in stream order; record the round each EVENT_HEADER belongs to so the
            // emitted events can be paired with their rounds by index.
            final List<Long> eventRounds = new ArrayList<>();
            long currentRound = -1;
            long minRound = Long.MAX_VALUE;
            for (final BlockItem item : block.items()) {
                if (item.hasRoundHeader()) {
                    currentRound = item.roundHeader().roundNumber();
                    minRound = Math.min(minRound, currentRound);
                } else if (item.hasEventHeader()) {
                    eventRounds.add(currentRound);
                }
            }
            if (minRound != Long.MAX_VALUE && minRound > hi) {
                break;
            }
            final List<PlatformEvent> built = new ArrayList<>();
            builder.processBlock(block, built::add);
            for (int j = 0; j < built.size(); j++) {
                final long round = eventRounds.get(j);
                if (round < lo || round > hi) {
                    continue;
                }
                final PlatformEvent event = built.get(j);
                final int position = nextPositionInRound.merge(round, 1, Integer::sum) - 1;
                final String hash = event.getHash().toHex();
                events.put(
                        hash,
                        new EventInfo(
                                hash,
                                event.getCreatorId().id(),
                                event.getBirthRound(),
                                event.getTimeCreated(),
                                round,
                                position,
                                numbers.get(i),
                                event.getTransactionCount()));
            }
        }
        return events;
    }

    /** Binary search for the first block (by index into {@code numbers}) whose highest round is at least {@code round}. */
    private static int firstBlockEndingAtOrAfter(
            final List<Long> numbers, final Map<Long, Path> byNumber, final long round) {
        int lo = 0;
        int hi = numbers.size();
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            final long maxRound = maxRound(readBlock(byNumber.get(numbers.get(mid))));
            if (maxRound < round) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static long maxRound(final Block block) {
        long max = -1;
        if (block != null) {
            for (final BlockItem item : block.items()) {
                if (item.hasRoundHeader()) {
                    max = Math.max(max, item.roundHeader().roundNumber());
                }
            }
        }
        return max;
    }

    /** Reads a block, or returns null for an empty (unfinished) block file. */
    private static Block readBlock(final Path path) {
        try {
            return Files.size(path) == 0 ? null : BlockStreamAccess.blockFrom(path);
        } catch (final IOException e) {
            throw new RuntimeException("Failed to read block " + path, e);
        }
    }

    private EventSetCompare() {}
}
