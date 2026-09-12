// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_NEW_VALID_SIGNATURES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SOME_SIGNATURES_WERE_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

/**
 * Pins the scope of a schedule's persisted signatory list: a key is recorded only when it is a constituent
 * of the schedule's required keys at the moment it signs, so a key that had no authority over the schedule
 * cannot accumulate an approval that a later key rotation would redeem.
 */
public class ScheduleSignatoryScopeTest {
    @HapiTest
    @DisplayName("a key that was never required is not recorded, so a later rotation to it does not execute")
    final Stream<DynamicTest> unrequiredKeyIsNotRecordedAcrossAKeyRotation() {
        return hapiTest(
                newKeyNamed("senderKey"),
                newKeyNamed("outsiderKey"),
                newKeyNamed("triggerKey"),
                cryptoCreate("sender").key("senderKey").balance(ONE_HBAR),
                cryptoCreate("receiver").balance(0L),
                cryptoCreate("trigger").key("triggerKey").balance(ONE_HBAR),
                scheduleCreate("sked", cryptoTransfer(tinyBarsFromTo("sender", "receiver", 1L)))
                        .payingWith(DEFAULT_PAYER),
                getAccountDetails("receiver")
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(0L)),
                // The outsider key is not part of the schedule's required keys, so signing with it records nothing
                scheduleSign("sked")
                        .alsoSigningWith("outsiderKey")
                        .hasKnownStatusFrom(NO_NEW_VALID_SIGNATURES, SOME_SIGNATURES_WERE_INVALID),
                // The sender now rotates its key TO that same outsider key
                cryptoUpdate("sender").key("outsiderKey"),
                // Re-evaluate: an unrelated key's sign forces the schedule to be reconsidered
                scheduleSign("sked")
                        .signedBy("triggerKey")
                        .payingWith("trigger")
                        .hasKnownStatusFrom(NO_NEW_VALID_SIGNATURES, SOME_SIGNATURES_WERE_INVALID),
                // Nothing was banked: the transfer has still not happened
                getAccountDetails("receiver")
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(0L)),
                // It executes only once the now-required key actually signs
                scheduleSign("sked").alsoSigningWith("outsiderKey"),
                getAccountDetails("receiver")
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(1L)));
    }

    /**
     * Documents the cross-account consequence of storing bare primitive keys: a key recorded because one
     * account's requirement needed it also satisfies a second account that later adopts that same key,
     * without the second account signing this schedule. That is what {@code Schedule.signatories} intends by
     * storing primitive keys "regardless of signing order, intervening changes, or other situations"
     * (services/state/schedule/schedule.proto:175-177). No authority is created: adopting the key requires
     * signatures from both the adopting account's existing key and the adopted key, after which the key's
     * holder can act as that account directly.
     */
    @HapiTest
    @DisplayName("a recorded key also satisfies another account that later adopts it")
    final Stream<DynamicTest> aRecordedKeyAlsoSatisfiesAnAccountThatLaterAdoptsIt() {
        return hapiTest(
                newKeyNamed("senderKey"),
                newKeyNamed("receiverKey"),
                cryptoCreate("sender").key("senderKey").balance(ONE_HBAR),
                // The receiver's own signature is required for the transfer, so the schedule needs both keys
                cryptoCreate("receiver").key("receiverKey").balance(0L).receiverSigRequired(true),
                scheduleCreate("sked", cryptoTransfer(tinyBarsFromTo("sender", "receiver", 1L)))
                        .payingWith(DEFAULT_PAYER)
                        .alsoSigningWith("senderKey"),
                // Only the sender's key is recorded; the receiver has not signed, so nothing executes
                getAccountDetails("receiver")
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(0L)),
                // The receiver now adopts the sender's key, signed by both its current key and the new one
                cryptoUpdate("receiver").key("senderKey"),
                // Re-evaluation: the recorded key now satisfies the receiver's requirement as well
                scheduleSign("sked")
                        .alsoSigningWith("senderKey")
                        .hasKnownStatusFrom(SUCCESS, NO_NEW_VALID_SIGNATURES, SOME_SIGNATURES_WERE_INVALID),
                getAccountDetails("receiver")
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(1L)));
    }
}
