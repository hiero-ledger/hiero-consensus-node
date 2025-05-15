// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.Validator;

/**
 * Implementation of the {@link Validator} interface.
 */
public class ValidatorImpl implements Validator {

    private static final Logger log = LogManager.getLogger(ValidatorImpl.class);
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

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator assertPlatformStatus(@NonNull PlatformStatusConfig... configs) {
        log.warn("platform status validation is not implemented yet.");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator assertMetrics(@NonNull MetricsConfig... configs) {
        log.warn("metrics validation is not implemented yet.");
        return this;
    }
}
