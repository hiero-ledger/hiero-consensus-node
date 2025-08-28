// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * This class is a wrapper around a Toxiproxy container.
 */
public class ToxiproxyContainer extends GenericContainer<ToxiproxyContainer> {

    private static final Logger log = LogManager.getLogger();

    /** The alias for the Toxiproxy container in the Docker network. */
    public static final String ALIAS = "toxiproxy";

    /** The control port on which the Toxiproxy server is running. */
    public static final int CONTROL_PORT = 8474;

    private static final DockerImageName TOXIPROXY_IMAGE = DockerImageName.parse("ghcr.io/shopify/toxiproxy");

    /**
     * Constructs a new NetworkContainer instance using the Toxiproxy image.
     *
     * @param network the Docker network to attach the container to
     */
    public ToxiproxyContainer(final Network network) {
        super(TOXIPROXY_IMAGE);

        log.info("Starting Toxiproxy container");

        setNetwork(network);
        setNetworkAliases(List.of(ALIAS));
        addExposedPort(CONTROL_PORT);
        setWaitStrategy(new HttpWaitStrategy().forPath("/version").forPort(CONTROL_PORT));
    }
}
