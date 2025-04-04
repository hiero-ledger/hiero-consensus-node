// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.validator;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.Validator;

/**
 * Implementation of the {@link Validator} interface.
 */
public class ValidatorImpl implements Validator {
    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator assertLogErrors(@NonNull final LogErrorConfig... configs) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator assertStdOut() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator eventStream(@NonNull final EventStreamConfig... configs) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator reconnectEventStream(@NonNull final Node node) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator validateRemaining(@NonNull final Profile profile) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator consensusRatio(@NonNull final RatioConfig... configs) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Validator staleRatio(@NonNull final RatioConfig... configs) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
