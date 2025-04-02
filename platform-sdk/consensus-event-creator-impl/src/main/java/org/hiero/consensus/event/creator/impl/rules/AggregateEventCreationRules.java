// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static org.hiero.consensus.event.creator.EventCreationStatus.RATE_LIMITED;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.event.creator.EventCreationRule;
import org.hiero.consensus.event.creator.EventCreationStatus;

/**
 * Combines multiple {@link EventCreationRule} objects into a single object. Allows event creation if all the contained
 * limiters allow event creation.
 */
public class AggregateEventCreationRules implements EventCreationRule {

    private final List<EventCreationRule> rules;
    private EventCreationStatus mostRecentStatus = RATE_LIMITED;

    /**
     * Create a new {@link AggregateEventCreationRules} from the given list of rules.
     *
     * @param rules the rules to combine, if no rules are provided then event creation is always permitted.
     * @return an aggregate rule that permits event creation if and only if all rules permit creation.
     */
    public static AggregateEventCreationRules of(@NonNull final List<EventCreationRule> rules) {
        return new AggregateEventCreationRules(rules);
    }

    /**
     * Constructor.
     *
     * @param rules the limiters to combine
     */
    private AggregateEventCreationRules(@NonNull final List<EventCreationRule> rules) {
        this.rules = Objects.requireNonNull(rules);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEventCreationPermitted() {
        for (final EventCreationRule rule : rules) {
            if (!rule.isEventCreationPermitted()) {
                mostRecentStatus = rule.getEventCreationStatus();
                return false;
            }
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventWasCreated() {
        for (final EventCreationRule rule : rules) {
            rule.eventWasCreated();
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Note: this method only returns the current status if the most recent call to {@link #isEventCreationPermitted()}
     * returned false, otherwise the value returned by this method is undefined.
     */
    @NonNull
    @Override
    public EventCreationStatus getEventCreationStatus() {
        return mostRecentStatus;
    }
}
