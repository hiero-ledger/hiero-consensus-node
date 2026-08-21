// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Integration tests for the frontend (ingest) throttle capacity reclaim performed by
 * {@code DispatchUsageManager.finalizeAndSaveUsage} when an auto-creating transaction passes the frontend
 * throttle but fails at consensus. The reclaim must return capacity to exactly the bucket that was charged, and
 * only that bucket.
 *
 * <p>Both scenarios follow the same shape: constrain one frontend bucket to a single logical operation, prove
 * it is saturated, run a transaction that fails at consensus, then assert that capacity comes back only to the
 * bucket that was actually charged. They run in embedded mode so there is a single frontend throttle to observe,
 * and the throttled transactions are paid by {@link com.hedera.services.bdd.suites.HapiSuite#CIVILIAN_PAYER}
 * because system accounts are exempt from throttling.
 */
@Tag(TOKEN)
public class FrontendThrottleReclaimTest {

    /**
     * A high-volume {@code CRYPTO_TRANSFER} auto-creation claims its implicit {@code CRYPTO_CREATE} against the
     * high-volume bucket, so on consensus failure the reclaim must return that capacity to the high-volume
     * bucket and leave the normal bucket untouched. Both buckets are constrained to a single operation so the
     * routing is fully observable: the normal bucket stays saturated (its probe is throttled) while the
     * high-volume bucket is credited back (its probe is admitted).
     */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            requirement = {THROTTLE_OVERRIDES},
            throttles = "testSystemFiles/frontend-reclaim-hv-create-throttles.json")
    @DisplayName("Failed high-volume auto-creation refunds the high-volume bucket, not the normal bucket")
    final Stream<DynamicTest> failedHighVolumeAutoCreationRefundsHighVolumeBucketNotNormalBucket() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate("poorSender").balance(1L),
                newKeyNamed("saturateAlias"),
                newKeyNamed("sanityAlias"),
                newKeyNamed("failAlias"),
                newKeyNamed("normalProbeAlias"),
                newKeyNamed("hvProbeAlias"),
                // 1) Saturate the normal CryptoCreate bucket with one successful (non-high-volume) auto-creation.
                cryptoTransfer(movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, "saturateAlias"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER),
                // 2) Confirm the normal bucket is saturated: a second normal auto-creation is throttled at ingest.
                cryptoTransfer(movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, "sanityAlias"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                // 3) Fail a high-volume auto-creation at consensus. It claims the high-volume CryptoCreate bucket
                //    at ingest, so its reclaim must return capacity there.
                cryptoTransfer(movingHbar(ONE_HUNDRED_HBARS).between("poorSender", "failAlias"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, "poorSender")
                        .withHighVolume()
                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE),
                // 4) Normal-bucket probe: the failed transfer never charged the normal bucket, so it stays saturated.
                cryptoTransfer(movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, "normalProbeAlias"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .hasPrecheck(BUSY),
                // 5) High-volume-bucket probe: the failed transfer charged and then reclaimed the high-volume
                //    bucket, so it has capacity again and is admitted at ingest. Only the frontend (precheck)
                //    result matters here; the consensus outcome may be SUCCESS or (backend-)throttled.
                cryptoTransfer(movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, "hvProbeAlias"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .withHighVolume()
                        .hasPrecheck(OK)
                        .hasKnownStatusFrom(SUCCESS, THROTTLED_AT_CONSENSUS));
    }

    /**
     * A {@code CRYPTO_TRANSFER} that both auto-creates an account and auto-associates a token claims only its
     * implicit {@code CRYPTO_CREATE} capacity (implicit creations take precedence over auto associations at
     * claim time). On consensus failure the reclaim must return capacity to exactly that charged leg and to
     * nothing else. Both the {@code CRYPTO_CREATE} and {@code TOKEN_ASSOCIATE_TO_ACCOUNT} buckets are constrained
     * to a single operation so the routing is fully observable: the auto-association bucket stays saturated (its
     * probe is throttled) because it was never charged, while the charged CryptoCreate leg is credited back (its
     * probe is admitted).
     */
    @LeakyEmbeddedHapiTest(
            reason = NEEDS_STATE_ACCESS,
            requirement = {THROTTLE_OVERRIDES},
            throttles = "testSystemFiles/frontend-reclaim-assoc-throttles.json")
    @DisplayName("Failed auto-creating transfer does not refund the auto-association bucket")
    final Stream<DynamicTest> failedAutoCreationDoesNotRefundAutoAssociationBucket() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate("treasury").balance(ONE_HUNDRED_HBARS),
                tokenCreate("tok")
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury("treasury"),
                cryptoCreate("saturateReceiver").maxAutomaticTokenAssociations(1),
                cryptoCreate("sanityReceiver").maxAutomaticTokenAssociations(1),
                cryptoCreate("failReceiver").maxAutomaticTokenAssociations(1),
                cryptoCreate("probeReceiver").maxAutomaticTokenAssociations(1),
                cryptoCreate("poorSender").balance(1L),
                newKeyNamed("failAlias"),
                newKeyNamed("createProbeAlias"),
                // 1) Saturate the TokenAssociateToAccount bucket with one successful auto-association.
                cryptoTransfer(moving(1L, "tok").between("treasury", "saturateReceiver"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, "treasury"),
                // 2) Confirm saturation: a second auto-association is throttled at ingest.
                cryptoTransfer(moving(1L, "tok").between("treasury", "sanityReceiver"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, "treasury")
                        .hasPrecheck(BUSY),
                // 3) Fail a transfer that has BOTH an implicit creation and an auto association. At ingest it
                //    claims only CryptoCreate (implicit creations take precedence), so its reclaim must leave
                //    the TokenAssociateToAccount bucket untouched.
                cryptoTransfer(
                                movingHbar(ONE_HUNDRED_HBARS).between("poorSender", "failAlias"),
                                moving(1L, "tok").between("treasury", "failReceiver"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, "poorSender", "treasury")
                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE),
                // 4) Associate-bucket probe: it was never charged by the failed transfer, so it stays saturated.
                cryptoTransfer(moving(1L, "tok").between("treasury", "probeReceiver"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, "treasury")
                        .hasPrecheck(BUSY),
                // 5) CryptoCreate-bucket probe: the failed transfer charged and then reclaimed the CryptoCreate
                //    bucket, so it has capacity again and an implicit-creation transfer is admitted at ingest.
                //    Only the frontend (precheck) result matters here; the consensus outcome may be SUCCESS or
                //    (backend-)throttled.
                cryptoTransfer(movingHbar(ONE_HBAR).between(CIVILIAN_PAYER, "createProbeAlias"))
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .hasPrecheck(OK)
                        .hasKnownStatusFrom(SUCCESS, THROTTLED_AT_CONSENSUS));
    }
}
