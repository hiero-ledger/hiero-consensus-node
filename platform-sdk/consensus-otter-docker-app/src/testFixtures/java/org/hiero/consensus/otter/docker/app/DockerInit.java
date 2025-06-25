// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import com.hedera.hapi.platform.state.legacy.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import org.hiero.consensus.otter.docker.app.platform.DockerApp;
import org.hiero.otter.fixtures.KeysAndCertsConverter;
import org.hiero.otter.fixtures.ProtobufConverter;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.LogEntry;
import org.hiero.otter.fixtures.container.proto.PlatformStatusChange;
import org.hiero.otter.fixtures.container.proto.ProtoConsensusRound;
import org.hiero.otter.fixtures.container.proto.ProtoConsensusRounds;
import org.hiero.otter.fixtures.container.proto.StartRequest;
import org.hiero.otter.fixtures.container.proto.TestControlGrpc;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.logging.internal.InMemoryAppender;

/**
 * Boots a {@link DockerApp} inside a minimal gRPC wrapper.
 * <p>
 * The class is a tiny self-contained launcher that exposes only a single
 * {@code start()} RPC through which a test harness can supply the information
 * required to bootstrap a {@link DockerApp}. All mutable state is kept inside
 * the singleton instance and never published outside this class.
 */
public final class DockerInit {

    /** Singleton instance exposed for the gRPC service. */
    static final DockerInit INSTANCE = new DockerInit();

    /** Port on which the gRPC service listens. */
    private static final int GRPC_PORT = 8080;

    /** Underlying gRPC server. */
    private final Server grpcServer;

    /** Lazily initialised docker app; only set once. */
    private DockerApp app;

    private DockerInit() {
        grpcServer = ServerBuilder.forPort(GRPC_PORT)
                .addService(new TestControlImpl())
                .build();
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
                        ProtobufConverter.fromGoogle(selfId),
                        ProtobufConverter.fromGoogle(request.getVersion()),
                        ProtobufConverter.fromGoogle(request.getRoster()),
                        KeysAndCertsConverter.fromProto(request.getKeysAndCerts()),
                        request.getOverriddenPropertiesMap());

                // Forward platform status changes to the caller
                app.registerPlatformStatusChangeListener(notification -> {
                    final PlatformStatusChange platformStatusChangeMsg =
                            org.hiero.otter.fixtures.container.proto.PlatformStatusChange.newBuilder()
                                    .setNewStatus(notification.getNewStatus().name())
                                    .build();

                    final EventMessage eventMessage = EventMessage.newBuilder()
                            .setPlatformStatusChange(platformStatusChangeMsg)
                            .build();

                    responseObserver.onNext(eventMessage);
                });

                // Forward consensus rounds
                app.registerConsensusRoundListener(rounds -> {
                    final List<ProtoConsensusRound> protoRounds =
                            rounds.stream().map(ProtobufConverter::toGoogle).toList();

                    final EventMessage eventMessage = EventMessage.newBuilder()
                            .setConsensusRounds(ProtoConsensusRounds.newBuilder()
                                    .addAllRounds(protoRounds)
                                    .build())
                            .build();
                    responseObserver.onNext(eventMessage);
                });

                // Forward StructuredLog entries via gRPC using InMemoryAppender listener
                InMemoryAppender.addListener((StructuredLog log) -> {
                    final LogEntry logEntry = ProtobufConverter.toGoogle(log);
                    final EventMessage eventMsg =
                            EventMessage.newBuilder().setLogEntry(logEntry).build();
                    responseObserver.onNext(eventMsg);
                });

                app.start();
            } catch (final Exception e) {
                responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
            }
        }
    }
}
