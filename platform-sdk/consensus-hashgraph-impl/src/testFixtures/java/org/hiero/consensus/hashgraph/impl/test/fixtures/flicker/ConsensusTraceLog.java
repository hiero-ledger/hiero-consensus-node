// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.Change.MetadataCleared;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.Change.RoundCreatedSet;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.Change.WitnessFound;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;

/**
 * An accumulating record of what the consensus algorithm did, in the order it did it.
 * <p>
 * The algorithm's intermediate state is transient: {@code ConsensusImpl#recalculateAndVote} clears and recomputes every
 * non-terminal event on each decided round, so a value can be created and destroyed entirely inside one
 * {@code addEvent} call. This log is written from inside that call, by {@link RecordingEventImpl}, which is what makes
 * those values reachable at all.
 * <p>
 * The log evaluates nothing. It accumulates, and tests read it afterward.
 */
public class ConsensusTraceLog implements ChangeSink {

    private final List<Change> changes = new ArrayList<>();

    /**
     * Constructor. The log starts empty.
     */
    public ConsensusTraceLog() {
        // nothing to initialize
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void accept(@NonNull final Change change) {
        changes.add(change);
    }

    /**
     * Get every change the algorithm made.
     *
     * @return every change, in the order the algorithm made it
     */
    @NonNull
    public List<Change> changes() {
        return Collections.unmodifiableList(changes);
    }

    /**
     * Get the changes of one type.
     *
     * @param type the change type to select
     * @param <T>  the change type
     * @return every change of that type, in order
     */
    @NonNull
    public <T extends Change> Stream<T> changes(@NonNull final Class<T> type) {
        return changes.stream().filter(type::isInstance).map(type::cast);
    }

    /**
     * The first event in the whole run to be marked a witness while sitting in the given round.
     * <p>
     * "First" is global-first, not first-per-event. {@code recalculateAndVote} clears and recalculates every
     * non-terminal event on each decided round, so a genuine witness is marked one again on each subsequent pass and
     * nothing in the record itself distinguishes a re-mark from the original. Only the log's order does.
     *
     * @param roundCreated the round to look in
     * @return the first such change, or empty if no event was ever marked a witness in that round
     */
    @NonNull
    public Optional<WitnessFound> firstWitnessInRound(final long roundCreated) {
        return changes(WitnessFound.class)
                .filter(witnessFound -> witnessFound.roundCreated() == roundCreated)
                .findFirst();
    }

    /**
     * A readable dump of the log, for attaching to an assertion failure.
     *
     * @param type the change type to render, or null for all of them
     * @return one line per change
     */
    @NonNull
    public String render(@Nullable final Class<? extends Change> type) {
        return IntStream.range(0, changes.size())
                .filter(index -> type == null || type.isInstance(changes.get(index)))
                .mapToObj(index -> renderOne(index, changes.get(index)))
                .collect(Collectors.joining("\n"));
    }

    /** Render one change, prefixed by its position in the log. */
    @NonNull
    private static String renderOne(final int index, @NonNull final Change change) {
        return switch (change) {
            case RoundCreatedSet roundCreatedSet ->
                "%4d  round    %-4s %s -> %s"
                        .formatted(
                                index,
                                roundCreatedSet.name(),
                                round(roundCreatedSet.from()),
                                round(roundCreatedSet.to()));
            case MetadataCleared metadataCleared ->
                "%4d  cleared  %-4s round=%s%s%s"
                        .formatted(
                                index,
                                metadataCleared.name(),
                                round(metadataCleared.roundCreated()),
                                metadataCleared.wasWitness() ? " wasWitness" : "",
                                metadataCleared.wasJudge() ? " wasJudge" : "");
            case WitnessFound witnessFound ->
                "%4d  witness  %-4s round=%d".formatted(index, witnessFound.name(), witnessFound.roundCreated());
        };
    }

    /** Render the sentinel rounds by name; the raw values are large negative numbers that obscure the log. */
    @NonNull
    private static String round(final long round) {
        if (round == ConsensusConstants.ROUND_NEGATIVE_INFINITY) {
            return "-inf";
        }
        if (round == ConsensusConstants.ROUND_UNDEFINED) {
            return "undef";
        }
        return String.valueOf(round);
    }
}
