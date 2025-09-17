// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateStreams;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;

@OrderedInIsolation
public class AtomicBatchTest {

    @Order(1)
    @HapiTest
    public Stream<DynamicTest> validateFeesForChildren() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        final double BASE_FEE_HBAR_CRYPTO_TRANSFER = 0.0001;
        final double BASE_FEE_SUBMIT_MESSAGE_CUSTOM_FEE = 0.05;

        final var innerTxn1 = cryptoTransfer(tinyBarsFromTo("alice", "bob", ONE_HBAR))
                .payingWith("alice")
                .via("innerTxn")
                .blankMemo()
                .batchKey("batchOperator");
        final var innerTxn2 = submitMessageTo("topic")
                .message("TEST")
                .payingWith("bob")
                .via("innerTxn2")
                .blankMemo()
                .batchKey("batchOperator");
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("submitKey"),
                newKeyNamed("feeScheduleKey"),
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("alice").balance(2 * ONE_HBAR),
                cryptoCreate("bob").balance(4 * ONE_HBAR),
                cryptoCreate("collector").balance(0L),
                createTopic("topic")
                        .adminKeyName("adminKey")
                        .feeScheduleKeyName("feeScheduleKey")
                        .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, "collector")),
                atomicBatch(innerTxn1, innerTxn2).payingWith("batchOperator").via("batchTxn"),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),
                validateInnerTxnChargedUsd("innerTxn", "batchTxn", BASE_FEE_HBAR_CRYPTO_TRANSFER, 5),
                validateInnerTxnChargedUsd("innerTxn2", "batchTxn", BASE_FEE_SUBMIT_MESSAGE_CUSTOM_FEE, 5));
    }

    @Order(1000)
    @LeakyHapiTest
    final Stream<DynamicTest> streamsAreValid() {
        return hapiTest(validateStreams());
    }
}
