// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A parsed {@code .graph} file: the header, and the events in the order they must be fed.
 * <p>
 * The specs are in creation order — parents first — which is also the order of the file's rows, so a row's position
 * in the file is its position in the feed.
 * <p>
 * Membership changes in the rosters are not currently supported. It will be implemented later when a test requires it.
 * Every roster block must list the same nodes, though not necessarily in the same order.
 * <p>
 * The format accepts any number of roster blocks, but there is only one {@code anchor} round, so the two-phase replay
 * it describes uses at most two. Which rosters a replay uses is up to the replay, which looks them up by name; the
 * format does not tie a roster to a phase.
 *
 * @param kb          the knowledge-base entry this fixture reproduces, or null if it names none
 * @param config      consensus configuration keys the fixture needs, as written
 * @param defaults    the per-event fields a row does not override
 * @param anchorRound the round to take a snapshot at for a two-phase replay, or null for a single-phase fixture
 * @param rosters     every roster block, in declaration order
 * @param specs       the events, in creation order
 */
public record FlickerGraph(
        @Nullable String kb,
        @NonNull Map<String, String> config,
        @NonNull GraphDefaults defaults,
        @Nullable Long anchorRound,
        @NonNull List<NamedRoster> rosters,
        @NonNull List<EventSpec> specs) {

    public FlickerGraph {
        config = Collections.unmodifiableMap(new LinkedHashMap<>(requireNonNull(config, "config")));
        requireNonNull(defaults, "defaults must not be null");
        rosters = List.copyOf(requireNonNull(rosters, "rosters must not be null"));
        specs = List.copyOf(requireNonNull(specs, "specs must not be null"));
    }

    /**
     * Returns the roster with the given name.
     *
     * @param name the name a roster block was declared with
     * @return that roster
     * @throws IllegalArgumentException if the file declares no roster by that name
     */
    @NonNull
    public NamedRoster roster(@NonNull final String name) {
        return rosters.stream()
                .filter(roster -> roster.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no roster named '%s'; this graph declares %s"
                        .formatted(name, rosters.stream().map(NamedRoster::name).toList())));
    }

    /**
     * Build this graph's events.
     * <p>
     * No roster is needed: every creator id comes from the letter starting its event's name, so the events, and every
     * hash in them, are the same whichever of the graph's rosters they are later fed under.
     * <p>
     * The {@link Random} is fixed because it no longer reaches any hash — every hashed field is specified — so a seed
     * would be a knob that changes nothing a fixture can observe.
     *
     * @return the events in feed order, each paired with its name
     */
    @NonNull
    public List<NamedEvent> build() {
        return GraphBuilder.build(specs, new Random(0), defaults);
    }

    /**
     * Write this graph back out as {@code .graph} text.
     * <p>
     * The text parses back to an equal graph. Nothing in the suite consumes it yet; it exists so that the round-trip
     * test can detect a field the format cannot carry.
     *
     * @return the file this graph would be written as
     */
    @NonNull
    public String toText() {
        return GraphWriter.write(this);
    }
}
