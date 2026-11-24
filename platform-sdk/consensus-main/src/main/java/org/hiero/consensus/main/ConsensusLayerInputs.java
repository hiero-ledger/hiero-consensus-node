// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.main;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.main.model.ConsensusLayerRosterInputs;
import org.hiero.consensus.main.model.KeysAndCerts;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.main.model.RunningEventHashOverride;
import org.hiero.consensus.main.model.TransactionLimits;

/**
 * This record contains all the required and optional inputs when creating the {@link ConsensusLayer}.
 * <p>
 * <b>Freeze Time</b>
 * If a freeze time is provided during construction of the consensus layer, that is the freeze time and it will be
 * honored unless cleared by passing a {@code null} value for {@code freezeTime} in
 * {@link ConsensusLayer#requestNextRound(Roster, Instant)}. If there is no freeze time provided at construction time,
 * there is no known future freeze time. It is important to provide a {@code null} {@code freezeTime} when starting from
 * a freeze round. In the current system, the freeze state contains a {@code lastFrozenTime} that is equal to the
 * {@code freezeTime}.
 *
 * @param configuration            the configuration the consensus layer should use
 * @param metrics                  the metrics framework for recording metric values
 * @param time                     the source of time
 * @param rosterInputs             the history of rosters for all non-ancient rounds based on the provided
 *                                 {@code consensusSnapshot}.
 * @param keysAndCerts             the keys and certificates the local node
 * @param selfId                   the unique id of this local node
 * @param recycleBin               the instance of the recycle bin for the consensus layer to use
 * @param fileSystemManager        the instance of the file system manager for the consensus layer to use
 * @param executionLayerCallbacks  the execution layer API used by the consensus layer to communicate
 * @param consensusSnapshot        the snapshot of the hashgraph where the consensus layer should continue from. A
 *                                 snapshot is available in every consensus round provided to the execution layer. If
 *                                 not provided, the consensus layer will start a new hashgraph from genesis.
 * @param runningEventHashOverride the starting hash of the consensus event stream. Must be provided if a
 *                                 {@code consensusSnapshot} is provided in order for the consensus event stream to
 *                                 match that of other nodes. This field will be deleted when the consensus event stream
 *                                 is retired.
 * @param consensusEventStreamName the name of the consensus event stream directory on disk. This field will be deleted
 *                                 when the consensus event stream is retired.
 * @param version                  the version of the consensus node software currently running
 * @param transactionOffsetNanos   the number of nanoseconds to add to the first transaction's consensus timestamp in
 *                                 each event
 * @param transactionLimits        the configured limits regarding transactions in events that the consensus layer
 *                                 should enforce when validating events
 * @param freezeTime               the current freeze time, or {@code null} if there is no current freeze time.
 */
public record ConsensusLayerInputs(
        @NonNull Configuration configuration,
        @NonNull Metrics metrics,
        @NonNull Time time,
        @NonNull ConsensusLayerRosterInputs rosterInputs,
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
        @Nullable Instant freezeTime) {

    public ConsensusLayerInputs copyWithNewValues(
            @NonNull final ConsensusSnapshot newSnapshot,
            @NonNull final RunningEventHashOverride newRunningEventHashOverride) {
        return new ConsensusLayerInputs(
                configuration(),
                metrics(),
                time(),
                rosterInputs(),
                keysAndCerts(),
                selfId(),
                recycleBin(),
                fileSystemManager(),
                executionLayerCallbacks(),
                newSnapshot,
                newRunningEventHashOverride,
                consensusEventStreamName(),
                version(),
                transactionOffsetNanos(),
                transactionLimits(),
                freezeTime());
    }
}
