package com.hedera.node.app.fees;

import com.google.protobuf.InvalidProtocolBufferException;
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
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;

public class StandaloneFeeCalculatorCLI {
    public static void main(String[] args) throws IOException {
        try {
            System.out.println("args are " + Arrays.toString(args));
            final String report_file = "/Users/josh/WebstormProjects/kitchen-sink-analyzer/full/march08_1024/reports/standalone.json";
            final var json = new JSONFormatter(new FileWriter(report_file));


            final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");
            final State state = FakeGenesisState.make(overrides);
            final var properties = TransactionExecutors.Properties.newBuilder()
                    .state(state)
                    .appProperties(overrides)
                    .build();
            final StandaloneFeeCalculator calc =
                    new StandaloneFeeCalculatorImpl(state, properties, new AppEntityIdFactory(DEFAULT_CONFIG));

            final String records_dir = "/Users/josh/WebstormProjects/kitchen-sink-analyzer/full/march08_1024/record_streams/simple/record0.0.3";
            System.out.println("records_dir is " + records_dir);

            try (Stream<Path> paths = Files.list(Path.of(records_dir))) {
                paths.filter(Files::isRegularFile).forEach(file -> {
                    if (!file.toString().endsWith("rcd.gz")) {
                        return;
                    }
//                    System.out.println("file is " + file.toString());
                    try (final var fin = new GZIPInputStream(new FileInputStream(file.toFile()))) {
                        // we have to read the first 4 bytes
                        final var recordFileVersion =
                                ByteBuffer.wrap(fin.readNBytes(4)).getInt();
                        final var recordStreamFile = RecordStreamFile.parseFrom(fin);
                        recordStreamFile.getRecordStreamItemsList()
                                .stream()
                                .forEach(item -> {
                                    try {
                                        process_item(item, calc, json);
                                    } catch (Exception e) {
                                        System.out.println("exception " + e);
                                    }
                                });
                    } catch (Exception e) {
                        System.out.println("exception " + e);
//                    throw new RuntimeException(e);
                    }
                });
            }

            json.close();

        } catch (Exception e) {
            System.out.println("exception " + e);
        }
    }

    private static void process_item(RecordStreamItem item, StandaloneFeeCalculator calc, JSONFormatter json) throws IOException, ParseException {
        System.out.println("processing " + item.getTransaction().hashCode());
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
        System.out.println(result);
        json.startRecord();
        json.key("name", body.data().kind().name());
        final var txnId = item.getRecord().getTransactionID();
        json.key("accountNum",txnId.getAccountID().getAccountNum());
        json.key("realmNum",txnId.getAccountID().getRealmNum());
        json.key("sharedNum",txnId.getAccountID().getShardNum());
        json.key("seconds",txnId.getTransactionValidStart().getSeconds());
        json.key("nanos", txnId.getTransactionValidStart().getNanos());
        json.key("nonce", txnId.getNonce());
        json.startObject("simpleFee");
        json.key("totalFee",result.totalTinycents());
        json.key("serviceBaseFee",result.getServiceBaseFeeTinycents());
        json.key("serviceTotal",result.getServiceTotalTinycents());
        json.key("serviceExtras",result.getServiceExtraDetails().stream().<Map<String,Object>>map(d -> Map.of("name",d.name(),"perUnit",d.perUnit(),"used",d.used(),"included",d.included(),"charged",d.charged())).toList());
        json.key("nodeBaseFee",result.getNodeBaseFeeTinycents());
        json.key("nodeTotal",result.getNodeTotalTinycents());
        json.key("nodeExtras",result.getNodeExtraDetails().stream().<Map<String,Object>>map(d -> Map.of("name",d.name(),"perUnit",d.perUnit(),"used",d.used(),"included",d.included(),"charged",d.charged())).toList());
        json.key("networkMultiplier",result.getNetworkMultiplier());
        json.key("networkTotal",result.getNetworkTotalTinycents());
        json.key("highVolumeMultiplier",result.getHighVolumeMultiplier());
        json.endObject();
        json.endRecord();
    }


}
