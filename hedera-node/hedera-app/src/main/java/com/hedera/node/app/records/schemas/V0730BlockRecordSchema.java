// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVoteTally;
import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVotingState;
import com.hedera.hapi.node.state.blockrecords.MigrationWrappedHashes;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.node.app.records.BlockRecordService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Adds migration root-hash voting state for {@link BlockRecordService}.
 *
 * <p>The layout is intentionally split across four states to keep handle logic deterministic and idempotent:
 * <ul>
 *   <li>{@code MIGRATION_ROOT_HASH_VOTING_STATE} (singleton) stores finalization and deadline metadata and acts as
 *       the one-way gate that disables further voting/queue work after consensus is reached or the deadline passes.</li>
 *   <li>{@code MIGRATION_ROOT_HASH_VOTES} (nodeId -> vote body) enforces one vote per node and prevents duplicate
 *       submissions from being counted more than once.</li>
 *   <li>{@code MIGRATION_ROOT_HASH_TALLIES} (vote body -> aggregate weight) supports efficient weighted threshold
 *       checks without rescanning all node votes on every transaction.</li>
 *   <li>{@code MIGRATION_WRAPPED_HASHES_QUEUE} (ordered queue) buffers per-block wrapped-hash inputs while voting is
 *       pending so the agreed migration state can be replayed to the current block once voting finalizes.</li>
 * </ul>
 */
public class V0730BlockRecordSchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(73).patch(0).build();

    public static final String MIGRATION_ROOT_HASH_VOTING_STATE_KEY = "MIGRATION_ROOT_HASH_VOTING_STATE";
    public static final int MIGRATION_ROOT_HASH_VOTING_STATE_ID =
            SingletonType.BLOCKRECORDSERVICE_I_MIGRATION_ROOT_HASH_VOTING_STATE.protoOrdinal();

    public static final String MIGRATION_ROOT_HASH_VOTES_KEY = "MIGRATION_ROOT_HASH_VOTES";
    public static final int MIGRATION_ROOT_HASH_VOTES_STATE_ID =
            StateKey.KeyOneOfType.BLOCKRECORDSERVICE_I_MIGRATION_ROOT_HASH_VOTES.protoOrdinal();

    public static final String MIGRATION_ROOT_HASH_TALLIES_KEY = "MIGRATION_ROOT_HASH_TALLIES";
    public static final int MIGRATION_ROOT_HASH_TALLIES_STATE_ID =
            StateKey.KeyOneOfType.BLOCKRECORDSERVICE_I_MIGRATION_ROOT_HASH_TALLIES.protoOrdinal();

    public static final String MIGRATION_WRAPPED_HASHES_QUEUE_KEY = "MIGRATION_WRAPPED_HASHES_QUEUE";
    public static final int MIGRATION_WRAPPED_HASHES_QUEUE_STATE_ID =
            StateKey.KeyOneOfType.BLOCKRECORDSERVICE_I_MIGRATION_WRAPPED_HASHES_QUEUE.protoOrdinal();

    public V0730BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(
                        MIGRATION_ROOT_HASH_VOTING_STATE_ID,
                        MIGRATION_ROOT_HASH_VOTING_STATE_KEY,
                        MigrationRootHashVotingState.PROTOBUF),
                StateDefinition.keyValue(
                        MIGRATION_ROOT_HASH_VOTES_STATE_ID,
                        MIGRATION_ROOT_HASH_VOTES_KEY,
                        NodeId.PROTOBUF,
                        MigrationRootHashVoteTransactionBody.PROTOBUF),
                StateDefinition.keyValue(
                        MIGRATION_ROOT_HASH_TALLIES_STATE_ID,
                        MIGRATION_ROOT_HASH_TALLIES_KEY,
                        MigrationRootHashVoteTransactionBody.PROTOBUF,
                        MigrationRootHashVoteTally.PROTOBUF),
                StateDefinition.queue(
                        MIGRATION_WRAPPED_HASHES_QUEUE_STATE_ID,
                        MIGRATION_WRAPPED_HASHES_QUEUE_KEY,
                        MigrationWrappedHashes.PROTOBUF));
    }
}
