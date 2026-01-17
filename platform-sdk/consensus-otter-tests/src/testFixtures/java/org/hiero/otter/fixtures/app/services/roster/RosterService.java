// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.roster;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.app.OtterService;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * The main entry point for the Roster service in the Otter application.
 */
public class RosterService implements OtterService {

    /** The name of the service. */
    public static final String NAME = "RosterService";

    private static final RosterStateSpecification STATE_SPECIFICATION = new RosterStateSpecification();

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OtterServiceStateSpecification stateSpecification() {
        return STATE_SPECIFICATION;
    }
}
