// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static com.swirlds.platform.test.fixtures.consensus.framework.validation.PlatformTestFixtureValidationUtils.assertBaseEventLists;

import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;

public class OutputEventsAddedInDifferentOrderValidation implements ConsensusOutputValidation {

    /**
     * Validate that the events are added in a different order
     */
    @Override
    public void validate(final ConsensusOutput output1, final ConsensusOutput output2) {
        assertBaseEventLists(
                "Verifying input events are not equal", output1.getAddedEvents(), output2.getAddedEvents(), false);
    }
}
