// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.platform.state.service.ReadablePlatformStateStore;

/**
 * Validates a signed state received via reconnect.
 */
public interface SignedStateValidator {

    /**
     * Determines if a signed state is valid with the roster. Validation usually includes verifying that the signed
     * state is signed with a sufficient number of valid signatures to meet a certain weighting threshold, but other
     * requirements could be included as well.
     *
     * @param signedState       the signed state to validate
     * @param roster            the roster used for this signed state
     * @param previousStateData A {@link SignedStateValidationData} containing data from the
     *        {@link ReadablePlatformStateStore} in the state before the signed state to be validated.
     *        This may be used to ensure signed state is usable and valid, and also contains useful information for
     *        diagnostics produced when the signed state is not considered valid.
     * @throws SignedStateInvalidException if the signed state is not valid
     */
    void validate(final SignedState signedState, final Roster roster, final SignedStateValidationData previousStateData)
            throws SignedStateInvalidException;
}
