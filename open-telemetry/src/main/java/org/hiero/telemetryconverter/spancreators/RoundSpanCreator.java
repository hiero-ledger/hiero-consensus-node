package org.hiero.telemetryconverter.spancreators;

import static org.hiero.telemetryconverter.model.VirtualResource.EXECUTION;
import static org.hiero.telemetryconverter.util.Utils.putSpan;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.opentelemetry.pbj.trace.v1.Span;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.hiero.telemetryconverter.model.VirtualResource;
import org.hiero.telemetryconverter.model.combined.EventInfo;
import org.hiero.telemetryconverter.model.combined.RoundInfo;
import org.hiero.telemetryconverter.model.trace.EventTraceInfo;
import org.hiero.telemetryconverter.model.trace.RoundTraceInfo;
import org.hiero.telemetryconverter.util.Utils;

public class RoundSpanCreator {

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
                    .attributes(Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()))
                    .build();
            putSpan(spanMap, VirtualResource.CONSENSUS, blockSpan);
        } catch (Exception e) {
            System.err.printf("Error converting round %s in block %s: %s%n", roundInfo.roundNumber(),
                    roundInfo.blockNumber(), e.getMessage());
            e.printStackTrace();
        }
    }

    private static void createIngestAndConsensusSpans(final RoundInfo roundInfo, final long[] nodeIds,
            final long roundSpanNum, Map<VirtualResource, List<Span>> spanMap, Bytes roundTraceID, Bytes roundSpanID) {
        // create span for transactions arriving for all events in round
        if(roundInfo.events().stream().map(EventInfo::transactions).mapToLong(List::size).sum() > 0) {
            Utils.putSpan(spanMap, VirtualResource.INGESTION, Span.newBuilder()
                    .traceId(roundTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(roundSpanNum, 1)) // 8 byte span id
                    .parentSpanId(roundSpanID)
                    .name("Tx Ingestion")
                    .startTimeUnixNano(roundInfo.events().stream()
                            .mapToLong(EventInfo::firstTransactionOrEventCreationStart)
                            .min().orElseThrow())
                    .endTimeUnixNano(roundInfo.events().stream()
                            .mapToLong(EventInfo::lastTransactionOrEventCreationEnd)
                            .max().orElseThrow())
                    .attributes(Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()))
                    .build());
        }
        // create span for summary of all events creation in round
        for (EventInfo event : roundInfo.events()) {
            Utils.putSpan(spanMap, VirtualResource.EVENT_CREATION, Span.newBuilder()
                    .traceId(roundTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(event.eventHash(), 2)) // 8 byte span id
                    .parentSpanId(roundSpanID)
                    .name("Event Creation N"+event.eventCreatorNodeId()+" T"+event.transactions().size())
                    .startTimeUnixNano(event.createdTrace().startTimeNanos())
                    .endTimeUnixNano(event.createdTrace().endTimeNanos())
                    .attributes(Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()),
                            Utils.kv("node",event.eventCreatorNodeId()), Utils.kv("transactions",event.transactions().size()))
                    .build());
        }
        // create span for summary of all events gossip in round
        if (roundInfo.events().stream().map(EventInfo::gossipedTraces).mapToLong(List::size).sum() > 0) {
            Utils.putSpan(spanMap, VirtualResource.GOSSIP, Span.newBuilder()
                    .traceId(roundTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(roundSpanNum, 3)) // 8 byte span id
                    .parentSpanId(roundSpanID)
                    .name("Gossip")
                    .startTimeUnixNano(roundInfo.events().stream().map(EventInfo::gossipedTraces)
                            .flatMap(List::stream)
                            .mapToLong(EventTraceInfo::startTimeNanos)
                            .min().orElseThrow())
                    .endTimeUnixNano(roundInfo.events().stream().map(EventInfo::gossipedTraces)
                            .flatMap(List::stream)
                            .mapToLong(EventTraceInfo::endTimeNanos)
                            .max().orElseThrow())
                    .attributes(Utils.kv("block",roundInfo.blockNumber()), Utils.kv("round",roundInfo.roundNumber()))
                    .build());
        }
        for (long nodeId: nodeIds) {
            // create span for consensus time span
            Utils.putSpan(spanMap, VirtualResource.CONSENSUS, Span.newBuilder()
                    .traceId(roundTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(roundSpanNum, nodeId * 1000 + 1)) // 8 byte span id
                    .parentSpanId(roundSpanID)
                    .name("Consensus N"+nodeId)
                    .startTimeUnixNano(roundInfo.events().stream()
                            .filter(e -> e.eventCreatorNodeId() == nodeId)
                            .mapToLong(EventInfo::endOfGossipOrEventCreation).max().orElseThrow())
                    .endTimeUnixNano(roundInfo.createdTraces().stream()
                            .filter(t -> t.nodeId() == nodeId)
                            .mapToLong(RoundTraceInfo::endTimeNanos).min()
                            .orElseThrow())
                    .attributes(Utils.kv("block", roundInfo.blockNumber()), Utils.kv("round", roundInfo.roundNumber()),
                            Utils.kv("node",nodeId))
                    .build());
            // create round execution span
            Utils.putSpan(spanMap, EXECUTION, Span.newBuilder()
                    .traceId(roundTraceID) // 16 byte trace id
                    .spanId(Utils.longToHash8Bytes(roundSpanNum, nodeId * 1000 + 2)) // 8 byte span id
                    .parentSpanId(roundSpanID)
                    .name("Execution N"+nodeId)
                    .startTimeUnixNano(roundInfo.executedTraces().stream()
                            .filter(t -> t.nodeId() == nodeId)
                            .mapToLong(RoundTraceInfo::startTimeNanos)
                            .min().orElse(roundInfo.roundStartTimeNanos()))
                    .endTimeUnixNano(roundInfo.executedTraces().stream()
                            .filter(t -> t.nodeId() == nodeId)
                            .mapToLong(RoundTraceInfo::endTimeNanos)
                            .max().orElse(roundInfo.roundEndTimeNanos()))
                    .attributes(Utils.kv("block", roundInfo.blockNumber()), Utils.kv("round", roundInfo.roundNumber()),
                            Utils.kv("node",nodeId))
                    .build());
        }
    }
}
