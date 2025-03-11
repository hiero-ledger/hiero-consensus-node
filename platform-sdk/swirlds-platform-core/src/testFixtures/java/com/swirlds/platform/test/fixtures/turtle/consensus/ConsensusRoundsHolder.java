// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.consensus;

import com.swirlds.platform.internal.ConsensusRound;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A test component collecting consensus rounds produced by the ConsensusEngine
 */
public interface ConsensusRoundsHolder {

    /**
     * Intercept the consensus rounds produced by the ConsensusEngine and adds them to a collection.
     *
     * @param rounds
     */
    void interceptRounds(final List<ConsensusRound> rounds);

    /**
     * Clear the internal state of this collector.
     *
     * @param ignored ignored trigger object
     */
    void clear(@NonNull final Object ignored);
}
