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
    private final EventTraceInfo createdTrace;
    private final List<EventTraceInfo> gossipedTraces = new ArrayList<>();
    private final List<EventTraceInfo> receivedTraces = new ArrayList<>();
    private final List<EventTraceInfo> preHandledTraces = new ArrayList<>();
    private final List<TransactionInfo> transactions = new ArrayList<>();
    private final long eventStartTimeNanos;
    private final long eventEndTimeNanos;

    public EventInfo(final List<BlockItem> eventItems,
            final IntObjectHashMap<List<EventTraceInfo>> eventTraces,
            final IntObjectHashMap<List<TransactionTraceInfo>> transactionTraces) {
        final EventHeader eventHeader = eventItems.getFirst().eventHeader();
        eventHash = eventHeader.eventCore().hashCode();
        createdTrace = eventTraces.get(eventHash).stream()
                .filter(t -> t.eventType() == EventTraceInfo.EventType.CREATED)
                .findFirst()
                .orElse(null);
        gossipedTraces.addAll(eventTraces.get(eventHash).stream()
                .filter(t -> t.eventType() == EventTraceInfo.EventType.GOSSIPED)
                .toList());
        receivedTraces.addAll(eventTraces.get(eventHash).stream()
                .filter(t -> t.eventType() == EventTraceInfo.EventType.RECEIVED)
                .toList());
        preHandledTraces.addAll(eventTraces.get(eventHash).stream()
                .filter(t -> t.eventType() == EventTraceInfo.EventType.PRE_HANDLED)
                .toList());
        // scan through all block items and find transactions
        List<BlockItem> transactionItems = null;
        for (final BlockItem item : eventItems) {
            if (item.item().kind() == ItemOneOfType.SIGNED_TRANSACTION) {
                if (transactionItems != null) {
                    transactions.add(new TransactionInfo(transactionItems, transactionTraces));
                }
                transactionItems = new ArrayList<>();
            }
            if (transactionItems != null) transactionItems.add(item);
        }
        if (transactionItems != null) transactions.add(new TransactionInfo(transactionItems, transactionTraces));
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
}
