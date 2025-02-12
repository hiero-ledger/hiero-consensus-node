/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
