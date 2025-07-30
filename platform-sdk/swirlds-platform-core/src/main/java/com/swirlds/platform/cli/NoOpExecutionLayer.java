package com.swirlds.platform.cli;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.builder.ExecutionLayer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.status.PlatformStatus;

public class NoOpExecutionLayer implements ExecutionLayer {
    @Override
    public void submitStateSignature(@NonNull final StateSignatureTransaction transaction) {

    }

    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {

    }

    @NonNull
    @Override
    public List<Bytes> getTransactionsForEvent() {
        return List.of();
    }

    @Override
    public boolean hasBufferedSignatureTransactions() {
        return false;
    }
}
