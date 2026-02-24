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
    /*

good:
ROUND:                               238858155
HASH:                                4c505baf460d63545859f57a362cc1e8f4613383283265a593df23a4fbca27721436ae624cb07c639b43abe8e8256234
HASH_MNEMONIC:                       meat-invest-script-hedgehog
NUMBER_OF_CONSENSUS_EVENTS:          98945886808
CONSENSUS_TIMESTAMP:                 2026-02-20T19:45:00.384747Z
LEGACY_RUNNING_EVENT_HASH:           2aaa3daf94df20ebb010ef1ea4f1e3c24df99d2b19d65b23f6ba068ddfaa21044fec0e5b0d6d295776fde69968f5ba94
LEGACY_RUNNING_EVENT_HASH_MNEMONIC:  fetch-remove-goddess-buddy
MINIMUM_BIRTH_ROUND_NON_ANCIENT:     238858127
SOFTWARE_VERSION:                    SemanticVersion[major=0, minor=70, patch=0, pre=, build=0]
WALL_CLOCK_TIME:                     2026-02-20T19:45:07.642150244Z
NODE_ID:                             1
SIGNING_NODES:                       1, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 24, 25, 26, 27, 28, 29, 31, 32, 33, 34, 35
SIGNING_WEIGHT_SUM:                  965428892200000000
TOTAL_WEIGHT:                        1390468431700000000
FREEZE_STATE:                        false

const TIMESTAMP_15   = '2026-02-19T16_15'

using state from
bad
ROUND:                               238744171
HASH:                                e33367cf9997e8b395a0ca21a55c16db14b633767e4497fe6c4dc7c0aa5b02bc769de0b4c886af856e75bbd2f228fe26
HASH_MNEMONIC:                       often-wheat-snake-trust
NUMBER_OF_CONSENSUS_EVENTS:          98898305208
CONSENSUS_TIMESTAMP:                 2026-02-19T16:00:02.172989040Z
LEGACY_RUNNING_EVENT_HASH:           838e4ff9af0631717a60441779734acbf0754094badf2a8623d880522eb335c8e9c92d41f23b74f2abf698262f99779e
LEGACY_RUNNING_EVENT_HASH_MNEMONIC:  mixture-you-butter-comfort
MINIMUM_BIRTH_ROUND_NON_ANCIENT:     238744144
SOFTWARE_VERSION:                    SemanticVersion[major=0, minor=70, patch=0, pre=, build=0]
WALL_CLOCK_TIME:                     2026-02-19T16:00:09.834184010Z
NODE_ID:                             1
SIGNING_NODES:                       1, 3, 5, 10, 11, 12, 14, 15, 18, 19, 22, 24, 25, 26, 27, 28, 29, 31, 32, 33, 34, 35
SIGNING_WEIGHT_SUM:                  950253670800000000
TOTAL_WEIGHT:                        1390468431700000000
FREEZE_STATE:                        false
mainnet/latest-round/stateMetadata.txt (END)
     */

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
}
