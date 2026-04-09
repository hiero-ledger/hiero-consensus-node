package com.swirlds.platform.test.fixtures.event;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.crypto.KeyGeneratingException;
import com.swirlds.platform.crypto.KeysAndCertsGenerator;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.consensus.crypto.SigningFactory;
import org.hiero.consensus.crypto.SigningImplementation;
import org.hiero.consensus.crypto.SigningSchema;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.config.EventCreationConfig_;
import org.hiero.consensus.event.creator.impl.DefaultEventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;

public class EventCreatorNetwork {
    final List<DefaultEventCreationManager> eventCreators;
    final DefaultOrphanBuffer orphanBuffer;
    final FakeTime time;
    final Roster roster;
    final PlatformContext platformContext;

    public EventCreatorNetwork(final long seed, final int numNodes) {
        this(seed, numNodes, 1);
    }

    public EventCreatorNetwork(final long seed, final int numNodes, final int maxOtherParents) {
        // Build a roster with real keys
        final RandomRosterBuilder rosterBuilder = RandomRosterBuilder.create(Randotron.create(seed))
                .withSize(numNodes)
                .withWeightGenerator(WeightGenerators.BALANCED)
                .withRealKeysEnabled(true);

        roster = rosterBuilder.build();

        eventCreators = new ArrayList<>(numNodes);
        final Configuration configuration = new TestConfigBuilder()
                .withConfigDataType(EventCreationConfig.class)
                .withValue(EventCreationConfig_.MAX_CREATION_RATE, 0)
                .withValue("event.creation.maxOtherParents", Integer.toString(maxOtherParents))
                .getOrCreateConfig();
        time = new FakeTime();

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
            //eventCreationManager.setEventWindow(eventWindow);

            eventCreators.add(eventCreationManager);
        }
        orphanBuffer = new DefaultOrphanBuffer(metrics, new NoOpIntakeEventCounter());
    }

    public Roster getRoster() {
        return roster;
    }

    public PlatformContext getPlatformContext() {
        return platformContext;
    }

    public void setEventWindow(final EventWindow eventWindow) {
        for (final DefaultEventCreationManager creator : eventCreators) {
            creator.setEventWindow(eventWindow);
        }
    }

    public List<PlatformEvent> cycle(){
        return cycle(Duration.of(100, ChronoUnit.MICROS));
    }

    public List<PlatformEvent> cycle(final Duration delay){
        final List<PlatformEvent> newEvents = new ArrayList<>();
        for (final DefaultEventCreationManager creator : eventCreators) {
            final PlatformEvent event = creator.maybeCreateEvent();
            if (event != null) {
                newEvents.add(event);
            }
        }
        if (newEvents.isEmpty()) {
            throw new RuntimeException("At least one creator should always be able to create an event");
        }
        final List<PlatformEvent> unorphanedEvents = newEvents.stream().map(orphanBuffer::handleEvent).flatMap(List::stream).toList();
        if (unorphanedEvents.size() != newEvents.size()) {
            throw new RuntimeException("There should be no orphaned events in this benchmark");
        }

        time.tick(delay);
        // Share newly created events with all nodes (simulating gossip)
        for (final DefaultEventCreationManager creator : eventCreators) {
            for (final PlatformEvent newEvent : newEvents) {
                creator.registerEvent(newEvent);
            }
        }
        return newEvents;
    }
}
