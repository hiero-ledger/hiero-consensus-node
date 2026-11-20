// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.grpc.client.helidon.PbjGrpcClient;
import com.hedera.pbj.grpc.client.helidon.PbjGrpcClientConfig;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.grpc.GrpcClient;
import com.hedera.pbj.runtime.grpc.GrpcCompression;
import com.hedera.pbj.runtime.grpc.GrpcException;
import com.hedera.pbj.runtime.grpc.GrpcStatus;
import com.hedera.pbj.runtime.grpc.ServiceInterface;
import com.hedera.pbj.runtime.grpc.ServiceInterface.RequestOptions;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.common.tls.Tls;
import io.helidon.webclient.api.WebClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A connection to a gRPC service that runs in a container.
 *
 * <p>The underlying network connection is established when the first call is made. It is discarded when a call fails
 * because the service is unavailable, so that the next call establishes a new one. Every failed call is reported as a
 * {@link GrpcException}, whether the service reported the error or the service could not be reached.
 *
 * @param <C> the type of the client of the gRPC service
 */
final class GrpcConnection<C extends ServiceInterface> {

    private static final Logger log = LogManager.getLogger(GrpcConnection.class);

    /** The maximum size of a message received from a service. A single consensus round can be several megabytes. */
    private static final int MAX_MESSAGE_SIZE_BYTES = 32 * 1024 * 1024;

    /**
     * The maximum size of the buffer in which incoming messages are assembled. It has to hold a message of the
     * maximum size plus the data that arrives before that message has been handed over.
     */
    private static final int MAX_INCOMING_BUFFER_SIZE_BYTES = 2 * MAX_MESSAGE_SIZE_BYTES;

    /** How long a call waits for data before it pings the service to find out whether the connection is broken. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /** How long to wait for the network connection to be established. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** The options sent with every request. */
    private static final RequestOptions REQUEST_OPTIONS =
            new Options(Optional.empty(), RequestOptions.APPLICATION_GRPC);

    /** The host on which the service is reachable. */
    private final String host;

    /** The port on which the service is reachable. */
    private final int port;

    /** Creates the client of the service for a given network connection. */
    private final BiFunction<GrpcClient, RequestOptions, C> clientFactory;

    /** The client that uses the current network connection, or {@code null} if there is none. */
    @Nullable
    private C client;

    /**
     * Creates a new {@link GrpcConnection}. The network connection is not established until the first call.
     *
     * @param host the host on which the service is reachable
     * @param port the port on which the service is reachable
     * @param clientFactory creates the client of the service for a given network connection
     */
    GrpcConnection(
            @NonNull final String host,
            final int port,
            @NonNull final BiFunction<GrpcClient, RequestOptions, C> clientFactory) {
        this.host = requireNonNull(host);
        this.port = port;
        this.clientFactory = requireNonNull(clientFactory);
    }

    /**
     * Establishes a new network connection to a gRPC service and returns a client that uses it. The caller owns the
     * client and has to close it.
     *
     * @param host the host on which the service is reachable
     * @param port the port on which the service is reachable
     * @param clientFactory creates the client of the service for a given network connection
     * @param <C> the type of the client of the gRPC service
     * @return the client of the service
     * @throws GrpcException with status {@link GrpcStatus#UNAVAILABLE} if the connection cannot be established
     */
    @NonNull
    static <C extends ServiceInterface> C connect(
            @NonNull final String host,
            final int port,
            @NonNull final BiFunction<GrpcClient, RequestOptions, C> clientFactory) {
        final Tls tls = Tls.builder().enabled(false).build();
        final WebClient webClient = WebClient.builder()
                .baseUri("http://" + host + ":" + port)
                .tls(tls)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        final PbjGrpcClientConfig config = new PbjGrpcClientConfig(
                READ_TIMEOUT,
                tls,
                REQUEST_OPTIONS.authority(),
                REQUEST_OPTIONS.contentType(),
                GrpcCompression.IDENTITY,
                GrpcCompression.getDecompressorNames(),
                MAX_MESSAGE_SIZE_BYTES,
                MAX_INCOMING_BUFFER_SIZE_BYTES);
        try {
            return clientFactory.apply(new PbjGrpcClient(webClient, config), REQUEST_OPTIONS);
        } catch (final RuntimeException e) {
            throw failure(GrpcStatus.UNAVAILABLE, "Failed to connect to " + host + ":" + port, e);
        }
    }

    /**
     * Invokes a unary call and waits for its reply without a time limit.
     *
     * @param rpc the call to invoke on the client of the service
     * @param <T> the type of the reply
     * @return the reply of the service
     * @throws GrpcException if the call fails
     */
    @NonNull
    <T> T call(@NonNull final Function<C, T> rpc) {
        final C usedClient = client();
        try {
            return rpc.apply(usedClient);
        } catch (final RuntimeException e) {
            final GrpcException grpcException = toGrpcException(e);
            if (grpcException.status() == GrpcStatus.UNAVAILABLE) {
                discard(usedClient);
            }
            throw grpcException;
        }
    }

    /**
     * Invokes a unary call and waits for its reply for at most the given time.
     *
     * @param rpc the call to invoke on the client of the service
     * @param timeout the maximum time to wait for the reply
     * @param <T> the type of the reply
     * @return the reply of the service
     * @throws GrpcException if the call fails, or with status {@link GrpcStatus#DEADLINE_EXCEEDED} if no reply
     * arrives in time
     */
    @NonNull
    <T> T call(@NonNull final Function<C, T> rpc, @NonNull final Duration timeout) {
        // The clients have no notion of a deadline, so the call runs on its own thread and is abandoned on timeout.
        final FutureTask<T> task = new FutureTask<>(() -> call(rpc));
        Thread.ofVirtual().name("grpc-call-" + host + ":" + port).start(task);
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final TimeoutException e) {
            task.cancel(true);
            throw failure(GrpcStatus.DEADLINE_EXCEEDED, "No reply within " + timeout, e);
        } catch (final ExecutionException e) {
            // The task has already converted its failure
            throw e.getCause() instanceof final GrpcException grpcException ? grpcException : toGrpcException(e);
        } catch (final InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(GrpcStatus.CANCELLED, "Interrupted while waiting for the reply", e);
        }
    }

    /**
     * Closes the network connection, if there is one. A later call establishes a new connection.
     */
    synchronized void close() {
        if (client != null) {
            closeQuietly(client);
            client = null;
        }
    }

    @NonNull
    private synchronized C client() {
        if (client == null) {
            client = connect(host, port, clientFactory);
        }
        return client;
    }

    /**
     * Closes the network connection of the given client, unless a newer connection has replaced it already.
     */
    private synchronized void discard(@NonNull final C failedClient) {
        if (client == failedClient) {
            closeQuietly(failedClient);
            client = null;
        }
    }

    private void closeQuietly(@NonNull final C clientToClose) {
        try {
            clientToClose.close();
        } catch (final RuntimeException e) {
            log.debug("Failed to close the connection to {}:{}", host, port, e);
        }
    }

    /**
     * Converts a failure of a call into a {@link GrpcException}. An error reported by the service keeps its status.
     * Everything else means that the service could not be reached, unless the reply was received but unreadable or
     * the calling thread was interrupted.
     */
    @NonNull
    private static GrpcException toGrpcException(@NonNull final Exception failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof final GrpcException reported) {
                final String description = reported.getMessage();
                return failure(reported.status(), description == null ? "" : description, failure);
            }
            if (t instanceof ParseException) {
                return failure(GrpcStatus.INTERNAL, "Failed to parse the reply", failure);
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return failure(GrpcStatus.CANCELLED, "Interrupted while waiting for the reply", failure);
            }
        }
        return failure(GrpcStatus.UNAVAILABLE, "The service is unavailable", failure);
    }

    /**
     * Creates the exception for a failed call. Its message starts with the status, because the status is not part
     * of the string representation of a {@link GrpcException}.
     */
    @NonNull
    private static GrpcException failure(
            @NonNull final GrpcStatus status, @NonNull final String description, @NonNull final Throwable cause) {
        return new GrpcException(status, description.isEmpty() ? status.name() : status + ": " + description, cause);
    }

    /** The options sent with every request. */
    private record Options(
            @NonNull Optional<String> authority, @NonNull String contentType) implements RequestOptions {}
}
