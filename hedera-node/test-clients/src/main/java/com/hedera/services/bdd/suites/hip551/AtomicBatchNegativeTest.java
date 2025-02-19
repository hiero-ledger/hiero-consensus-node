/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class AtomicBatchNegativeTest {

    @Nested
    @DisplayName("Order and Execution - NEGATIVE")
    class OrderAndExecutionNegative {

        @HapiTest
        @DisplayName("Batch containing schedule sign and failing inner transaction")
        // BATCH_56
        public Stream<DynamicTest> scheduleSignAndFailingInnerTxn() {
            final var batchOperator = "batchOperator";
            final var sender = "sender";
            final var receiver = "receiver";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(FIVE_HBARS),
                    cryptoCreate(sender).balance(ONE_HBAR),
                    cryptoCreate(receiver).balance(0L),

                    // create a schedule
                    scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                            .waitForExpiry(false),
                    atomicBatch(
                                    // sign the schedule
                                    scheduleSign("schedule").payingWith(sender).batchKey(batchOperator),
                                    // failing transfer
                                    cryptoTransfer(tinyBarsFromTo(sender, receiver, ONE_HUNDRED_HBARS))
                                            .batchKey(batchOperator))
                            .payingWith(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // validate executed schedule was reverted
                    getScheduleInfo("schedule").isNotExecuted(),
                    getAccountBalance(receiver).hasTinyBars(0L));
        }

        @HapiTest
        @DisplayName("Batch transactions reverts on failure")
        // BATCH_57
        public Stream<DynamicTest> batchTransactionsRevertsOnFailure() {
            final var sender = "sender";
            final var oldKey = "oldKey";
            final var newKey = "newKey";
            return hapiTest(
                    newKeyNamed(oldKey),
                    cryptoCreate(sender).key(oldKey).balance(FIVE_HBARS),
                    newKeyNamed(newKey),
                    atomicBatch(
                                    cryptoUpdate(sender).key(newKey).batchKey(sender),
                                    cryptoDelete(sender).batchKey(sender),
                                    cryptoTransfer(tinyBarsFromTo(GENESIS, sender, 1))
                                            .batchKey(sender))
                            .payingWith(sender)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // validate the account update and delete were reverted
                    withOpContext((spec, opLog) -> {
                        final var expectedKey = spec.registry().getKey(oldKey);
                        final var accountQuery = getAccountDetails(sender)
                                .logged()
                                .has(accountDetailsWith().key(expectedKey));
                        allRunFor(spec, accountQuery);
                    }));
        }
    }

    @Nested
    @DisplayName("Fees - NEGATIVE")
    class FeesNegative {

        @HapiTest
        @DisplayName("Batch containing failing transfer still charges inner txn payer")
        // BATCH_64
        public Stream<DynamicTest> failingBatchStillChargesFees() {
            return hapiTest(
                    // create accounts and tokens
                    cryptoCreate("Alice").balance(ONE_HBAR),
                    cryptoCreate("Bob").balance(ONE_HBAR),
                    cryptoCreate("receiver"),
                    cryptoCreate("collector"),
                    cryptoCreate("treasury"),
                    tokenCreate("ftC").treasury("treasury"),
                    tokenCreate("ftB").treasury("treasury"),
                    tokenAssociate("collector", "ftB"),
                    tokenCreate("ftA")
                            .withCustom(fixedHtsFee(1, "ftB", "collector"))
                            .treasury("treasury"),
                    tokenAssociate("Bob", "ftA", "ftB", "ftC"),
                    tokenAssociate("receiver", "ftA", "ftB"),
                    cryptoTransfer(TokenMovement.moving(1, "ftA").between("treasury", "Bob")),
                    cryptoTransfer(TokenMovement.moving(1, "ftB").between("treasury", "Bob")),
                    cryptoTransfer(TokenMovement.moving(1, "ftC").between("treasury", "Bob")),
                    // batch txn
                    atomicBatch(
                                    cryptoTransfer(TokenMovement.moving(1, "ftA")
                                                    .between("Bob", "receiver"))
                                            .batchKey("Alice")
                                            .payingWith("Bob")
                                            .signedBy("Bob"),
                                    // will fail because receiver is not associated with ftC
                                    cryptoTransfer(TokenMovement.moving(1, "ftC")
                                                    .between("Bob", "receiver"))
                                            .batchKey("Alice")
                                            .payingWith("Bob")
                                            .signedBy("Bob"))
                            .payingWith("Alice")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED)
                            .via("batchTxn"),
                    // asserts
                    getAccountRecords("Bob").exposingTo(records -> assertEquals(2, records.size())),
                    getAccountRecords("Alice").exposingTo(records -> assertEquals(1, records.size())),
                    getAccountBalance("collector").hasTokenBalance("ftB", 0),
                    getAccountBalance("receiver").hasTokenBalance("ftA", 0),
                    getAccountBalance("receiver").hasTokenBalance("ftC", 0));
        }

        @HapiTest
        @DisplayName("Batch containing expired transaction charges on rollback")
        // BATCH_66
        public Stream<DynamicTest> failingWithExpiryStillChargesFees() {
            return hapiTest(
                    // create accounts and tokens
                    cryptoCreate("Alice").balance(ONE_HBAR),
                    // batch txn
                    atomicBatch(
                                    tokenCreate("ftA").batchKey("Alice").payingWith("Alice"),
                                    tokenCreate("ftB")
                                            .withTxnTransform(txn -> TxnUtils.replaceTxnDuration(txn, -1L))
                                            .batchKey("Alice")
                                            .payingWith("Alice"))
                            .payingWith("Alice")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED)
                            .via("batchTxn"),
                    // asserts
                    getAccountRecords("Alice").exposingTo(records -> assertEquals(2, records.size())));
        }

        @HapiTest
        @DisplayName("Expired batch does not charge fees")
        // BATCH_68
        public Stream<DynamicTest> failingBatchWithExpiryDoesNotChargeFees() {
            return hapiTest(
                    // create accounts and tokens
                    cryptoCreate("Alice").balance(ONE_HBAR),
                    cryptoCreate("Bob").balance(ONE_HBAR),
                    // batch txn
                    atomicBatch(
                                    tokenCreate("ftA").batchKey("Alice").payingWith("Bob"),
                                    tokenCreate("ftB").batchKey("Alice").payingWith("Bob"))
                            .payingWith("Alice")
                            .withTxnTransform(txn -> TxnUtils.replaceTxnDuration(txn, -1L))
                            .hasPrecheck(INVALID_TRANSACTION_DURATION)
                            .via("batchTxn"),
                    // asserts
                    getAccountBalance("Alice").hasTinyBars(ONE_HBAR),
                    getAccountBalance("Bob").hasTinyBars(ONE_HBAR));
        }
    }
}
