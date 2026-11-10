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
    private final List<AddMark> adds = new ArrayList<>();

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
     * Mark the start of a {@link FlickerIntake#add} call, so {@link #during} can find the changes that call caused.
     * <p>
     * Called at the top of {@code add}, not just around {@code ConsensusImpl#addEvent}, for two reasons. First, not
     * every change comes from {@code addEvent}: when a round decides, {@code add} advances the linker's event window,
     * and the linker clears every event that became ancient, producing {@code MetadataCleared} changes. Second, an
     * event that {@code add} rejects before it reaches consensus still leaves its mark, so {@link #during} returns an
     * empty scope for it rather than reporting that no event of that name was added.
     *
     * @param name the name of the event being added
     */
    void beginAdd(@NonNull final String name) {
        adds.add(new AddMark(name, changes.size()));
    }

    /**
     * The changes made while {@link FlickerIntake#add} was adding the named event: everything {@code ConsensusImpl}
     * did in response to that one event, including any {@code recalculateAndVote} it triggered.
     * <p>
     * Allows callers to scope their assertions to a single event, rather than having to search the whole log for the changes that event caused
     *
     * @param name the name the event was added under
     * @return the changes recorded while that event was being added
     * @throws IllegalArgumentException if no event was added under that name, or more than one was
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
        return new Scope(name, mark.start(), List.copyOf(changes.subList(mark.start(), end)));
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
     * non-terminal event on each decided round, so a genuine witness is marked one again on each later recalculation and
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
        return render(changes, 0, type);
    }

    /**
     * The changes made while one event was being added, as returned by {@link #during}. Offers the same reads as the
     * whole log, so narrowing an assertion to one event means adding {@code during(name)}, not rewriting it.
     *
     * @param addName the name the event was added under
     * @param start   the position in the whole log of this scope's first change, so a rendered scope numbers its
     *                lines the same way the whole log does
     * @param changes the changes recorded while the event was being added
     */
    public record Scope(
            @NonNull String addName, int start, @NonNull List<Change> changes) {

        /**
         * Get the changes of one type in this scope.
         *
         * @param type the change type to select
         * @param <T>  the change type
         * @return every change of that type in this scope, in order
         */
        @NonNull
        public <T extends Change> Stream<T> changes(@NonNull final Class<T> type) {
            return changes.stream().filter(type::isInstance).map(type::cast);
        }

        /**
         * A readable dump of this scope, numbered by position in the whole log.
         *
         * @param type the change type to render, or null for all of them
         * @return one line per change in this scope
         */
        @NonNull
        public String render(@Nullable final Class<? extends Change> type) {
            return ConsensusTraceLog.render(changes, start, type);
        }
    }

    /** The position in the log where the changes from adding the named event begin. */
    private record AddMark(@NonNull String name, int start) {}

    /** Render a run of changes, numbering each by its position in the whole log. */
    @NonNull
    private static String render(
            @NonNull final List<Change> changes, final int start, @Nullable final Class<? extends Change> type) {
        return IntStream.range(0, changes.size())
                .filter(index -> type == null || type.isInstance(changes.get(index)))
                .mapToObj(index -> renderOne(start + index, changes.get(index)))
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
