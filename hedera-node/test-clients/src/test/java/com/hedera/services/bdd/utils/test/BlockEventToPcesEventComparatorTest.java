// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.utils.test;

import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.BlockStreamAccess;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.utility.NoOpRecycleBin;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.event.preconsensus.PcesConfig_;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.model.event.PlatformEvent;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * A utility class that reads and compares events from PCES files and block files. Events are sorted according to their
 * creation timestamps and compared for equivalency. This tool is useful for debugging block stream event hash chain
 * errors as detected in {@link com.hedera.services.bdd.junit.support.validators.block.EventHashBlockStreamValidator}.
 */
public class BlockEventToPcesEventComparatorTest {

    private static final String PCES_FILE = "preconsensus-events/0/2025/09/29/";
    private static final String BLOCK_FILE = "block-11.12.3";

    /**
     * A "test" that reads in the files specified in PCES_FILE and BLOCK_FILE, sorts the events by their creation
     *  timestamps and compares them for equivalency. Any differences are printed to standard out.
     */
    @Disabled("Disabled because it is not a real test, but a debugging tool")
    @Test
    void comparePcesAndBlockEvents() throws ParseException, IOException {
        final List<Pair<Timestamp, List<TransactionBody>>> blockPairs = readBlockEvents();
        final List<PlatformEvent> events = readPcesEvents();

        for (int i = 0; i < events.size() && i < blockPairs.size(); i++) {
            final PlatformEvent event = events.get(i);
            final Pair<Timestamp, List<TransactionBody>> blockPair = blockPairs.get(i);
            System.out.println("PCES Event " + i + " timestamp: "
                    + event.getEventCore().timeCreated() + ", Block Event timestamp: " + blockPair.left());
            if (!Objects.equals(event.getEventCore().timeCreated(), blockPair.left())) {
                System.out.println("Event " + i + " has different timestamps: "
                        + event.getEventCore().timeCreated() + " vs " + blockPair.left());
            }
            if (event.getTransactions().size() != blockPair.right().size()) {
                System.out.println("Event " + i + " has different number of transactions: "
                        + event.getTransactions().size() + " vs "
                        + blockPair.right().size());
            }
        }
    }

    private List<Pair<Timestamp, List<TransactionBody>>> readBlockEvents() throws ParseException {
        final Path path = Paths.get(BLOCK_FILE);
        final List<Block> blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(path);

        final List<Pair<Timestamp, List<TransactionBody>>> eventPairs = new ArrayList<>();
        Pair<Timestamp, List<TransactionBody>> eventPair = null;

        for (final Block block : blocks) {
            for (final BlockItem item : block.items()) {
                final var itemKind = item.item().kind();

                switch (itemKind) {
                    case EVENT_HEADER:
                        if (eventPair != null) {
                            eventPairs.add(eventPair);
                        }
                        final Timestamp timeCreated =
                                item.eventHeader().eventCore().timeCreated();
                        eventPair = Pair.of(timeCreated, new ArrayList<>());
                        break;
                    case SIGNED_TRANSACTION:
                        final Bytes transactionBytes = item.signedTransaction();
                        final SignedTransaction signedTransaction = SignedTransaction.PROTOBUF.parse(transactionBytes);
                        final TransactionBody transactionBody =
                                TransactionBody.PROTOBUF.parse(signedTransaction.bodyBytes());
                        final TransactionID transactionId = transactionBody.transactionIDOrThrow();
                        if (transactionId.nonce() == 0) {
                            eventPair.right().add(transactionBody);
                            if (transactionId.scheduled()) {
                                fail("Found nonce zero transaction and is scheduled");
                            }
                        }
                        break;
                    default:
                        // Skip other item types (block headers, proofs, etc.)
                        break;
                }
            }

            if (eventPair != null) {
                eventPairs.add(eventPair);
                eventPair = null;
            }
        }
        eventPairs.sort((e1, e2) -> HapiUtils.TIMESTAMP_COMPARATOR.compare(e1.left(), e2.left()));
        return eventPairs;
    }

    private List<PlatformEvent> readPcesEvents() throws IOException {
        final Path path = Paths.get(PCES_FILE);
        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                new TestConfigBuilder()
                        .withValue(PcesConfig_.COMPACT_LAST_FILE_ON_STARTUP, false)
                        .getOrCreateConfig(),
                new NoOpRecycleBin(),
                path,
                0L,
                false);
        final PbjStreamHasher hasher = new PbjStreamHasher();
        final List<PlatformEvent> events = new ArrayList<>();
        try (final IOIterator<PlatformEvent> iterator = fileTracker.getEventIterator(0L, 0L)) {
            while (iterator.hasNext()) {
                final PlatformEvent event = iterator.next();
                events.add(event);
                hasher.hashEvent(event);
            }
        }
        events.sort((e1, e2) -> {
            final Timestamp t1 = e1.getEventCore().timeCreated();
            final Timestamp t2 = e2.getEventCore().timeCreated();
            return HapiUtils.TIMESTAMP_COMPARATOR.compare(t1, t2);
        });
        return events;
    }
}
