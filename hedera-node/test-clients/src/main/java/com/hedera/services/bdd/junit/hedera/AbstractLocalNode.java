// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera;

import static com.hedera.node.app.info.DiskStartupNetworks.ARCHIVE;
import static com.hedera.node.app.info.DiskStartupNetworks.GENESIS_NETWORK_JSON;
import static com.hedera.node.app.info.DiskStartupNetworks.OVERRIDE_NETWORK_JSON;
import static com.hedera.node.app.info.DiskStartupNetworks.ROUND_DIR_PATTERN;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.UPGRADE_ARTIFACTS_DIR;
import static com.hedera.services.bdd.junit.hedera.subprocess.ProcessUtils.conditionFuture;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.recreateWorkingDir;
import static java.util.Objects.requireNonNull;

import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation support for a node that uses a local working directory.
 */
public abstract class AbstractLocalNode<T extends AbstractLocalNode<T>> extends AbstractNode implements HederaNode {
    /**
     * How many milliseconds to wait between re-checking if a marker file exists.
     */
    private static final long MF_BACKOFF_MS = 50L;
    /**
     * Whether the working directory has been initialized.
     */
    protected boolean workingDirInitialized;

    protected AbstractLocalNode(@NonNull final NodeMetadata metadata) {
        super(metadata);
    }

    @Override
    public @NonNull T initWorkingDir(@NonNull final String configTxt) {
        requireNonNull(configTxt);
        recreateWorkingDir(requireNonNull(metadata.workingDir()), configTxt, metadata.nodeId());
        workingDirInitialized = true;
        return self();
    }

    protected void assertWorkingDirInitialized() {
        if (!workingDirInitialized) {
            throw new IllegalStateException("Working directory not initialized");
        }
    }

    @Override
    public CompletableFuture<Void> mfFuture(@NonNull final MarkerFile markerFile) {
        requireNonNull(markerFile);
        return conditionFuture(() -> mfExists(markerFile), () -> MF_BACKOFF_MS);
    }

    @Override
    public Optional<Network> startupNetwork() {
        return getPossiblyArchivedStartupAddressBook(getExternalPath(DATA_CONFIG_DIR));
    }

    protected abstract T self();

    private boolean mfExists(@NonNull final MarkerFile markerFile) {
        return Files.exists(getExternalPath(UPGRADE_ARTIFACTS_DIR).resolve(markerFile.fileName()));
    }

    /**
     * Tries to find any startup address book in the given directory or its {@code .archive} subdirectory.
     * @param path the path to search
     * @return the address book, if found
     */
    private Optional<Network> getPossiblyArchivedStartupAddressBook(@NonNull final Path path) {
        return getStartupAddressBookIn(path).or(() -> getStartupAddressBookIn(path.resolve(ARCHIVE)));
    }

    /**
     * Tries to find a startup address book in the given directory. This may be either a {@code genesis-network.json}
     * or a {@code override-network.json} file; which may itself be "scoped" inside a numbered round directory, in
     * which case we always choose the override network for the highest round number.
     * @param path the path to search
     * @return the address book, if found
     */
    private Optional<Network> getStartupAddressBookIn(@NonNull final Path path) {
        return getStartupAddressBookAt(path.resolve(GENESIS_NETWORK_JSON))
                .or(() -> getStartupAddressBookAt(path.resolve(OVERRIDE_NETWORK_JSON)))
                .or(() -> {
                    Optional<Network> scopedAddressBook = Optional.empty();
                    try (final var dirStream = Files.list(path)) {
                        scopedAddressBook = dirStream
                                .filter(Files::isDirectory)
                                .filter(dir -> ROUND_DIR_PATTERN
                                        .matcher(dir.getFileName().toString())
                                        .matches())
                                .sorted(Comparator.<Path>comparingLong(dir ->
                                                Long.parseLong(dir.getFileName().toString()))
                                        .reversed())
                                .map(dir -> getStartupAddressBookAt(dir.resolve(OVERRIDE_NETWORK_JSON)))
                                .flatMap(Optional::stream)
                                .findFirst();
                    } catch (IOException ignore) {
                    }
                    return scopedAddressBook;
                });
    }

    private Optional<Network> getStartupAddressBookAt(@NonNull final Path path) {
        if (Files.exists(path)) {
            try (final var fin = Files.newInputStream(path)) {
                return Optional.of(Network.JSON.parse(new ReadableStreamingData(fin)));
            } catch (Exception ignore) {
            }
        }
        return Optional.empty();
    }
}
