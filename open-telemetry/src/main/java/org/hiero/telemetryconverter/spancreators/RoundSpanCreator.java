package org.hiero.telemetryconverter.spancreators;

import static org.hiero.telemetryconverter.model.VirtualResource.EXECUTION;
import static org.hiero.telemetryconverter.util.Utils.putSpan;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.opentelemetry.pbj.common.v1.KeyValue;
import io.opentelemetry.pbj.trace.v1.Span;
import io.opentelemetry.pbj.trace.v1.Span.Event;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import org.hiero.telemetryconverter.model.VirtualResource;
import org.hiero.telemetryconverter.model.combined.EventInfo;
import org.hiero.telemetryconverter.model.combined.RoundInfo;
import org.hiero.telemetryconverter.model.combined.TransactionInfo;
import org.hiero.telemetryconverter.model.trace.EventTraceInfo;
import org.hiero.telemetryconverter.model.trace.RoundTraceInfo;
import org.hiero.telemetryconverter.util.Utils;

public class RoundSpanCreator {
    private static final KeyValue ROUND_LEVEL = Utils.kv("level", "round");

    /** create a unique span id for the round */
    public static long roundSpanNum(final RoundInfo roundInfo) {
        return roundInfo.roundNumber() * 1000 + 79;
    }

    public static Bytes roundSpanID(final RoundInfo roundInfo) {
        return Utils.longToHash8Bytes(roundSpanNum(roundInfo));
    }

    public static Bytes roundTraceID(final RoundInfo roundInfo, final MessageDigest digest) {
        return Utils.longToHash16Bytes(digest, roundInfo.roundNumber() * 1000 + 79);
    }

    public static void createRoundSpans(final RoundInfo roundInfo, final long[] nodeIds,
            final Map<VirtualResource, List<Span>> spanMap, final MessageDigest digest) {
        try {
            // create a trace id and span id based on the block
            final Bytes roundTraceID = roundTraceID(roundInfo, digest);
            final long roundSpanNum = roundSpanNum(roundInfo);
            final Bytes roundSpanID = roundSpanID(roundInfo);
            createIngestAndConsensusSpans(roundInfo, nodeIds, roundSpanNum, spanMap, roundTraceID, roundSpanID);
            // create a root span for the round
            final Span blockSpan = Span.newBuilder()
                    .traceId(roundTraceID) // 16 byte trace id
                    .spanId(roundSpanID) // 8 byte span id
                    .name("Round " + roundInfo.roundNumber()+" in block " + roundInfo.blockNumber())
                    .startTimeUnixNano(roundInfo.roundStartTimeNanos())
                    .endTimeUnixNano(roundInfo.roundEndTimeNanos())
                    .attributes(ROUND_LEVEL, Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()))
                    .build();
            putSpan(spanMap, VirtualResource.ROUNDS, blockSpan);
        } catch (Exception e) {
            System.err.printf("Error converting round %s in block %s: %s%n", roundInfo.roundNumber(),
                    roundInfo.blockNumber(), e.getMessage());
            e.printStackTrace();
        }
    }

    private static void createIngestAndConsensusSpans(final RoundInfo roundInfo, final long[] nodeIds,
            final long roundSpanNum, Map<VirtualResource, List<Span>> spanMap, Bytes roundTraceID, Bytes roundSpanID) {
        Bytes allEventsSpanID = Utils.longToHash8Bytes(roundSpanNum, 57);
        Utils.putSpan(spanMap, VirtualResource.EVENT_CREATION, Span.newBuilder()
                .traceId(roundTraceID) // 16 byte trace id
                .spanId(allEventsSpanID) // 8 byte span id
                .parentSpanId(roundSpanID)
                .name("All Events")
                .startTimeUnixNano(roundInfo.events().stream()
                        .map(EventInfo::createdTrace)
                        .mapToLong(EventTraceInfo::startTimeNanos).min()
                        .orElseThrow())
                .endTimeUnixNano(roundInfo.events()
                        .stream().flatMap(e ->
                            Stream.of(e.gossipedTraces(), e.receivedTraces(), e.preHandledTraces()))
                        .flatMap(List::stream)
                        .mapToLong(EventTraceInfo::endTimeNanos).max()
                        .orElseThrow())
                .attributes(ROUND_LEVEL, Utils.kv("block", roundInfo.blockNumber()), Utils.kv("round", roundInfo.roundNumber()))
                .build());
        for(EventInfo event: roundInfo.events()) {
            final List<Event> eventEvents = new ArrayList<>();
            event.transactions().stream()
                    .filter(TransactionInfo::hasReceivedTimingData)
                    .map(tx -> Event.newBuilder()
                        .name("Tx Received")
                        .timeUnixNano(tx.transactionReceivedTimeNanos())
                        .attributes(ROUND_LEVEL, Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()),
                                Utils.kv("node",event.eventCreatorNodeId()))
                        .build())
                    .forEach(eventEvents::add);
            for(var et : event.gossipedTraces()) {
                eventEvents.add(Event.newBuilder()
                    .name("Event Gossip")
                    .timeUnixNano(et.startTimeNanos())
                    .attributes(ROUND_LEVEL, Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()),
                            Utils.kv("node",event.eventCreatorNodeId()))
                    .build());
            }
            for(var et : event.preHandledTraces()) {
                eventEvents.add(Event.newBuilder()
                    .name("Event Prehandled")
                    .timeUnixNano(et.startTimeNanos())
                    .attributes(ROUND_LEVEL, Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()),
                            Utils.kv("node",event.eventCreatorNodeId()))
                    .build());
            }
            Utils.putSpan(spanMap, VirtualResource.EVENT_CREATION, Span.newBuilder()
                    .traceId(roundTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(event.eventHash(), 509)) // 8 byte span id
                    .parentSpanId(allEventsSpanID)
                    .name("Event, created on N"+event.eventCreatorNodeId()+" T"+event.transactions().size())
                    .startTimeUnixNano(event.createdTrace().startTimeNanos())
                    .endTimeUnixNano(event.createdTrace().endTimeNanos())
                    .attributes(ROUND_LEVEL, Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()),
                            Utils.kv("node",event.eventCreatorNodeId()), Utils.kv("transactions",event.transactions().size()))
                    .events(eventEvents)
                    .build());
        }


        // create span for summary of all events creation in round
//        for (EventInfo event : roundInfo.events()) {
//            // create events for each transaction arriving for event
//            List<Event> txEvents = event.transactions().stream()
//                    .filter(TransactionInfo::hasReceivedTimingData)
//                    .map(tx -> Event.newBuilder()
//                        .name("Tx Received")
//                        .timeUnixNano(tx.transactionReceivedTimeNanos())
//                        .attributes(Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()),
//                                Utils.kv("node",event.eventCreatorNodeId()))
//                        .build())
//                    .toList();
//
//            Utils.putSpan(spanMap, VirtualResource.EVENT_CREATION, Span.newBuilder()
//                    .traceId(roundTraceID) // 16 byte trace id
//                    .spanId(Utils.longToHash8Bytes(event.eventHash(), 2)) // 8 byte span id
//                    .parentSpanId(roundSpanID)
//                    .name("Event Creation N"+event.eventCreatorNodeId()+" T"+event.transactions().size())
//                    .startTimeUnixNano(event.createdTrace().startTimeNanos())
//                    .endTimeUnixNano(event.createdTrace().endTimeNanos())
//                    .attributes(Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()),
//                            Utils.kv("node",event.eventCreatorNodeId()), Utils.kv("transactions",event.transactions().size()))
//                    .events(txEvents)
//                    .build());
//        }
        // create span for summary of all events gossip in round
//        if (roundInfo.events().stream().map(EventInfo::gossipedTraces).mapToLong(List::size).sum() > 0) {
//            Utils.putSpan(spanMap, VirtualResource.GOSSIP, Span.newBuilder()
//                    .traceId(roundTraceID) // 16 byte trace id
//                    .spanId(Utils.longToHash8Bytes(roundSpanNum, 3)) // 8 byte span id
//                    .parentSpanId(roundSpanID)
//                    .name("Gossip")
//                    .startTimeUnixNano(roundInfo.events().stream().map(EventInfo::gossipedTraces)
//                            .flatMap(List::stream)
//                            .mapToLong(EventTraceInfo::startTimeNanos)
//                            .min().orElseThrow())
//                    .endTimeUnixNano(roundInfo.events().stream().map(EventInfo::gossipedTraces)
//                            .flatMap(List::stream)
//                            .mapToLong(EventTraceInfo::endTimeNanos)
//                            .max().orElseThrow())
//                    .attributes(Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()))
//                    .build());
//        }
        // create overall consensus and execution spans for each node in round
        final Bytes overallConsensusSpanID = Utils.longToHash8Bytes(roundSpanNum, 4);
        Utils.putSpan(spanMap, VirtualResource.CONSENSUS, Span.newBuilder()
                .traceId(roundTraceID) // 16 byte trace id
                .spanId(overallConsensusSpanID) // 8 byte span id
                .parentSpanId(roundSpanID)
                .name("All Consensus")
//                .startTimeUnixNano(roundInfo.events().stream()
//                        .mapToLong(EventInfo::endOfGossipOrEventCreation).min().orElseThrow())
                .startTimeUnixNano(roundInfo.createdTraces().stream()
                        .mapToLong(RoundTraceInfo::startTimeNanos).min()
                        .orElseThrow())
                .endTimeUnixNano(roundInfo.createdTraces().stream()
                        .mapToLong(RoundTraceInfo::endTimeNanos).max()
                        .orElseThrow())
                .attributes(ROUND_LEVEL, Utils.kv("block", roundInfo.blockNumber()), Utils.kv("round", roundInfo.roundNumber()))
                .build());
        // create round execution span
        final Bytes overallExecutionSpanID = Utils.longToHash8Bytes(roundSpanNum, 5);
        Utils.putSpan(spanMap, EXECUTION, Span.newBuilder()
                .traceId(roundTraceID) // 16 byte trace id
                .spanId(overallExecutionSpanID) // 8 byte span id
                .parentSpanId(roundSpanID)
                .name("All Execution")
                .startTimeUnixNano(roundInfo.executedTraces().stream()
                        .mapToLong(RoundTraceInfo::startTimeNanos)
                        .min().orElseThrow())
                .endTimeUnixNano(roundInfo.executedTraces().stream()
                        .mapToLong(RoundTraceInfo::endTimeNanos)
                        .max().orElseThrow())
                .attributes(ROUND_LEVEL, Utils.kv("block", roundInfo.blockNumber()), Utils.kv("round", roundInfo.roundNumber()))
                .build());
        for (long nodeId: nodeIds) {
            // create span for consensus time span
            try{
                Utils.putSpan(spanMap, VirtualResource.CONSENSUS, Span.newBuilder()
                    .traceId(roundTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(roundSpanNum, nodeId * 1000 + 1)) // 8 byte span id
                    .parentSpanId(overallConsensusSpanID)
                    .name("Consensus N"+nodeId)
                    .startTimeUnixNano(roundInfo.events().stream()
                            .mapToLong(e -> {
                                // if it is a self event then take end of creation time
                                if(e.eventCreatorNodeId() == nodeId) {
                                    return e.createdTrace().endTimeNanos();
                                } else { // otherwise take start of gossip end time
                                    return e.gossipedTraces().stream()
                                        .filter(t -> t.nodeId() == nodeId)
                                        .mapToLong(EventTraceInfo::endTimeNanos)
                                        .min().orElseThrow();
                                }
                            })
                            .max().orElseThrow())
//                    .startTimeUnixNano(roundInfo.createdTraces().stream()
//                            .filter(t -> t.nodeId() == nodeId)
//                            .mapToLong(RoundTraceInfo::startTimeNanos).min()
//                            .orElseThrow())
                    .endTimeUnixNano(roundInfo.createdTraces().stream()
                            .filter(t -> t.nodeId() == nodeId)
                            .mapToLong(RoundTraceInfo::endTimeNanos).max()
                            .orElseThrow())
                    .attributes(ROUND_LEVEL, Utils.kv("block", roundInfo.blockNumber()), Utils.kv("round", roundInfo.roundNumber()),
                            Utils.kv("node",nodeId))
                    .build());
            } catch (NoSuchElementException e) {
                //  ignore missing data
            }
            // create round execution span
            Utils.putSpan(spanMap, EXECUTION, Span.newBuilder()
                    .traceId(roundTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(roundSpanNum, nodeId * 1000 + 2)) // 8 byte span id
                    .parentSpanId(overallExecutionSpanID)
                    .name("Execution N"+nodeId)
                    .startTimeUnixNano(roundInfo.executedTraces().stream()
                            .filter(t -> t.nodeId() == nodeId)
                            .mapToLong(RoundTraceInfo::startTimeNanos)
//                            .min().orElse(roundInfo.roundStartTimeNanos()))
                            .min().orElseThrow())
                    .endTimeUnixNano(roundInfo.executedTraces().stream()
                            .filter(t -> t.nodeId() == nodeId)
                            .mapToLong(RoundTraceInfo::endTimeNanos)
//                            .max().orElse(roundInfo.roundEndTimeNanos()))
                            .max().orElseThrow())
                    .attributes(ROUND_LEVEL, Utils.kv("block", roundInfo.blockNumber()), Utils.kv("round", roundInfo.roundNumber()),
                            Utils.kv("node",nodeId))
                    .build());
        }
    }
}
