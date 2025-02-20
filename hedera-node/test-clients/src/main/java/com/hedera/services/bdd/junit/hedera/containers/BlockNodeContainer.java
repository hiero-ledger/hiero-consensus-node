// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.containers;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A testcontainer for running a block node server instance.
 */
public class BlockNodeContainer extends GenericContainer<BlockNodeContainer> {
    private static final int INTERNAL_PORT = 8080;
    private static final DockerImageName DEFAULT_IMAGE_NAME = DockerImageName.parse("block-node-server:0.4.0-SNAPSHOT");
    private static final String blockNodeVersion = "0.4.0-SNAPSHOT";

    /**
     * Creates a new block node container with the default image.
     */
    public BlockNodeContainer() {
        this(DEFAULT_IMAGE_NAME);
    }

    /**
     * Creates a new block node container with the specified image.
     *
     * @param dockerImageName the docker image to use
     */
    public BlockNodeContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        withExposedPorts(INTERNAL_PORT);
        withEnv("VERSION", blockNodeVersion);
        waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        waitingFor(Wait.forHealthcheck());
    }

    /**
     * Gets the mapped port for the block node gRPC server.
     *
     * @return the host port mapped to the container's internal port
     */
    public int getGrpcPort() {
        return getMappedPort(INTERNAL_PORT);
    }
}
