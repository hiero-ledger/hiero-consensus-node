// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVoteTally;
import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVotingState;
import com.hedera.hapi.node.state.blockrecords.MigrationWrappedHashes;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
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
 * Adds migration voting and temporary wrapped-hash queue state for BlockRecord service.
 */
public class V0730BlockRecordSchema extends Schema<SemanticVersion> {
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(73).patch(0).build();

    public static final String MIGRATION_ROOT_HASH_VOTING_STATE_KEY = "MIGRATION_ROOT_HASH_VOTING_STATE";
    public static final int MIGRATION_ROOT_HASH_VOTING_STATE_ID =
            SingletonType.BLOCKRECORDSERVICE_I_MIGRATION_ROOT_HASH_VOTING_STATE.protoOrdinal();
    public static final String MIGRATION_ROOT_HASH_VOTING_STATE_LABEL =
            computeLabel(BlockRecordService.NAME, MIGRATION_ROOT_HASH_VOTING_STATE_KEY);

    public static final String MIGRATION_ROOT_HASH_VOTES_KEY = "MIGRATION_ROOT_HASH_VOTES";
    public static final int MIGRATION_ROOT_HASH_VOTES_STATE_ID =
            StateKey.KeyOneOfType.BLOCKRECORDSERVICE_I_MIGRATION_ROOT_HASH_VOTES.protoOrdinal();
    public static final String MIGRATION_ROOT_HASH_VOTES_STATE_LABEL =
            computeLabel(BlockRecordService.NAME, MIGRATION_ROOT_HASH_VOTES_KEY);

    public static final String MIGRATION_ROOT_HASH_TALLIES_KEY = "MIGRATION_ROOT_HASH_TALLIES";
    public static final int MIGRATION_ROOT_HASH_TALLIES_STATE_ID =
            StateKey.KeyOneOfType.BLOCKRECORDSERVICE_I_MIGRATION_ROOT_HASH_TALLIES.protoOrdinal();
    public static final String MIGRATION_ROOT_HASH_TALLIES_STATE_LABEL =
            computeLabel(BlockRecordService.NAME, MIGRATION_ROOT_HASH_TALLIES_KEY);

    public static final String MIGRATION_WRAPPED_HASHES_QUEUE_KEY = "MIGRATION_WRAPPED_HASHES_QUEUE";
    public static final int MIGRATION_WRAPPED_HASHES_QUEUE_STATE_ID =
            StateKey.KeyOneOfType.BLOCKRECORDSERVICE_I_MIGRATION_WRAPPED_HASHES_QUEUE.protoOrdinal();
    public static final String MIGRATION_WRAPPED_HASHES_QUEUE_STATE_LABEL =
            computeLabel(BlockRecordService.NAME, MIGRATION_WRAPPED_HASHES_QUEUE_KEY);

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
                        ProtoBytes.PROTOBUF,
                        MigrationRootHashVoteTally.PROTOBUF),
                StateDefinition.queue(
                        MIGRATION_WRAPPED_HASHES_QUEUE_STATE_ID,
                        MIGRATION_WRAPPED_HASHES_QUEUE_KEY,
                        MigrationWrappedHashes.PROTOBUF));
    }
}
