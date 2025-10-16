// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container.utils;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.swirlds.common.config.StateCommonConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.lang3.exception.UncheckedInterruptedException;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.util.OtterSavedStateUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

public class ContainerUtils {
    private ContainerUtils() {
        // Utility class
    }

    /**
     * Copies a saved state directory to a container's data/saved directory.
     * This method creates a temporary directory, copies the saved state for the specified node,
     * transfers it to the container, and ensures proper ownership permissions are set.
     *
     * @param container the container to copy the saved state to
     * @param selfId the node ID of the container
     * @param stateCommonConfig stateCommonConfig
     * @param savedStateDirectory the path to the saved state directory on the host filesystem
     * @throws UncheckedIOException if an I/O error occurs while copying the saved state
     */
    public static void copySavedStateToContainer(
            @NonNull final GenericContainer<?> container,
            @NonNull final NodeId selfId,
            @NonNull final StateCommonConfig stateCommonConfig,
            @NonNull final Path savedStateDirectory) {
        requireNonNull(container, "container must not be null");
        requireNonNull(selfId, "selfId must not be null");
        requireNonNull(stateCommonConfig, "stateCommonConfig must not be null");
        requireNonNull(savedStateDirectory, "savedStateDirectory must not be null");
        try {
            final Path tempDir = Files.createTempDirectory("state-");
            OtterSavedStateUtils.copySaveState(selfId, savedStateDirectory, tempDir);

            final Path dockerPath = Path.of(CONTAINER_APP_WORKING_DIR).resolve(stateCommonConfig.savedStateDirectory());

            container.copyFileToContainer(
                    MountableFile.forHostPath(tempDir.resolve(stateCommonConfig.savedStateDirectory())),
                    dockerPath.toString());
            final DockerClient client = DockerClientFactory.instance().client();
            final ExecCreateCmdResponse exec = client.execCreateCmd(container.getContainerId())
                    .withUser("root")
                    .withCmd("sh", "-lc", "chown -R appuser:appuser " + CONTAINER_APP_WORKING_DIR + "/data")
                    .exec();

            try (final ResultCallbackTemplate<?, Frame> stream =
                    client.execStartCmd(exec.getId()).start()) {
                stream.awaitCompletion();
            } catch (final InterruptedException e) {
                throw new UncheckedInterruptedException(e);
            }
        } catch (final IOException exception) {
            throw new UncheckedIOException("Unable to copy saved state directory", exception);
        }
    }
}
