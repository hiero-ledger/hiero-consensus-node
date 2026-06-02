// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.schemas;

import static com.hedera.hapi.util.HapiUtils.SEMANTIC_VERSION_COMPARATOR;
import static com.hedera.node.app.records.impl.WrappedRecordFileBlockHashesDiskWriter.DEFAULT_FILE_NAME;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.config.data.BlockRecordStreamConfig;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Migration schema for release 0.76 that deletes the stale on-disk
 * {@value com.hedera.node.app.records.impl.WrappedRecordFileBlockHashesDiskWriter#DEFAULT_FILE_NAME}
 * file if it exists. The file was used by earlier releases to record per-block hashes for the
 * block-stream cutover and is no longer needed once a node has reached 0.76.
 */
public class V0760BlockRecordSchema extends Schema<SemanticVersion> {
    private static final Logger log = LogManager.getLogger(V0760BlockRecordSchema.class);

    private static final SemanticVersion VERSION =
            SemanticVersion.newBuilder().major(0).minor(76).patch(0).build();

    public V0760BlockRecordSchema() {
        super(VERSION, SEMANTIC_VERSION_COMPARATOR);
    }

    @Override
    public void migrate(@NonNull final MigrationContext<SemanticVersion> ctx) {
        if (ctx.isGenesis()) {
            return;
        }
        final var cfg = ctx.appConfig().getConfigData(BlockRecordStreamConfig.class);
        if (!cfg.deleteStaleWrappedRecordHashesFile()) {
            log.info("Skipping wrapped record hashes file deletion (deleteStaleWrappedRecordHashesFile=false)");
            return;
        }
        final Path file = Paths.get(cfg.wrappedRecordHashesDir()).resolve(DEFAULT_FILE_NAME);
        try {
            if (Files.deleteIfExists(file)) {
                log.info("Deleted stale wrapped record hashes file {}", file);
            }
        } catch (final IOException e) {
            log.warn("Failed to delete stale wrapped record hashes file {}", file, e);
        }
    }
}
