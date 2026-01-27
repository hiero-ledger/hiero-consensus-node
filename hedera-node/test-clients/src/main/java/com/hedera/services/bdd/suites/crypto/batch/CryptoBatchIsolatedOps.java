// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto.batch;

import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isEndOfStakingPeriodRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

final class CryptoBatchIsolatedOps {
    private CryptoBatchIsolatedOps() {}

    // Copied constants needed by moved method
    private static final String TRUE = "true";
    private static final String FALSE = "false";
    private static final String LAZY_MEMO = "";
    private static final String VALID_ALIAS = "validAlias";
    private static final String CIVILIAN = "somebody";
    private static final String SPONSOR = "autoCreateSponsor";
    private static final String PAYER = "payer";
    private static final String TRANSFER_TXN = "transferTxn";
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final long INITIAL_BALANCE = 1000L;
    private static final String AUTO_MEMO = "";
    private static final long EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE = 39_376_619L;

    static Stream<DynamicTest> autoAccountCreationsUnlimitedAssociationsDisabled() {
        final var creationTime = new AtomicLong();
        final long transferFee = 188608L;
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                overriding("entities.unlimitedAutoAssociationsEnabled", FALSE),
                newKeyNamed(VALID_ALIAS),
                cryptoCreate(CIVILIAN).balance(10 * ONE_HBAR),
                cryptoCreate(PAYER).balance(10 * ONE_HBAR),
                cryptoCreate(SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                atomicBatch(cryptoTransfer(
                                        tinyBarsFromToWithAlias(SPONSOR, VALID_ALIAS, ONE_HUNDRED_HBARS),
                                        tinyBarsFromToWithAlias(CIVILIAN, VALID_ALIAS, ONE_HBAR))
                                .via(TRANSFER_TXN)
                                .payingWith(PAYER)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getReceipt(TRANSFER_TXN).andAnyChildReceipts().hasChildAutoAccountCreations(1),
                getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
                getAccountInfo(SPONSOR)
                        .has(accountWith()
                                .balance((INITIAL_BALANCE * ONE_HBAR) - ONE_HUNDRED_HBARS)
                                .noAlias()),
                childRecordsCheck(
                        TRANSFER_TXN,
                        SUCCESS,
                        recordWith().status(SUCCESS).fee(EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE)),
                assertionsHold((spec, opLog) -> {
                    final var lookup = getTxnRecord(TRANSFER_TXN)
                            .andAllChildRecords()
                            .hasNonStakingChildRecordCount(1)
                            .hasNoAliasInChildRecord(0)
                            .logged();
                    allRunFor(spec, lookup);
                    final var sponsor = spec.registry().getAccountID(SPONSOR);
                    final var payer = spec.registry().getAccountID(PAYER);
                    final var parent = lookup.getResponseRecord();
                    var child = lookup.getChildRecord(0);
                    if (isEndOfStakingPeriodRecord(child)) {
                        child = lookup.getChildRecord(1);
                    }
                    com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.assertAliasBalanceAndFeeInChildRecord(
                            parent, child, sponsor, payer, ONE_HUNDRED_HBARS + ONE_HBAR, transferFee, 0);
                    creationTime.set(child.getConsensusTimestamp().getSeconds());
                }),
                sourcing(() -> getAliasedAccountInfo(VALID_ALIAS)
                        .has(accountWith()
                                .key(VALID_ALIAS)
                                .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS + ONE_HBAR, 0, 0)
                                .alias(VALID_ALIAS)
                                .autoRenew(THREE_MONTHS_IN_SECONDS)
                                .receiverSigReq(false)
                                .expiry(creationTime.get() + THREE_MONTHS_IN_SECONDS, 0)
                                .memo(AUTO_MEMO)
                                .maxAutoAssociations(0))
                        .logged()));
    }
}

