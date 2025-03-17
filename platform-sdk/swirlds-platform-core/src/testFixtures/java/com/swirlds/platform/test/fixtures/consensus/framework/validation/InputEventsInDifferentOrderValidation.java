// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static com.swirlds.platform.test.fixtures.consensus.framework.validation.InputEventsValidation.assertBaseEventLists;

import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validate that the events are added in a different order
 */
public class InputEventsInDifferentOrderValidation implements ConsensusOutputValidation {

    /**
     * Validate that the base events contained in ConsensusOutput are added in a different order
     */
    @Override
    public void validate(@NonNull ConsensusOutput output1, @NonNull ConsensusOutput output2) {
        assertBaseEventLists(
                "Verifying input events are not equal", output1.getAddedEvents(), output2.getAddedEvents(), false);
    }
}
