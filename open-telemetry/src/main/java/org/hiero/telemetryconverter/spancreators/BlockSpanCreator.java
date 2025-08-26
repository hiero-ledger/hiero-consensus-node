package org.hiero.telemetryconverter.spancreators;

import static org.hiero.telemetryconverter.model.VirtualResource.BLOCK_FINISHING;
import static org.hiero.telemetryconverter.model.VirtualResource.EXECUTION;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.opentelemetry.pbj.trace.v1.Span;
import io.opentelemetry.pbj.trace.v1.Span.Event;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.telemetryconverter.model.VirtualResource;
import org.hiero.telemetryconverter.model.combined.BlockInfo;
import org.hiero.telemetryconverter.model.combined.EventInfo;
import org.hiero.telemetryconverter.model.combined.RoundInfo;
import org.hiero.telemetryconverter.model.combined.TransactionInfo;
import org.hiero.telemetryconverter.model.trace.BlockTraceInfo;
import org.hiero.telemetryconverter.model.trace.EventTraceInfo;
import org.hiero.telemetryconverter.model.trace.RoundTraceInfo;
import org.hiero.telemetryconverter.model.trace.TransactionTraceInfo;
import org.hiero.telemetryconverter.util.Utils;

/**
 * Create trace spans for a block, from the perspective of the block.
 */
public class BlockSpanCreator {
    public static Map<VirtualResource, List<Span>> createBlockSpans(final BlockInfo blockInfo) {
        final Map<VirtualResource, List<Span>> spanMap = new HashMap<>();
        try {
            // create a digest for creating trace ids
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            // create a trace id and span id based on the block
            final Bytes blockTraceID = Utils.longToHash16Bytes(digest, blockInfo.blockNum());
            final Bytes blockSpanID = Utils.longToHash8Bytes(blockInfo.blockNum());
            createIngestAndConsensusSpans(blockInfo, spanMap, blockTraceID, blockSpanID);
            createExecutionSpans(blockInfo, spanMap, blockTraceID, blockSpanID);
            createBlockFinishingSpans(blockInfo, spanMap, blockTraceID, blockSpanID);
            // create a span for the block
            final Span blockSpan = Span.newBuilder()
                    .traceId(blockTraceID) // 16 byte trace id
                    .spanId(blockSpanID) // 8 byte span id
                    .name("Block " + blockInfo.blockNum()+" ("
                            + "R="+ blockInfo.rounds().size()+
                            ",E="+blockInfo.rounds().stream()
                                .map(RoundInfo::events)
                                .mapToLong(List::size).sum()+
                            ",T="+blockInfo.txCount()+")")
                    .startTimeUnixNano(blockInfo.blockStartTimeNanos())
                    .endTimeUnixNano(blockInfo.blockEndTimeNanos())
                    .build();
            putSpan(spanMap, VirtualResource.CN, blockSpan);
        } catch (Exception e) {
            System.err.printf("Error converting block %s: %s%n", blockInfo.blockNum(), e.getMessage());
            e.printStackTrace();
        }
        return spanMap;
    }

    private static void createIngestAndConsensusSpans(BlockInfo blockInfo, Map<VirtualResource, List<Span>> spanMap, Bytes blockTraceID,
            Bytes blockSpanID) {
        // create root span for all Ingest and Consensus activity in the block
        final Bytes ingestConsensusSpanID = Utils.longToHash8Bytes(blockInfo.blockNum(), 1);
        putSpan(spanMap, VirtualResource.CONSENSUS, Span.newBuilder()
                .traceId(blockTraceID) // 16 byte trace id
                .spanId(ingestConsensusSpanID) // 8 byte span id
                .parentSpanId(blockSpanID)
                .name("Ingestion & Consensus")
                .startTimeUnixNano(blockInfo.rounds().stream()
                        .map(RoundInfo::events)
                        .flatMap(List::stream)
                        .mapToLong(EventInfo::firstTransactionOrEventCreation)
                        .min().orElseThrow())
                .endTimeUnixNano(blockInfo.rounds().stream()
                        .map(RoundInfo::createdTraces)
                        .flatMap(List::stream)
                        .mapToLong(RoundTraceInfo::endTimeNanos).max().orElseThrow())
                .build());
        // create spans for each round in the block
        for (var round : blockInfo.rounds()) {
            final Bytes roundSpanID = Utils.longToHash8Bytes(round.roundNumber());
            List<Event> events = new ArrayList<>();
            // add events for round created on each node
            for (var createdTrace : round.createdTraces()) {
                events.add(Event.newBuilder()
                        .name("Round Created on Node " + createdTrace.nodeId())
                        .timeUnixNano(createdTrace.startTimeNanos())
                        .build());
            }
            final Span roundSpan = Span.newBuilder()
                    .traceId(blockTraceID) // 16 byte trace id
                    .spanId(roundSpanID) // 8 byte span id
                    .parentSpanId(ingestConsensusSpanID)
                    .name("Round " + round.roundNumber()+"  ")
                    .startTimeUnixNano(round.roundStartTimeNanos())
                    .endTimeUnixNano(round.roundEndTimeNanos())
                    .events(events)
                    .build();
            putSpan(spanMap, VirtualResource.CN, roundSpan);
            // create span for transactions arriving for all events in round
            if(round.events().stream().map(EventInfo::transactions).mapToLong(List::size).sum() > 0) {
                putSpan(spanMap, VirtualResource.INGESTION, Span.newBuilder()
                        .traceId(blockTraceID) // 16 byte trace id
                        .spanId(Utils.longToHash8Bytes(round.roundNumber(), 1)) // 8 byte span id
                        .parentSpanId(roundSpanID)
                        .name("Tx Ingestion")
                        .startTimeUnixNano(round.events().stream().map(EventInfo::transactions)
                                .flatMap(List::stream)
                                .map(TransactionInfo::receivedTraces)
                                .flatMap(List::stream)
                                .mapToLong(TransactionTraceInfo::startTimeNanos)
                                .min().orElseThrow())
                        .endTimeUnixNano(round.events().stream().map(EventInfo::transactions)
                                .flatMap(List::stream)
                                .map(TransactionInfo::receivedTraces)
                                .flatMap(List::stream)
                                .mapToLong(TransactionTraceInfo::endTimeNanos)
                                .max().orElseThrow())
                        .events(events)
                        .build());
            }
            // create span for summary of all events creation in round
            putSpan(spanMap, VirtualResource.EVENT_CREATION, Span.newBuilder()
                    .traceId(blockTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(round.roundNumber(), 2)) // 8 byte span id
                    .parentSpanId(roundSpanID)
                    .name("Event Creation")
                    .startTimeUnixNano(round.events().stream().map(EventInfo::createdTrace)
                            .mapToLong(EventTraceInfo::startTimeNanos)
                            .min().orElseThrow())
                    .endTimeUnixNano(round.events().stream().map(EventInfo::createdTrace)
                            .mapToLong(EventTraceInfo::endTimeNanos)
                            .max().orElseThrow())
                    .events(events)
                    .build());
            // create span for summary of all events gossip in round
            if (round.events().stream().map(EventInfo::gossipedTraces).mapToLong(List::size).sum() > 0) {
                putSpan(spanMap, VirtualResource.GOSSIP, Span.newBuilder()
                        .traceId(blockTraceID) // 16 byte trace id
                        .spanId(Utils.longToHash8Bytes(round.roundNumber(), 3)) // 8 byte span id
                        .parentSpanId(roundSpanID)
                        .name("Gossip")
                        .startTimeUnixNano(round.events().stream().map(EventInfo::gossipedTraces)
                                .flatMap(List::stream)
                                .mapToLong(EventTraceInfo::startTimeNanos)
                                .min().orElseThrow())
                        .endTimeUnixNano(round.events().stream().map(EventInfo::gossipedTraces)
                                .flatMap(List::stream)
                                .mapToLong(EventTraceInfo::endTimeNanos)
                                .max().orElseThrow())
                        .events(events)
                        .build());
            }
            // create span for consensus time span
            putSpan(spanMap, VirtualResource.CONSENSUS, Span.newBuilder()
                    .traceId(blockTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(round.roundNumber(), 4)) // 8 byte span id
                    .parentSpanId(roundSpanID)
                    .name("Consensus")
                    .startTimeUnixNano(round.events().stream()
                            .mapToLong(EventInfo::endOfGossipOrEventCreation).max().orElseThrow())
                    .endTimeUnixNano(round.createdTraces().stream().mapToLong(RoundTraceInfo::endTimeNanos).min().orElseThrow())
                    .events(events)
                    .build());

        }
    }

    private static void createExecutionSpans(BlockInfo blockInfo, Map<VirtualResource, List<Span>> spanMap, Bytes blockTraceID,
            Bytes blockSpanID) {
        // create span for all execution in the block
        final Bytes executionSpanId = Utils.longToHash8Bytes(blockInfo.blockNum(), 2);
        putSpan(spanMap, EXECUTION, Span.newBuilder()
                .traceId(blockTraceID) // 16 byte trace id
                .spanId(executionSpanId) // 8 byte span id
                .parentSpanId(blockSpanID)
                .name("Execution")
                .startTimeUnixNano(blockInfo.rounds().stream()
                        .mapToLong(RoundInfo::executionStartTimeNanos)
                        .min().orElseThrow())
                .endTimeUnixNano(blockInfo.rounds().stream()
                        .mapToLong(RoundInfo::executionEndTimeNanos)
                        .max().orElseThrow())
                .build());
        // create execution sub-spans for each round
        for (var round : blockInfo.rounds()) {
            putSpan(spanMap, EXECUTION, Span.newBuilder()
                    .traceId(blockTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(blockInfo.blockNum(), 2 + round.roundNumber() * 100)) // 8 byte span id
                    .parentSpanId(executionSpanId)
                    .name("Round " + round.roundNumber()+" Execution")
                    .startTimeUnixNano(round.executionStartTimeNanos())
                    .endTimeUnixNano(round.executionEndTimeNanos())
                    .build());
        }
    }

    private static void createBlockFinishingSpans(BlockInfo blockInfo, Map<VirtualResource, List<Span>> spanMap, Bytes blockTraceID,
            Bytes blockSpanID) {
        // find some timings
        final long startOfBlockHashing = blockInfo.rounds().stream()
                .map(RoundInfo::hashedTraces)
                .flatMap(List::stream)
                .mapToLong(RoundTraceInfo::startTimeNanos)
                .min().orElseThrow();
        final long endOfBlockHashing = blockInfo.rounds().stream()
                .map(RoundInfo::hashedTraces)
                .flatMap(List::stream)
                .mapToLong(RoundTraceInfo::endTimeNanos)
                .max().orElseThrow();
        final long startOfBlockCreation = blockInfo.blockCreationTraces().stream()
                .mapToLong(BlockTraceInfo::startTimeNanos)
                .min().orElseThrow();
        final long endOfBlockCreation = blockInfo.blockCreationTraces().stream()
                .mapToLong(BlockTraceInfo::endTimeNanos)
                .max().orElseThrow();
        // create span for all block finishing in the block
        final Bytes blockFinishingSpanId = Utils.longToHash8Bytes(blockInfo.blockNum(), 20);
        putSpan(spanMap, BLOCK_FINISHING, Span.newBuilder()
                .traceId(blockTraceID) // 16 byte trace id
                .spanId(blockFinishingSpanId) // 8 byte span id
                .parentSpanId(blockSpanID)
                .name("Block Finishing")
                .startTimeUnixNano(Math.min(startOfBlockHashing, startOfBlockCreation))
                .endTimeUnixNano(Math.max(endOfBlockHashing, endOfBlockCreation))
                .build());
        // create block hashing span
        putSpan(spanMap, BLOCK_FINISHING, Span.newBuilder()
                .traceId(blockTraceID) // 16 byte trace id
                .spanId(Utils.longToHash8Bytes(blockInfo.blockNum(), 21))
                .parentSpanId(blockFinishingSpanId)
                .name("State Hashing")
                .startTimeUnixNano(startOfBlockHashing)
                .endTimeUnixNano(endOfBlockHashing)
                .build());
        // create block creation span
        putSpan(spanMap, BLOCK_FINISHING, Span.newBuilder()
                .traceId(blockTraceID) // 16 byte trace id
                .spanId(Utils.longToHash8Bytes(blockInfo.blockNum(), 22))
                .parentSpanId(blockFinishingSpanId)
                .name("Block Creation")
                .startTimeUnixNano(startOfBlockCreation)
                .endTimeUnixNano(endOfBlockCreation)
                .build());
    }

    private static void putSpan(Map<VirtualResource, List<Span>> spanMap, VirtualResource resource, Span span) {
        spanMap.computeIfAbsent(resource, k -> new ArrayList<>()).add(span);
    }
}
