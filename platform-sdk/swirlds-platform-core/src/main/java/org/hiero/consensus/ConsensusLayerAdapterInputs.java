// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.base.time.Time;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.builder.ExecutionLayer;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.StaleEventConsumer;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.state.signed.ReservedSignedState;

public record ConsensusLayerAdapterInputs(
        @NonNull Configuration configuration,
        @NonNull Metrics metrics,
        @NonNull Time time,
        @NonNull RosterHistory rosterHistory,
        @NonNull KeysAndCerts keysAndCerts,
        @NonNull NodeId selfId,
        @NonNull RecycleBin recycleBin,
        @NonNull FileSystemManager fileSystemManager,
        @NonNull ExecutionLayer executionLayer,
        @NonNull ConsensusStateEventHandler consensusStateEventHandler,
        @NonNull ReservedSignedState initialState,
        @NonNull StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
        @NonNull SemanticVersion version,
        @NonNull String appName,
        @NonNull String swirldName,
        @NonNull String consensusEventStreamName,
        long transactionOffsetNanos,
        @Nullable StaleEventConsumer staleEventConsumer,
        @Nullable WiringModel wiringModel,
        @Nullable SecureRandom secureRandom,
        @Nullable Instant freezeTime,
        @NonNull Map<String, Object> additionalProperties) {}
