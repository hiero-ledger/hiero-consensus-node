// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.DESTROYED;
import static org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle.RUNNING;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.ServiceEndpoint;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.AsyncNodeActions;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePcesResult;
import org.hiero.otter.fixtures.result.SingleNodeStatusProgression;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.shaded.com.github.dockerjava.core.MediaType;

/**
 * Implementation of {@link Node} for a container environment.
 */
public class ContainerNode extends AbstractNode implements Node {

    private static final Logger log = LogManager.getLogger();

    private static final int GOSSIP_PORT = 5777;
    private static final int CONTROL_PORT = 8080;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);

    private final GenericContainer<?> container;
    private final RosterEntry rosterEntry;
    private final AsyncNodeActions defaultAsyncAction = withTimeout(DEFAULT_TIMEOUT);
    private final ContainerNodeConfiguration nodeConfiguration = new ContainerNodeConfiguration();

    private Roster roster = Roster.DEFAULT;

    /**
     * Constructor for the {@link ContainerNode} class.
     *
     * @param selfId the unique identifier for this node
     * @param network the network this node is part of
     * @param dockerImage the Docker image to use for this node
     * @throws IOException if an I/O error occurs while starting the container
     * @throws InterruptedException if the thread is interrupted while starting the container
     */
    public ContainerNode(@NonNull final NodeId selfId, @NonNull final Network network,
            @NonNull final ImageFromDockerfile dockerImage) throws IOException, InterruptedException {
        super(selfId);

        final Consumer<OutputFrame> logWriter = frame ->
                log.log(frame.getType() == OutputType.STDERR ? Level.ERROR : Level.INFO, frame.getUtf8String());
        final PlatformStatusLogParser platformStatusLogParser =
                new PlatformStatusLogParser(newValue -> platformStatus = newValue);

        final String alias = "node" + selfId;
        this.container = new GenericContainer<>(dockerImage)
                .withNetwork(network)
                .withNetworkAliases(alias)
                .withLogConsumer(logWriter.andThen(platformStatusLogParser))
                .withExposedPorts(8080);
        container.start();

        final String response = new PostCommand()
                .withPath("self-id")
                .withContentType(MediaType.APPLICATION_JSON.getMediaType())
                .withContent("{\"selfId\":" + selfId.id() + "}")
                .withErrorMessage("Failed to set self ID")
                .send();
        final String base64 = new ObjectMapper().readTree(response).get("sigcrt").asText();
        final byte[] sigCertBytes = Base64.getDecoder().decode(base64);

        this.rosterEntry = RosterEntry.newBuilder()
                .nodeId(selfId.id())
                .weight(1L)
                .gossipCaCertificate(Bytes.wrap(sigCertBytes))
                .gossipEndpoint(ServiceEndpoint.newBuilder().domainName(alias).port(GOSSIP_PORT).build())
                .build();
    }

    /**
     * Returns the {@link RosterEntry} for this node.
     *
     * @return the roster entry associated with this node
     */
    RosterEntry rosterEntry() {
        return rosterEntry;
    }

    /**
     * Sets the roster for this node.
     *
     * @param roster the new roster used during the next start of the node
     */
    void setRoster(@NonNull final Roster roster) {
        throwIfIn(RUNNING, "Cannot set roster while the node is running");
        throwIfIn(DESTROYED, "Cannot set roster after the node has been destroyed");

        this.roster = requireNonNull(roster);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void killImmediately() throws InterruptedException {
        defaultAsyncAction.killImmediately();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdownGracefully() throws InterruptedException {
        defaultAsyncAction.shutdownGracefully();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws IOException, InterruptedException {
        defaultAsyncAction.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AsyncNodeActions withTimeout(@NonNull final Duration timeout) {
        return new ContainerAsyncNodeActions(timeout);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final byte[] transaction) {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public NodeConfiguration configuration() {
        return nodeConfiguration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeConsensusResult getConsensusResult() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeLogResult getLogResult() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodeStatusProgression getStatusProgression() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public SingleNodePcesResult getPcesResult() {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * Shuts down the node and cleans up resources. Once this method is called, the node cannot be started again. This
     * method is idempotent and can be called multiple times without any side effects.
     */
    void destroy() {
        container.stop();
    }

    /**
     * Container-specific implementation of {@link AsyncNodeActions}.
     */
    private class ContainerAsyncNodeActions implements AsyncNodeActions {

        private final Duration timeout;

        /**
         * Constructor for the {@link ContainerAsyncNodeActions} class.
         *
         * @param timeout the duration to wait for actions to complete
         */
        public ContainerAsyncNodeActions(@NonNull final Duration timeout) {
            this.timeout = timeout;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void start() throws IOException, InterruptedException {
            throwIfIn(LifeCycle.RUNNING, "Node has already been started.");
            throwIfIn(LifeCycle.DESTROYED, "Node has already been destroyed.");

            log.info("Starting node {}...", selfId);

            final ObjectMapper mapper = new ObjectMapper();
            final Map<String, Object> initConfig = Map.of(
                    "version", SemanticVersion.JSON.toJSON(version),
                    "roster", Roster.JSON.toJSON(roster),
                    "properties", nodeConfiguration.overriddenProperties()
            );
            final String content = mapper.writeValueAsString(initConfig);
            new PostCommand()
                    .withPath("start-node")
                    .withContentType(MediaType.APPLICATION_JSON.getMediaType())
                    .withContent(content)
                    .withErrorMessage("Failed to set startup configuration")
                    .send();

            container.start();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void shutdownGracefully() {
            throw new UnsupportedOperationException("Not implemented yet!");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void killImmediately() {
            log.info("Killing node {} immediately...", selfId);
            container.stop();
        }
    }

    /**
     * Helper class to send a POST request to the node's control interface.
     */
    class PostCommand {

        private String path;
        private String contentType;
        private String content;
        private String errorMessage;

        private String send() throws IOException, InterruptedException {
            try (final HttpClient client = HttpClient.newHttpClient()) {
                final String host = container.getHost();
                final Integer port = container.getMappedPort(CONTROL_PORT);
                final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create("http://%s:%d/%s".formatted(host, port, path)))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(content));
                final HttpResponse<String> response = client.send(requestBuilder.build(), BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    throw new IOException(
                            "%s (Error: %d): %s".formatted(errorMessage, response.statusCode(), response.body()));
                }
                return response.body();
            }
        }

        private PostCommand withPath(@NonNull final String path) {
            this.path = path;
            return this;
        }

        private PostCommand withContentType(@NonNull final String contentType) {
            this.contentType = contentType;
            return this;
        }

        private PostCommand withContent(@NonNull final String content) {
            this.content = content;
            return this;
        }

        private PostCommand withErrorMessage(@NonNull final String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }
    }
}
