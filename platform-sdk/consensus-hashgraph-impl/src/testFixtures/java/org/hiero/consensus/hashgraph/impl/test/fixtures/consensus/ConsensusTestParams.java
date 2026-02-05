// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.consensus;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.test.fixtures.WeightGenerator;

public record ConsensusTestParams(
        int numNodes,
        @NonNull WeightGenerator weightGenerator,
        @NonNull String weightDesc,
        long... seeds) {
    @Override
    public String toString() {
        return numNodes + " nodes, " + weightDesc;
    }
}
