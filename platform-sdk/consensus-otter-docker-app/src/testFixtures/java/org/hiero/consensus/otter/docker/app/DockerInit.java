// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import com.hedera.hapi.platform.state.legacy.NodeId;
import com.hederahashgraph.api.proto.java.Roster;
import com.hederahashgraph.api.proto.java.SemanticVersion;
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
                .addService(new TestControlImpl(this))
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
    private static final class TestControlImpl extends TestControlGrpc.TestControlImplBase {

        private final DockerInit context;

        private TestControlImpl(final DockerInit context) {
            this.context = context;
        }

        @Override
        public void start(
                @NonNull final StartRequest request, @NonNull final StreamObserver<EventMessage> responseObserver) {

            if (context.app != null) {
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

            try {
                final SemanticVersion version =
                        request.hasVersion() ? request.getVersion() : SemanticVersion.getDefaultInstance();

                final Roster roster = request.hasRoster() ? request.getRoster() : Roster.getDefaultInstance();

                context.app = new DockerApp(
                        ProtobufConverter.fromGoogle(selfId),
                        ProtobufConverter.fromGoogle(version),
                        ProtobufConverter.fromGoogle(roster),
                        KeysAndCertsConverter.fromProto(request.getKeysAndCerts()),
                        request.getOverriddenPropertiesMap());

                // Forward platform status changes to the caller
                context.app.registerPlatformStatusChangeListener(notification -> {
                    final var platformStatusChangeMsg =
                            org.hiero.otter.fixtures.container.proto.PlatformStatusChange.newBuilder()
                                    .setNewStatus(notification.getNewStatus().name())
                                    .build();

                    final var eventMessage = EventMessage.newBuilder()
                            .setPlatformStatusChange(platformStatusChangeMsg)
                            .build();

                    responseObserver.onNext(eventMessage);
                });

                // Forward consensus rounds
                context.app.registerConsensusRoundListener(rounds -> {
                    final List<ProtoConsensusRound> protoRounds =
                            rounds.stream().map(ProtobufConverter::toGoogle).toList();

                    final var eventMessage = EventMessage.newBuilder()
                            .setConsensusRounds(ProtoConsensusRounds.newBuilder()
                                    .addAllRounds(protoRounds)
                                    .build())
                            .build();
                    responseObserver.onNext(eventMessage);
                });

                // Forward StructuredLog entries via gRPC using InMemoryAppender listener
                InMemoryAppender.addListener((StructuredLog log) -> {
                    final var logEntry = ProtobufConverter.toGoogle(log);
                    final var eventMsg =
                            EventMessage.newBuilder().setLogEntry(logEntry).build();
                    responseObserver.onNext(eventMsg);
                });

                context.app.start();
            } catch (final Exception e) {
                responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
            }
        }
    }
}
