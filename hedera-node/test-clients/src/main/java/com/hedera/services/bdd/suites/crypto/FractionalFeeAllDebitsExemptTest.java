// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * A token with two non-net-of-transfers fractional fees, FeeA (collectorA, {@code allCollectorsExempt = false}) and
 * FeeB (collectorB, {@code allCollectorsExempt = true}). As the sole sender, collectorA is exempt from FeeA as its
 * collector and from FeeB as a collector of another fee on the token, so no fractional fee is assessed.
 */
@Tag(CRYPTO)
public class FractionalFeeAllDebitsExemptTest {

    private static final String TOKEN = "allDebitsExemptToken";
    private static final String TREASURY = "allDebitsExemptTreasury";
    private static final String COLLECTOR_A = "collectorA";
    private static final String COLLECTOR_B = "collectorB";
    private static final String RECEIVER = "receiver";
    private static final String XFER = "xfer";

    @HapiTest
    final Stream<DynamicTest> collectorSenderExemptFromAllFractionalFees() {
        return hapiTest(
                cryptoCreate(TREASURY).key(GENESIS),
                newKeyNamed("keyA"),
                newKeyNamed("keyB"),
                cryptoCreate(COLLECTOR_A).balance(ONE_HUNDRED_HBARS).key("keyA"),
                cryptoCreate(COLLECTOR_B).balance(ONE_HUNDRED_HBARS).key("keyB"),
                cryptoCreate(RECEIVER).receiverSigRequired(false),
                tokenCreate(TOKEN)
                        .treasury(TREASURY)
                        .initialSupply(1_000L)
                        // FeeA: collectorA collects; no global collector exemption
                        .withCustom(fractionalFee(1L, 10L, 0L, OptionalLong.empty(), COLLECTOR_A, false))
                        // FeeB: collectorB collects; all collectors on this token are exempt
                        .withCustom(fractionalFee(1L, 10L, 0L, OptionalLong.empty(), COLLECTOR_B, true))
                        .signedBy(GENESIS, "keyA", "keyB"),
                tokenAssociate(RECEIVER, TOKEN),
                // Fund collectorA via treasury (treasury is skipped by the assessment loop entirely)
                cryptoTransfer(moving(500L, TOKEN).between(TREASURY, COLLECTOR_A))
                        .signedBy(GENESIS),
                cryptoTransfer(moving(100L, TOKEN).between(COLLECTOR_A, RECEIVER))
                        .payingWith(COLLECTOR_A)
                        .signedBy(GENESIS, "keyA")
                        .via(XFER)
                        .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                getTxnRecord(XFER).hasAssessedCustomFeesSize(0),
                getAccountBalance(RECEIVER).hasTokenBalance(TOKEN, 100L));
    }
}
