// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdateAliased;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Note that we cannot test the behavior of the network when the auto-created account expires,
 * because all auto-created accounts are set to an expiration of three months. There is also no way
 * to decrease the expiration time of any entity, so we cannot test the behavior of the network when
 * the auto-created account is about to expire.
 */
@Tag(CRYPTO)
public class AutoAccountUpdateSuite {
    public static final long INITIAL_BALANCE = 1000L;

    private static final String PAYER = "payer";
    public static final String ALIAS = "testAlias";
    private static final String TRANSFER_TXN = "transferTxn";
    public static final String TRANSFER_TXN_2 = "transferTxn2";
    private static final String TRANSFER_TXN_3 = "transferTxn3";

    @HapiTest
    final Stream<DynamicTest> modifySigRequiredAfterAutoAccountCreation() {
        return hapiTest(
                newKeyNamed(ALIAS),
                cryptoCreate(PAYER).balance(INITIAL_BALANCE * ONE_HBAR),
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                        .via(TRANSFER_TXN),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)),
                /* validate child record has no alias set and has fields as expected */
                getTxnRecord(TRANSFER_TXN)
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(1)
                        .hasNoAliasInChildRecord(0)
                        .logged(),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith()
                                .autoRenew(THREE_MONTHS_IN_SECONDS)
                                .receiverSigReq(false)
                                .expectedBalanceWithChargedUsd((ONE_HUNDRED_HBARS), 0, 0)),
                /* change receiverSigRequired to false and validate */
                cryptoUpdateAliased(ALIAS).receiverSigRequired(true).signedBy(ALIAS, PAYER, DEFAULT_PAYER),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith()
                                .autoRenew(THREE_MONTHS_IN_SECONDS)
                                .receiverSigReq(true)
                                .expectedBalanceWithChargedUsd((ONE_HUNDRED_HBARS), 0, 0)),

                /* transfer without receiver sig fails */
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                        .via(TRANSFER_TXN_2)
                        .signedBy(PAYER, DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE),

                /* transfer with receiver sig passes */
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                        .via(TRANSFER_TXN_3)
                        .signedBy(ALIAS, PAYER, DEFAULT_PAYER),
                getTxnRecord(TRANSFER_TXN_3).andAllChildRecords().hasNonStakingChildRecordCount(0),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> updateKeyOnAutoCreatedAccount() {
        final var complexKey = "complexKey";

        SigControl ENOUGH_UNIQUE_SIGS = KeyShape.threshSigs(
                2,
                KeyShape.threshSigs(1, OFF, OFF, OFF, OFF, OFF, OFF, ON),
                KeyShape.threshSigs(3, ON, ON, ON, OFF, OFF, OFF, OFF));

        return hapiTest(
                newKeyNamed(ALIAS),
                newKeyNamed(complexKey).shape(ENOUGH_UNIQUE_SIGS),
                cryptoCreate(PAYER).balance(INITIAL_BALANCE * ONE_HBAR),
                /* auto account is created */
                cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                        .payingWith(PAYER)
                        .via(TRANSFER_TXN),
                withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith()
                                .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)
                                .alias(ALIAS)),
                /* validate the key on account can be updated to complex key, and has no relation to alias*/
                cryptoUpdateAliased(ALIAS)
                        .key(complexKey)
                        .payingWith(PAYER)
                        .signedBy(ALIAS, complexKey, PAYER, DEFAULT_PAYER),
                getAliasedAccountInfo(ALIAS)
                        .has(accountWith()
                                .expectedBalanceWithChargedUsd((ONE_HUNDRED_HBARS), 0, 0)
                                .key(complexKey)));
    }
}
