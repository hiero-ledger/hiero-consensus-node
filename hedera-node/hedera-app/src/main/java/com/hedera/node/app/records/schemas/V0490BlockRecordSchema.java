// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.records.BlockRecordService;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

public class V0490BlockRecordSchema extends Schema<SemanticVersion> {

    /** {@link RunningHashes} state */
    public static final String RUNNING_HASHES_KEY = "RUNNING_HASHES";

    public static final int RUNNING_HASHES_STATE_ID = SingletonType.BLOCKRECORDSERVICE_I_RUNNING_HASHES.protoOrdinal();
    public static final String RUNNING_HASHES_STATE_LABEL = computeLabel(BlockRecordService.NAME, RUNNING_HASHES_KEY);

    /** {@link BlockInfo} state */
    public static final String BLOCKS_KEY = "BLOCKS";

    public static final int BLOCKS_STATE_ID = SingletonType.BLOCKRECORDSERVICE_I_BLOCKS.protoOrdinal();
    public static final String BLOCKS_STATE_LABEL = computeLabel(BlockRecordService.NAME, BLOCKS_KEY);

    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    public V0490BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(RUNNING_HASHES_STATE_ID, RUNNING_HASHES_KEY, RunningHashes.PROTOBUF),
                StateDefinition.singleton(BLOCKS_STATE_ID, BLOCKS_KEY, BlockInfo.PROTOBUF));
    }
}
