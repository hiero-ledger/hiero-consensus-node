// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.benchmark;

import com.swirlds.common.merkle.MerkleNode;
import java.util.Random;

/**
 * Boiler plate logic for operations.
 */
public abstract class AbstractBenchmarkOperation<S extends MerkleNode, M extends BenchmarkMetadata>
        implements BenchmarkOperation<S, M> {

    private final double weight;

    private boolean abort;

    public AbstractBenchmarkOperation(final double weight) {
        this.weight = weight;
        abort = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getWeight() {
        return weight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "Operation %s (%.3f)".formatted(getName(), weight);
    }

    /**
     * Causes the operation to be aborted. Should only be called in {@link #prepare(BenchmarkMetadata, Random)}.
     */
    protected void abort() {
        abort = true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldAbort() {
        final boolean shouldAbort = abort;
        abort = false;
        return shouldAbort;
    }
}
