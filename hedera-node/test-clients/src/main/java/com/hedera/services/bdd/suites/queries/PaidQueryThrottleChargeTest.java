// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.queries;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;

import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
@DisplayName("Paid query throttling and charging")
public class PaidQueryThrottleChargeTest {

    private static final String THROTTLES = "testSystemFiles/crypto-get-info-only-throttle.json";

    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            requirement = {THROTTLE_OVERRIDES},
            throttles = "testSystemFiles/crypto-get-info-only-throttle.json")
    @DisplayName("A throttled paid query does not charge the payer")
    final Stream<DynamicTest> throttledPaidQueryDoesNotChargePayer() {
        final var payer = "queryPayer";
        return hapiTest(
                overridingThrottles(THROTTLES),
                cryptoCreate(payer).balance(ONE_HUNDRED_HBARS),
                balanceSnapshot("before", payer),
                // The CryptoGetInfo bucket is saturated to near-zero, so this paid query is throttled at the
                // query-throttle step and answered BUSY. Its CryptoTransfer payment bucket stays generous, so the
                // payment itself is not blocked at ingest.
                getAccountInfo(payer).payingWith(payer).hasAnswerOnlyPrecheck(BUSY),
                // Force a consensus round so that, if the query had submitted the payment, that transfer would be
                // handled before we read the balance.
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                // The payment is submitted only after the throttle check passes, so a BUSY query never reaches
                // submit() and the payer is not charged: its balance is unchanged.
                getAccountBalance(payer).hasTinyBars(changeFromSnapshot("before", 0L)));
    }
}
