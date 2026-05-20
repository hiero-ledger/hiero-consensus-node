// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation.fixtures;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.base.crypto.SigningFactory;
import org.hiero.base.crypto.SigningImplementation;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.event.creator.impl.DefaultEventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.consensus.test.fixtures.WeightGenerators;

public class EventCreatorNetwork {
    final Map<NodeId,DefaultEventCreationManager> eventCreators;
    final DefaultOrphanBuffer orphanBuffer;
    final FakeTime time;
    final Roster roster;
    final PlatformContext platformContext;
    final SimulatedBroadcast network;

    public EventCreatorNetwork(final long seed, final int numNodes, final Configuration configuration, final NetworkLatency latency) {
        // Build a roster with real keys
        final RandomRosterBuilder rosterBuilder = RandomRosterBuilder.create(Randotron.create(seed))
                .withSize(numNodes)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .withRealKeysEnabled(true);

        roster = rosterBuilder.build();

        eventCreators = new HashMap<>();
        time = new FakeTime(Instant.parse("2026-01-01T00:00:00Z"), Duration.ZERO);

        platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withTime(time)
                .build();
        final Metrics metrics = platformContext.getMetrics();

        // Create an event creator for each node
        for (final RosterEntry entry : roster.rosterEntries()) {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            final SecureRandom nodeRandom = new SecureRandom();
            nodeRandom.setSeed(nodeId.id());
            final KeyPair keyPair = SigningFactory.generateKeyPair(SigningSchema.ED25519, nodeRandom);
            final BytesSigner signer = SigningFactory.createSigner(SigningImplementation.ED25519_SODIUM, keyPair);

            final EventCreator eventCreator =
                    new TipsetEventCreator(configuration, metrics, time, nodeRandom, signer, roster, nodeId,
                            ()->List.of(new TimestampedTransaction(Bytes.EMPTY, time.now())));

            final DefaultEventCreationManager eventCreationManager = new DefaultEventCreationManager(
                    configuration, metrics, time, () -> false, eventCreator, roster, nodeId);

            // Set platform status to ACTIVE so events can be created
            eventCreationManager.updatePlatformStatus(PlatformStatus.ACTIVE);

            eventCreators.put(nodeId, eventCreationManager);
        }
        orphanBuffer = new DefaultOrphanBuffer(metrics, new NoOpIntakeEventCounter());
        final List<NodeId> ids = roster.rosterEntries().stream().map(entry -> NodeId.of(entry.nodeId())).toList();
        network = new SimulatedBroadcast(time.now(), ids);
        network.setLatency(latency);
    }

    public Roster getRoster() {
        return roster;
    }

    public PlatformContext getPlatformContext() {
        return platformContext;
    }

    public void setEventWindow(final EventWindow eventWindow) {
        for (final DefaultEventCreationManager creator : eventCreators.values()) {
            creator.setEventWindow(eventWindow);
        }
    }

    public List<PlatformEvent> tick(final Duration delay){
        final List<PlatformEvent> newEvents = new ArrayList<>();
        for (final DefaultEventCreationManager creator : eventCreators.values()) {
            final PlatformEvent event = creator.maybeCreateEvent();
            if (event != null) {
                newEvents.add(event);
            }
        }
        final List<PlatformEvent> unorphanedEvents = newEvents.stream().map(orphanBuffer::handleEvent).flatMap(List::stream).toList();
        if (unorphanedEvents.size() != newEvents.size()) {
            throw new RuntimeException("There should be no orphaned events in this benchmark");
        }

        unorphanedEvents.forEach(network::submitEvent);
        time.tick(delay);
        network.tick(time.now());

        for (final Entry<NodeId, DefaultEventCreationManager> entry : eventCreators.entrySet()) {
            final List<PlatformEvent> deliveredEvents = network.getDeliveredEvents(entry.getKey());
            deliveredEvents.forEach(entry.getValue()::registerEvent);
        }
        return newEvents;
    }
}
