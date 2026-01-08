// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class JoshTest {
    @Test
    void readStream() throws IOException {
        final String record_path = "../../temp/2025-09-10T03_02_14.342128000Z.rcd";
        final String sidecar_path = "sidecar";
        final var records = StreamFileAccess.STREAM_FILE_ACCESS.readStreamDataFrom(record_path, sidecar_path);
        System.out.println("records ");
        records.records().stream()
                // pull out the record stream items
                .flatMap(recordWithSidecars -> recordWithSidecars.recordFile().getRecordStreamItemsList().stream())
                .forEach(item -> {
                    System.out.println("record " + item);
                    System.out.println("OUT: memo = " + item.getRecord().getMemo());
                    System.out.println(
                            "OUT: transaction fee " + item.getRecord().getTransactionFee());
                    System.out.println(
                            "OUT: transaction " + item.getTransaction().getSignedTransactionBytes());
                    final var receipt = item.getRecord().getReceipt();
                    System.out.println("OUT: status " + receipt.getStatus());
                    System.out.println("OUT: exchange rate " + receipt.getExchangeRate());
                    try {
                        final var signedTxn = SignedTransaction.parseFrom(
                                item.getTransaction().getSignedTransactionBytes());
                        System.out.println("the real transaction is" + signedTxn);
                        var transactionBody = TransactionBody.parseFrom(signedTxn.getBodyBytes());
                        System.out.println("TXN:transaction body is " + transactionBody);
                        System.out.println("TXN: memo " + transactionBody.getMemo());
                        System.out.println("TXN: fee " + transactionBody.getTransactionFee());
                        System.out.println("TXN: id " + transactionBody.getTransactionID());
                        System.out.println("TXN: data case " + transactionBody.getDataCase());

                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}
