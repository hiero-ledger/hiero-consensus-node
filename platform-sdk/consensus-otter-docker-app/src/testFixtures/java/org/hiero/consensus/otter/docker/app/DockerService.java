// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import com.google.protobuf.Empty;
import com.hedera.hapi.platform.state.legacy.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ExecutorService;
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
 * gRPC service implementation that delegates event streaming to an {@link OutboundDispatcher} instance.
 */
public final class DockerService extends TestControlGrpc.TestControlImplBase {

    /** Logger */
    private static final Logger LOGGER = LoggerFactory.getLogger(DockerService.class);

    /** Executor service for handling the dispatched messages */
    private final ExecutorService executor;

    /** App that contains the Platform */
    private DockerApp app;

    /** Dispatcher for handling outgoing messages */
    private OutboundDispatcher dispatcher;

    /**
     * Creates a DockerService with the provided executor
     * @param executor Executor Service
     */
    public DockerService(@NonNull final ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Starting the communication channel with a {@link StartRequest} send from the test.
     *
     * This Service will then create the Platfrom and send results back to the test
     *
     * @param request The Request object with all details needed to construct a Platform
     * @param responseObserver The observer to send msgs back to the test
     */
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

            dispatcher = new OutboundDispatcher(executor, responseObserver);

            // Capture the dispatcher in a final variable so the lambda remains valid
            final OutboundDispatcher currentDispatcher = dispatcher;

            final Consumer<EventMessage> enqueue = dispatcher::enqueue;
            app.registerPlatformStatusChangeListener(
                    notification -> enqueue.accept(EventMessageFactory.fromPlatformStatusChange(notification)));

            app.registerConsensusRoundListener(
                    rounds -> enqueue.accept(EventMessageFactory.fromConsensusRounds(rounds)));

            InMemoryAppender.subscribe(log -> {
                enqueue.accept(EventMessageFactory.fromStructuredLog(log));
                return currentDispatcher.isCancelled() ? SubscriberAction.UNSUBSCRIBE : SubscriberAction.CONTINUE;
            });

            app.start();
        } catch (final Exception e) {
            LOGGER.error("Unexpected error while starting grpc server", e);
            if (dispatcher != null) {
                dispatcher.shutdown();
            }
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
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (final Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void killImmediately(final KillImmediatelyRequest request, final StreamObserver<Empty> responseObserver) {
        try {
            if (app != null) {
                app.destroy();
                app = null;
            }

            if (dispatcher != null) {
                dispatcher.shutdown();
            }

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (final InterruptedException ie) {
            throw new RuntimeException(ie);
        }
    }
}
