// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testcontainers.DockerClientFactory;
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

    private static final DockerImageName TOXIPROXY_IMAGE = DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0");

    /** How long to wait for the control API to serve again after a restart. */
    private static final Duration RESTART_READINESS_TIMEOUT = Duration.ofSeconds(60L);

    /** Per-probe connect/request timeout while polling the control API after a restart. */
    private static final Duration CONTROL_PROBE_TIMEOUT = Duration.ofSeconds(2L);

    /** Delay between readiness probes after a restart. */
    private static final Duration CONTROL_PROBE_INTERVAL = Duration.ofMillis(250L);

    private final String networkId;

    /**
     * Constructs a new NetworkContainer instance using the Toxiproxy image.
     *
     * @param network the Docker network to attach the container to
     */
    public ToxiproxyContainer(final Network network) {
        super(TOXIPROXY_IMAGE);
        this.networkId = network.getId();

        log.info("Starting Toxiproxy container");

        setNetwork(network);
        setNetworkAliases(List.of(ALIAS));
        addExposedPort(CONTROL_PORT);
        setWaitStrategy(new HttpWaitStrategy().forPath("/version").forPort(CONTROL_PORT));
    }

    /**
     * Returns the IP address of the Toxiproxy container.
     *
     * @return the IP address of the Toxiproxy container
     */
    public String getNetworkIpAddress() {
        final Map<String, ContainerNetwork> networks =
                getContainerInfo().getNetworkSettings().getNetworks();
        final ContainerNetwork network = networks.values().stream()
                .filter(it -> networkId.equals(it.getNetworkID()))
                .findAny()
                .orElseThrow();
        return network.getIpAddress();
    }

    /**
     * Returns the host port currently mapped to the Toxiproxy control port. Unlike {@link #getMappedPort(int)}, which is
     * cached when the container first starts, this re-reads the live binding from Docker &ndash; necessary after
     * {@link #restart()}, which re-publishes the control port to a new host port.
     *
     * @return the host port currently mapped to the Toxiproxy control port
     */
    public int getControlPort() {
        final Ports.Binding[] bindings = getCurrentContainerInfo()
                .getNetworkSettings()
                .getPorts()
                .getBindings()
                .get(ExposedPort.tcp(CONTROL_PORT));
        if (bindings == null || bindings.length == 0) {
            throw new IllegalStateException("Toxiproxy control port is not published");
        }
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    /**
     * Restarts the underlying Toxiproxy process, discarding all proxies and any wedged control-plane state, then blocks
     * until the control API is serving again. A wedged proxy cannot be recovered through the REST API, so bouncing the
     * process is the only reliable way to clear it.
     *
     * <p>The container keeps its identity and its Docker-network IP across the restart, so the proxies' internal listen
     * addresses stay valid and nodes reconnect transparently once the proxies are rebuilt. The host-mapped control port,
     * however, is re-published to a new host port, so callers must re-read it via {@link #getControlPort()} and repoint
     * their control clients afterwards.
     */
    public void restart() {
        log.info("Restarting Toxiproxy container to clear any wedged control-plane state");
        DockerClientFactory.instance()
                .client()
                .restartContainerCmd(getContainerId())
                .exec();
        awaitControlApi();
    }

    /**
     * Polls the control API's {@code /version} endpoint until it responds or {@link #RESTART_READINESS_TIMEOUT} elapses.
     */
    private void awaitControlApi() {
        final URI versionUri = URI.create("http://%s:%d/version".formatted(getHost(), getControlPort()));
        final HttpClient client =
                HttpClient.newBuilder().connectTimeout(CONTROL_PROBE_TIMEOUT).build();
        final HttpRequest request = HttpRequest.newBuilder(versionUri)
                .timeout(CONTROL_PROBE_TIMEOUT)
                .GET()
                .build();
        final long deadlineNanos = System.nanoTime() + RESTART_READINESS_TIMEOUT.toNanos();
        while (true) {
            try {
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (final IOException e) {
                // The control server is not accepting requests yet; fall through and retry until the deadline.
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Toxiproxy to restart", e);
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new IllegalStateException("Toxiproxy did not become ready within %s after being restarted"
                        .formatted(RESTART_READINESS_TIMEOUT));
            }
            try {
                Thread.sleep(CONTROL_PROBE_INTERVAL.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Toxiproxy to restart", e);
            }
        }
    }
}
