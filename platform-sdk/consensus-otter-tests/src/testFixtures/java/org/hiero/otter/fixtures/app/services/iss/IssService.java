// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.iss;

import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.scratchpad.Scratchpad;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.otter.fixtures.app.HashPartition;
import org.hiero.otter.fixtures.app.OtterAppState;
import org.hiero.otter.fixtures.app.OtterIssTransaction;
import org.hiero.otter.fixtures.app.OtterService;
import org.hiero.otter.fixtures.app.OtterTransaction;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;

/**
 * A service that can trigger ISSes based on transactions it receives.
 */
public class IssService implements OtterService {

    private static final Logger log = LogManager.getLogger();

    private static final IssStateSpecification STATE_SPECIFICATION = new IssStateSpecification();

    /** The name of this service. */
    public static final String NAME = "IssService";

    /** The id of this node. */
    private NodeId selfId;

    /** A local record of recoverable ISSes triggered */
    private Scratchpad<IssServiceScratchpad> scratchPad;

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(
            @NonNull final InitTrigger trigger,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration,
            @NonNull final OtterAppState state) {
        this.selfId = selfId;
        this.scratchPad = Scratchpad.create(configuration, selfId, IssServiceScratchpad.class, NAME);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleTransaction(
            @NonNull final WritableStates writableStates,
            @NonNull final ConsensusEvent event,
            @NonNull final OtterTransaction transaction,
            @NonNull final Instant transactionTimestamp,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {

        if (!transaction.hasIssTransaction()) {
            return;
        }

        final OtterIssTransaction issTransaction = transaction.getIssTransaction();

        for (int i = 0; i < issTransaction.getPartitionCount(); i++) {
            final HashPartition partition = issTransaction.getPartition(i);

            /*
             * If this node is in the partition, trigger an ISS by increasing
             * the ISS state value for this partition by the index of the
             * partition + 1 to account for the zeroth index. All nodes in
             * a partition will apply the same value and will disagree on
             * the value with all other nodes.
             */
            if (partition.getNodeIdList().contains(selfId.id()) && !previouslyTriggered(transactionTimestamp)) {
                log.info(
                        "Triggering ISS - selfId: {}, partition index: {}, partition nodes: {}",
                        selfId.id(),
                        i,
                        partition.getNodeIdList());
                final WritableIssStateStore store = new WritableIssStateStore(writableStates);
                store.setStateValue(i + 1);

                // Record the consensus time at which this ISS was provoked
                scratchPad.set(
                        IssServiceScratchpad.PROVOKED_ISS, new SerializableLong(transactionTimestamp.toEpochMilli()));
            }
        }
    }

    private boolean previouslyTriggered(@NonNull final Instant timestamp) {
        final SerializableLong issLong = scratchPad.get(IssServiceScratchpad.PROVOKED_ISS);
        if (issLong != null) {
            final Instant lastProvokedIssTime = Instant.ofEpochMilli(issLong.getValue());
            if (lastProvokedIssTime.equals(timestamp)) {
                log.info(
                        STARTUP.getMarker(),
                        "Restart recoverable ISS at {} was triggered previously - ignoring.",
                        timestamp);
            }
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public OtterServiceStateSpecification stateSpecification() {
        return STATE_SPECIFICATION;
    }
}
