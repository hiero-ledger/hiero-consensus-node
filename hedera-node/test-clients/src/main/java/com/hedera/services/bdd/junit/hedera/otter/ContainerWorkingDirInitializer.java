// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.otter;

import static com.hedera.node.app.info.DiskStartupNetworks.GENESIS_NETWORK_JSON;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CONFIG_FOLDER;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CONFIG_TXT;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.CURRENT_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.DATA_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.UPGRADE_DIR;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.WORKING_DIR_DATA_FOLDERS;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.bootstrapAssetsLoc;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.networkFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.node.internal.network.Network;
import com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.OnlyRoster;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.hiero.otter.fixtures.container.ContainerNode;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;

/**
 * Utility methods for managing the working directory of a containerized Hedera node.
 */
public class ContainerWorkingDirInitializer {

    private final GenericContainer<?> container;
    private final Map<String, String> overriddenProperties;
    private final Path workingDir;
    private final String configTxt;

    public ContainerWorkingDirInitializer(
            @NonNull final ContainerNode node,
            @NonNull final Path workingDir,
            @NonNull final String configTxt) {
        this.container = requireNonNull(node.container());
        this.overriddenProperties = requireNonNull(node.configuration().overriddenProperties());
        this.workingDir = requireNonNull(workingDir);
        this.configTxt = requireNonNull(configTxt);
    }

    /**
     * Initializes the working directory by deleting it and creating a new one with the given <i>config.txt</i> file.
     */
    public void recreateWorkingDir() {
        // Clean up any existing directory structure
        removeWorkingDir();
        // Initialize the data folders
        WORKING_DIR_DATA_FOLDERS.forEach(folder ->
                createDirectoriesUnchecked(workingDir.resolve(DATA_DIR).resolve(folder)));
        // Initialize the current upgrade folder
        createDirectoriesUnchecked(
                workingDir.resolve(DATA_DIR).resolve(UPGRADE_DIR).resolve(CURRENT_DIR));
        // Write the address book (config.txt) and genesis network (genesis-network.json) files
        writeStringUnchecked(workingDir.resolve(CONFIG_TXT), configTxt);
        final var network = networkFrom(configTxt, OnlyRoster.NO);
        writeStringUnchecked(
                workingDir.resolve(DATA_DIR).resolve(CONFIG_FOLDER).resolve(GENESIS_NETWORK_JSON),
                Network.JSON.toJSON(network));
        // Copy the bootstrap assets into the working directory
        copyBootstrapAssets(bootstrapAssetsLoc());
        // Update properties in application.properties
        updateApplicationProperties();
    }

    /**
     * Deletes the working directory inside the container.
     */
    private void removeWorkingDir() {
        try {
            final String containerPath = workingDir.resolve("*").toString();
            final ExecResult result = container.execInContainer("rm", "-rf", containerPath);
            if (result.getExitCode() != 0) {
                throw new IOException("Failed to remove path: " + containerPath + "\n" + result.getStderr());
            }
        } catch (final IOException | InterruptedException e) {
            throw new UncheckedIOException(new IOException("Error removing path in container", e));
        }
    }

    private void createDirectoriesUnchecked(@NonNull final Path dir) {
        try {
            final String containerPath = dir.toString();
            final ExecResult result = container.execInContainer("mkdir", "-p", containerPath);
            if (result.getExitCode() != 0) {
                throw new IOException("Failed to create path: " + containerPath + "\n" + result.getStderr());
            }
        } catch (final IOException | InterruptedException e) {
            throw new UncheckedIOException(new IOException("Error creating path in container", e));
        }
    }

    private void writeStringUnchecked(@NonNull final Path path, @NonNull final String content) {
        try {
            final String containerPath = path.toString();
            final ExecResult result = container.execInContainer(
                    "sh", "-c",
                    String.format("printf '%%s' '%s' > %s", content, containerPath));
            if (result.getExitCode() != 0) {
                throw new IOException("Failed to write to path: " + containerPath + "\n" + result.getStderr());
            }
        } catch (final IOException | InterruptedException e) {
            throw new UncheckedIOException(new IOException("Error writing to path in container", e));
        }
    }

    private void copyToContainerUnchecked(@NonNull final Path source, @NonNull final Path target) {
        try {
            // Read the file content from the host
            final String content = Files.readString(source).replace("'", "'\"'\"'");

            // Create the file directly in the container with proper ownership from the start
            final String containerPath = target.toString();
            final ExecResult result = container.execInContainer(
                    "sh", "-c",
                    String.format("printf '%%s' '%s' > %s", content, containerPath));

            if (result.getExitCode() != 0) {
                throw new IOException(
                        "Failed to create file in container: " + source + "\n" + result.getStderr());
            }
        } catch (final IOException | InterruptedException e) {
            throw new UncheckedIOException(new IOException("Error copying file to container", e));
        }
    }

    private void copyBootstrapAssets(@NonNull final Path assetDir) {
        try (final var files = Files.walk(assetDir)) {
            files.filter(file -> !file.equals(assetDir)).forEach(file -> {
                final var fileName = file.getFileName().toString();
                if (fileName.endsWith(".properties") || fileName.endsWith(".json")) {
                    copyToContainerUnchecked(
                            file,
                            workingDir
                                    .resolve(DATA_DIR)
                                    .resolve(CONFIG_FOLDER)
                                    .resolve(file.getFileName().toString()));
                } else {
                    copyToContainerUnchecked(file, workingDir.resolve(file.getFileName().toString()));
                }
            });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void updateApplicationProperties() {
        for (final Map.Entry<String, String> entry : overriddenProperties.entrySet()) {
            updateApplicationProperty(entry.getKey(), entry.getValue());
        }
    }

    private void updateApplicationProperty(@NonNull final String key, @NonNull final String subFolder) {
        try {
            final String containerPath = workingDir
                    .resolve(DATA_DIR)
                    .resolve(CONFIG_FOLDER)
                    .resolve("application.properties")
                    .toString();
            final String update = key + "=" + workingDir.resolve(subFolder);
            final ExecResult result = container.execInContainer("sh", "-c", String.format("printf '%%s\\n' '%s' >> %s", update, containerPath));
            if (result.getExitCode() != 0) {
                throw new IOException("Failed to add property: " + update + "\n" + result.getStderr());
            }
        } catch (final IOException | InterruptedException e) {
            throw new UncheckedIOException(new IOException("Error updating property in container", e));
        }
    }


}
