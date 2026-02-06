// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema.BLOCK_STREAM_INFO_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;
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

    @Nullable
    private Bytes migratedLastBlockHash;

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0560BlockStreamSchema(this::setMigratedLastBlockHash));
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
     * Returns the last block hash as migrated from a state that used record streams, or empty
     * if there was no such hash observed during migration.
     * @return the last block hash
     */
    public Optional<Bytes> migratedLastBlockHash() {
        return Optional.ofNullable(migratedLastBlockHash);
    }

    /**
     * Resets the migrated last block hash to null.
     */
    public void resetMigratedLastBlockHash() {
        migratedLastBlockHash = null;
    }

    private void setMigratedLastBlockHash(@NonNull final Bytes migratedLastBlockHash) {
        this.migratedLastBlockHash = requireNonNull(migratedLastBlockHash);
        log.info("Migrated last block hash '{}'", migratedLastBlockHash);
    }
}
