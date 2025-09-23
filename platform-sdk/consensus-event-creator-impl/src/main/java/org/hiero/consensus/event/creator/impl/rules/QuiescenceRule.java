// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static org.hiero.consensus.event.creator.impl.EventCreationStatus.QUIESCENCE;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Supplier;
import org.hiero.consensus.event.creator.impl.EventCreationStatus;
import org.hiero.consensus.model.quiescence.QuiescenceStatus;

public class QuiescenceRule implements EventCreationRule {

    private final Supplier<QuiescenceStatus> quiescenceStatusSupplier;

    /**
     * Constructor.
     *
     * @param quiescenceStatusSupplier provides the current quiescence status
     */
    public QuiescenceRule(@NonNull final Supplier<QuiescenceStatus> quiescenceStatusSupplier) {
        this.quiescenceStatusSupplier = Objects.requireNonNull(quiescenceStatusSupplier);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEventCreationPermitted() {
        final QuiescenceStatus currentStatus = quiescenceStatusSupplier.get();

        return currentStatus != QuiescenceStatus.QUIESCING;
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
