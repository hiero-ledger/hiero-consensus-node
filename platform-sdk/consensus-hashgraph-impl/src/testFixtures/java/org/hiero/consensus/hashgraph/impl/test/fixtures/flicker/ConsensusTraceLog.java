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
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.Change.WitnessFound;

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
     * {@inheritDoc}
     */
    @Override
    public void accept(@NonNull final ChangeFactory factory) {
        changes.add(factory.create(changes.size()));
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

    @NonNull
    private static String renderOne(@NonNull final Change change) {
        if (change instanceof WitnessFound witnessFound) {
            return "%4d  witness  %-4s round=%d"
                    .formatted(witnessFound.seq(), witnessFound.name(), witnessFound.roundCreated());
        }
        return "%4d  %s".formatted(change.seq(), change);
    }
}
