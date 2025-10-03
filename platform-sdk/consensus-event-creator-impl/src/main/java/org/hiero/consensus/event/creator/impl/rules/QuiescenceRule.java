// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static org.hiero.consensus.event.creator.impl.EventCreationStatus.QUIESCENCE;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.event.creator.impl.EventCreationStatus;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * Limits the creation of new events if system is in QUIESCE mode
 */
public class QuiescenceRule implements EventCreationRule {

    /**
     * Current QuiescenceCommand of the system
     */
    private QuiescenceCommand quiescenceCommand = QuiescenceCommand.DONT_QUIESCE;

    /**
     * Constructor.
     */
    public QuiescenceRule() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEventCreationPermitted() {

        return quiescenceCommand != QuiescenceCommand.QUIESCE;
    }

    /**
     * Informs rule about current quiescence command
     *
     * @param quiescenceCommand current command to set
     */
    public void quiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {
        this.quiescenceCommand = quiescenceCommand;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void eventWasCreated() {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public EventCreationStatus getEventCreationStatus() {
        return QUIESCENCE;
    }
}
