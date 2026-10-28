// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.flicker;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
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
    private final List<AddMark> adds = new ArrayList<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void accept(@NonNull final ChangeFactory factory) {
        changes.add(factory.create(changes.size()));
    }

    /**
     * Mark the start of the work one {@code add} triggers. Called by {@link FlickerIntake} before it hands the event
     * to the algorithm, which is what makes {@link #during} able to say where a pass began.
     *
     * @param name the name of the event being added
     */
    void beginAdd(@NonNull final String name) {
        adds.add(new AddMark(name, changes.size()));
    }

    /**
     * The changes the algorithm made while processing one {@code add}.
     * <p>
     * A whole-run filter stops working at the size real fixtures reach: a four-node ladder produces 76 records, 44 of
     * them from a single {@code recalculateAndVote} pass, and SCN-001 produces several hundred. Worse, it answers a
     * different question. "Somewhere in this run, {@code c3} was cleared" is weaker than "the pass triggered by
     * adding {@code d7} cleared {@code c3}", and the weaker claim passes on runs the stronger one catches.
     *
     * @param name the name given to the {@code add} to scope to
     * @return the changes recorded during that add
     * @throws IllegalArgumentException if no add was made under that name, or more than one was
     */
    @NonNull
    public Scope during(@NonNull final String name) {
        final List<AddMark> matching =
                adds.stream().filter(mark -> mark.name().equals(name)).toList();
        if (matching.size() != 1) {
            throw new IllegalArgumentException(
                    matching.isEmpty()
                            ? "no event named '%s' was added to this harness; it added %s"
                                    .formatted(
                                            name,
                                            adds.stream().map(AddMark::name).toList())
                            : "'%s' was added %d times, so 'during' is ambiguous".formatted(name, matching.size()));
        }
        final AddMark mark = matching.getFirst();
        final int index = adds.indexOf(mark);
        final int end = index + 1 < adds.size() ? adds.get(index + 1).start() : changes.size();
        return new Scope(name, List.copyOf(changes.subList(mark.start(), end)));
    }

    /**
     * @return every change, in the order the algorithm made it
     */
    @NonNull
    public List<Change> changes() {
        return Collections.unmodifiableList(changes);
    }

    /**
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
     * nothing in the record itself distinguishes a re-mark from the original. The sequence number is what makes
     * "first" mean anything.
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
        return changes.stream()
                .filter(change -> type == null || type.isInstance(change))
                .map(ConsensusTraceLog::renderOne)
                .collect(Collectors.joining("\n"));
    }

    /**
     * The changes from one {@code add}, offering the same reads as the whole log so that an assertion narrows by
     * adding a scope rather than by being rewritten.
     *
     * @param addName the event whose add this covers
     * @param changes the changes recorded during it
     */
    public record Scope(@NonNull String addName, @NonNull List<Change> changes) {

        /**
         * @param type the change type to select
         * @param <T>  the change type
         * @return every change of that type in this scope, in order
         */
        @NonNull
        public <T extends Change> Stream<T> changes(@NonNull final Class<T> type) {
            return changes.stream().filter(type::isInstance).map(type::cast);
        }

        /**
         * @param type the change type to render, or null for all of them
         * @return one line per change in this scope
         */
        @NonNull
        public String render(@Nullable final Class<? extends Change> type) {
            return changes.stream()
                    .filter(change -> type == null || type.isInstance(change))
                    .map(ConsensusTraceLog::renderOne)
                    .collect(Collectors.joining("\n"));
        }
    }

    /** Where in the log the work triggered by one {@code add} began. */
    private record AddMark(@NonNull String name, int start) {}

    @NonNull
    private static String renderOne(@NonNull final Change change) {
        return switch (change) {
            case RoundCreatedSet roundCreatedSet ->
                "%4d  round    %-4s %s -> %s"
                        .formatted(
                                roundCreatedSet.seq(),
                                roundCreatedSet.name(),
                                round(roundCreatedSet.from()),
                                round(roundCreatedSet.to()));
            case MetadataCleared metadataCleared ->
                "%4d  cleared  %-4s round=%s%s%s"
                        .formatted(
                                metadataCleared.seq(),
                                metadataCleared.name(),
                                round(metadataCleared.roundCreated()),
                                metadataCleared.wasWitness() ? " wasWitness" : "",
                                metadataCleared.wasJudge() ? " wasJudge" : "");
            case WitnessFound witnessFound ->
                "%4d  witness  %-4s round=%d"
                        .formatted(witnessFound.seq(), witnessFound.name(), witnessFound.roundCreated());
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
