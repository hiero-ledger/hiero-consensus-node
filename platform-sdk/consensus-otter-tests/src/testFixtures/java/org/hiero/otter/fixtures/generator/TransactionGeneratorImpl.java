// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.generator;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.TransactionGenerator;

/**
 * Implementation of the {@link TransactionGenerator} interface.
 */
public class TransactionGeneratorImpl implements TransactionGenerator {

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
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
