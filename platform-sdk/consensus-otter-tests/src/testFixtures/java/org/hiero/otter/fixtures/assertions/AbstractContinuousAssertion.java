// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.assertj.core.api.AbstractAssert;

/**
 * Abstract implementation of {@link ContinuousAssertion} that provides common functionality.
 *
 * @param <SELF>   the type of the concrete assertion class
 * @param <ACTUAL> the type of the actual object being asserted
 */
@SuppressWarnings("java:S119") // java:S119 enforces one letter type names
public abstract class AbstractContinuousAssertion<SELF extends AbstractAssert<SELF, ACTUAL>, ACTUAL>
        extends AbstractAssert<SELF, ACTUAL> implements ContinuousAssertion {

    /**
     * The state of the continuous assertion, which can be either ACTIVE, PAUSED, or DESTROYED.
     */
    protected enum State {
        ACTIVE,
        PAUSED,
        DESTROYED
    }

    // This class may be used in a multi-threaded context, so we use volatile to ensure visibility of state changes
    protected volatile State state = State.ACTIVE;

    /**
     * Constructor of {@link AbstractContinuousAssertion}.
     *
     * @param actual the actual object to assert
     * @param selfType the class type of the concrete assertion class
     */
    public AbstractContinuousAssertion(@Nullable final ACTUAL actual, @NonNull final Class<? extends SELF> selfType) {
        super(actual, selfType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void pause() {
        state = State.PAUSED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resume() {
        state = State.ACTIVE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        state = State.DESTROYED;
    }
}
