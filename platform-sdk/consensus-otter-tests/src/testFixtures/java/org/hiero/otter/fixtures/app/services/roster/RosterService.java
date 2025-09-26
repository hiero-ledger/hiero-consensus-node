// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.roster;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.app.OtterService;

/**
 * The main entry point for the Roster service in the Otter application.
 */
public class RosterService implements OtterService {

    /** The name of the service. */
    public static final String NAME = "RosterService";

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
    public Schema genesisSchema(@NonNull final SemanticVersion version) {
        return new V0540RosterBaseSchema();
    }
}
