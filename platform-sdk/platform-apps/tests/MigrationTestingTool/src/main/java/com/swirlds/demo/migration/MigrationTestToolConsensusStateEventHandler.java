// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import static com.swirlds.demo.migration.MigrationTestingToolMain.PREVIOUS_SOFTWARE_VERSION;
import static com.swirlds.demo.migration.TransactionUtils.isSystemTransaction;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.lifecycle.HapiUtils;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * This class handles the lifecycle events for the {@link MigrationTestingToolState}.
 */
public class MigrationTestToolConsensusStateEventHandler
        implements ConsensusStateEventHandler<MigrationTestingToolState> {
    private static final Logger logger = LogManager.getLogger(MigrationTestToolConsensusStateEventHandler.class);

    public NodeId selfId;

    @Override
    public void onStateInitialized(
            @NonNull final MigrationTestingToolState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        final MerkleMap<AccountID, MapValue> merkleMap = state.getMerkleMap();
        if (merkleMap != null) {
            logger.info(STARTUP.getMarker(), "MerkleMap initialized with {} values", merkleMap.size());
        }
        final VirtualMap<?, ?> virtualMap = state.getVirtualMap();
        if (virtualMap != null) {
            logger.info(STARTUP.getMarker(), "VirtualMap initialized with {} values", virtualMap.size());
        }
        selfId = platform.getSelfId();

        if (trigger == InitTrigger.GENESIS) {
            logger.warn(STARTUP.getMarker(), "InitTrigger was {} when expecting RESTART or RECONNECT", trigger);
            selfId = platform.getSelfId();
        }

        final SemanticVersion staticPrevVersion = PREVIOUS_SOFTWARE_VERSION;
        if (previousVersion == null
                || HapiUtils.SEMANTIC_VERSION_COMPARATOR.compare(previousVersion, staticPrevVersion) != 0) {
            logger.warn(
                    STARTUP.getMarker(),
                    "previousSoftwareVersion was {} when expecting it to be {}",
                    previousVersion,
                    PREVIOUS_SOFTWARE_VERSION);
        }

        if (trigger == InitTrigger.GENESIS) {
            logger.info(STARTUP.getMarker(), "Doing genesis initialization");
            state.genesisInit();
        }
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull MigrationTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        state.throwIfImmutable();
        for (final Iterator<ConsensusEvent> eventIt = round.iterator(); eventIt.hasNext(); ) {
            final ConsensusEvent event = eventIt.next();
            for (final Iterator<ConsensusTransaction> transIt = event.consensusTransactionIterator();
                    transIt.hasNext(); ) {
                final ConsensusTransaction trans = transIt.next();
                if (isSystemTransaction(trans.getApplicationTransaction())) {
                    consumeSystemTransaction(trans, event, stateSignatureTransactionCallback);
                    continue;
                }

                final MigrationTestingToolTransaction mTrans =
                        TransactionUtils.parseTransaction(trans.getApplicationTransaction());
                mTrans.applyTo(state);
            }
        }
    }

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull MigrationTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        event.forEachTransaction(transaction -> {
            if (isSystemTransaction(transaction.getApplicationTransaction())) {
                consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
            }
        });
    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull MigrationTestingToolState state) {
        // no-op
        return true;
    }

    @Override
    public void onUpdateWeight(
            @NonNull MigrationTestingToolState state,
            @NonNull AddressBook configAddressBook,
            @NonNull PlatformContext context) {
        // no-op
    }

    @Override
    public void onNewRecoveredState(@NonNull MigrationTestingToolState recoveredState) {
        // no-op
    }

    private void consumeSystemTransaction(
            final @NonNull Transaction transaction,
            final @NonNull Event event,
            final @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>>
                            stateSignatureTransactionCallback) {
        try {
            final var stateSignatureTransaction =
                    StateSignatureTransaction.PROTOBUF.parse(transaction.getApplicationTransaction());
            stateSignatureTransactionCallback.accept(new ScopedSystemTransaction<>(
                    event.getCreatorId(), event.getSoftwareVersion(), stateSignatureTransaction));
        } catch (final ParseException e) {
            logger.error("Failed to parse StateSignatureTransaction", e);
        }
    }
}
