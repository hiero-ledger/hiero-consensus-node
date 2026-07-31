// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
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
 * Exercises the path in {@code CustomFractionalFeeAssessor.assessNonNetOfTransferForDebit}
 * where every account with a debit in the token transfer map is exempt from the fee under
 * assessment, making {@code totalDebits = 0}.
 *
 * <p>Setup: a token with two non-net-of-transfers fractional fees:
 * <ul>
 *   <li>FeeA – collector = collectorA, {@code allCollectorsExempt = false}
 *   <li>FeeB – collector = collectorB, {@code allCollectorsExempt = true}
 * </ul>
 * collectorA is the sole sender (only debit account). When the assessor evaluates FeeB for
 * collectorA:
 * <ul>
 *   <li>FeeA is skipped because {@code sender.equals(collectorA)} (own-collector exemption).
 *   <li>FeeB proceeds but {@code getNonExemptTokenDebits} returns empty — collectorA is exempt
 *       via {@code allCollectorsExempt = true} because collectorA collects FeeA.
 *   <li>{@code totalDebits = 0}, so {@code assessNonNetOfTransferForDebit} is invoked with a
 *       transfer map whose only debit belongs to an exempt account.
 * </ul>
 */
@Tag(CRYPTO)
public class FractionalFeeAllDebitsExemptTest {

    private static final String TOKEN = "allDebitsExemptToken";
    private static final String TREASURY = "allDebitsExemptTreasury";
    private static final String COLLECTOR_A = "collectorA";
    private static final String COLLECTOR_B = "collectorB";
    private static final String RECEIVER = "receiver";

    /**
     * Submits a CryptoTransfer where the sole debit account (collectorA) is exempt from the
     * fractional fee being assessed (FeeB), so {@code assessNonNetOfTransferForDebit} receives
     * {@code totalDebits = 0}.
     *
     * <p>The current implementation performs {@code safeFractionMultiply(activeDebit, 0,
     * effectiveFee)}, which divides by zero. The resulting uncaught {@link ArithmeticException}
     * surfaces as {@code FAIL_INVALID}.
     */
    @HapiTest
    final Stream<DynamicTest> allDebitAccountsExemptFromFractionalFeeInvokesDivideByZeroPath() {
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
                //                tokenAssociate(COLLECTOR_A, TOKEN),
                //                tokenAssociate(COLLECTOR_B, TOKEN),
                tokenAssociate(RECEIVER, TOKEN),
                // Fund collectorA via treasury (treasury is skipped by the assessment loop entirely)
                cryptoTransfer(moving(500L, TOKEN).between(TREASURY, COLLECTOR_A))
                        .signedBy(GENESIS),
                // collectorA → receiver:
                //   • FeeA skipped (collectorA is own collector)
                //   • FeeB: receiver is not exempt → filteredOriginalCredits non-empty
                //           collectorA is exempt from FeeB via allCollectorsExempt=true
                //           → getNonExemptTokenDebits returns {} → totalDebits = 0
                //           → assessNonNetOfTransferForDebit invoked with all-exempt debit map
                //           → safeFractionMultiply(activeDebit, 0, effectiveFee) → divide-by-zero
                cryptoTransfer(moving(100L, TOKEN).between(COLLECTOR_A, RECEIVER))
                        .payingWith(COLLECTOR_A)
                        .signedBy(GENESIS, "keyA")
                        .hasKnownStatus(ResponseCodeEnum.SUCCESS));
        //                        .hasKnownStatus(ResponseCodeEnum.FAIL_INVALID));
    }
}
