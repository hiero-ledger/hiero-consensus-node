package org.hiero.telemetryconverter;

import static java.lang.System.Logger.Level.INFO;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.hiero.telemetryconverter.model.trace.BlockTraceInfo;
import org.hiero.telemetryconverter.model.trace.EventTraceInfo;
import org.hiero.telemetryconverter.model.trace.RoundTraceInfo;
import org.hiero.telemetryconverter.model.trace.TransactionTraceInfo;
import org.hiero.telemetryconverter.util.Utils;

public class JfrFileReader {
    private static final System.Logger LOGGER = System.getLogger(JfrFileReader.class.getName());

    /**
     * Read a single JFR file from single node and populate the roundTraces, eventTraces, and transactionTraces maps.
     * <p>
     * We expect the JFR file to have a name like "trace-node-25.jfr" where 25 is the node ID.
     * </p>
     *
     * @param jfrFile the path to the JFR file
     */
    public static void readJfrFile(final Path jfrFile,
            final LongObjectHashMap<List<BlockTraceInfo>> blockTraces,
            final LongObjectHashMap<List<RoundTraceInfo>> roundTraces,
            final IntObjectHashMap<List<EventTraceInfo>> eventTraces,
            final IntObjectHashMap<List<TransactionTraceInfo>> transactionTraces) {
        final String fileName = jfrFile.getFileName().toString();
        final long nodeId = Long.parseLong(fileName.substring(fileName.indexOf("node-") + 5, fileName.indexOf(".jfr")));
        LOGGER.log(INFO,"Reading JFR file: {0} , nodeId:{1}", jfrFile, nodeId);
        try (var rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                final RecordedEvent e = rf.readEvent();
                try {
                    switch (e.getEventType().getName()) {
                        case "consensus.Block" -> {
                            final long blockNum = e.getLong("blockNumber");
                            final int eventTypeOrdinal = e.getInt("eventType");
                            final BlockTraceInfo blockTraceInfo = new BlockTraceInfo(nodeId, blockNum,
                                    BlockTraceInfo.EventType.values()[eventTypeOrdinal],
                                    Utils.instantToUnixEpocNanos(e.getStartTime()),
                                    Utils.instantToUnixEpocNanos(e.getEndTime()));
                            blockTraces.getIfAbsentPut(blockNum, ArrayList::new).add(blockTraceInfo);
                        }
                        case "consensus.Round" -> {
                            final long roundNum = e.getLong("roundNum");
                            final int eventTypeOrdinal = e.getInt("eventType");
                            final long startTimeNanos = Utils.instantToUnixEpocNanos(e.getStartTime());
                            final long endTimeNanos = Utils.instantToUnixEpocNanos(e.getEndTime());
                            final java.time.Duration duration = java.time.Duration.ofNanos(
                                    endTimeNanos-startTimeNanos);
                            if (!duration.equals(e.getDuration())) throw new IllegalStateException(
                                    "Inconsistent duration in Round event: " + e);
                            if (duration.getSeconds() >= 1) {
                                LOGGER.log(Level.WARNING,
                                        () -> String.format(
                                                "Long duration in Round event: %d, duration: %s",
                                                roundNum, duration));
                            }
                            final RoundTraceInfo roundTraceInfo = new RoundTraceInfo(nodeId, roundNum,
                                    RoundTraceInfo.EventType.values()[eventTypeOrdinal],
                                    startTimeNanos,endTimeNanos);
                            roundTraces.getIfAbsentPut(roundNum, ArrayList::new).add(roundTraceInfo);
                        }
                        case "consensus.Event" -> {
                            final int eventHash = e.getInt("eventHash");
                            final int eventTypeOrdinal = e.getInt("eventType");
                            final EventTraceInfo eventTraceInfo = new EventTraceInfo(nodeId, eventHash,
                                    EventTraceInfo.EventType.values()[eventTypeOrdinal],
                                    Utils.instantToUnixEpocNanos(e.getStartTime()),
                                    Utils.instantToUnixEpocNanos(e.getEndTime()));
                            eventTraces.getIfAbsentPut(eventHash, ArrayList::new)
                                    .add(eventTraceInfo);
                        }
                        case "consensus.Transaction" -> {
                            final int txHash = e.getInt("txHash");
                            final int eventTypeOrdinal = e.getInt("eventType");
                            final TransactionTraceInfo transactionTraceInfo = new TransactionTraceInfo(nodeId, txHash,
                                    TransactionTraceInfo.EventType.values()[eventTypeOrdinal],
                                    Utils.instantToUnixEpocNanos(e.getStartTime()),
                                    Utils.instantToUnixEpocNanos(e.getEndTime()));
                            transactionTraces.getIfAbsentPut(txHash, ArrayList::new)
                                    .add(transactionTraceInfo);
                        }
                        // ignore other events
                    }
                } catch (Exception ex) {
                    System.err.printf("Error processing event %s: %s -> %s%n", e.getEventType().getName(),
                            ex.getMessage(), e);
                }
            }
        } catch (IOException e) {
            System.err.printf("Error reading JFR file %s: %s%n", jfrFile, e.getMessage());
            e.printStackTrace();
        }
        final long numberOfBlockTraces = blockTraces.values().stream().mapToLong(List::size).sum();
        final long numberOfRoundTraces = roundTraces.values().stream().mapToLong(List::size).sum();
        final long numberOfEventTraces = eventTraces.values().stream().mapToLong(List::size).sum();
        final long numberOfTransactionTraces = transactionTraces.values().stream()
                .mapToLong(List::size).sum();
        LOGGER.log(INFO,
                "    Finished reading JFR file: {0}, found "
                        + "{1} block traces, "
                        + "{2} round traces, "
                        + "{3} event traces, "
                        + "{4} transaction traces",
                jfrFile.getFileName(), numberOfBlockTraces, numberOfRoundTraces, numberOfEventTraces,
                numberOfTransactionTraces);
    }
}
