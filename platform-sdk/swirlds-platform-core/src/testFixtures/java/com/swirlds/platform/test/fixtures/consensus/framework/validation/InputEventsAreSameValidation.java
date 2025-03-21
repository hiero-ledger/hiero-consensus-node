// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static com.swirlds.platform.test.fixtures.consensus.framework.validation.InputEventsValidation.assertBaseEventLists;

import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validate that the base events contained in ConsensusOutput are the same
 */
public class InputEventsAreSameValidation implements ConsensusOutputValidation {

    /**
     * Verify that ALL base events fed into consensus are exactly identical this will check only pre-consensus data, for
     * non-consensus events, the consensus data does not have to match
     */
    @Override
    public void validate(@NonNull ConsensusOutput output1, @NonNull ConsensusOutput output2) {
        assertBaseEventLists(
                "Verifying sorted input events are equal",
                output1.sortedAddedEvents(),
                output2.sortedAddedEvents(),
                true);
    }
}
