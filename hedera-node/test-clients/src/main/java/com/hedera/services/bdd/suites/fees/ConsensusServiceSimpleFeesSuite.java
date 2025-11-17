// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.crypto.CryptoTransferSuite.sdec;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@Tag(MATS)
@HapiTestLifecycle
public class ConsensusServiceSimpleFeesSuite {

    @FunctionalInterface
    public interface OpsProvider {
        List<SpecOperation> provide();
    }

    private static CustomSpecAssert validateChargedSimpleFees(
            String name, String txn, double expectedUsd, double allowedPercentDiff) {
        return assertionsHold((spec, assertLog) -> {
            final var actualUsdCharged = getChargedUsed(spec, txn);
            assertEquals(
                    expectedUsd,
                    actualUsdCharged,
                    (allowedPercentDiff / 100.0) * expectedUsd,
                    String.format(
                            "%s: %s fee (%s) more than %.2f percent different than expected!",
                            name, sdec(actualUsdCharged, 4), txn, allowedPercentDiff));
        });
    }

    private static double getChargedUsed(@NonNull final HapiSpec spec, @NonNull final String txn) {
        requireNonNull(spec);
        requireNonNull(txn);
        var subOp = getTxnRecord(txn).logged();
        allRunFor(spec, subOp);
        final var rcd = subOp.getResponseRecord();
        return (1.0 * rcd.getTransactionFee())
                / ONE_HBAR
                / rcd.getReceipt().getExchangeRate().getCurrentRate().getHbarEquiv()
                * rcd.getReceipt().getExchangeRate().getCurrentRate().getCentEquiv()
                / 100;
    }

    private Stream<DynamicTest> compareSimpleToOld(
            OpsProvider provider, String txName, double fee, double simpleDiff, double oldDiff) {
        List<SpecOperation> opsList = new ArrayList<>();

        opsList.add(overriding("fees.simpleFeesEnabled", "true"));
        opsList.addAll(provider.provide());
        opsList.add(validateChargedSimpleFees("Simple Fees", txName, fee, simpleDiff));

        opsList.add(overriding("fees.simpleFeesEnabled", "false"));
        opsList.addAll(provider.provide());
        opsList.add(validateChargedSimpleFees("Old Fees", txName, fee, oldDiff));

        return hapiTest(opsList.toArray(new SpecOperation[opsList.size()]));
    }

    @Nested
    class TopicFeesComparison {
        private static final String PAYER = "payer";
        private static final String ADMIN = "admin";

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic")
        final Stream<DynamicTest> createTopicPlainComparison() {
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-txn")),
                    "create-topic-txn",
                    0.01009,
                    1,
                    1);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic with admin key")
        final Stream<DynamicTest> createTopicWithAdminComparison() {
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            newKeyNamed(ADMIN),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(ADMIN)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-admin-txn")),
                    "create-topic-admin-txn",
                    0.02109,
                    1,
                    30);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare create topic with payer as admin key")
        final Stream<DynamicTest> createTopicWithPayerAdminComparison() {
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            newKeyNamed(ADMIN),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-admin-txn")),
                    "create-topic-admin-txn",
                    0.02009,
                    1,
                    100);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare update topic with admin key")
        final Stream<DynamicTest> updateTopicComparisonWithPayerAdmin() {
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-admin-txn"),
                            updateTopic("testTopic")
                                    .payingWith(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("update-topic-txn")),
                    "update-topic-txn",
                    0.000310,
                    1,
                    1000);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare update topic with admin key")
        final Stream<DynamicTest> updateTopicComparisonWithAdmin() {
            final String ADMIN = "admin";
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            newKeyNamed(ADMIN),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(ADMIN)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-admin-txn"),
                            updateTopic("testTopic")
                                    .adminKey(ADMIN)
                                    .payingWith(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("update-topic-txn")),
                    "update-topic-txn",
                    0.00131,
                    1,
                    100);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare submit message with included bytes")
        final Stream<DynamicTest> submitMessageFeeWithIncludedBytesComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 100;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            newKeyNamed(PAYER),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            // create topic, provide up to 1 hbar to pay for it
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-txn"),
                            // submit message, provide up to 1 hbar to pay for it
                            submitMessageTo("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .message(new String(messageBytes))
                                    .fee(ONE_HBAR)
                                    .via("submit-message-txn")),
                    "submit-message-txn",
                    0.0001900,
                    1,
                    90);
        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare submit message with extra bytes")
        final Stream<DynamicTest> submitBiggerMessageFeeComparison() {
            // 100 is less than the free size, so there's no per byte charge
            final var byte_size = 1023;
            final byte[] messageBytes = new byte[byte_size]; // up to 1k
            Arrays.fill(messageBytes, (byte) 0b1);
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            newKeyNamed(PAYER),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            // create topic, provide up to 1 hbar to pay for it
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-txn"),
                            // submit message, provide up to 1 hbar to pay for it
                            submitMessageTo("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .message(new String(messageBytes))
                                    .fee(ONE_HBAR)
                                    .via("submit-message-txn")),
                    "submit-message-txn",
                    0.000200,
                    1,
                    40);
        }

        // TODO: support queries
        //        @HapiTest()
        //        @DisplayName("compare get topic info")
        //        final Stream<DynamicTest> getTopicInfoComparison() {
        //            return compare(() -> Arrays.asList(
        //                    newKeyNamed(PAYER),
        //                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
        //                    // create topic. provide up to 1 hbar to pay for it
        //                    createTopic("testTopic")
        //                            .blankMemo()
        //                            .payingWith(PAYER)
        //                            .adminKeyName(PAYER)
        //                            .fee(ONE_HBAR)
        //                            .via("create-topic-txn"),
        //                    // the extra 10 is for the admin key
        //                    validateChargedUsd("create-topic-txn", 0.01022),
        //                    // get topic info, provide up to 1 hbar to pay for it
        //                    getTopicInfo("testTopic")
        //                            .payingWith(PAYER)
        //                            .fee(ONE_HBAR)
        //                            .via("get-topic-txn")
        //                            .logged(),
        //                    validateChargedUsd("get-topic-txn", 0.000101)));
        //        }

        @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
        @DisplayName("compare delete topic with admin key")
        final Stream<DynamicTest> deleteTopicPlainComparison() {
            return compareSimpleToOld(
                    () -> Arrays.asList(
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            createTopic("testTopic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .adminKeyName(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("create-topic-admin-txn"),
                            deleteTopic("testTopic")
                                    .signedBy(PAYER)
                                    .payingWith(PAYER)
                                    .fee(ONE_HBAR)
                                    .via("delete-topic-txn")),
                    "delete-topic-txn",
                    0.005,
                    1,
                    10);
        }
    }
}
