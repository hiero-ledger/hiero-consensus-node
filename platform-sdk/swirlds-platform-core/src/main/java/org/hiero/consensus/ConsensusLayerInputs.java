// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.stream.RunningEventHashOverride;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.transaction.TransactionLimits;

public record ConsensusLayerInputs(
        @NonNull Configuration configuration,
        @NonNull Metrics metrics,
        @NonNull Time time,
        @NonNull RosterHistory rosterHistory,
        @NonNull KeysAndCerts keysAndCerts,
        @NonNull NodeId selfId,
        @NonNull RecycleBin recycleBin,
        @NonNull FileSystemManager fileSystemManager,
        @NonNull ExecutionLayerCallbacks executionLayerCallbacks,
        @Nullable ConsensusSnapshot consensusSnapshot,
        @Nullable RunningEventHashOverride runningEventHashOverride,
        @NonNull String consensusEventStreamName,
        @NonNull SemanticVersion version,
        long transactionOffsetNanos,
        @NonNull TransactionLimits transactionLimits,
        @Nullable Instant freezeTime,
        // The fields below are for testing only.
        @Nullable WiringModel wiringModel,
        @Nullable SecureRandom secureRandom,
        @NonNull Map<String, Object> additionalProperties) {}
