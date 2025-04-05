// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

/**
 * Interface representing an instrumented node.
 *
 * <p>An instrumented node is a node that has additional instrumentation for testing purposes.
 * For example, it can exhibit malicious or erroneous behavior.
 */
public interface InstrumentedNode extends Node {

    /**
     * Set the branching probability for the instrumented node.
     *
     * <p>This method allows you to specify the probability of the node branching when creating an event.
     * The value should be between 0.0 and 1.0, where 0.0 means no branching and 1.0 means certain branching.
     *
     * @param probability the branching probability
     */
    void setBranchingProbability(double probability);
}
