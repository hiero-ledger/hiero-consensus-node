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
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
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
    @DisplayName("Signatures - NEGATIVE")
    class SignaturesNegative {
        @HapiTest
        @DisplayName("Batch transaction fails due to missing threshold key signatures")
        // BATCH_70
        public Stream<DynamicTest> missingThresholdKeySignaturesFails() {
            final var alice = "alice";
            final var bob = "bob";
            final var dave = "dave";
            final var thresholdKey = "thresholdKey";
            final var innerTxnId1 = "innerId1";
            final var innerTxnId2 = "innerId2";

            final KeyShape threshKeyShape = KeyShape.threshOf(2, PREDEFINED_SHAPE, PREDEFINED_SHAPE);

            final var innerTxn1 = cryptoCreate("foo1")
                    .balance(ONE_HBAR)
                    //                    .txnId(innerTxnId1)
                    .batchKey(thresholdKey)
                    .payingWith(alice);

            final var innerTxn2 = cryptoCreate("foo2")
                    .balance(ONE_HBAR)
                    //                    .txnId(innerTxnId2)
                    .batchKey(thresholdKey)
                    .payingWith(alice);

            return hapiTest(
                    cryptoCreate(alice),
                    cryptoCreate(bob),
                    cryptoCreate(dave),
                    newKeyNamed(thresholdKey).shape(threshKeyShape.signedWith(sigs(bob, dave))),
                    //                    usableTxnIdNamed(innerTxnId1).payerId(alice),
                    //                    usableTxnIdNamed(innerTxnId2).payerId(alice),
                    atomicBatch(innerTxn1, innerTxn2)
                            .payingWith(bob) // Bob submits the transaction
                            .signedBy(bob) // Missing Dave’s key, you can't sign with the threshold key
                            .hasKnownStatus(INVALID_SIGNATURE));
        }

        @HapiTest
        @DisplayName("Batch transaction fails due to mismatched batch keys across inner transactions")
        // BATCH_71
        public Stream<DynamicTest> mismatchedBatchKeysFails() { // TO-DO fxix as positive
            final var alice = "alice";
            final var bob = "bob";
            final var dave = "dave";
            final var thresholdKey = "thresholdKey";
            final var innerTxnId1 = "innerId1";
            final var innerTxnId2 = "innerId2";

            final KeyShape threshKeyShape = KeyShape.threshOf(2, PREDEFINED_SHAPE, PREDEFINED_SHAPE);

            final var innerTxn1 = cryptoCreate("foo1")
                    .balance(ONE_HBAR)
                    //                    .txnId(innerTxnId1)
                    .batchKey(thresholdKey)
                    .payingWith(alice);

            final var innerTxn2 = cryptoCreate("foo2")
                    .balance(ONE_HBAR)
                    //                    .txnId(innerTxnId2)
                    .batchKey(bob)
                    .payingWith(alice);

            return hapiTest(
                    cryptoCreate(alice), // creates account and creates key with alias "alice"
                    cryptoCreate(bob),
                    cryptoCreate(dave),
                    newKeyNamed(thresholdKey).shape(threshKeyShape.signedWith(sigs(bob, dave))),
                    //                    usableTxnIdNamed(innerTxnId1).payerId(alice),
                    //                    usableTxnIdNamed(innerTxnId2).payerId(alice),
                    atomicBatch(innerTxn1, innerTxn2)
                            .payingWith(bob) // Bob submits the transaction
                            .signedBy(bob, dave), // Bob signs with the threshold key
                    getAccountBalance("foo1").hasTinyBars(ONE_HBAR),
                    getAccountBalance("foo2").hasTinyBars(ONE_HBAR));
        }

        @HapiTest
        @DisplayName("Batch transaction fails when submitted with an incorrect signer")
        // BATCH_72
        public Stream<DynamicTest> batchWithIncorrectSignerFails() {
            final var alice = "alice";
            final var bob = "bob";
            final var batchKeyBob = "batchKeyBob";
            final var innerTxnId = "innerTxnId";

            return hapiTest(
                    //                    newKeyNamed(batchKeyBob),
                    cryptoCreate(alice),
                    cryptoCreate(bob),
                    atomicBatch(cryptoCreate("foo").txnId(innerTxnId).batchKey(bob))
                            .payingWith(alice) // Alice pays for the batch
                            .signedBy(alice)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED)); // TO-DO add error message for batchKey
        }

        @HapiTest
        @DisplayName("Batch transaction fails when one inner transaction has a different BatchKey")
        // BATCH_72 && BATCH_73
        public Stream<DynamicTest> batchWithDifferentBatchKeysFails() {
            final var alice = "alice";
            final var bob = "bob";
            final var batchKey1 = "batchKey1";
            final var batchKey2 = "batchKey2";
            final var innerTxnId1 = "innerTxnId1";
            final var innerTxnId2 = "innerTxnId2";

            return hapiTest(
                    newKeyNamed(batchKey1),
                    newKeyNamed(batchKey2),
                    cryptoCreate(alice),
                    cryptoCreate(bob),
                    usableTxnIdNamed(innerTxnId1).payerId(alice),
                    usableTxnIdNamed(innerTxnId2).payerId(alice),
                    atomicBatch(
                                    cryptoCreate("foo1").txnId(innerTxnId1).batchKey(batchKey1),
                                    cryptoCreate("foo2").txnId(innerTxnId2).batchKey(batchKey2))
                            .payingWith(alice) // Alice pays for the batch
                            .signedBy(batchKey1) // Alice signs with only batchKey1
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Batch transaction fails when one inner transaction has no BatchKey set")
        // BATCH_74
        public Stream<DynamicTest> batchWithMissingBatchKeyFails() {
            final var alice = "alice";

            return hapiTest(
                    cryptoCreate(alice),
                    atomicBatch(cryptoCreate("foo1").batchKey(alice), cryptoCreate("foo2")) // No BatchKey set
                            .payingWith(alice) // Alice pays for the batch
                            .signedBy(alice) // Alice signs with the valid BatchKey
                            .hasPrecheck(MISSING_BATCH_KEY));
        }
    }
}
