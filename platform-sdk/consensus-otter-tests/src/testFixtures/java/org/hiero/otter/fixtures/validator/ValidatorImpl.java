// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.Validator;

/**
 * Implementation of the {@link Validator} interface.
 */
public class ValidatorImpl implements Validator {

    private static final Logger log = Loggers.getLogger(ValidatorImpl.class);

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator assertLogErrors(@NonNull final LogErrorConfig... configs) {
        log.warn("log error validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator assertStdOut() {
        log.warn("stdout validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator eventStream(@NonNull final EventStreamConfig... configs) {
        log.warn("event stream validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator reconnectEventStream(@NonNull final Node node) {
        log.warn("reconnect event stream validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator validateRemaining(@NonNull final Profile profile) {
        log.warn("remaining validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator consensusRatio(@NonNull final RatioConfig... configs) {
        log.warn("consensus ratio validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator staleRatio(@NonNull final RatioConfig... configs) {
        log.warn("stale ratio validation is not implemented yet.");
        return this;
    }
}
