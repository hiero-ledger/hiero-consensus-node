// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.state.recordcache.BlockRecordSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * A temporary wrapper class as we transition from the V6 record stream to the block stream;
 * includes at least one of the V6 record stream and/or the block stream output from a user transaction.
 *
 * @param blockRecordSource maybe the block stream output items
 * @param recordSource maybe record source derived from the V6 record stream items
 * @param firstAssignedConsensusTime the first consensus time assigned to a transaction in the output
 */
public record HandleOutput(
        @Nullable BlockRecordSource blockRecordSource,
        @Nullable RecordSource recordSource,
        @NonNull Instant firstAssignedConsensusTime) {
    public HandleOutput {
        if (blockRecordSource == null) {
            requireNonNull(recordSource);
        }
        requireNonNull(firstAssignedConsensusTime);
    }

    public @NonNull RecordSource recordSourceOrThrow() {
        return requireNonNull(recordSource);
    }

    public @NonNull BlockRecordSource blockRecordSourceOrThrow() {
        return requireNonNull(blockRecordSource);
    }

    public @NonNull RecordSource preferringBlockRecordSource() {
        return blockRecordSource != null ? blockRecordSource : requireNonNull(recordSource);
    }
}
