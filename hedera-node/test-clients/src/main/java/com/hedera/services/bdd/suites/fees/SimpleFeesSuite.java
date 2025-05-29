package com.hedera.services.bdd.suites.fees;

import com.hedera.services.bdd.junit.HapiTest;
import org.junit.jupiter.api.DynamicTest;

import java.util.Arrays;
import java.util.stream.Stream;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.HCS_FREE_BYTES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.*;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

public class SimpleFeesSuite {
    private static final String PAYER = "payer";

    // create topic, basic
    @HapiTest
    final Stream<DynamicTest> createTopicFee() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic").blankMemo().payingWith(PAYER).via("create-topic-txn"),
                validateChargedUsd("create-topic-txn", 0.01)
        );
    }

    // create topic with a custom fee
    // update topic
    @HapiTest
    final Stream<DynamicTest> updateTopicFee() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic").blankMemo().payingWith(PAYER).adminKeyName(PAYER).via("create-topic-txn"),
                updateTopic("testTopic").adminKey(PAYER).payingWith(PAYER).via("update-topic-txn"),
                validateChargedUsd("create-topic-txn", 0.01),
                validateChargedUsd("update-topic-txn", 0.000_22)
        );
    }
    //TODO: get topic info
    // submit message
    @HapiTest
    final Stream<DynamicTest> submitMessageFee() {
        final byte[] messageBytes = new byte[600]; // up to 1k
        Arrays.fill(messageBytes, (byte) 0b1);
        final var free_bytes = HCS_FREE_BYTES;// 256;
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic").blankMemo().payingWith(PAYER).adminKeyName(PAYER).via("create-topic-txn"),
                submitMessageTo("testTopic").payingWith(PAYER).message(messageBytes).via("submit-message-txn"),

                validateChargedUsd("create-topic-txn", 0.010_00),
                validateChargedUsd("submit-message-txn", 0.000_10 + Math.max((messageBytes.length - free_bytes),0) * 0.000_011,1)
        );
    }
    // submit bigger message
    // with custom fees


    // delete topic
    @HapiTest
    final Stream<DynamicTest> deleteTopicFee() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic").blankMemo().payingWith(PAYER).adminKeyName(PAYER).via("create-topic-txn"),
                deleteTopic("testTopic").payingWith(PAYER).via("delete-topic-txn"),
                validateChargedUsd("create-topic-txn", 0.010_00),
                validateChargedUsd("delete-topic-txn", 0.005_00)
        );
    }

    // TODO: CryptoCreate, create token
    // TODO: CryptoCreate, create token with custom fees
    // TODO: CryptoDelete, delete token
    // TODO: CryptoTransfer, transfer value in a FT
    // TODO: CryptoGetAccountRecords: ??
    // TODO: CryptoGetAccountBalance: ??
    // TODO: CryptoGetInfo: ??
    // TODO: CryptoApproveAllowance: approve single and multiple allowances
    // TODO: CryptoDeleteAllowance: approvate

    // TODO: Token services
    // TODO: scheduled gransactions
    // TODO: smart contracts
    // TODO: File service
    // TODO: random other stuff
}
