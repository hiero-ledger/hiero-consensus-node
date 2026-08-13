// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One-shot removal of the retired legacy fee schedule file {@code 0.0.111} from state.
 *
 * <p>The node stopped creating this file at genesis and updating it at upgrade boundaries when the legacy
 * usage-based fee system was replaced by the simple fee schedule in {@code 0.0.113}. Networks that were
 * created before that change still carry the stale entry, so it is removed here at the first post-upgrade
 * boundary. Networks that never created the file are left untouched.
 *
 * <p>This cannot be a {@link com.swirlds.state.lifecycle.Schema} migration: removing the file must also
 * decrement the {@code FILE} entity counter, which lives in the entity id service state and is not reachable
 * from a file service schema's {@code newStates()}.
 */
public final class RetiredFeeScheduleFileMigration {

    private static final Logger logger = LogManager.getLogger(RetiredFeeScheduleFileMigration.class);

    /**
     * The number of the retired legacy fee schedule file. Deliberately a constant and not a config property;
     * the node no longer reads, writes, or authorizes updates to this file.
     */
    public static final long RETIRED_FEE_SCHEDULE_FILE_NUM = 111L;

    private RetiredFeeScheduleFileMigration() {
        throw new UnsupportedOperationException();
    }

    /**
     * Removes the retired legacy fee schedule file from state if it is still present, decrementing the file
     * entity counter to match. A no-op if the file was never created.
     *
     * @param fileStore the writable file store to remove the file from
     * @param config the configuration supplying the shard and realm of the file
     */
    public static void removeIfPresent(
            @NonNull final WritableFileStore fileStore, @NonNull final Configuration config) {
        requireNonNull(fileStore);
        requireNonNull(config);
        final var hederaConfig = config.getConfigData(HederaConfig.class);
        final var fileId = FileID.newBuilder()
                .realmNum(hederaConfig.realm())
                .shardNum(hederaConfig.shard())
                .fileNum(RETIRED_FEE_SCHEDULE_FILE_NUM)
                .build();
        if (fileStore.get(fileId).isPresent()) {
            fileStore.removeFile(fileId);
            logger.info(
                    "Removed retired legacy fee schedule file {}.{}.{} from state",
                    fileId.shardNum(),
                    fileId.realmNum(),
                    fileId.fileNum());
        }
    }
}
