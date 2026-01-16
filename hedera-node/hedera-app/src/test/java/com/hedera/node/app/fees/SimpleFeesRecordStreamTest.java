// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.impl.AppEntityIdFactory;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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

    private static CSVWriter csv;

    /**
     * Initialize the test class with simple fees enabled.
     * This ensures the SimpleFeeCalculator is initialized at startup,
     * which is required for switching between simple and legacy fees mid-test.
     */
    @BeforeAll
    static void beforeAll() throws IOException {
        csv = new CSVWriter(new FileWriter("simple-fees-historical-comparison.csv"));
        csv.write("Service Name, Simple Fee, Old Fees, Comparison, Details");
        csv.endLine();
    }

    @AfterAll
    static void afterAll() throws IOException {
        if (csv != null) {
            csv.close();
        }
    }

    @Test
    void streamingSimpleFees() throws IOException {
        // set the overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");

        final State state = FakeGenesisState.make(overrides);

        // config props
        final var properties = TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();
        final StandaloneFeeCalculator calc =
                new StandaloneFeeCalculatorImpl(state, properties, new AppEntityIdFactory(DEFAULT_CONFIG));

        final String records_dir = "../../temp/";

        try (Stream<Path> paths = Files.list(Path.of(records_dir))) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                //                System.out.println("reading file " + file);
                if (!file.toString().endsWith("rcd")) {
//                    System.out.println("skipping");
                    return;
                }
                try (final var fin = new FileInputStream(file.toFile())) {
                    final var recordFileVersion = ByteBuffer.wrap(fin.readNBytes(4)).getInt();
                    final var recordStreamFile = RecordStreamFile.parseFrom(fin);
                    recordStreamFile.getRecordStreamItemsList().stream().forEach(item -> {
                        try {
                            process_item(item, calc);
                        } catch (Exception e) {
                            System.out.println("exception " + e);
                            e.printStackTrace();
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
        final Transaction txn =
                Transaction.newBuilder().body(body).build();
        if (shouldSkip(body.data().kind())) {
            return;
        }
        final var result = calc.calculate(txn, ServiceFeeCalculator.EstimationMode.INTRINSIC);
        final var record = item.getRecord();
        final var txnFee = record.getTransactionFee();
        final var rate = record.getReceipt().getExchangeRate();
        var fract = ((double) result.totalTC()) / (double) (txnFee * rate.getCurrentRate().getCentEquiv());
        if (Math.abs(1 - fract) > 0.05) {
            System.out.println("TXN:" + body.data().kind());
            csv.field(body.data().kind().name());
            csv.field(result.totalTC());
            csv.field(txnFee*rate.getCurrentRate().getCentEquiv());
            csv.fieldPercentage(fract * 100);
            csv.field(result.toString());
            csv.endLine();
        }
    }

    private boolean shouldSkip(TransactionBody.DataOneOfType kind) {
        // requires readable store
        if (kind == TransactionBody.DataOneOfType.CONSENSUS_SUBMIT_MESSAGE) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.CRYPTO_TRANSFER) {
            return true;
        }

        // fee calculator not implemented yet
        // coming in PR: https://github.com/hiero-ledger/hiero-consensus-node/pull/22584
        if (kind == TransactionBody.DataOneOfType.TOKEN_AIRDROP) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_ASSOCIATE) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_DISSOCIATE) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_UPDATE) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_UPDATE_NFTS) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_WIPE) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_REJECT) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_GRANT_KYC) {
            return true;
        }
        if (kind == TransactionBody.DataOneOfType.TOKEN_FEE_SCHEDULE_UPDATE) {
            return true;
        }

        return false;
    }
}
