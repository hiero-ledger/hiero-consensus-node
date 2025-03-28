// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import static com.swirlds.logging.legacy.LogMarker.STATE_HASH;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.node.app.Hedera;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.events.Event;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Implements the major lifecycle events for Hedera Services by delegating to a Hedera instance.
 */
public class ConsensusStateEventHandlerImpl implements ConsensusStateEventHandler<MerkleNodeState> {
    private static final Logger logger = LogManager.getLogger(ConsensusStateEventHandlerImpl.class);
    private final Hedera hedera;
    private Set<Long> rounds = new HashSet<>(2);



    public ConsensusStateEventHandlerImpl(@NonNull final Hedera hedera) {
        this.hedera = requireNonNull(hedera);
    }

    @Override
    public void onPreHandle(
            @NonNull final Event event,
            @NonNull final MerkleNodeState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        hedera.onPreHandle(event, state, stateSignatureTransactionCallback);
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull final Round round,
            @NonNull final MerkleNodeState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTxnCallback) {
        hedera.onHandleConsensusRound(round, state, stateSignatureTxnCallback);
    }

    @Override
    public boolean onSealConsensusRound(@NonNull final Round round, @NonNull final MerkleNodeState state) {
        requireNonNull(state);
        requireNonNull(round);
        long roundNum = round.getRoundNum();
        if(!rounds.contains(roundNum) && hedera.appContext.selfNodeInfoSupplier().get().accountId().hasAccountNum() &&
        hedera.appContext.selfNodeInfoSupplier().get().accountId().accountNum() == 3 ) {
//            logger.info(STATE_HASH.getMarker(), hedera.platformStateFacade.getInfoString(state, 1));
            rounds.add(roundNum);
        }

        if(rounds.size() == 2) {
            rounds.removeIf(next -> !next.equals(roundNum));
        }


        return hedera.onSealConsensusRound(round, state);
    }

    @Override
    public void onStateInitialized(
            @NonNull final MerkleNodeState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        hedera.onStateInitialized(state, platform, trigger);
    }

    @Override
    public void onUpdateWeight(
            @NonNull final MerkleNodeState stateRoot,
            @NonNull final AddressBook configAddressBook,
            @NonNull final PlatformContext context) {
        // No-op
    }

    @Override
    public void onNewRecoveredState(@NonNull final MerkleNodeState recoveredStateRoot) {
        hedera.onNewRecoveredState();
    }
}
