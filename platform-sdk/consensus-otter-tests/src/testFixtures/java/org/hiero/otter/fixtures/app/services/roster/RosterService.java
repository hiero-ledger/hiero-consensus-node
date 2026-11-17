// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.roster;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.RosterStateId;
import org.hiero.consensus.roster.WritableRosterStore;
import org.hiero.otter.fixtures.app.OtterService;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * The main entry point for the Roster service in the Otter application.
 */
public class RosterService implements OtterService {

    private static final Logger log = LogManager.getLogger();

    /** The name of the service. */
    public static final String NAME = RosterStateId.SERVICE_NAME;

    private static final RosterStateSpecification STATE_SPECIFICATION = new RosterStateSpecification();

    private final Roster genesisRoster;

    /**
     * Create the service.
     *
     * @param genesisRoster the roster to write to the state in the first round, if the state does not contain a roster
     * yet
     */
    public RosterService(@NonNull final Roster genesisRoster) {
        this.genesisRoster = requireNonNull(genesisRoster);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final InitTrigger trigger,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration,
            @NonNull final FileSystemManager fileSystemManager,
            @NonNull final VirtualMapState state) {
        log.info(STARTUP.getMarker(), "RosterService initialized");
    }

    /**
     * {@inheritDoc}
     *
     * <p>If the state does not contain a roster yet, i.e. this is the first round after genesis, the genesis roster
     * is written to the state. This mirrors the genesis setup of the roster service in production.
     */
    @Override
    public void onRoundStart(@NonNull final WritableStates writableStates, @NonNull final Round round) {
        if (writableStates.getSingleton(RosterStateId.ROSTER_STATE_STATE_ID).get() == null) {
            new WritableRosterStore(writableStates).putActiveRoster(genesisRoster, 0L);
        }
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
    public OtterServiceStateSpecification stateSpecification() {
        return STATE_SPECIFICATION;
    }
}
