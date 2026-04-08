// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.app.services.roster;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.sloth.fixtures.app.SlothService;
import org.hiero.sloth.fixtures.app.state.BenchmarkServiceStateSpecification;

/**
 * The main entry point for the Roster service in the sloth application.
 */
public class RosterService implements SlothService {

    private static final Logger log = LogManager.getLogger();

    /** The name of the service. */
    public static final String NAME = "RosterService";

    private static final RosterStateSpecification STATE_SPECIFICATION = new RosterStateSpecification();

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final InitTrigger trigger,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final VirtualMapState state) {
        log.info(STARTUP.getMarker(), "RosterService initialized");
    }

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
    public BenchmarkServiceStateSpecification stateSpecification() {
        return STATE_SPECIFICATION;
    }
}
