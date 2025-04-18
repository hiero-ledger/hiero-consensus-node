// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static org.hiero.base.utility.CommonUtils.toPbjTimestamp;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.turtle.runner.TurtleTestingToolState;
import com.swirlds.state.spi.WritableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

@SuppressWarnings("removal")
public enum TurtleApp implements ConsensusStateEventHandler<TurtleTestingToolState> {
    INSTANCE;

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull TurtleTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {}

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull TurtleTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        for (final ConsensusEvent event : round) {
            event.forEachTransaction(txn -> {
                final Bytes payload = txn.getApplicationTransaction();
                if ("FREEZE".equalsIgnoreCase(payload.asUtf8String())) {
                    final Timestamp freezeTime =
                            toPbjTimestamp(event.getConsensusTimestamp().plusMillis(1L));
                    final WritableSingletonState<PlatformState> singleton =
                            state.getWritableStates("PlatformStateService").getSingleton("PLATFORM_STATE");
                    final PlatformState newState =
                            singleton.get().copyBuilder().freezeTime(freezeTime).build();
                    singleton.put(newState);
                }
            });
        }
    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull TurtleTestingToolState state) {
        return false;
    }

    @Override
    public void onStateInitialized(
            @NonNull TurtleTestingToolState state,
            @NonNull Platform platform,
            @NonNull InitTrigger trigger,
            @Nullable SemanticVersion previousVersion) {}

    @Override
    public void onUpdateWeight(
            @NonNull TurtleTestingToolState state,
            @NonNull AddressBook configAddressBook,
            @NonNull PlatformContext context) {}

    @Override
    public void onNewRecoveredState(@NonNull TurtleTestingToolState recoveredState) {}

    public static Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction stateSignatureTransaction) {
        return Bytes.wrap("StateSignature:")
                .append(StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction));
    }
}
