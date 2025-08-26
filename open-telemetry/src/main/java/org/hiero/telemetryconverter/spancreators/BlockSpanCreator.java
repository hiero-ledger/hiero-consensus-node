package org.hiero.telemetryconverter.spancreators;

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
import org.hiero.telemetryconverter.model.trace.EventTraceInfo;
import org.hiero.telemetryconverter.model.trace.RoundTraceInfo;
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
                        .parentSpanId(blockSpanID)
                        .name("Round " + round.roundNumber()+"  ")
                        .startTimeUnixNano(round.roundStartTimeNanos())
                        .endTimeUnixNano(round.roundEndTimeNanos())
                        .events(events)
                        .build();
                putSpan(spanMap, VirtualResource.BLOCK, roundSpan);
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
            // create a span for the block
            final Span blockSpan = Span.newBuilder()
                    .traceId(blockTraceID) // 16 byte trace id
                    .spanId(blockSpanID) // 8 byte span id
                    .name("Block " + blockInfo.blockNum())
                    .startTimeUnixNano(blockInfo.blockStartTimeNanos())
                    .endTimeUnixNano(blockInfo.blockEndTimeNanos())
                    .build();
            putSpan(spanMap, VirtualResource.BLOCK, blockSpan);
        } catch (Exception e) {
            System.err.printf("Error converting block %s: %s%n", blockInfo.blockNum(), e.getMessage());
            e.printStackTrace();
        }
        return spanMap;
    }

    private static void putSpan(Map<VirtualResource, List<Span>> spanMap, VirtualResource resource, Span span) {
        spanMap.computeIfAbsent(resource, k -> new ArrayList<>()).add(span);
    }
}
