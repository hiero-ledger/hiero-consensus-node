// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl;

import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.UPGRADE_DATA_STATE_KEY_PATTERN;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Provides write methods for modifying underlying data storage mechanisms for working with Files.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail. This
 * class is not complete, it will be extended with other methods like remove, update etc.,
 */
public class WritableUpgradeFileStore extends ReadableUpgradeFileStoreImpl {

    private final WritableStates states;

    private final WritableKVState<FileID, File> writableUpgradeFileState;

    private static final Predicate<ProtoBytes> TRUE_PREDICATE = new TruePredicate();

    /**
     * Create a new {@link WritableUpgradeFileStore} instance.
     *
     * @param states The state to use.
     */
    public WritableUpgradeFileStore(@NonNull final WritableStates states) {
        super(states);
        this.states = requireNonNull(states);
        writableUpgradeFileState = requireNonNull(states.get(FILES_STATE_ID));
    }

    /**
     * Adds a file to the store.
     *
     * @param file the file to add
     */
    public void add(@NonNull final File file) {
        requireNonNull(file);
        writableUpgradeFileState.put(file.fileIdOrThrow(), file);
    }

    /**
     * Adds upgrade file to the store.
     *
     * @param fileID the file id
     * @param content content file to add
     */
    public void addUpgradeContent(@NonNull final FileID fileID, Bytes content) {
        final WritableQueueState<ProtoBytes> upgradeState = getUpgradeState(fileID);
        upgradeState.add(new ProtoBytes(content));
    }

    /**
     * Appends bytes to the file.
     *
     * @param bytes the bytes to append
     * @param fileID the file id
     */
    public void append(@NonNull final Bytes bytes, @NonNull final FileID fileID) {
        requireNonNull(bytes);
        requireNonNull(fileID);
        final WritableQueueState<ProtoBytes> upgradeState = getUpgradeState(fileID);
        upgradeState.add(new ProtoBytes(bytes));
    }

    /**
     * Resets the file contents.
     *
     * @param fileID the file id
     */
    @SuppressWarnings("StatementWithEmptyBody")
    public void resetFileContents(@NonNull final FileID fileID) {
        final WritableQueueState<ProtoBytes> upgradeState = getUpgradeState(fileID);
        while (upgradeState.removeIf(TRUE_PREDICATE) != null) {
            // no-op
        }
    }

    private static class TruePredicate implements Predicate<ProtoBytes> {
        @Override
        public boolean test(final ProtoBytes file) {
            return true;
        }
    }

    @NonNull
    private WritableQueueState<ProtoBytes> getUpgradeState(@NonNull FileID fileID) {
        final String stateKey =
                UPGRADE_DATA_STATE_KEY_PATTERN.formatted(fileID.fileNum()).toUpperCase();
        final int stateId = StateKey.KeyOneOfType.fromString(stateKey).protoOrdinal();
        return Objects.requireNonNull(states.getQueue(stateId));
    }
}
