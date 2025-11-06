// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import static com.swirlds.demo.migration.MigrationTestingToolMain.PREVIOUS_SOFTWARE_VERSION;
import static com.swirlds.demo.migration.TransactionUtils.isSystemTransaction;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
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

    /**
     * Offset added to the round's consensus timestamp when scheduling a freeze.
     */
    public static final Duration FREEZE_TIME_OFFSET = Duration.ofSeconds(10);

    private MigrationTestingToolConfig configData;

    @Override
    public void onStateInitialized(
            @NonNull final MigrationTestingToolState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        if (trigger == InitTrigger.GENESIS) {
            logger.warn(STARTUP.getMarker(), "InitTrigger was {} when expecting RESTART or RECONNECT", trigger);
        }

        if (previousVersion == null
                || HapiUtils.SEMANTIC_VERSION_COMPARATOR.compare(previousVersion, PREVIOUS_SOFTWARE_VERSION) != 0) {
            logger.warn(
                    STARTUP.getMarker(),
                    "previousSoftwareVersion was {} when expecting it to be {}",
                    previousVersion,
                    PREVIOUS_SOFTWARE_VERSION);
        }

        configData = platform.getContext().getConfiguration().getConfigData(MigrationTestingToolConfig.class);
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull MigrationTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        state.throwIfImmutable();

        // After enough rounds, we set the state to be a freeze state
        if (configData.applyFreezeTimeInRound() > 0 && round.getRoundNum() == configData.applyFreezeTimeInRound()) {
            final Instant freezeTime = round.getConsensusTimestamp().plus(FREEZE_TIME_OFFSET);
            logger.info(
                    STARTUP.getMarker(),
                    "Setting freeze time to {} seconds after:{}. Value:{}",
                    FREEZE_TIME_OFFSET.getSeconds(),
                    round.getConsensusTimestamp(),
                    freezeTime);
            PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE.bulkUpdateOf(state, v -> {
                v.setFreezeTime(freezeTime);
            });
        }

        for (final ConsensusEvent event : round) {
            for (final Iterator<ConsensusTransaction> transIt = event.consensusTransactionIterator();
                    transIt.hasNext(); ) {
                final ConsensusTransaction trans = transIt.next();
                if (isSystemTransaction(trans.getApplicationTransaction())) {
                    consumeSystemTransaction(trans, event, stateSignatureTransactionCallback);
                    continue;
                }

                final MigrationTestingToolTransaction mTrans =
                        TransactionUtils.parseTransaction(trans.getApplicationTransaction());
                mTrans.applyToState(state);
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
                    event.getCreatorId(), event.getBirthRound(), stateSignatureTransaction));
        } catch (final ParseException e) {
            logger.error("Failed to parse StateSignatureTransaction", e);
        }
    }
}
