// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.hiero.consensus.model.event.EventDescriptorWrapper;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Detects "stale" parents while the {@link BlocksToPcesWorkflow} writer streams reconstructed events, and writes
 * their hashes to a sidecar file ({@value #SIDECAR_FILE_NAME}) in the PCES output directory.
 *
 * <p>A stale parent is an event referenced as a cross-block parent (an {@code EVENT_DESCRIPTOR} reference carrying
 * the parent's hash) by some event we write, but that is itself never produced by reconstruction — i.e. it never
 * reached consensus and is absent from the block stream. During replay of the generated PCES such a parent can never
 * arrive; the sidecar lets {@code DefaultOrphanBuffer} treat it as permanently absent so its children are released
 * instead of deadlocking the orphan buffer.
 *
 * <p><b>Bounded-memory design (relies on block-order writing).</b> The writer emits events in block (stream) order,
 * which is parent-before-child by construction (INDEX references resolve within a block; EVENT_DESCRIPTOR references
 * resolve to an earlier block). Therefore, at the moment we write a child and examine its cross-block parent
 * references, a real parent has <i>already been written</i>. This tracker keeps a sliding window of the most recently
 * written event hashes ({@link #retainedEvents} of them, in write order); a cross-block reference whose hash is not
 * in that window points to an event that was never produced — it is stale (or, if its birth round precedes
 * {@code windowStart}, a pre-window boundary artifact covered by the replay origin state, excluded from the sidecar).
 *
 * <p>The window is evicted by <b>stream position</b> (oldest-written first), not by birth round, because birth rounds
 * are non-monotonic in the stream (consensus order is not birth-round order) — a straggler child with a low birth
 * round can appear long after the frontier has advanced, and a birth-round-relative window could evict its real
 * parent prematurely and misclassify it as stale. {@link #retainedEvents} must exceed the maximum number of events
 * that can separate a parent from its referencing child in stream order; a referenced parent is non-ancient relative
 * to its child (within {@code roundsNonAncient} birth rounds), which spans far fewer events than the generously-sized
 * window {@link BlocksToPcesWorkflow} passes.
 *
 * <p>Not thread-safe: all methods are called from the single writer thread.
 */
final class StaleParentTracker {

    /** Sidecar file name, written into the PCES output directory next to the .pces files. */
    static final String SIDECAR_FILE_NAME = "stale-parents.txt";

    /** Context retained for a detected stale/boundary reference, for the sidecar and for reporting. */
    private record RefInfo(long parentCreator, long parentBirthRound, long childCreator, long childBirthRound) {}

    private final long windowStart;
    private final int retainedEvents;

    /** Hashes of recently written events (membership test). Mirrors {@link #producedOrder}. */
    private final Set<HashKey> producedSet;

    /** Write-order queue of recently written event hashes, for oldest-first eviction. */
    private final ArrayDeque<HashKey> producedOrder;

    /** Confirmed genuine (in-window) stale parents, deduplicated by hash, collected for the sidecar. */
    private final Map<HashKey, RefInfo> stale = new HashMap<>();

    /** Pre-window boundary references (excluded from the sidecar), deduplicated by hash; kept for a summary. */
    private final Map<HashKey, RefInfo> boundary = new HashMap<>();

    StaleParentTracker(final long windowStart, final int retainedEvents) {
        if (retainedEvents <= 0) {
            throw new IllegalArgumentException("retainedEvents must be positive: " + retainedEvents);
        }
        this.windowStart = windowStart;
        this.retainedEvents = retainedEvents;
        this.producedSet = new HashSet<>(Math.min(retainedEvents, 1 << 20));
        this.producedOrder = new ArrayDeque<>(Math.min(retainedEvents, 1 << 20));
    }

    /**
     * Record an event as it is written to PCES, in ascending birth-round order. Registers the event's own hash in
     * the recently-produced window, classifies each of its cross-block parent references (real / stale / boundary),
     * and evicts births that have fallen out of the horizon window.
     *
     * @param event the event being written
     */
    void onEventWritten(@NonNull final PlatformEvent event) {
        final long childBirthRound = event.getBirthRound();
        final long childCreator = event.getCreatorId().id();

        // Classify each cross-block parent reference. Because a real parent has already been written (earlier block),
        // it is in the sliding window; absence means it is unproducible.
        for (final EventDescriptorWrapper parent : event.getAllParents()) {
            final HashKey ph = new HashKey(parent.hash().getBytes().toByteArray());
            if (producedSet.contains(ph)) {
                continue; // real parent, already written
            }
            if (stale.containsKey(ph) || boundary.containsKey(ph)) {
                continue; // already classified via another child
            }
            final long pbr = parent.toPbj().birthRound();
            if (pbr >= windowStart) {
                stale.put(ph, new RefInfo(parent.creator().id(), pbr, childCreator, childBirthRound));
            } else {
                boundary.put(ph, new RefInfo(parent.creator().id(), pbr, childCreator, childBirthRound));
            }
        }

        // Add this event's own hash to the sliding window, evicting the oldest if full.
        final HashKey selfHash = new HashKey(event.getHash().getBytes().toByteArray());
        if (producedSet.add(selfHash)) {
            producedOrder.addLast(selfHash);
            if (producedOrder.size() > retainedEvents) {
                final HashKey evicted = producedOrder.pollFirst();
                producedSet.remove(evicted);
            }
        }
    }

    /**
     * Write the sidecar file. Called once after the last event has been written.
     *
     * @param pcesOutputDir the directory to write the sidecar into
     * @return the number of genuine (in-window) stale parents written
     * @throws IOException if the sidecar cannot be written
     */
    long finishAndWriteSidecar(@NonNull final Path pcesOutputDir) throws IOException {
        final Path sidecar = pcesOutputDir.resolve(SIDECAR_FILE_NAME);
        final List<Map.Entry<HashKey, RefInfo>> ordered = new ArrayList<>(stale.entrySet());
        ordered.sort((a, b) -> {
            final int c =
                    Long.compare(a.getValue().parentBirthRound(), b.getValue().parentBirthRound());
            return c != 0
                    ? c
                    : Long.compare(a.getValue().parentCreator(), b.getValue().parentCreator());
        });
        try (final BufferedWriter w = Files.newBufferedWriter(sidecar, StandardCharsets.UTF_8)) {
            for (final Map.Entry<HashKey, RefInfo> e : ordered) {
                w.write(e.getKey().hex());
                w.newLine();
            }
        }
        return stale.size();
    }

    /** @return the number of genuine (in-window) stale parents detected */
    int staleCount() {
        return stale.size();
    }

    /** @return the number of pre-window boundary references excluded from the sidecar */
    int boundaryCount() {
        return boundary.size();
    }

    /** @return a per-creator tally of genuine stale parents, for logging */
    @NonNull
    Map<Long, Integer> staleByCreator() {
        final Map<Long, Integer> m = new TreeMap<>();
        for (final RefInfo ri : stale.values()) {
            m.merge(ri.parentCreator(), 1, Integer::sum);
        }
        return m;
    }

    /** Value-based wrapper around a 48-byte SHA-384 hash for use as a map/set key. */
    private static final class HashKey {
        private final byte[] b;
        private final int hc;

        HashKey(@NonNull final byte[] b) {
            this.b = b;
            this.hc = Arrays.hashCode(b);
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof HashKey k && Arrays.equals(b, k.b);
        }

        @Override
        public int hashCode() {
            return hc;
        }

        @NonNull
        String hex() {
            final StringBuilder sb = new StringBuilder(b.length * 2);
            for (final byte x : b) {
                sb.append(Character.forDigit((x >> 4) & 0xf, 16));
                sb.append(Character.forDigit(x & 0xf, 16));
            }
            return sb.toString();
        }
    }
}
