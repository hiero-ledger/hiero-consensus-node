// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import com.google.protobuf.Empty;
import com.hedera.hapi.platform.state.legacy.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import org.hiero.consensus.otter.docker.app.platform.DockerApp;
import org.hiero.otter.fixtures.KeysAndCertsConverter;
import org.hiero.otter.fixtures.ProtobufConverter;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.KillImmediatelyRequest;
import org.hiero.otter.fixtures.container.proto.StartRequest;
import org.hiero.otter.fixtures.container.proto.TestControlGrpc;
import org.hiero.otter.fixtures.container.proto.TransactionRequest;
import org.hiero.otter.fixtures.logging.internal.InMemoryAppender;
import org.hiero.otter.fixtures.result.SubscriberAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boots a {@link DockerApp} inside a minimal gRPC wrapper.
 * <p>
 * The class is a tiny self-contained launcher that exposes only a single
 * {@code start()} RPC through which a test harness can supply the information
 * required to bootstrap a {@link DockerApp}. All mutable state is kept inside
 * the singleton instance and never published outside this class.
 */
public final class DockerInit {

    /** Logger */
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerInit.class);

    /** Singleton instance exposed for the gRPC service. */
    static final DockerInit INSTANCE = new DockerInit();

    /** Port on which the gRPC service listens. */
    private static final int GRPC_PORT = 8080;

    /** Underlying gRPC server. */
    private final Server grpcServer;

    /** Executor responsible for running background tasks */
    private final ExecutorService executor;

    /** Lazily initialised docker app; only set once. */
    private DockerApp app;

    private volatile boolean cancelled = false;

    /**
     * Outbound queue used by the dispatcher thread to forward messages to the
     * gRPC response stream. Needs to be accessible from {@code killImmediately}
     * to guarantee that no further messages are delivered after a forced
     * shutdown.
     */
    private BlockingQueue<EventMessage> outbound;

    /** Handle to the running dispatcher task so it can be cancelled. */
    private Future<?> dispatcherFuture;

    private DockerInit() {
        this(createDefaultExecutor());
    }

    public DockerInit(@NonNull final ExecutorService executor) {
        this.executor = executor;
        grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(new TestControlImpl())
                .build();
    }

    private static ExecutorService createDefaultExecutor() {
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "grpc-outbound-dispatcher");
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    public static void main(final String[] args) throws IOException, InterruptedException {
        INSTANCE.startGrpcServer();
    }

    private void startGrpcServer() throws IOException, InterruptedException {
        grpcServer.start();
        grpcServer.awaitTermination();
    }

    /**
     * gRPC implementation that delegates to the {@link DockerInit} singleton.
     */
    private final class TestControlImpl extends TestControlGrpc.TestControlImplBase {

        private TestControlImpl() {}

        @Override
        public void start(
                @NonNull final StartRequest request, @NonNull final StreamObserver<EventMessage> responseObserver) {

            if (app != null) {
                responseObserver.onError(Status.ALREADY_EXISTS.asRuntimeException());
                return;
            }

            final NodeId selfId = request.getSelfId();
            if (selfId.getId() < 0) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("selfId must be positive")
                        .asRuntimeException());
                return;
            }
            if (!request.hasVersion()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("version has to be specified")
                        .asRuntimeException());
                return;
            }
            if (!request.hasRoster()) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription("roster has to be specified")
                        .asRuntimeException());
                return;
            }

            try {
                app = new DockerApp(
                        ProtobufConverter.toPbj(selfId),
                        ProtobufConverter.toPbj(request.getVersion()),
                        ProtobufConverter.toPbj(request.getRoster()),
                        KeysAndCertsConverter.fromProto(request.getKeysAndCerts()),
                        request.getOverriddenPropertiesMap());
                cancelled = false;

                // Outbound queue and single-writer dispatcher thread. Both are stored
                // in instance fields so they can be accessed from killImmediately().
                outbound = new LinkedBlockingQueue<>();

                final Runnable dispatcherTask = () -> {
                    try {
                        while (!cancelled) {
                            final EventMessage msg = outbound.take();
                            try {
                                responseObserver.onNext(msg);
                            } catch (final RuntimeException e) {
                                // Any exception here means the stream is no longer writable
                                LOGGER.error("Unexpected error while processing event", e);
                                cancelled = true;
                            }
                        }
                    } catch (final InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                };

                if (responseObserver instanceof ServerCallStreamObserver<?> serverObserver) {
                    serverObserver.setOnCancelHandler(() -> {
                        cancelled = true;
                        if (dispatcherFuture != null) {
                            dispatcherFuture.cancel(true);
                        }
                        outbound.clear();
                    });
                }

                dispatcherFuture = executor.submit(dispatcherTask);

                // Helper that enqueues messages if the stream is still open
                final Consumer<EventMessage> enqueue = msg -> {
                    if (!cancelled) {
                        outbound.offer(msg);
                    }
                };

                app.registerPlatformStatusChangeListener(
                        notification -> enqueue.accept(EventMessageFactory.fromPlatformStatusChange(notification)));

                app.registerConsensusRoundListener(
                        rounds -> enqueue.accept(EventMessageFactory.fromConsensusRounds(rounds)));

                InMemoryAppender.subscribe(log -> {
                    enqueue.accept(EventMessageFactory.fromStructuredLog(log));
                    return cancelled ? SubscriberAction.UNSUBSCRIBE : SubscriberAction.CONTINUE;
                });

                app.start();
            } catch (final Exception e) {
                LOGGER.error("Unexpected error while starting grpc server", e);
                cancelled = true;
                responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
            }
        }

        @Override
        public void submitTransaction(
                @NonNull final TransactionRequest request, @NonNull final StreamObserver<Empty> responseObserver) {

            if (app == null) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Application not started yet")
                        .asRuntimeException());
                return;
            }

            try {
                app.submitTransaction(request.getPayload().toByteArray());

                // Since in the test is waiting for completion of this request, we'll need to send an "empty" answer.
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (final Exception e) {
                responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
            }
        }

        @Override
        public void killImmediately(
                final KillImmediatelyRequest request, final StreamObserver<Empty> responseObserver) {
            try {
                if (app != null) {
                    app.destroy();
                    app = null;
                }

                // Prevent any further outbound communication
                cancelled = true;

                if (dispatcherFuture != null) {
                    dispatcherFuture.cancel(true);
                    dispatcherFuture = null;
                }

                if (outbound != null) {
                    outbound.clear();
                    outbound = null;
                }

                // Since in the test is waiting for completion of this request, we'll need to send an "empty" answer.
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
