// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.utils.test;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.Codec;
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
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.hiero.consensus.crypto.PbjStreamHasher;
import org.hiero.consensus.model.event.PlatformEvent;
import org.junit.jupiter.api.Test;

public class ReadBlocksAndPces {
    String pcesFile = "/Users/lazarpetrovic/Downloads/2025-09-22T13+03+02.389731Z_seq0_minr1_maxr501_orgn0.pces";

    @Test
    void printBlockFiles() throws ParseException {
        System.out.println("BEGIN");
        final Path path = Paths.get("/Users/lazarpetrovic/Downloads/block-11.12.3");
        final List<Block> blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(path);

        int count = 0;
        final List<Pair<Timestamp, List<TransactionBody>>> events = new ArrayList<>();
        final List<TransactionBody> transactions = new ArrayList<>();
        Pair<Timestamp, List<TransactionBody>> event = null;
        for (final Block block : blocks) {
            for (final BlockItem item : block.items()) {
                final var itemKind = item.item().kind();

                switch (itemKind) {
                    case EVENT_HEADER:
                        count++;
                        // System.out.println(item.eventHeader().eventCore().timeCreated());
                        if (event != null) {
                            events.add(event);
                        }
                        event = Pair.of(item.eventHeader().eventCore().timeCreated(), new ArrayList<>());
                        break;
                    case SIGNED_TRANSACTION:
                        final Bytes transactionBytes = item.signedTransaction();
                        final SignedTransaction signedTransaction = SignedTransaction.PROTOBUF.parse(transactionBytes);
                        final TransactionBody transactionBody =
                                TransactionBody.PROTOBUF.parse(signedTransaction.bodyBytes());
                        if (transactionBody.hasStateSignatureTransaction()) {
                            System.out.println("Found it");
                        }
                        final TransactionID transactionId = transactionBody.transactionIDOrThrow();
                        if (transactionBody.hasStateSignatureTransaction() || transactionId.nonce() == 0) {
                            event.right().add(transactionBody);
                            // System.out.println(TransactionBody.JSON.toJSON(transactionBody));
                        }
                        break;
                    default:
                        // Skip other item types (block headers, proofs, etc.)
                        break;
                }
            }
        }
        Collections.sort(events, (e1, e2) -> {
            return HapiUtils.TIMESTAMP_COMPARATOR.compare(e1.left(), e2.left());
        });
        //        for (Pair<Timestamp, List<TransactionBody>> pair : events) {
        //            System.out.println(pair.left());
        //            for (final TransactionBody tb : pair.right()) {
        //                System.out.println(TransactionBody.JSON.toJSON(tb));
        //            }
        //        }
        System.out.println("Total events: " + count);
    }

    @Test
    void readAndPrintPcesFiles() throws IOException {
        System.out.println("BEGIN");
        final Path path = Paths.get(pcesFile);
        final PcesFileTracker fileTracker = PcesFileReader.readFilesFromDisk(
                new TestConfigBuilder()
                        .withValue(PcesConfig_.COMPACT_LAST_FILE_ON_STARTUP, false)
                        .getOrCreateConfig(),
                new NoOpRecycleBin(),
                path,
                0L,
                false);
        final PbjStreamHasher hasher = new PbjStreamHasher();
        int count = 0;
        final List<PlatformEvent> events = new ArrayList<>();
        try (final IOIterator<PlatformEvent> iterator = fileTracker.getEventIterator(0l, 0l)) {
            while (iterator.hasNext()) {
                final PlatformEvent event = iterator.next();
                events.add(event);
                hasher.hashEvent(event);
                count++;
            }
        }
        Collections.sort(events, (e1, e2) -> {
            final Timestamp t1 = e1.getEventCore().timeCreated();
            final Timestamp t2 = e2.getEventCore().timeCreated();
            return HapiUtils.TIMESTAMP_COMPARATOR.compare(t1, t2);
        });
        for (final PlatformEvent event : events) {
            System.out.println(event.getEventCore().timeCreated());
            event.getGossipEvent().transactions().stream()
                    .map(uncheckedParse(SignedTransaction.PROTOBUF))
                    .map(SignedTransaction::bodyBytes)
                    .map(uncheckedParse(TransactionBody.PROTOBUF))
                    .forEach(tb -> System.out.println(TransactionBody.JSON.toJSON(tb)));
        }
        System.out.println("Total events: " + count);
    }

    private static <T> Function<Bytes, T> uncheckedParse(final Codec<T> codec) {
        return bytes -> {
            try {
                return codec.parse(bytes);
            } catch (final ParseException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
