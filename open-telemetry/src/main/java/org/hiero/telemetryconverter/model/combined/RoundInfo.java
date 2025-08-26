package org.hiero.telemetryconverter.model.combined;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockItem.ItemOneOfType;
import com.hedera.hapi.block.stream.input.RoundHeader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.hiero.telemetryconverter.model.trace.EventTraceInfo;
import org.hiero.telemetryconverter.model.trace.RoundTraceInfo;
import org.hiero.telemetryconverter.model.trace.TransactionTraceInfo;
import org.hiero.telemetryconverter.util.WarningException;

/**
 * Correlated round information from JFR events and block stream. We collect and correlate all data into this class so
 * we have everything in one place to produce spans with.
 */
public class RoundInfo {
    private final long roundNumber;
    private final List<RoundTraceInfo> createdTraces = new ArrayList<>();
    private final List<RoundTraceInfo> executedTraces = new ArrayList<>();
    private final List<RoundTraceInfo> hashedTraces = new ArrayList<>();
    private final List<EventInfo> events = new ArrayList<>();
    private final long roundStartTimeNanos;
    private final long roundEndTimeNanos;

    public RoundInfo(final List<BlockItem> roundItems,
            final LongObjectHashMap<List<RoundTraceInfo>> roundTraces,
            final IntObjectHashMap<List<EventTraceInfo>> eventTraces,
            final IntObjectHashMap<List<TransactionTraceInfo>> transactionTraces) {
        final RoundHeader roundHeader = roundItems.getFirst().roundHeader();
        roundNumber = roundHeader.roundNumber();
        // find all round trace info
        var traces = roundTraces.get(roundNumber);
        if (traces == null) {
            throw new WarningException("No round traces found in JFR files for round " + roundNumber);
        }
        traces.forEach(t -> {;
            switch (t.eventType()) {
                case CREATED -> createdTraces.add(t);
                case EXECUTED -> executedTraces.add(t);
                case HASHED -> hashedTraces.add(t);
            }
        });
        // scan through all block items and find events
        List<BlockItem> eventItems = null;
        for (final BlockItem item : roundItems) {
            if (Objects.requireNonNull(item.item().kind()) == ItemOneOfType.EVENT_HEADER) {
                if (eventItems != null) {
                    events.add(new EventInfo(eventItems, eventTraces, transactionTraces));
                }
                eventItems = new ArrayList<>();
            }
            if(eventItems != null) eventItems.add(item);
        }
        if (eventItems != null) events.add(new EventInfo(eventItems, eventTraces, transactionTraces));


        // scan all transactions to find the earliest start time.
        // If there are no transactions, use the earliest event created time.
        // If there are no events, use the earliest round created time.
        roundStartTimeNanos = events.stream().mapToLong(EventInfo::firstTransactionOrEventCreation).min().orElseThrow();
//        roundStartTimeNanos = traces.stream()
//                .mapToLong(RoundTraceInfo::startTimeNanos)
//                .max().orElseThrow();
        // find the oldest block creation time
        roundEndTimeNanos = traces.stream()
                .mapToLong(RoundTraceInfo::endTimeNanos)
                .max().orElseThrow();
        System.out.println("roundNumber="+roundNumber+" size= "+traces.size()+" roundStartTimeNanos = " + roundStartTimeNanos+"  roundEndTimeNanos = " + roundEndTimeNanos+" difference = "+(roundEndTimeNanos-roundStartTimeNanos));
        for (var t : traces) {
            System.out.println("   "+t);
        }
    }

    public long roundNumber() {
        return roundNumber;
    }

    public List<RoundTraceInfo> createdTraces() {
        return createdTraces;
    }

    public List<RoundTraceInfo> executedTraces() {
        return executedTraces;
    }

    public List<RoundTraceInfo> hashedTraces() {
        return hashedTraces;
    }

    public List<EventInfo> events() {
        return events;
    }

    public long roundStartTimeNanos() {
        return roundStartTimeNanos;
    }

    public long roundEndTimeNanos() {
        return roundEndTimeNanos;
    }

    public long executionStartTimeNanos() {
        return executedTraces.stream().mapToLong(RoundTraceInfo::startTimeNanos).min().orElse(roundStartTimeNanos);
    }

    public long executionEndTimeNanos() {
        return executedTraces.stream().mapToLong(RoundTraceInfo::endTimeNanos).max().orElse(roundEndTimeNanos);
    }

}
