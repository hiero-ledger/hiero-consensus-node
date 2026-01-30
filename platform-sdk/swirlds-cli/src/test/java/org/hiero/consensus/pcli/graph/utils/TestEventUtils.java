// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli.graph.utils;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterFactory;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;
import org.hiero.base.crypto.Signer;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.impl.common.CommonPcesWriter;
import org.hiero.consensus.pces.impl.common.PcesFileManager;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;

/**
 * Platform level unit test base class for common setup and teardown.
 */
public class TestEventUtils {

    /**
     * Creates signers for a map of keysAndCerts
     */
    @NonNull
    public static <S extends Signer> Map<NodeId, S> generateSigners(
            @NonNull final Map<NodeId, KeysAndCerts> keysAndCertsMap, @NonNull final Function<KeysAndCerts, S> toS) {
        final Map<NodeId, S> signers = new HashMap<>();
        keysAndCertsMap.forEach((nodeId, keysAndCerts) -> signers.put(nodeId, toS.apply(keysAndCerts)));
        return signers;
    }

    /**
     * Generates a number of random maybe signed events for the given roster
     */
    @NonNull
    public static List<PlatformEvent> generateEvents(
            @NonNull final Random random,
            final int numEvents,
            @NonNull final PlatformContext context,
            @NonNull final Roster roster,
            @Nullable final Map<NodeId, KeysAndCerts> keysAndCertsMap) {
        final StandardEventEmitter eventEmitter = new EventEmitterFactory(context, random, roster).newStandardEmitter();

        Stream<PlatformEvent> stream = eventEmitter.emitEvents(numEvents).stream();

        if (keysAndCertsMap != null) {
            final var signers = generateSigners(keysAndCertsMap, v -> (Signer) new PlatformSigner(v));

            stream = stream.map(event -> {
                final var signature = signers.get(event.getCreatorId())
                        .sign(event.getHash().getBytes().toByteArray());
                return new PlatformEvent(
                        new UnsignedEvent(
                                event.getCreatorId(),
                                event.getAllParents(),
                                event.getBirthRound(),
                                event.getTimeCreated(),
                                event.getTransactions().stream()
                                        .map(t -> t.getApplicationTransaction())
                                        .toList(),
                                event.getEventCore().coin()),
                        signature.getBytes());
            });
        }

        return stream.toList();
    }

    /**
     * Generates a pces stream of a given number of random signed events for the given roster
     */
    public static void generatePreConsensusStream(
            @NonNull final PlatformContext context,
            @NonNull final Path pcesDirectory,
            @NonNull final Roster roster,
            @NonNull final Map<NodeId, KeysAndCerts> keysAndCertsMap,
            int numEvents)
            throws IOException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(keysAndCertsMap);
        Objects.requireNonNull(pcesDirectory);
        Files.createDirectories(pcesDirectory);

        final List<PlatformEvent> events =
                generateEvents(Randotron.create(), numEvents, context, roster, keysAndCertsMap);
        final PcesFileTracker fileTracker = new PcesFileTracker();
        final PcesFileManager fileManager = new PcesFileManager(
                context.getConfiguration(), context.getMetrics(), context.getTime(), fileTracker, pcesDirectory, 0);

        final CommonPcesWriter pcesWriter = new CommonPcesWriter(context.getConfiguration(), fileManager);
        // Start streaming new events
        pcesWriter.beginStreamingNewEvents();

        for (final PlatformEvent event : events) {
            // Write each event
            pcesWriter.prepareOutputStream(event);
            pcesWriter.getCurrentMutableFile().writeEvent(event);
        }
        pcesWriter.getCurrentMutableFile().flush();
        // Close the writer
        pcesWriter.closeCurrentMutableFile();
    }
}
