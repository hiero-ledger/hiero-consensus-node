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
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/*
to use this first download historical data.

This example downloads a one-hour slice on November 10th 2025 from 8:00:00 to 8:59:59.

gsutil -u hedera-regression cp "gs://hedera-preview-testnet-streams/recordstreams/record0.0.9/2025-11-10T08*" .
try again with -m

this will write the csv output file to hedera-app/simple-fees-historical-comparison.csv


 */
public class SimpleFeesRecordStreamTest {
    private static class CSVWriter {

        private final Writer writer;
        private int fieldCount;

        public CSVWriter(Writer fwriter) {
            this.writer = fwriter;
            this.fieldCount = 0;
        }

        private static String escapeCsv(String value) {
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                value = value.replace("\"", "\"\"");
                return "\"" + value + "\"";
            }
            return value;
        }

        public void write(String s) throws IOException {
            this.writer.write(s);
        }

        public void field(String value) throws IOException {
            if (this.fieldCount > 0) {
                this.write(",");
            }
            this.write(escapeCsv(value));
            this.fieldCount += 1;
        }

        public void endLine() throws IOException {
            this.write("\n");
            this.fieldCount = 0;
        }

        public void field(int i) throws IOException {
            this.field(i + "");
        }

        public void field(long fee) throws IOException {
            this.field(fee + "");
        }

        public void fieldPercentage(double diff) throws IOException {
            this.field(String.format("%9.2f%%", diff));
        }

        public void close() throws IOException {
            this.writer.flush();
            this.writer.close();
        }
    }

    private static class JSONFormatter {

        private final FileWriter writer;
        private boolean start;

        public JSONFormatter(FileWriter writer) {
            this.writer = writer;
            this.start = false;
        }

        public void startRecord() throws IOException {
            writer.write("{ ");
            this.start = true;
        }

        public void key(String name, String value) throws IOException {
            if (!this.start) {
                writer.append(", ");
            }
            writer.append(String.format("\"%s\":\"%s\"", name, value));
            this.start = false;
        }

        public void endRecord() throws IOException {
            writer.write("}\n");
        }

        public void key(String name, long value) throws IOException {
            if (!this.start) {
                writer.append(", ");
            }
            writer.append(String.format("\"%s\" : %s ", name, "" + value));
        }

        public void key(String name, double value) throws IOException {
            if (!this.start) {
                writer.append(", ");
            }
            writer.append(String.format("\"%s\" : %.5f", name, value));
        }

        public void close() throws IOException {
            this.writer.flush();
            this.writer.close();
        }
    }


    static void process_dir(String records_dir) throws IOException {
        CSVWriter csv = new CSVWriter(new FileWriter(records_dir+".csv"));
        JSONFormatter json = new JSONFormatter(new FileWriter(records_dir+".json"));
        csv.write(
                "Service Name, AccountID, Txn Seconds, Txn Nanos, Txn Nonce, Transaction Fee, Status, Signed Txn Bytes, Custom Fees Count, Memo");
        csv.endLine();
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
                            final var signedTxnBytes = item.getTransaction().getSignedTransactionBytes();
                            final var signedTxn = SignedTransaction.parseFrom(signedTxnBytes);
                            final var body = TransactionBody.PROTOBUF.parse(
                                    Bytes.wrap(signedTxn.getBodyBytes().toByteArray()));
                            final Transaction txn = Transaction.newBuilder().body(body).build();
                            if (txn.body().data().kind() == TransactionBody.DataOneOfType.UNSET) {
                                System.out.println("skipping unset");
                                // skip unset types
                                return;
                            }
                            final var record = item.getRecord();
                            final var txnFee = record.getTransactionFee();
                            final var rate = record.getReceipt().getExchangeRate();
                            json.startRecord();
                            csv.field(body.data().kind().name());
                            json.key("name", body.data().kind().name());
                            var txnId = body.transactionID();
                            csv.field(txnId.accountID().accountNum());
                            json.key("account",txnId.accountID().accountNum());
                            csv.field(txnId.transactionValidStart().seconds());
                            json.key("seconds",txnId.transactionValidStart().seconds());
                            csv.field(txnId.transactionValidStart().nanos());
                            json.key("nanos",txnId.transactionValidStart().nanos());
                            csv.field(txnId.nonce());
                            json.key("nonce",txnId.nonce());
                            csv.field(txnFee);
                            json.key("fee",txnFee);
                            csv.field(item.getRecord().getReceipt().getStatus().name());
                            json.key("status",item.getRecord().getReceipt().getStatus().name());
                            csv.field(signedTxnBytes.size());
                            json.key("signedTxnBytes",signedTxnBytes.size());
                            csv.field(item.getRecord().getAssessedCustomFeesCount());
                            json.key("custom_fees_count",item.getRecord().getAssessedCustomFeesCount());
                            csv.field(item.getRecord().getMemo());
                            json.key("memo",item.getRecord().getMemo());
                            csv.endLine();
                            json.endRecord();
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
        csv.close();
        json.close();
    }
    @Test
    void recordsToCSV() throws IOException {
        process_dir("../../sftest/legacy_fees_record");
        process_dir("../../sftest/simple_fees_record");
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

        CSVWriter csv = new CSVWriter(new FileWriter("simple-fees-historical-comparison.csv"));
        csv.write(
                "Service Name, Simple Fee, Old Fees, Comparison, SF Service, SF Node, SF Network, Timestamp, Details, rate cents, rate hbar");
        csv.endLine();
        JSONFormatter json = new JSONFormatter(new FileWriter("simple-fees-historical-comparison.json"));

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
                            process_item(item, calc, csv, json);
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
        if (csv != null) {
            csv.close();
        }
        if (json != null) {
            json.close();
        }

    }

    private void process_item(RecordStreamItem item, StandaloneFeeCalculator calc, CSVWriter csv, JSONFormatter json) throws ParseException, IOException {
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
        csv.endLine();
        json.endRecord();
    }
}
