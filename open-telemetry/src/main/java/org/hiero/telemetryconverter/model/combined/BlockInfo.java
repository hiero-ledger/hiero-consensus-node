package org.hiero.telemetryconverter.model.combined;

import static org.hiero.telemetryconverter.util.Utils.unixEpocNanosToInstant;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import java.io.EOFException;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.hiero.telemetryconverter.JfrFileReader;
import org.hiero.telemetryconverter.model.trace.BlockTraceInfo;
import org.hiero.telemetryconverter.model.trace.BlockTraceInfo.EventType;
import org.hiero.telemetryconverter.model.trace.EventTraceInfo;
import org.hiero.telemetryconverter.model.trace.RoundTraceInfo;
import org.hiero.telemetryconverter.model.trace.TransactionTraceInfo;
import org.hiero.telemetryconverter.util.CleanColorfulFormatter;
import org.hiero.telemetryconverter.util.WarningException;

/**
 * Correlated block information from JFR events and block stream. We collect and correlate all data into this class so
 * we have everything in one place to produce spans with.
 */
@SuppressWarnings("DataFlowIssue")
public class BlockInfo {
    private static final System.Logger LOGGER = System.getLogger(BlockInfo.class.getName());
    private final long blockNum;
    private final List<BlockTraceInfo> blockCreationTraces;
    private final List<RoundInfo> rounds = new ArrayList<>();
    private final long blockStartTimeNanos;
    private final long blockEndTimeNanos;

    public BlockInfo(final Path blockFile,
            final LongObjectHashMap<List<BlockTraceInfo>> blockTraces,
            final LongObjectHashMap<List<RoundTraceInfo>> roundTraces,
            final IntObjectHashMap<List<EventTraceInfo>> eventTraces,
            final IntObjectHashMap<List<TransactionTraceInfo>> transactionTraces) {

        try (var in = new ReadableStreamingData(new GZIPInputStream(Files.newInputStream(blockFile)))) {
            // parse the block file
            final Block block =  Block.PROTOBUF.parse(in);
            // get all items in the block
            final List<BlockItem> items = block.items();
            // get the block header
            final BlockHeader header = items.getFirst().blockHeader();
            blockNum = header.number();
            // find block trace info
            final List<BlockTraceInfo> traces = blockTraces.get(blockNum);
            if (traces == null) {
                throw new WarningException("No block traces found in JFR files for block " + blockNum);
            }
            blockCreationTraces = traces.stream()
                    .filter(t -> t.eventType() == EventType.CREATED).toList();
            // scan through all items and find rounds
            List<BlockItem> roundItems = null;
            for (final BlockItem item : items) {
                switch (item.item().kind()) {
                    case ROUND_HEADER, BLOCK_PROOF -> {
                        if (roundItems != null) {
                            rounds.add(new RoundInfo(roundItems, roundTraces, eventTraces, transactionTraces));
                        }
                        roundItems = new ArrayList<>();
                    }
                }
                if (roundItems != null) roundItems.add(item);
            }
            // count number of block items of each type
            LOGGER.log(Level.INFO, "Block " + blockNum + " has " + items.size() + " items with " +
                    CleanColorfulFormatter.GREY+"BlockItem.Kind Counts --> "+items.stream()
                        .map(i -> i.item().kind())
                        .distinct()
                        .map(k -> {
                            long count = items.stream().filter(i -> i.item().kind() == k).count();
                            return k + "="+count;
                        }).reduce((a, b) -> a + ", " + b).orElse(""));
        } catch (EOFException e) {
            throw new WarningException("Block file " + blockFile.getFileName() + " is not complete, server probably exited while writing");
        } catch (IOException | ParseException e) {
            throw new RuntimeException(e);
        }
        // scan all transactions to find the earliest start time.
        // If there are no transactions, use the earliest event created time.
        // If there are no events, use the earliest round created time.
        blockStartTimeNanos = rounds.stream()
                .flatMap(r -> r.events().stream())
                .flatMap(e -> e.transactions().stream())
                .mapToLong(TransactionInfo::transactionReceivedTimeNanos)
                .min().orElse(rounds.stream()
                        .flatMap(r -> r.events().stream())
                        .mapToLong(e -> e.createdTrace() != null ? e.createdTrace().startTimeNanos() : Long.MAX_VALUE)
                        .min().orElse(rounds.stream()
                                .flatMap(r -> r.createdTraces().stream())
                                .mapToLong(RoundTraceInfo::startTimeNanos)
                                .min().orElseThrow()));
        // find the oldest block creation time
        blockEndTimeNanos = blockCreationTraces.stream()
                .mapToLong(BlockTraceInfo::endTimeNanos)
                .max().orElseThrow();
    }

    public long blockNum() {
        return blockNum;
    }

    public List<BlockTraceInfo> blockCreationTraces() {
        return blockCreationTraces;
    }

    public List<RoundInfo> rounds() {
        return rounds;
    }

    public long blockStartTimeNanos() {
        return blockStartTimeNanos;
    }

    public long blockEndTimeNanos() {
        return blockEndTimeNanos;
    }

    /**
     * Count total number of transactions in all events of all rounds
     */
    public long txCount() {
        return rounds.stream()
                .flatMap(r -> r.events().stream())
                .mapToLong(e -> e.transactions().size())
                .sum();
    }

    @Override
    public String toString() {
        return "BlockInfo{" +
                "blockNum=" + blockNum +
                ", blockCreationTraces=" + blockCreationTraces.size() +
                ", rounds=" + rounds.size() +
                ", events=" + rounds.stream()
                        .mapToLong(r -> r.events().size())
                        .sum() +
                ", transactions=" + rounds.stream()
                            .flatMap(r -> r.events().stream())
                            .mapToLong(e -> e.transactions().size())
                            .sum() +
                ", blockStartTimeNanos=" + unixEpocNanosToInstant(blockStartTimeNanos) +
                ", blockEndTimeNanos=" + unixEpocNanosToInstant(blockEndTimeNanos) +
                '}';
    }
}
