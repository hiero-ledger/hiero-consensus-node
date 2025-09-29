// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static org.hiero.consensus.event.creator.impl.EventCreationStatus.QUIESCENCE;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Supplier;
import org.hiero.consensus.event.creator.impl.EventCreationStatus;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

public class QuiescenceRule implements EventCreationRule {

    private final Supplier<QuiescenceCommand> quiescenceCommandSupplier;

    /**
     * Constructor.
     *
     * @param quiescenceCommandSupplier provides the current quiescence status
     */
    public QuiescenceRule(@NonNull final Supplier<QuiescenceCommand> quiescenceCommandSupplier) {
        this.quiescenceCommandSupplier = Objects.requireNonNull(quiescenceCommandSupplier);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEventCreationPermitted() {
        final QuiescenceCommand currentStatus = quiescenceCommandSupplier.get();

        return currentStatus != QuiescenceCommand.QUIESCE;
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
