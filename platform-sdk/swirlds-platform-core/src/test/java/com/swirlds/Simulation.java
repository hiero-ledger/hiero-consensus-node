package com.swirlds;

import com.swirlds.platform.components.consensus.ConsensusEngineOutput;
import com.swirlds.platform.components.consensus.DefaultConsensusEngine;
import com.swirlds.platform.test.fixtures.event.EventCreatorNetwork;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

public class Simulation {

    @Test
    void sim(){
        final EventCreatorNetwork creatorNetwork = new EventCreatorNetwork(0, 10);
        final DefaultConsensusEngine consensusEngine = new DefaultConsensusEngine(
                creatorNetwork.getPlatformContext(),
                creatorNetwork.getRoster(),
                NodeId.of(creatorNetwork.getRoster().rosterEntries().getFirst().nodeId()),
                t -> false
        );


        final List<Duration> c2cs = new ArrayList<>();
        final Instant start = creatorNetwork.getPlatformContext().getTime().now();
        int numEvents = 0;
        for (int i = 0; i < 10000; i++) {
            final List<PlatformEvent> events = creatorNetwork.cycle();
            numEvents += events.size();
            final List<ConsensusEngineOutput> engineOutputs = events.stream().map(consensusEngine::addEvent).toList();
            engineOutputs.stream().map(ConsensusEngineOutput::consensusRounds).flatMap(List::stream)
                    .map(ConsensusRound::getEventWindow).forEach(creatorNetwork::setEventWindow);

            engineOutputs.stream()
                    .map(ConsensusEngineOutput::consensusRounds)
                    .flatMap(List::stream)
                    .map(cr->cr.getConsensusEvents().stream()
                            .map(ce->Duration.between(ce.getTimeCreated(), cr.getReachedConsTimestamp()))
                            .toList())
                    .flatMap(List::stream)
                    .forEach(c2cs::add);
        }
        final double averageC2C = c2cs.stream().mapToLong(Duration::toNanos).average().orElse(0);
        final Duration max = c2cs.stream().max(Comparator.naturalOrder()).orElse(Duration.ZERO);
        final Duration timePassed = Duration.between(start, creatorNetwork.getPlatformContext().getTime().now());
        System.out.println("Average C2C: " + Duration.ofNanos((long)averageC2C));
        System.out.println("Max C2C:     " + max);
        System.out.println("Time passed: " + timePassed);
        System.out.println("Num events:  " + numEvents);
        System.out.println("ev/sec:      " + numEvents/((double)timePassed.toMillis()/1000));

    }
}
