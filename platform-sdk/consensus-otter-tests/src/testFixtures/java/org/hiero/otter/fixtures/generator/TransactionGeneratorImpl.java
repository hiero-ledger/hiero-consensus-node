// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.generator;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.time.TimeTickReceiver;

/**
 * Implementation of the {@link TransactionGenerator} interface.
 */
public class TransactionGeneratorImpl implements TransactionGenerator, TimeTickReceiver {

    private static final Logger log = Loggers.getLogger(TransactionGeneratorImpl.class);

    private final TransactionSubmitter submitter;

    /**
     * Constructor for the {@link TransactionGeneratorImpl} class.
     *
     * @param submitter the transaction submitter
     */
    public TransactionGeneratorImpl(@NonNull final TransactionSubmitter submitter) {
        this.submitter = submitter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void generateTransactions(
            final int count, @NonNull final Rate rate, @NonNull final Distribution distribution) {
        log.warn("Transaction generation is not implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        // Not implemented. Logs no warning as this method is called with high frequency.
    }
}
