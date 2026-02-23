// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.util;

import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.file.File;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;

public class FileUtilities {

    private FileUtilities() {
        throw new IllegalStateException("Utility class");
    }

    @NonNull
    public static Bytes getFileContent(@NonNull final State state, @NonNull final FileID fileID) {
        final var states = state.getReadableStates(FileService.NAME);
        final var filesMap = states.<FileID, File>get(FILES_STATE_ID);
        final var file = filesMap.get(fileID);
        return file != null ? file.contents() : Bytes.EMPTY;
    }

    /**
     * Observes the properties and permissions of the network from a saved state.
     */
    @FunctionalInterface
    public interface SpecialFilesObserver {
        /**
         * Accepts the properties and permissions of the network from a saved state.
         *
         * @param properties the raw bytes of the properties file
         * @param permissions the raw bytes of the permissions file
         */
        void acceptPropertiesAndPermissions(Bytes properties, Bytes permissions);
    }

    /**
     * Given a state, configuration, and observer, observes the properties and
     * permissions of the network.
     *
     * @param state the state to observe
     * @param config the configuration to use
     * @param observer the observer to notify
     */
    public static void observePropertiesAndPermissions(
            @NonNull final State state,
            @NonNull final Configuration config,
            @NonNull final SpecialFilesObserver observer) {
        requireNonNull(state);
        requireNonNull(config);
        requireNonNull(observer);
        final var filesConfig = config.getConfigData(FilesConfig.class);
        observePropertiesAndPermissions(
                state,
                FileUtilities.createFileID(filesConfig.networkProperties(), config),
                FileUtilities.createFileID(filesConfig.hapiPermissions(), config),
                observer);
    }

    private static void observePropertiesAndPermissions(
            @NonNull final State state,
            @NonNull final FileID propertiesId,
            @NonNull final FileID permissionsId,
            @NonNull final SpecialFilesObserver observer) {
        observer.acceptPropertiesAndPermissions(
                getFileContent(state, propertiesId), getFileContent(state, permissionsId));
    }

    public static FileID createFileID(final long fileNum, @NonNull final Configuration configuration) {
        final var hederaConfig = configuration.getConfigData(HederaConfig.class);
        return FileID.newBuilder()
                .realmNum(hederaConfig.realm())
                .shardNum(hederaConfig.shard())
                .fileNum(fileNum)
                .build();
    }
}
