/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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
