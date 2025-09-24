// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.containers;

import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A test container for running a block node server instance.
 */
public class BlockNodeContainer extends GenericContainer<BlockNodeContainer> {
    private static final String BLOCK_NODE_VERSION = "0.17.0";
    private static final DockerImageName DEFAULT_IMAGE_NAME =
            DockerImageName.parse("ghcr.io/hiero-ledger/hiero-block-node:" + BLOCK_NODE_VERSION);
    private static final int GRPC_PORT = 40840;
    private static final int HEALTH_PORT = 16007;
    private String containerId;

    /**
     * Creates a new block node container with the default image.
     * @param blockNodeId the id of the block node
     * @param port the internal port of the block node container to expose
     */
    public BlockNodeContainer(final long blockNodeId, final int port) {
        this(DEFAULT_IMAGE_NAME, blockNodeId, port);
    }

    /**
     * Creates a new block node container with the specified image.
     *
     * @param dockerImageName the docker image to use
     */
    private BlockNodeContainer(DockerImageName dockerImageName, final long blockNodeId, final int port) {
        super(dockerImageName);

        // Expose the gRPC port for block node communication
        this.addFixedExposedPort(port, GRPC_PORT);
        // Also expose the health check port
        this.addExposedPort(HEALTH_PORT);
        this.withNetworkAliases("block-node-" + blockNodeId)
                .withEnv("VERSION", BLOCK_NODE_VERSION)
                // Use HTTP health check on the health port to verify the service is ready
                .waitingFor(Wait.forHttp("/-/healthy").forPort(HEALTH_PORT).withStartupTimeout(Duration.ofMinutes(2)));
    }

    @Override
    public void start() {
        if (!isRunning()) {
            super.start();
        }
        waitForHealthy(Duration.ofMinutes(2));
        containerId = getContainerId();
    }

    @Override
    public void stop() {
        if (isRunning()) {
            super.stop();
        }
    }

    /**
     * Gets the mapped port for the block node gRPC server.
     *
     * @return the host port mapped to the container's internal port
     */
    public int getPort() {
        return getMappedPort(GRPC_PORT);
    }

    /**
     * Waits for the block node container to be healthy by configuring the health check timeout.
     *
     * @param timeout the maximum duration to wait for the container's health check to pass
     */
    public void waitForHealthy(final Duration timeout) {
        this.waitingFor(Wait.forHealthcheck().withStartupTimeout(timeout));
    }

    /**
     * Pauses the container, freezing all processes inside it.
     * The container will remain in memory but will not consume CPU resources.
     */
    public void pause() {
        if (!isRunning()) {
            throw new IllegalStateException("Cannot pause container that is not running");
        }

        try (StopContainerCmd stopContainerCmd = getDockerClient().stopContainerCmd(containerId)) {
            stopContainerCmd.exec();
        } catch (Exception e) {
            throw new RuntimeException("Failed to pause container: " + containerId, e);
        }
    }

    /**
     * Resumes the container, resuming all processes inside it.
     */
    public void resume() {
        try (StartContainerCmd startContainerCmd = getDockerClient().startContainerCmd(containerId)) {
            startContainerCmd.exec();

            // Wait a moment for the container to fully resume
            try {
                Thread.sleep(1000); // 1-second warm-up period
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to resume container: " + containerId, e);
        }
    }

    @Override
    public String toString() {
        return this.getHost() + ":" + this.getPort();
    }
}
