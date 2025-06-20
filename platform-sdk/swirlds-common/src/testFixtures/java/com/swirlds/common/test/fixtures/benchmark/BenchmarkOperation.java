// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.benchmark;

import com.swirlds.common.merkle.MerkleNode;
import java.util.Random;

/**
 * An operation type that is performed within a benchmark.
 *
 * @param <S>
 * 		the type of the merkle node state
 * @param <M>
 * 		the type of the benchmark metadata
 */
public interface BenchmarkOperation<S extends MerkleNode, M extends BenchmarkMetadata> {

    /**
     * Get the name of this operation. Must be unique with respect to all other operations in a benchmark.
     */
    String getName();

    /**
     * Get the relative weight of this operations. Operations with a higher weight will be chosen more often.
     */
    double getWeight();

    /**
     * Perform work that shouldn't be included in the temporal measurement.
     *
     * <p>
     * Each operation is only run on a single thread, so it is safe to store data in class member variables when
     * this method is called.
     *
     * <p>
     * An example use case would be to look up random keys to be used in the transaction. In a regular system the
     * transaction would already contain those keys, but in the benchmark there may be some overhead when looking
     * them up.
     */
    void prepare(M metadata, Random random);

    /**
     * If a problem is detected in the prepare phase (e.g. no keys of a type available) then it can make this method
     * return true. If this method returns true the operation will be canceled without effecting statistics.
     *
     * This method is not a getter. This method has the side effect of resetting the "should abort" status.
     * This is needed because the operation object is reused over and over.
     */
    default boolean shouldAbort() {
        return false;
    }

    /**
     * Execute a single operation against the state. This operation will be timed.
     *
     * @param state
     * 		the merkle state against which the operation is performed
     */
    void execute(S state);

    /**
     * Get a copy of this operation. If this method is called at all, it will always be called before this object
     * begins to handle operations.
     */
    BenchmarkOperation<S, M> copy();
}
