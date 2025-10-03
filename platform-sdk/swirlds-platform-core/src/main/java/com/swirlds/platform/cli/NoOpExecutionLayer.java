// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.cli;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.platform.builder.ExecutionLayer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.TimestampedTransaction;

/**
 * A no-op implementation of the {@link ExecutionLayer} interface.
 * This class is used when no execution layer is needed, such as in CLI applications.
 */
public class NoOpExecutionLayer implements ExecutionLayer {
    @Override
    public void submitStateSignature(@NonNull final StateSignatureTransaction transaction) {}

    @Override
    public void newPlatformStatus(@NonNull final PlatformStatus platformStatus) {}

    @NonNull
    @Override
    public List<TimestampedTransaction> getTransactionsForEvent() {
        return List.of();
    }

    @Override
    public boolean hasBufferedSignatureTransactions() {
        return false;
    }

    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {}
}
