package org.hiero.otter.fixtures.turtle;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.fixtures.turtle.runner.TurtleTestingToolState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

public enum TurtleApp implements ConsensusStateEventHandler<TurtleTestingToolState> {

    INSTANCE;

    @Override
    public void onPreHandle(@NonNull Event event, @NonNull TurtleTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {

    }

    @Override
    public void onHandleConsensusRound(@NonNull Round round, @NonNull TurtleTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {

    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull TurtleTestingToolState state) {
        return false;
    }

    @Override
    public void onStateInitialized(@NonNull TurtleTestingToolState state, @NonNull Platform platform,
            @NonNull InitTrigger trigger, @Nullable SemanticVersion previousVersion) {

    }

    @Override
    public void onUpdateWeight(@NonNull TurtleTestingToolState state, @NonNull AddressBook configAddressBook,
            @NonNull PlatformContext context) {

    }

    @Override
    public void onNewRecoveredState(@NonNull TurtleTestingToolState recoveredState) {

    }

    public static Bytes encodeSystemTransaction(@NonNull final StateSignatureTransaction stateSignatureTransaction) {
        return Bytes.wrap("StateSignature:").append(StateSignatureTransaction.PROTOBUF.toBytes(stateSignatureTransaction));
    }
}
