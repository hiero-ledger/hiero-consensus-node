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
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
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
}
