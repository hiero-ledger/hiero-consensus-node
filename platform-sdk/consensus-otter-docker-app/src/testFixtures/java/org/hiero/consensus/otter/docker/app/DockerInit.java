// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.crypto.CryptoStatic;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.cert.CertificateEncodingException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.otter.docker.app.netty.NettyRestServer;
import org.hiero.consensus.otter.docker.app.platform.DockerApp;
import org.hiero.otter.fixtures.logging.internal.InMemoryAppender;

/**
 * A simple REST server that controls the consensus node in a Docker container.
 */
public class DockerInit {

    private final NettyRestServer server;

    private NodeId selfId;
    private KeysAndCerts keysAndCerts;

    private DockerInit() {
        server = new NettyRestServer(8080);

        // POST /hello
        server.addPost("/hello", (req, body) -> {
            try {
                final Map<?, ?> json = new ObjectMapper().readValue(body, Map.class);
                if (json.containsKey("name")) {
                    final String name = json.get("name").toString();
                    return Map.of("answer", "Hello " + name + "!");
                }
                return Map.of("answer", "Hello World!");
            } catch (final Exception e) {
                return Map.of("error", "Invalid JSON");
            }
        });

        server.addPost("/self-id", (req, body) -> {
            try {
                final JsonNode json = new ObjectMapper().readTree(body);
                selfId = NodeId.of(json.get("selfId").asLong());
                if (selfId.id() < 1L) {
                    throw new IllegalArgumentException("selfId must be a positive number");
                }
                keysAndCerts = generateKeysAndCerts(selfId);
                final String content = Base64.getEncoder().encodeToString(keysAndCerts.sigCert().getEncoded());
                return Map.of("sigcrt", content);
            } catch (final IOException e) {
                throw new IllegalArgumentException(e);
            } catch (final CertificateEncodingException e) {
                // This should not happen, as we just generated the certificate
                throw new RuntimeException(e);
            }
        });

        server.addPost("/start-node", (req, body) -> {
            try {
                final ObjectMapper mapper = new ObjectMapper();
                final JsonNode json = mapper.readTree(body);

                final SemanticVersion version = SemanticVersion.JSON.parse(Bytes.wrap(json.get("version").textValue()));
                final Roster roster = Roster.JSON.parse(Bytes.wrap(json.get("roster").asText()));
                // noinspection unchecked
                final Map<String, String> overriddenProperties = (Map<String, String>)mapper.convertValue(json.get("properties"), Map.class);

                final DockerApp app = new DockerApp(selfId, version, roster, keysAndCerts, overriddenProperties);
                app.start();

                return Map.of("node", "started");
            } catch (final IOException | ParseException | ClassCastException e) {
                throw new IllegalArgumentException(e);
            }
        });

        server.addGet("/logs", req -> InMemoryAppender.getLogs());
    }

    private static KeysAndCerts generateKeysAndCerts(@NonNull final NodeId selfId) {
        try {
            return CryptoStatic.generateKeysAndCerts(List.of(selfId), null).get(selfId);
        } catch (final ExecutionException | InterruptedException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * Starts the web server that listens for requests to control the Docker container.
     *
     * @throws InterruptedException if the thread is interrupted while starting the server
     */
    public void startWebserver() throws InterruptedException {
        server.start();
    }

    /**
     * Main method to start the DockerInit web server.
     *
     * @param args command line arguments (not used)
     * @throws Exception if an error occurs while starting the web server
     */
    public static void main(final String[] args) throws Exception {
        new DockerInit().startWebserver();
    }
}
