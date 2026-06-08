// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.node.app.blocks.schemas.V0740BlockStreamSchema;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Service for BlockStreams implementation responsible for tracking state changes
 * and writing them to a block
 */
public class BlockStreamService implements Service {
    private static final Logger log = LogManager.getLogger(BlockStreamService.class);

    /**
     * The block stream manager increments the previous number when starting a block; so to start
     * the genesis block number at {@code 0}, we set the "previous" number to {@code -1}.
     */
    public static final BlockStreamInfo GENESIS_BLOCK_STREAM_INFO =
            BlockStreamInfo.newBuilder().blockNumber(-1).build();

    public static final String NAME = "BlockStreamService";

    private boolean bsiSchemaOverwriteExecuted;

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0560BlockStreamSchema());
        registry.register(new V0740BlockStreamSchema(this::markSchemaOverwriteExecuted));
    }

    @Override
    public boolean doGenesisSetup(
            @NonNull final WritableStates writableStates, @NonNull final Configuration configuration) {
        requireNonNull(writableStates);
        requireNonNull(configuration);
        writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_STATE_ID).put(GENESIS_BLOCK_STREAM_INFO);
        return true;
    }

    /**
     * Returns whether the schema overwrite of the {@code BlockStreamInfo} object, which happens as part
     * of the block streams cutover release, was executed during the most recent schema migration.
     */
    public boolean isBsiSchemaOverwriteExecuted() {
        return bsiSchemaOverwriteExecuted;
    }

    private void markSchemaOverwriteExecuted() {
        this.bsiSchemaOverwriteExecuted = true;
        log.info("Block stream cutover's schema overwrite executed during schema migration");
    }
}
