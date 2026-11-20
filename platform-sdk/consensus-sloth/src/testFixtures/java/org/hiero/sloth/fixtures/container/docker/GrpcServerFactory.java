// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container.docker;

import com.hedera.pbj.grpc.helidon.PbjRouting;
import com.hedera.pbj.grpc.helidon.config.PbjConfig;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.helidon.webserver.WebServer;
import java.time.Duration;

/**
 * Creates the web servers that expose the gRPC services of the container to the test framework.
 */
final class GrpcServerFactory {

    /**
     * The maximum size of a request message. A start request carries the roster and the keys of the node, which does
     * not fit into the 10 KB that PBJ allows by default.
     */
    private static final int MAX_REQUEST_SIZE_BYTES = 4 * 1024 * 1024;

    /**
     * The time after which the server closes a connection that has not received a request. The test framework keeps
     * its connections open for the whole test and may not send a request for a long time, so they must outlive the 5
     * minutes that Helidon allows by default.
     */
    private static final Duration IDLE_CONNECTION_TIMEOUT = Duration.ofDays(1);

    // Utility class, do not instantiate.
    private GrpcServerFactory() {}

    /**
     * Creates a web server that serves the given gRPC service. The server is not started.
     *
     * @param port the port on which the server listens
     * @param service the gRPC service to serve
     * @return the web server
     */
    @NonNull
    static WebServer create(final int port, @NonNull final ServiceInterface service) {
        final PbjConfig pbjConfig = PbjConfig.builder()
                .name("pbj")
                .maxMessageSizeBytes(MAX_REQUEST_SIZE_BYTES)
                .build();
        return WebServer.builder()
                .port(port)
                .idleConnectionTimeout(IDLE_CONNECTION_TIMEOUT)
                .addProtocol(pbjConfig)
                .addRouting(PbjRouting.builder().service(service))
                .build();
    }
}
