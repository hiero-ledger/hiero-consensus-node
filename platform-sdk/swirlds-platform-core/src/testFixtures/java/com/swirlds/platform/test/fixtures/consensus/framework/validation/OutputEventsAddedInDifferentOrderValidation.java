// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus.framework.validation;

import static com.swirlds.platform.test.fixtures.consensus.framework.validation.TestFixtureValidationUtils.assertBaseEventLists;

import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;

/**
 * Validate that the events added to different nodes were added in different orders.
 */
public class OutputEventsAddedInDifferentOrderValidation implements ConsensusOutputValidation {

    /**
     * Validate that the base events from {@link ConsensusOutput} are added in a different order.
     *
     * @param output1 the output from one node
     * @param output2 the output from another node
     */
    @Override
    public void validate(final ConsensusOutput output1, final ConsensusOutput output2) {
        assertBaseEventLists(
                "Verifying input events are not equal", output1.getAddedEvents(), output2.getAddedEvents(), false);
    }
}
