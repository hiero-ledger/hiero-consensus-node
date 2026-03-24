// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.hapi.node.state.history.WrapsPhase.AGGREGATE;
import static com.hedera.hapi.node.state.history.WrapsPhase.POST_AGGREGATION;
import static com.hedera.hapi.node.state.history.WrapsPhase.R1;
import static com.hedera.hapi.node.state.history.WrapsPhase.R2;
import static com.hedera.hapi.node.state.history.WrapsPhase.R3;
import static com.hedera.node.app.service.roster.impl.RosterTransitionWeights.moreThanHalfOfTotal;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.history.WrapsPhase;
import com.hedera.node.app.history.ReadableHistoryStore.WrapsMessagePublication;
import com.hedera.node.app.service.roster.impl.RosterTransitionWeights;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * State machine logic for the multi-party computation (MPC) phases of the WRAPS protocol.
 */
@Singleton
public class WrapsMpcStateMachine {
    public static final Set<WrapsPhase> POST_MPC_PHASES = Set.of(AGGREGATE, POST_AGGREGATION);

    @Inject
    public WrapsMpcStateMachine() {
        // Dagger2
    }

    /**
     * Represents a transition of the WRAPS signing state machine; may be a no-op transition, and always is
     * if the publication triggering the transition was not accepted (e.g., because it was for the wrong phase
     * or was a duplicate).
     * @param publicationAccepted whether the publication triggering the transition was accepted
     * @param newCurrentPhase the new current phase after the transition
     * @param gracePeriodEndTimeUpdate the new grace period end time after the transition, if applicable
     */
    public record Transition(
            boolean publicationAccepted,
            @NonNull WrapsPhase newCurrentPhase,
            @Nullable Instant gracePeriodEndTimeUpdate) {
        public Transition {
            requireNonNull(newCurrentPhase);
        }

        public static Transition rejectedAt(@NonNull final WrapsPhase currentPhase) {
            return new Transition(false, currentPhase, null);
        }

        public static Transition incorporatedIn(@NonNull final WrapsPhase currentPhase) {
            return new Transition(true, currentPhase, null);
        }

        public static Transition advanceTo(
                @NonNull final WrapsPhase currentPhase, @Nullable final Instant gracePeriodEndTimeUpdate) {
            return new Transition(true, currentPhase, gracePeriodEndTimeUpdate);
        }
    }

    /**
     * Computes the next state of the WRAPS signing state machine given the current state and a new publication.
     * <p>
     * <b>Important:</b> On acceptance, has the side effect of adding the publication to the phase messages map.
     * @param publication the new publication
     * @param currentPhase the current phase
     * @param weights the weights for parties in the MPC
     * @param gracePeriod the grace period for each phase of the protocol
     * @param phaseMessages the map of phase messages published so far
     * @return the transition
     */
    public Transition onNext(
            @NonNull final WrapsMessagePublication publication,
            @NonNull final WrapsPhase currentPhase,
            @NonNull final RosterTransitionWeights weights,
            @NonNull final Duration gracePeriod,
            @NonNull final Map<WrapsPhase, SortedMap<Long, WrapsMessagePublication>> phaseMessages) {
        requireNonNull(publication);
        requireNonNull(currentPhase);
        requireNonNull(weights);
        requireNonNull(gracePeriod);
        requireNonNull(phaseMessages);
        // The final phase involves publishing votes, not messages, so abort
        if (currentPhase == AGGREGATE) {
            return Transition.rejectedAt(AGGREGATE);
        }
        // Otherwise the phase should match the current phase
        if (publication.phase() != currentPhase) {
            return Transition.rejectedAt(currentPhase);
        }
        final var messages = phaseMessages.computeIfAbsent(currentPhase, p -> new TreeMap<>());
        if (currentPhase == R1) {
            if (messages.putIfAbsent(publication.nodeId(), publication) != null) {
                return Transition.rejectedAt(currentPhase);
            }
            final long r1Weight = messages.values().stream()
                    .mapToLong(p -> weights.sourceWeightOf(p.nodeId()))
                    .sum();
            if (r1Weight >= moreThanHalfOfTotal(weights.sourceNodeWeights())) {
                return Transition.advanceTo(R2, publication.receiptTime().plus(gracePeriod));
            }
        } else {
            final var r1Nodes = phaseMessages.get(R1).keySet();
            if (!r1Nodes.contains(publication.nodeId())) {
                return Transition.rejectedAt(currentPhase);
            }
            if (messages.putIfAbsent(publication.nodeId(), publication) != null) {
                return Transition.rejectedAt(currentPhase);
            }
            if (messages.keySet().containsAll(r1Nodes)) {
                final var nextPhase = currentPhase == R2 ? R3 : AGGREGATE;
                final var nextGracePeriodEndTime =
                        currentPhase == R2 ? publication.receiptTime().plus(gracePeriod) : null;
                return Transition.advanceTo(nextPhase, nextGracePeriodEndTime);
            }
        }
        return Transition.incorporatedIn(currentPhase);
    }
}
