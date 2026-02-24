// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.workflows.standalone.TransactionExecutors;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.stream.proto.RecordStreamFile;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.swirlds.state.State;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/*
to use this first download historical data.

This example downloads a one-hour slice on November 10th 2025 from 8:00:00 to 8:59:59.

gsutil -u hedera-regression cp "gs://hedera-preview-testnet-streams/recordstreams/record0.0.9/2025-11-10T08*" .
try again with -m

this will write the csv output file to hedera-app/simple-fees-historical-comparison.csv


 */
public class SimpleFeesRecordStreamTest {

    private static CSVWriter csv;
    private static JSONFormatter json;

    /**
     * Initialize the test class with simple fees enabled.
     * This ensures the SimpleFeeCalculator is initialized at startup,
     * which is required for switching between simple and legacy fees mid-test.
     */
    @BeforeAll
    static void beforeAll() throws IOException {
        csv = new CSVWriter(new FileWriter("../../reports/simple-fees-historical-comparison.csv"));
        csv.write(
                "Service Name, Simple Fee, Old Fees, Comparison, SF Service, SF Node, SF Network, Timestamp, Details, rate cents, rate hbar");
        csv.endLine();
        json = new JSONFormatter(new FileWriter("../../reports/simple-fees-historical-comparison.json"));
    }

    @AfterAll
    static void afterAll() throws IOException {
        if (csv != null) {
            csv.close();
        }
        if (json != null) {
            json.close();
        }
    }

    @Test
    void streamingSimpleFees() throws IOException {
        // set the overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");

        final State state = FakeGenesisState.make(overrides);

        final var properties = TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();
        final StandaloneFeeCalculator calc =
                new StandaloneFeeCalculatorImpl(state, properties, new AppEntityIdFactory(DEFAULT_CONFIG));

        final String records_dir = "../../temp/2025-11-10-9";

        try (Stream<Path> paths = Files.list(Path.of(records_dir))) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                if (!file.toString().endsWith("rcd.gz")) {
                    return;
                }
                try (final var fin = new GZIPInputStream(new FileInputStream(file.toFile()))) {
                    // we have to read the first 4 bytes
                    final var recordFileVersion =
                            ByteBuffer.wrap(fin.readNBytes(4)).getInt();
                    final var recordStreamFile = RecordStreamFile.parseFrom(fin);
                    recordStreamFile.getRecordStreamItemsList().stream().forEach(item -> {
                        try {
                            process_item(item, calc);
                        } catch (Exception e) {
                            System.out.println("exception " + e);
                        }
                    });
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void process_item(RecordStreamItem item, StandaloneFeeCalculator calc) throws ParseException, IOException {
        final var signedTxnBytes = item.getTransaction().getSignedTransactionBytes();
        final var signedTxn = SignedTransaction.parseFrom(signedTxnBytes);
        final var body = TransactionBody.PROTOBUF.parse(
                Bytes.wrap(signedTxn.getBodyBytes().toByteArray()));
        final Transaction txn = Transaction.newBuilder().body(body).build();
        if (txn.body().data().kind() == TransactionBody.DataOneOfType.UNSET) {
            // skip unset types
            return;
        }
        final var result = calc.calculateIntrinsic(txn);
        final var record = item.getRecord();
        final var txnFee = record.getTransactionFee();
        final var rate = record.getReceipt().getExchangeRate();

        final var simpleFee = result.totalTinycents();

        long legacyFee = 0;
        if (rate.getCurrentRate().getHbarEquiv() != 0) {
            legacyFee = txnFee
                    * rate.getCurrentRate().getCentEquiv()
                    / rate.getCurrentRate().getHbarEquiv();
        }

        long diff = simpleFee - legacyFee;
        double pctChange = legacyFee > 0 ? (diff * 100.0 / legacyFee) : 0;

        json.startRecord();
        csv.field(body.data().kind().name());
        json.key("name", body.data().kind().name());
        csv.field(simpleFee);
        json.key("simple_tc", simpleFee);
        csv.field(legacyFee);
        json.key("old_tc", legacyFee);
        //        csv.field(txnFee);
        //        json.key("txn_fee", txnFee);
        csv.fieldPercentage(pctChange);
        json.key("diff", pctChange);
        csv.field(result.getServiceTotalTinycents());
        json.key("simple_service", result.getServiceBaseFeeTinycents());
        csv.field(result.getNodeTotalTinycents());
        json.key("simple_node", result.getNodeTotalTinycents());
        csv.field(result.getNetworkTotalTinycents());
        json.key("network_total", result.getNetworkTotalTinycents());
        csv.field(record.getConsensusTimestamp().getSeconds());
        json.key("timestamp", record.getConsensusTimestamp().getSeconds());
        csv.field(result.toString());
        json.key("details", result.toString());
        csv.field(rate.getCurrentRate().getCentEquiv());
        json.key("rate_cents", rate.getCurrentRate().getCentEquiv());
        csv.field(rate.getCurrentRate().getHbarEquiv());
        json.key("rate_hbar", rate.getCurrentRate().getHbarEquiv());
        json.key("signed_txn_size",signedTxnBytes.size());
        csv.endLine();
        json.endRecord();
    }

    void processStateEvents(String records_dir, String report_file_path) throws IOException {
        final JSONFormatter json = new JSONFormatter(
                new FileWriter(report_file_path));
        System.out.println("records dir is" + records_dir);
        try (Stream<Path> paths = Files.list(Path.of(records_dir))) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                if (!file.toString().endsWith("rcd.gz")) {
                    return;
                }
//                System.out.println("parsing " + file);
                try (final var fin = new GZIPInputStream(new FileInputStream(file.toFile()))) {
                    // we have to read the first 4 bytes
                    final var recordFileVersion =
                            ByteBuffer.wrap(fin.readNBytes(4)).getInt();
                    final var recordStreamFile = RecordStreamFile.parseFrom(fin);
                    recordStreamFile.getRecordStreamItemsList().stream().forEach(item -> {
                        try {
                            final var signedTxnBytes = item.getTransaction().getSignedTransactionBytes();
                            final var signedTxn = SignedTransaction.parseFrom(signedTxnBytes);
                            final var body = TransactionBody.PROTOBUF.parse(
                                    Bytes.wrap(signedTxn.getBodyBytes().toByteArray()));
                            final Transaction txn = Transaction.newBuilder().body(body).build();
                            final var record = item.getRecord();
                            final var txnFee = record.getTransactionFee();
                            final var rate = record.getReceipt().getExchangeRate();
                            json.startRecord();
                            json.key("name", body.data().kind().name());
                            json.key("account", record.getReceipt().getAccountID().getAccountNum());
                            json.key("seconds", record.getConsensusTimestamp().getSeconds());
                            json.key("nanos", record.getConsensusTimestamp().getNanos());
                            long legacyFee = 0;
                            if (rate.getCurrentRate().getHbarEquiv() != 0) {
                                legacyFee = txnFee
                                        * rate.getCurrentRate().getCentEquiv()
                                        / rate.getCurrentRate().getHbarEquiv();
                            }
                            json.key("fee_hbar",txnFee);
                            json.key("fee_tc", legacyFee);
                            json.key("rate_cents", rate.getCurrentRate().getCentEquiv());
                            json.key("rate_hbar", rate.getCurrentRate().getHbarEquiv());
                            json.key("status",record.getReceipt().getStatus().name());
                            json.key("signed_txn_size",signedTxnBytes.size());
                            json.key("memo",body.memo());
                            //{ "name":"CRYPTO_TRANSFER", "account" : 10231006 , "seconds" : 1770230700 , "nanos" : 779324416 ,
                            // "nonce" : 0 , "fee" : 112235 , "status":"SUCCESS", "signedTxnBytes" : 183 , "custom_fees_count" : 0 , "memo":""}
                            json.endRecord();
//                            System.out.println("item is " + txn.body().data().kind().name());
                        } catch (Exception e) {
                            System.out.println("exception " + e);
                        }
                    });
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        System.out.println("wrote report to " + report_file_path);
        json.close();
    }

    @Test
    void convertStateEventsToJSON() throws IOException {
        processStateEvents("../../hedera-node/data/mainnet_legacy/recordStreams/record0.0.3","../../reports/replay-mainnet-legacy.json");
        processStateEvents("../../hedera-node/data/mainnet_sf/recordStreams/record0.0.3","../../reports/replay-mainnet-simple.json");
    }


}
