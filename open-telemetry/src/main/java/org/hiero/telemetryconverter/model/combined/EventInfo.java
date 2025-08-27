package org.hiero.telemetryconverter.model.combined;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockItem.ItemOneOfType;
import com.hedera.hapi.block.stream.input.EventHeader;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.hiero.telemetryconverter.model.trace.EventTraceInfo;
import org.hiero.telemetryconverter.model.trace.TransactionTraceInfo;

/**
 * Correlated event information from JFR events and block stream. We collect and correlate all data into this class so
 * we have everything in one place to produce spans with.
 */
public class EventInfo {
    private final int eventHash;// EventCore.hashCode() value
    private final long eventCreatorNodeId;
    private final EventTraceInfo createdTrace;
    private final List<EventTraceInfo> gossipedTraces = new ArrayList<>();
    private final List<EventTraceInfo> receivedTraces = new ArrayList<>();
    private final List<EventTraceInfo> preHandledTraces = new ArrayList<>();
    private final List<TransactionInfo> transactions = new ArrayList<>();
    private final long eventStartTimeNanos;
    private final long eventEndTimeNanos;

    public EventInfo(final long blockNum,
            final long eventCreatorNodeId,
            final List<BlockItem> eventItems,
            final IntObjectHashMap<List<EventTraceInfo>> eventTraces,
            final IntObjectHashMap<List<TransactionTraceInfo>> transactionTraces) {
        this.eventCreatorNodeId = eventCreatorNodeId;
        final EventHeader eventHeader = eventItems.getFirst().eventHeader();
        eventHash = eventHeader.eventCore().hashCode();
        final List<EventTraceInfo> thisEventTraces =
                eventTraces.getIfAbsent(eventHash, ArrayList::new);
        createdTrace = thisEventTraces.stream()
                .filter(t -> t.eventType() == EventTraceInfo.EventType.CREATED)
                .findFirst()
                .orElse(null);
        gossipedTraces.addAll(thisEventTraces.stream()
                .filter(t -> t.eventType() == EventTraceInfo.EventType.GOSSIPED)
                .toList());
        receivedTraces.addAll(thisEventTraces.stream()
                .filter(t -> t.eventType() == EventTraceInfo.EventType.RECEIVED)
                .toList());
        preHandledTraces.addAll(thisEventTraces.stream()
                .filter(t -> t.eventType() == EventTraceInfo.EventType.PRE_HANDLED)
                .toList());
        // scan through all block items and find transactions
        List<BlockItem> transactionItems = null;
        for (final BlockItem item : eventItems) {
            if (item.item().kind() == ItemOneOfType.SIGNED_TRANSACTION) {
                if (transactionItems != null) {
                    transactions.add(new TransactionInfo(blockNum, transactionItems, transactionTraces));
                }
                transactionItems = new ArrayList<>();
            }
            if (transactionItems != null) transactionItems.add(item);
        }
        if (transactionItems != null) transactions.add(new TransactionInfo(blockNum, transactionItems, transactionTraces));
        // find event start and end time
        eventStartTimeNanos = createdTrace != null ? createdTrace.startTimeNanos() :
                eventTraces.stream().flatMap(List::stream)
                        .mapToLong(t -> Math.min(t.startTimeNanos(), t.endTimeNanos()))
                        .min()
                        .orElse(0L);
        eventEndTimeNanos = preHandledTraces.stream()
                .mapToLong(ph -> ph.endTimeNanos())
                .max()
                .orElse(eventStartTimeNanos);
    }

    public int eventHash() {
        return eventHash;
    }

    public long eventCreatorNodeId() {
        return eventCreatorNodeId;
    }

    public EventTraceInfo createdTrace() {
        return createdTrace;
    }

    public List<EventTraceInfo> gossipedTraces() {
        return gossipedTraces;
    }

    public List<EventTraceInfo> receivedTraces() {
        return receivedTraces;
    }

    public List<EventTraceInfo> preHandledTraces() {
        return preHandledTraces;
    }

    public List<TransactionInfo> transactions() {
        return transactions;
    }

    public long eventStartTimeNanos() {
        return eventStartTimeNanos;
    }

    public long eventEndTimeNanos() {
        return eventEndTimeNanos;
    }

    public long firstTransactionOrEventCreationStart() {
        if (transactions.isEmpty()) {
            return createdTrace.startTimeNanos();
        } else {
            return transactions.stream()
                .mapToLong(t -> t.transactionReceivedTimeNanos())
                .min()
                .orElse(eventStartTimeNanos);
        }
    }

    public long lastTransactionOrEventCreationEnd() {
        if (transactions.isEmpty()) {
            return createdTrace.endTimeNanos();
        } else {
            return transactions.stream()
                .mapToLong(t -> t.transactionReceivedEndTimeNanos())
                .max()
                .orElse(eventStartTimeNanos);
        }
    }

    public long endOfGossipOrEventCreation() {
        if (gossipedTraces.isEmpty()) {
            return createdTrace.endTimeNanos();
        } else {
            return gossipedTraces.stream()
                .mapToLong(gt -> gt.endTimeNanos())
                .max()
                .orElse(eventStartTimeNanos);
        }
    }
}
