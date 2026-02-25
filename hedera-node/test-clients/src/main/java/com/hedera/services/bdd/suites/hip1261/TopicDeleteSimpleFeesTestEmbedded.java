// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicDeleteNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(ONLY_EMBEDDED)
@Tag(SIMPLE_FEES)
@TargetEmbeddedMode(CONCURRENT)
@HapiTestLifecycle
public class TopicDeleteSimpleFeesTestEmbedded {
    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOPIC = "testTopic";

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

            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));
            SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    createTopic(TOPIC)
                            .adminKeyName(ADMIN_KEY)
                            .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                            .fee(ONE_HUNDRED_HBARS),
                    getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                    deleteTopic(TOPIC)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, invalidSig))
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .setNode("0.0.4")
                            .via(INNER_ID)
                            .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                    getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    getAccountBalance("0.0.4").exposingBalanceTo(afterNodeBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                        assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    validateChargedFeeToUsd(
                            INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            expectedTopicDeleteNetworkFeeOnlyUsd(2L),
                            1.0));
        }
    }
}
