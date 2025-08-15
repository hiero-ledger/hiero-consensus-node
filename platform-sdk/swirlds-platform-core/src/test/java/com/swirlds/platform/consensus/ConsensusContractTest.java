package com.swirlds.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterFactory;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.junit.jupiter.api.Test;

public class ConsensusContractTest {
    @Test
    void test(){
        // parameters
        final int numEvents = 10_000;
        final int minNodes = 2;
        final int maxNodes = 10;

        // setup
        final Randotron random = Randotron.create();
        final Roster roster = RandomRosterBuilder.create(random)
                .withSize(random.nextInt(minNodes, maxNodes))
                .build();
        final PlatformContext context = TestPlatformContextBuilder.create().build();
        final StandardEventEmitter eventEmitter = new EventEmitterFactory(context, random, roster).newStandardEmitter();
        final List<PlatformEvent> generatedEvents = eventEmitter
                .emitEvents(numEvents)
                .stream()
                .map(EventImpl::getBaseEvent)
                .toList();


        // first part
        final TestIntake genesisIntake = new TestIntake(context, roster);
        final List<PlatformEvent> genesisEvents = copyShuffle(generatedEvents, random);
        genesisEvents.forEach(genesisIntake::addEvent);
        validate(genesisIntake.getOutput());


        // second part
        final ConsensusSnapshot snapshot = genesisIntake.getConsensusRounds()
                .get(genesisIntake.getConsensusRounds().size() / 2).getSnapshot();
        final TestIntake restartIntake = new TestIntake(context, roster);
        restartIntake.loadSnapshot(snapshot);
        final List<PlatformEvent> restartEvents = copyShuffle(generatedEvents, random);
        restartEvents.forEach(restartIntake::addEvent);


        assertEquals(genesisIntake.getOutput().getLastConsensusRound().getSnapshot(),
                restartIntake.getOutput().getLastConsensusRound().getSnapshot(),
                "Both consensus instances should have reached same last consensus round");
        validate(restartIntake.getOutput());

    }

    private List<PlatformEvent> copyShuffle(final List<PlatformEvent> events, final Randotron random) {
        final List<PlatformEvent> copiedEvents = events.stream()
                .map(PlatformEvent::copyGossipedData)
                .collect(Collectors.toList());
        Collections.shuffle(copiedEvents, random);
        return copiedEvents;
    }

    private static void validate(final ConsensusOutput output) {
        final EventWindow eventWindow = output.getLastConsensusRound().getEventWindow();
        final Set<Hash> consensusHashes = output.consensusEventHashes();
        final Set<Hash> preConsensusHashes = output.getPreConsensusEventHashes();

        for (final PlatformEvent preConsensusEvent : output.getPreConsensusEvents()) {
            if(eventWindow.isAncient(preConsensusEvent)){
               assertTrue(consensusHashes.contains(preConsensusEvent.getHash()),
                       "every ancient pre-consensus event added should have reached consensus");
            }
        }

        for (final Hash consensusHash : consensusHashes) {
            assertTrue(preConsensusHashes.contains(consensusHash),
                    "every consensus event hash should have been returned as a pre-consensus event");
        }
    }

    //TODO add test with changing roster
}
