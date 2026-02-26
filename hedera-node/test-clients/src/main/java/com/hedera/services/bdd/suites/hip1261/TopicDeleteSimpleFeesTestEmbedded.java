// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Tests for TopicDelete simple fees in embedded mode.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 */
@Tag(ONLY_EMBEDDED)
@Tag(SIMPLE_FEES)
@TargetEmbeddedMode(CONCURRENT)
@HapiTestLifecycle
public class TopicDeleteSimpleFeesTestEmbedded {
    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOPIC = "testTopic";
    private static final String NODE_ACCOUNT_ID = "0.0.4";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TopicDelete Failures on Pre-Handle")
    class TopicDeleteFailuresOnPreHandle {

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("TopicDelete - invalid payer signature fails on pre-handle - network fee only")
        final Stream<DynamicTest> topicDeleteInvalidPayerSigFailsOnPreHandle() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();

            final String INNER_ID = "topic-delete-txn-inner-id";

            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl invalidSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.OFF));

            return hapiTest(
                    UtilVerbs.newKeyNamed(PAYER_KEY).shape(keyShape),
                    TxnVerbs.cryptoCreate(PAYER).key(PAYER_KEY).balance(HapiSuite.ONE_HUNDRED_HBARS),
                    UtilVerbs.newKeyNamed(ADMIN_KEY),
                    TxnVerbs.createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .signedBy(HapiSuite.DEFAULT_PAYER, ADMIN_KEY)
                            .fee(HapiSuite.ONE_HUNDRED_HBARS),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    TxnVerbs.cryptoTransfer(TokenMovement.movingHbar(HapiSuite.ONE_HBAR)
                                    .between(HapiSuite.DEFAULT_PAYER, NODE_ACCOUNT_ID))
                            .fee(HapiSuite.ONE_HUNDRED_HBARS),
                    QueryVerbs.getAccountBalance(NODE_ACCOUNT_ID).exposingBalanceTo(initialNodeBalance::set),
                    TxnVerbs.deleteTopic(TOPIC)
                            .payingWith(PAYER)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, invalidSig))
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(HapiSuite.ONE_HUNDRED_HBARS)
                            .setNode(NODE_ACCOUNT_ID)
                            .via(INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INVALID_PAYER_SIGNATURE),
                    QueryVerbs.getTxnRecord(INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    QueryVerbs.getAccountBalance(NODE_ACCOUNT_ID).exposingBalanceTo(afterNodeBalance::set),
                    UtilVerbs.withOpContext((spec, log) -> {
                        Assertions.assertEquals(initialBalance.get(), afterBalance.get());
                        Assertions.assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    FeesChargingUtils.validateChargedFeeToUsd(
                            INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            FeesChargingUtils.expectedTopicDeleteNetworkFeeOnlyUsd(2L),
                            1.0));
        }
    }
}
