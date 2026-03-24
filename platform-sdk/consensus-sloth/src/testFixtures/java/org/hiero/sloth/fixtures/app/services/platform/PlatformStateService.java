// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.app.services.platform;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static org.hiero.consensus.platformstate.PlatformStateUtils.isInFreezePeriod;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.platformstate.WritablePlatformStateStore;
import org.hiero.sloth.fixtures.app.SlothService;
import org.hiero.sloth.fixtures.app.state.BenchmarkServiceStateSpecification;
import org.hiero.sloth.fixtures.network.transactions.SlothTransaction;

/**
 * The main entry point for the PlatformState service in the sloth application.
 */
public class PlatformStateService implements SlothService {

    private static final Logger log = LogManager.getLogger();

    private static final String NAME = "PlatformStateService";

    private static final PlatformStateSpecification STATE_SPECIFICATION = new PlatformStateSpecification();

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final InitTrigger trigger,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration,
            @NonNull final VirtualMapState state) {
        log.info(STARTUP.getMarker(), "PlatformStateService initialized");
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleTransaction(
            @NonNull final WritableStates writableStates,
            @NonNull final ConsensusEvent event,
            @NonNull final SlothTransaction transaction,
            @NonNull final Instant transactionTimestamp,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        switch (transaction.getDataCase()) {
            case EMPTYTRANSACTION, DATA_NOT_SET -> {
                // No action needed for empty transactions
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRoundComplete(@NonNull final WritableStates writableStates, @NonNull final Round round) {
        final WritablePlatformStateStore store = new WritablePlatformStateStore(writableStates);

        // Update the latest freeze round after everything is handled.
        // The platform sets the latestFreezeTime, but not the freeze round :(
        if (isInFreezePeriod(round.getConsensusTimestamp(), store.getFreezeTime(), store.getLastFrozenTime())) {
            // If this is a freeze round, we need to update the freeze info state
            store.setLatestFreezeRound(round.getRoundNum());
        }
    }
}
