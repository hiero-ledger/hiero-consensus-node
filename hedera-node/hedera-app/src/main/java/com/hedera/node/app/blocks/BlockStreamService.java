// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.Service;
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

    public static final Bytes FAKE_RESTART_BLOCK_HASH = Bytes.fromHex("abcd".repeat(24));

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
