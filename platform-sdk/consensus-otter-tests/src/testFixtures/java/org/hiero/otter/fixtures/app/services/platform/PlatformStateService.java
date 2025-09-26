// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.platform;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.app.services.OtterService;

/**
 * The main entry point for the PlatformState service in the Otter application.
 */
public class PlatformStateService implements OtterService {

    private static final String NAME = "PlatformStateService";

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
        return new V0540PlatformStateSchema(config -> version);
    }
}
