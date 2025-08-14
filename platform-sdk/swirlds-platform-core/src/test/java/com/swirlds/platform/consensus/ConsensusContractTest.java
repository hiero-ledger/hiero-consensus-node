package com.swirlds.platform.consensus;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
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
        final List<PlatformEvent> genesisEvents = generatedEvents
                .stream()
                .map(PlatformEvent::copyGossipedData)
                .collect(Collectors.toList());
        Collections.shuffle(genesisEvents, random);
        generatedEvents.forEach(genesisIntake::addEvent);
        validate(genesisIntake.getOutput());


    }

    private static void validate(final ConsensusOutput output) {
        final EventWindow eventWindow = output.getLastConsensusRound().getEventWindow();
        final Set<Hash> consensusEvents = output.consensusEventHashes();

        for (final PlatformEvent addedEvent : output.getPreConsensusEvents()) {
            if(eventWindow.isAncient(addedEvent)){
               assertTrue(consensusEvents.contains(addedEvent.getHash()),
                       "every ancient event added should have reached consensus");
            }
        }

    }
}
