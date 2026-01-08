// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees;

import static com.hedera.node.app.fees.SimpleFeesMirrorNodeAnotherTest.makeMirrorNodeCalculator;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.ServiceFeeCalculator;
import com.hedera.node.app.workflows.standalone.TransactionExecutors;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.stream.proto.RecordStreamFile;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.swirlds.state.State;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SimpleFeesMirrorNodeTest {

    @Test
    void basicStreaming() throws IOException {
        final String record_path = "../../temp/2025-09-10T03_02_14.342128000Z.rcd";
        //        final String sidecar_path = "sidecar";
        //        byte[] bytes = Files.readAllBytes(Path.of(record_path));

        try (final var fin = new FileInputStream(record_path)) {
            final var recordFileVersion = ByteBuffer.wrap(fin.readNBytes(4)).getInt();
            final var recordStreamFile = com.hedera.services.stream.proto.RecordStreamFile.parseFrom(fin);
            System.out.println("File version is " + recordFileVersion);
            System.out.println("stream file is " + recordStreamFile);
            //            recordStreamFile.getRecordStreamItemsList().stream().flatMap()
            recordStreamFile.getRecordStreamItemsList().stream()
                    //                    .flatMap(recordStreamItem -> recordStreamItem.getRecord())
                    // pull out the record stream items
                    //                    .flatMap(recordWithSidecars ->
                    // recordWithSidecars.recordFile().getRecordStreamItemsList().stream())
                    .forEach(item -> {
                        System.out.println("record " + item);
                        process_record(item);
                    });
        }
    }

    private void process_record(RecordStreamItem item) {
        System.out.println("record " + item);
        System.out.println("OUT: memo = " + item.getRecord().getMemo());
        System.out.println("OUT: transaction fee " + item.getRecord().getTransactionFee());
        System.out.println("OUT: transaction " + item.getTransaction().getSignedTransactionBytes());
        final var receipt = item.getRecord().getReceipt();
        System.out.println("OUT: status " + receipt.getStatus());
        System.out.println("OUT: exchange rate " + receipt.getExchangeRate());
        try {
            final var signedTxn =
                    SignedTransaction.parseFrom(item.getTransaction().getSignedTransactionBytes());
            System.out.println("the real transaction is" + signedTxn);
            com.hederahashgraph.api.proto.java.TransactionBody transactionBody =
                    com.hederahashgraph.api.proto.java.TransactionBody.parseFrom(signedTxn.getBodyBytes());
            System.out.println("TXN:transaction body is " + transactionBody);
            System.out.println("TXN: memo " + transactionBody.getMemo());
            System.out.println("TXN: fee " + transactionBody.getTransactionFee());
            System.out.println("TXN: id " + transactionBody.getTransactionID());
            System.out.println("TXN: data case " + transactionBody.getDataCase());

        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
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
        final SimpleFeesMirrorNodeAnotherTest.FeeCalculator calc = makeMirrorNodeCalculator(state, properties);


        final String records_dir = "../../temp/";

        try (Stream<Path> paths = Files.list(Path.of(records_dir))) {
            paths.filter(Files::isRegularFile).forEach(file -> {
//                System.out.println("reading file " + file);
                if (!file.toString().endsWith("rcd")) {
                    System.out.println("skipping");
                    return;
                }
                try (final var fin = new FileInputStream(file.toFile())) {
                    final var recordFileVersion =
                            ByteBuffer.wrap(fin.readNBytes(4)).getInt();
//                    System.out.println("read version " + recordFileVersion);
                    final var recordStreamFile = RecordStreamFile.parseFrom(fin);
                    recordStreamFile.getRecordStreamItemsList().stream().forEach(item -> {
                        try {
//                            final var txn = item.getTransaction();
                            final var signedTxnBytes = item.getTransaction().getSignedTransactionBytes();
                            final var signedTxn = SignedTransaction.parseFrom(signedTxnBytes);
//                            final Transaction txn = Transaction.newBuilder().signedTransactionBytes(signedTxn.getBodyBytes())
                            final var body = TransactionBody.PROTOBUF.parse(
                                    Bytes.wrap(signedTxn.getBodyBytes().toByteArray()));
                            final Transaction txn = Transaction.newBuilder().body(body).build();
                            if (shouldSkip(body.data().kind())) {
                                return;
                            }
//                            System.out.println("TXN: memo " + body.memo());
//                            System.out.println("TXN: fee " + body.transactionFee());
//                            System.out.println("TXN: id " + body.transactionID());
//                            System.out.println("calculating simple fees for transaction " + body);
                            final var result = calc.calculate(txn, ServiceFeeCalculator.EstimationMode.Intrinsic);
//                            System.out.println("result is " + result);
                            // max fee in tiny bar //
//                            System.out.println("original      is : " + body.transactionFee());
                            final var record = item.getRecord();
                            final var txnFee = record.getTransactionFee();
                            // actual fee charged (in tiny bar)?
                            var fract = ((double)result.total())/(double)(txnFee*12);
                            if(Math.abs(1 - fract) > 0.05) {
                                System.out.println("TXN: data case " + body.data().kind());
                                System.out.println("simple        is : " + result.total());
                                System.out.println("record trans fee : " + (txnFee*12));
                                System.out.println("fract = " + fract);
                            }
                            // rec fee * 12 to get cents
                            // 845911
//                            System.out.println(
//                                    "status is " + record.getReceipt().getStatus());
//                            System.out.println(
//                                    "exchange rate is " + record.getReceipt().getExchangeRate());

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

    private boolean shouldSkip(TransactionBody.DataOneOfType kind) {
        // requires readable store
        if (kind == TransactionBody.DataOneOfType.CONSENSUS_SUBMIT_MESSAGE) {
            return true;
        }
//        if (kind == TransactionBody.DataOneOfType.CRYPTO_TRANSFER) {
//            return true;
//        }

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

    @Test
    void doIt() {
        // set the overrides
        final var overrides = Map.of("hedera.transaction.maxMemoUtf8Bytes", "101", "fees.simpleFeesEnabled", "true");
        final State state = FakeGenesisState.make(overrides);
        // config props
        final var properties = TransactionExecutors.Properties.newBuilder()
                .state(state)
                .appProperties(overrides)
                .build();

        // make the calculator
        final SimpleFeesMirrorNodeAnotherTest.FeeCalculator calc = makeMirrorNodeCalculator(state, properties);

        System.out.println("got the calculator " + calc);
        final var body = TransactionBody.newBuilder()
                .tokenCreation(TokenCreateTransactionBody.newBuilder()
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .build())
                .build();
        final Transaction txn = Transaction.newBuilder().body(body).build();
        final var result = calc.calculate(txn, ServiceFeeCalculator.EstimationMode.Intrinsic);
        System.out.println("result is " + result);
        assertThat(result.service).isEqualTo(9999000000L);
    }

}
