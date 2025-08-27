// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app;

import com.swirlds.platform.listeners.PlatformStatusChangeNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.otter.fixtures.ProtobufConverter;
import org.hiero.otter.fixtures.container.proto.EventMessage;
import org.hiero.otter.fixtures.container.proto.LogEntry;
import org.hiero.otter.fixtures.container.proto.MarkerFileAdded;
import org.hiero.otter.fixtures.container.proto.PlatformStatusChange;
import org.hiero.otter.fixtures.container.proto.ProtoConsensusRound;
import org.hiero.otter.fixtures.container.proto.ProtoConsensusRounds;
import org.hiero.otter.fixtures.logging.StructuredLog;

/**
 * Utility class for creating {@link EventMessage} instances from various events.
 */
public final class EventMessageFactory {

    // Utility class, do not instantiate.
    private EventMessageFactory() {}

    /**
     * Creates an {@link EventMessage} representing a platform status change.
     *
     * @param notification the notification that contains the new platform status
     * @return the corresponding {@link EventMessage}
     */
    @NonNull
    public static EventMessage fromPlatformStatusChange(@NonNull final PlatformStatusChangeNotification notification) {
        final PlatformStatus newStatus = notification.getNewStatus();

        final PlatformStatusChange protoStatusChange =
                PlatformStatusChange.newBuilder().setNewStatus(newStatus.name()).build();

        return EventMessage.newBuilder()
                .setPlatformStatusChange(protoStatusChange)
                .build();
    }

    /**
     * Creates an {@link EventMessage} carrying a list of consensus rounds.
     *
     * @param rounds the list of consensus rounds
     * @return the corresponding {@link EventMessage}
     */
    @NonNull
    public static EventMessage fromConsensusRounds(@NonNull final List<ConsensusRound> rounds) {
        final List<ProtoConsensusRound> protoRounds =
                rounds.stream().map(ProtobufConverter::fromPlatform).toList();

        final ProtoConsensusRounds protoConsensusRounds =
                ProtoConsensusRounds.newBuilder().addAllRounds(protoRounds).build();

        return EventMessage.newBuilder()
                .setConsensusRounds(protoConsensusRounds)
                .build();
    }

    /**
     * Creates an {@link EventMessage} carrying a structured log entry.
     *
     * @param log the structured log entry
     * @return the corresponding {@link EventMessage}
     */
    @NonNull
    public static EventMessage fromStructuredLog(@NonNull final StructuredLog log) {
        final LogEntry logEntry = ProtobufConverter.fromPlatform(log);
        return EventMessage.newBuilder().setLogEntry(logEntry).build();
    }

    /**
     * Creates an {@link EventMessage} representing the addition of marker files.
     *
     * @param markerFiles the list of marker file names
     * @return the corresponding {@link EventMessage}
     */
    @NonNull
    public static EventMessage fromMarkerFiles(@NonNull final List<String> markerFiles) {
        final MarkerFileAdded markerFileAdded =
                MarkerFileAdded.newBuilder().addAllMarkerFileName(markerFiles).build();
        return EventMessage.newBuilder().setMarkerFileAdded(markerFileAdded).build();
    }
}
