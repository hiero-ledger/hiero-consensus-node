// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.node.app.records.BlockRecordService.EPOCH;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class V0490BlockRecordSchema extends Schema {
    private static final Logger logger = LogManager.getLogger(V0490BlockRecordSchema.class);

    /** The key for the {@link RunningHashes} object in state */
    public static final String RUNNING_HASHES_STATE_KEY = "RUNNING_HASHES";
    /** The key for the {@link BlockInfo} object in state */
    public static final String BLOCK_INFO_STATE_KEY = "BLOCKS";
    /** The original hash, only used at genesis */
    private static final Bytes GENESIS_HASH = Bytes.wrap(new byte[48]);
    /**
     * The version of the schema.
     */
    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(49).patch(0).build();

    public V0490BlockRecordSchema() {
        super(VERSION);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Set<StateDefinition> statesToCreate() {
        return Set.of(
                StateDefinition.singleton(RUNNING_HASHES_STATE_KEY, RunningHashes.PROTOBUF),
                StateDefinition.singleton(BLOCK_INFO_STATE_KEY, BlockInfo.PROTOBUF));
    }

    /**
     * {@inheritDoc}
     * */
    @Override
    public void migrate(@NonNull final MigrationContext ctx) {
        if (ctx.isGenesis()) {
            final var blocksState = ctx.newStates().getSingleton(BLOCK_INFO_STATE_KEY);
            // Note there is by convention no post-upgrade work to do if starting from genesis
            final var blocks = new BlockInfo(-1, EPOCH, Bytes.EMPTY, EPOCH, true, EPOCH, EPOCH, EPOCH);
            blocksState.put(blocks);
            final var runningHashState = ctx.newStates().getSingleton(RUNNING_HASHES_STATE_KEY);
            final var runningHashes =
                    RunningHashes.newBuilder().runningHash(GENESIS_HASH).build();
            runningHashState.put(runningHashes);
        }
    }
}
