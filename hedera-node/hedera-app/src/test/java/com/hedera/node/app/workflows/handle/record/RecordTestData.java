// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records;

import static com.swirlds.common.stream.LinkedObjectStreamUtilities.getPeriod;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.HashObject;
import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.hapi.streams.SidecarFile;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordFormatV6;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.app.state.SingleTransactionRecord.TransactionOutputs;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import com.swirlds.common.stream.Signer;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.crypto.PlatformSigner;
import com.swirlds.platform.crypto.PublicStores;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;

/**
 * Test data for record stream file tests. It starts with a single JSON dump of a real main net record file in
 * resources. From the transactions in that record file it generates a number of blocks of transactions. Some
 * with sidecar files and some without.
 */
@SuppressWarnings("DataFlowIssue")
public class RecordTestData {
    /** Empty byte array */
    private static final byte[] EMPTY_ARRAY = new byte[] {};
    /** Random with fixed seed for reproducibility of test data generated */
    private static final Random RANDOM = new Random(123456789L);

    /** Test Block Num **/
    public static final long BLOCK_NUM = RANDOM.nextInt(0, Integer.MAX_VALUE);
    /** Test Version */
    public static final SemanticVersion VERSION = new SemanticVersion(1, 2, 3, "", "");
    /** A hash used as the start of running hash changes in tests */
    public static final Hash STARTING_RUNNING_HASH;
    /** A hash used as the start of running hash changes in tests, in HashObject format */
    public static final HashObject STARTING_RUNNING_HASH_OBJ;
    /** An expected hash to get at the end of all transactions in all blocks */
    public static final Bytes ENDING_RUNNING_HASH;
    /** An expected hash object to get at the end of all transactions in all blocks */
    public static final HashObject ENDING_RUNNING_HASH_OBJ;
    /** List of test blocks, each containing a number of transaction records */
    public static final List<List<SingleTransactionRecord>> TEST_BLOCKS;
    /** blocks to create, true means generate sidecar items for transactions in that block */
    public static final boolean[] TEST_BLOCKS_WITH_SIDECARS =
            new boolean[] {false, true, true, true, false, true, false, false, true};
    //    block seconds       24,   26,   28,   30,    32,   34,    36,    38,   40

    /** Transaction Outputs data */
    private static final TransactionOutputs SIMPLE_OUTPUT = new TransactionOutputs(TokenType.FUNGIBLE_COMMON);
    /** Test Signer for signing record stream files */
    public static final Signer SIGNER;
    /** Test user public key */
    public static final PublicKey USER_PUBLIC_KEY;

    static {
        try {
            // generate node keys and signer
            final var keysAndCerts = KeysAndCerts.generate(
                    NodeId.FIRST_NODE_ID, EMPTY_ARRAY, EMPTY_ARRAY, EMPTY_ARRAY, new PublicStores());
            // get public key that was generated for the user
            USER_PUBLIC_KEY = keysAndCerts.sigKeyPair().getPublic();
            // create signer
            SIGNER = new PlatformSigner(keysAndCerts);
            // create blocks
            final List<List<SingleTransactionRecord>> testBlocks = new ArrayList<>();
            // load real record stream items from a JSON resource file
            final Path jsonPath = Path.of(RecordTestData.class
                    .getResource("/record-files/2023-05-01T00_00_24.038693760Z.json")
                    .toURI());
            final RecordStreamFile recordStreamFile =
                    RecordStreamFile.JSON.parse(new ReadableStreamingData(Files.newInputStream(jsonPath)));
            final List<SingleTransactionRecord> realRecordStreamItems = recordStreamFile.recordStreamItems().stream()
                    .map(item -> new SingleTransactionRecord(
                            item.transaction(), item.record(), Collections.emptyList(), SIMPLE_OUTPUT))
                    .toList();
            // load real sidecar items from a JSON resource file
            final Path sidecarPath = Path.of(RecordTestData.class
                    .getResource("/record-files/sidecar/2023-04-21T08_14_02.002040003Z_01.json")
                    .toURI());
            final SidecarFile sidecarFile =
                    SidecarFile.JSON.parse(new ReadableStreamingData(Files.newInputStream(sidecarPath)));
            final List<TransactionSidecarRecord> exampleSidecarItems = sidecarFile.sidecarRecords();

            // for the first block, use the loaded transactions as is
            testBlocks.add(realRecordStreamItems);
            // now add blocks for TEST_BLOCKS_WITH_SIDECARS types
            Instant firstTransactionConsensusTime = Instant.ofEpochSecond(
                    realRecordStreamItems
                            .get(0)
                            .transactionRecord()
                            .consensusTimestamp()
                            .seconds(),
                    realRecordStreamItems
                            .get(0)
                            .transactionRecord()
                            .consensusTimestamp()
                            .nanos());
            for (int j = 1; j < TEST_BLOCKS_WITH_SIDECARS.length; j++) {
                boolean generateSidecarItems = TEST_BLOCKS_WITH_SIDECARS[j];
                final int count = 100 + RANDOM.nextInt(900);
                firstTransactionConsensusTime = firstTransactionConsensusTime.plusSeconds(2);
                Instant consenusTime = firstTransactionConsensusTime;
                List<SingleTransactionRecord> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    SingleTransactionRecord item =
                            realRecordStreamItems.get(RANDOM.nextInt(realRecordStreamItems.size()));
                    items.add(changeTransactionConsensusTimeAndGenerateSideCarItems(
                            consenusTime, item, generateSidecarItems, exampleSidecarItems));
                    consenusTime = consenusTime.plusNanos(10);
                }
                testBlocks.add(items);
            }
            TEST_BLOCKS = Collections.unmodifiableList(testBlocks);
            // validate that all generated blocks have valid consensus time and transactions are in correct blocks
            TEST_BLOCKS.forEach(block -> {
                var time = Instant.ofEpochSecond(
                        block.get(0).transactionRecord().consensusTimestamp().seconds(),
                        block.get(0).transactionRecord().consensusTimestamp().nanos());
                var period = getPeriod(time, 2000);
                var count = block.stream()
                        .map(item -> Instant.ofEpochSecond(
                                item.transactionRecord().consensusTimestamp().seconds(),
                                item.transactionRecord().consensusTimestamp().nanos()))
                        .filter(time2 -> getPeriod(time2, 2000) != period)
                        .count();
                assert count == 0 : "Found at least one transaction in wrong block, count = " + count;
            });
            // create a random hash for running hash start
            MessageDigest messageDigest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
            byte[] randomBytes = new byte[100];
            RANDOM.nextBytes(randomBytes);
            messageDigest.update(randomBytes);
            final byte[] runningHashStart = messageDigest.digest();
            STARTING_RUNNING_HASH = new Hash(runningHashStart, DigestType.SHA_384);
            STARTING_RUNNING_HASH_OBJ =
                    new HashObject(HashAlgorithm.SHA_384, runningHashStart.length, Bytes.wrap(runningHashStart));
            // compute end hash
            ENDING_RUNNING_HASH = BlockRecordFormatV6.INSTANCE.computeNewRunningHash(
                    STARTING_RUNNING_HASH_OBJ.hash(),
                    TEST_BLOCKS.stream()
                            .flatMap(List::stream)
                            .map(str -> BlockRecordFormatV6.INSTANCE.serialize(str, BLOCK_NUM, VERSION))
                            .toList());
            ENDING_RUNNING_HASH_OBJ =
                    new HashObject(HashAlgorithm.SHA_384, (int) ENDING_RUNNING_HASH.length(), ENDING_RUNNING_HASH);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Given a SingleTransactionRecord update its consensus timestamp and generate sidecar items */
    private static SingleTransactionRecord changeTransactionConsensusTimeAndGenerateSideCarItems(
            final Instant newConsensusTime,
            final SingleTransactionRecord singleTransactionRecord,
            final boolean generateSideCarItems,
            final List<TransactionSidecarRecord> exampleSidecarItems)
            throws Exception {
        final Timestamp consensusTimestamp = Timestamp.newBuilder()
                .seconds(newConsensusTime.getEpochSecond())
                .nanos(newConsensusTime.getNano())
                .build();
        final Transaction transaction = singleTransactionRecord.transaction();
        final SignedTransaction signedTransaction = SignedTransaction.PROTOBUF.parse(
                BufferedData.wrap(transaction.signedTransactionBytes().toByteArray()));
        final TransactionBody transactionBody = TransactionBody.PROTOBUF.parse(
                BufferedData.wrap(signedTransaction.bodyBytes().toByteArray()));

        final TransactionBody newTransactionBody = transactionBody
                .copyBuilder()
                .transactionID(transactionBody
                        .transactionID()
                        .copyBuilder()
                        .transactionValidStart(consensusTimestamp)
                        .build())
                .build();
        final SignedTransaction newSignedTransaction = signedTransaction
                .copyBuilder()
                .bodyBytes(TransactionBody.PROTOBUF.toBytes(newTransactionBody))
                .build();
        final Transaction newTransaction = transaction
                .copyBuilder()
                .signedTransactionBytes(SignedTransaction.PROTOBUF.toBytes(newSignedTransaction))
                .build();
        // update transaction record consensus timestamp
        final TransactionRecord newTransactionRecord = singleTransactionRecord
                .transactionRecord()
                .copyBuilder()
                .consensusTimestamp(consensusTimestamp)
                .build();
        // generate random number 0-5 of sidecar items
        final ArrayList<TransactionSidecarRecord> sidecarItems = new ArrayList<>();
        if (generateSideCarItems) {
            for (int j = 0; j < RANDOM.nextInt(2); j++) {
                final TransactionSidecarRecord exampleSideCar;
                // use less of the last 50% of items as they are larger
                if (RANDOM.nextDouble() > 0.8) {
                    exampleSideCar = exampleSidecarItems.get(
                            RANDOM.nextInt(exampleSidecarItems.size() / 2, exampleSidecarItems.size()));
                } else {
                    exampleSideCar = exampleSidecarItems.get(RANDOM.nextInt(0, exampleSidecarItems.size() / 2));
                }
                sidecarItems.add(exampleSideCar
                        .copyBuilder()
                        .consensusTimestamp(consensusTimestamp)
                        .build());
            }
        }
        // return new SingleTransactionRecord
        return new SingleTransactionRecord(newTransaction, newTransactionRecord, sidecarItems, SIMPLE_OUTPUT);
    }
}
