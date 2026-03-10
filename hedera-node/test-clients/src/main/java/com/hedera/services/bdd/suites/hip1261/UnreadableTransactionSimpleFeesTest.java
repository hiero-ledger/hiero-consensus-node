// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.approxChangeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.unchangedFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TINY_PARTS_PER_WHOLE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.UNREADABLE_FEE_USD;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.Transaction;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class UnreadableTransactionSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String NODE = "4";
    private static final String PAYER_BALANCE_SNAPSHOT = "payerBalanceBeforeUnreadable";
    private static final String NODE_BALANCE_SNAPSHOT = "nodeBalanceBeforeUnreadable";
    private static final long UNREADABLE_FEE_EPSILON_TINYBARS = 1L;

    @BeforeAll
    static void beforeAll(final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "nodes.nodeRewardsEnabled", "false",
                "nodes.feeCollectionAccountEnabled", "false"));
    }

    @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
    @DisplayName("Unreadable transaction charges node and not payer")
    final Stream<DynamicTest> unreadableTransactionChargesNode() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, NODE, ONE_HUNDRED_HBARS)),
                balanceSnapshot(PAYER_BALANCE_SNAPSHOT, PAYER),
                balanceSnapshot(NODE_BALANCE_SNAPSHOT, NODE),
                cryptoTransfer(tinyBarsFromTo(PAYER, FUNDING, 1))
                        .payingWith(PAYER)
                        .setNode("4")
                        .withTxnTransform(UnreadableTransactionSimpleFeesTest::withUnreadableSignedTransactionBytes)
                        .fireAndForget(),
                sleepFor(2_000L),
                getAccountBalance(PAYER).hasTinyBars(unchangedFromSnapshot(PAYER_BALANCE_SNAPSHOT)),
                getAccountBalance(NODE)
                        .hasTinyBars(approxChangeFromSnapshot(
                                NODE_BALANCE_SNAPSHOT,
                                spec -> -spec.ratesProvider()
                                        .toTbWithActiveRates((long) (UNREADABLE_FEE_USD * 100 * TINY_PARTS_PER_WHOLE)),
                                UNREADABLE_FEE_EPSILON_TINYBARS)));
    }

    private static Transaction withUnreadableSignedTransactionBytes(final Transaction txn) {
        try {
            return txn.toBuilder()
                    .setSignedTransactionBytes(ByteString.copyFromUtf8("not a protobuf signed transaction"))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not mutate signed transaction bytes", e);
        }
    }
}
