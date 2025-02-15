/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.turtle.runner;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.utility.NonCryptographicHashing;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import com.swirlds.platform.state.StateLifecycles;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.events.Event;
import com.swirlds.platform.system.transaction.Transaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;

/**
 * This class handles the lifecycle events for the {@link TurtleTestingToolState}.
 */
enum TurtleStateLifecycles implements StateLifecycles<TurtleTestingToolState> {
    TURTLE_STATE_LIFECYCLES;

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull TurtleTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        event.forEachTransaction(transaction -> {
            consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
        });
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull TurtleTestingToolState turtleTestingToolState,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        turtleTestingToolState.state = NonCryptographicHashing.hash64(
                turtleTestingToolState.state,
                round.getRoundNum(),
                round.getConsensusTimestamp().getNano(),
                round.getConsensusTimestamp().getEpochSecond());

        round.forEachEventTransaction((ev, tx) -> {
            consumeSystemTransaction(tx, ev, stateSignatureTransactionCallback);
        });
    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull TurtleTestingToolState state) {
        // no op
        return true;
    }

    @Override
    public void onStateInitialized(
            @NonNull TurtleTestingToolState state,
            @NonNull Platform platform,
            @NonNull InitTrigger trigger,
            @Nullable SoftwareVersion previousVersion) {
        // no op
    }

    @Override
    public void onUpdateWeight(
            @NonNull TurtleTestingToolState state,
            @NonNull AddressBook configAddressBook,
            @NonNull PlatformContext context) {
        // no op
    }

    @Override
    public void onNewRecoveredState(@NonNull TurtleTestingToolState recoveredState) {
        // no op
    }

    /**
     * Converts a transaction to a {@link StateSignatureTransaction} and then consumes it into a callback.
     *
     * @param transaction the transaction to consume
     * @param event the event that contains the transaction
     * @param stateSignatureTransactionCallback the callback to call with the system transaction
     */
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
            throw new RuntimeException("Failed to parse StateSignatureTransaction", e);
        }
    }
}
