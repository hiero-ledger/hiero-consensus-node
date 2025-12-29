package com.swirlds;

import com.swirlds.platform.components.consensus.ConsensusEngineOutput;
import com.swirlds.platform.components.consensus.DefaultConsensusEngine;
import com.swirlds.platform.test.fixtures.event.EventCreatorNetwork;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

public class Simulation {
    private static final Duration SIMULATION_DURATION = Duration.ofMillis(100);

    private static final List<Integer> delays = List.of(80, 100, 120);
    private static final List<Integer> numNodes = List.of(4, 10);
    private static final List<Integer> maxOp = List.of(1, 3, 5, 10);

    @Test
    void sim(){
        System.out.println("Delay(μs)  Nodes  MaxOP avgC2C(μs) maxC2C(μs)  ev/sec");

        for (final Integer delay : delays) {
            for (final int nodes : numNodes) {
                for (final int maxOtherParents : maxOp) {


                    runSimulation(delay, nodes, maxOtherParents);

                }
            }
        }
    }

    private void runSimulation(final Integer delay, final int nodes, final int maxOtherParents) {
        final EventCreatorNetwork creatorNetwork = new EventCreatorNetwork(0, nodes, maxOtherParents);
        final DefaultConsensusEngine consensusEngine = new DefaultConsensusEngine(
                creatorNetwork.getPlatformContext(),
                creatorNetwork.getRoster(),
                NodeId.of(creatorNetwork.getRoster().rosterEntries().getFirst().nodeId()),
                t -> false
        );

        final Duration delayDuration = Duration.of(delay, ChronoUnit.MICROS);
        final List<Duration> c2cs = new ArrayList<>();
        final Instant start = creatorNetwork.getPlatformContext().getTime().now();
        final Instant end = start.plus(SIMULATION_DURATION);
        int numEvents = 0;
        while (creatorNetwork.getPlatformContext().getTime().now().isBefore(end)) {
            final List<PlatformEvent> events = creatorNetwork.cycle(delayDuration);
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
        //System.out.println("Delay(μs)  Nodes  MaxOP avgC2C(μs) maxC2C(μs) ev/sec");
        System.out.printf("%,9d %6d %6d %,10d %,10d %,7d %n",
                delay,
                nodes,
                maxOtherParents,
                (long)averageC2C/1000,
                toMicros(max),
                (long)(numEvents/((double)timePassed.toMillis()/1000)));
//        System.out.println("Average C2C: " + Duration.ofNanos((long)averageC2C));
//        System.out.println("Max C2C:     " + max);
//        System.out.println("Time passed: " + timePassed);
//        System.out.println("Num events:  " + numEvents);
//        System.out.println("ev/sec:      " + numEvents/((double)timePassed.toMillis()/1000));
    }

    public static long toMicros(final Duration d) {
        // seconds * 1_000_000 + nanos/1_000 handles negative durations correctly
        return d.getSeconds() * 1_000_000L + d.getNano() / 1_000L;
    }

    @Test
    void stringTest(){
        System.out.println("Delay(μs)  Nodes  MaxOP avgC2C(μs) maxC2C(μs)  ev/sec");
        System.out.printf("%,9d %6d %6d %,10d %,10d %,7d %n",
                80,
                4,
                5,
                1123,
                2500,
                100_000);
    }
}
