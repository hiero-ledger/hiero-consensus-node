// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FREEZE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SOFTWARE_UPDATE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.UPDATE_ZIP_FILE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

public class PrivilegedOpsTest {
    private static final String ACCOUNT_88 = "88";
    private static final String ACCOUNT_2 = "2";
    private static final String CIVILIAN = "civilian";
    private static final String NEW_88 = "new88";
    private static final int BURST_SIZE = 10;

    Function<String, HapiSpecOperation[]> transferBurstFn = payer -> IntStream.range(0, BURST_SIZE)
            .mapToObj(ignore -> cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                    .payingWith(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .deferStatusResolution())
            .toArray(HapiSpecOperation[]::new);
    Function<String, HapiSpecOperation[]> miscTxnBurstFn = payer -> IntStream.range(0, BURST_SIZE)
            .mapToObj(i -> cryptoCreate(String.format("Account%d", i))
                    .payingWith(payer)
                    .deferStatusResolution())
            .toArray(HapiSpecOperation[]::new);
    Function<String, HapiSpecOperation[]> hcsTxnBurstFn = payer -> IntStream.range(0, BURST_SIZE)
            .mapToObj(i ->
                    createTopic(String.format("Topic%d", i)).payingWith(payer).deferStatusResolution())
            .toArray(HapiSpecOperation[]::new);
    Function<String, HapiSpecOperation[]> miscQueryBurstFn = payer -> IntStream.range(0, BURST_SIZE)
            .mapToObj(
                    i -> getAccountInfo(ADDRESS_BOOK_CONTROL).nodePayment(100L).payingWith(payer))
            .toArray(HapiSpecOperation[]::new);
    Function<String, HapiSpecOperation[]> hcsQueryBurstFn = payer -> IntStream.range(0, BURST_SIZE)
            .mapToObj(i -> getTopicInfo("misc").nodePayment(100L).payingWith(payer))
            .toArray(HapiSpecOperation[]::new);

    @HapiTest
    final Stream<DynamicTest> freezeAdminPrivilegesAsExpected() {
        return defaultHapiSpec("freezeAdminPrivilegesAsExpected")
                .given(
                        cryptoCreate(CIVILIAN),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ONE_MILLION_HBARS)))
                .when(
                        fileUpdate(UPDATE_ZIP_FILE)
                                .payingWith(FREEZE_ADMIN)
                                .contents("Nope")
                                .hasPrecheck(AUTHORIZATION_FAILED),
                        fileUpdate(UPDATE_ZIP_FILE)
                                .payingWith(ADDRESS_BOOK_CONTROL)
                                .contents("Nope")
                                .hasPrecheck(AUTHORIZATION_FAILED),
                        fileUpdate(UPDATE_ZIP_FILE)
                                .payingWith(EXCHANGE_RATE_CONTROL)
                                .contents("Nope")
                                .hasPrecheck(AUTHORIZATION_FAILED),
                        fileUpdate(UPDATE_ZIP_FILE)
                                .payingWith(FEE_SCHEDULE_CONTROL)
                                .contents("Nope")
                                .hasPrecheck(AUTHORIZATION_FAILED),
                        fileUpdate(UPDATE_ZIP_FILE)
                                .payingWith(CIVILIAN)
                                .contents("Nope")
                                .hasPrecheck(AUTHORIZATION_FAILED))
                .then(
                        fileUpdate(UPDATE_ZIP_FILE)
                                .fee(0L)
                                .via("updateTxn")
                                .payingWith(SOFTWARE_UPDATE_ADMIN)
                                .contents("Yuu"),
                        getTxnRecord("updateTxn").showsNoTransfers(),
                        fileAppend(UPDATE_ZIP_FILE)
                                .fee(0L)
                                .via("appendTxn")
                                .payingWith(SOFTWARE_UPDATE_ADMIN)
                                .content("upp"),
                        getTxnRecord("appendTxn").showsNoTransfers(),
                        fileUpdate(UPDATE_ZIP_FILE)
                                .fee(0L)
                                .payingWith(SYSTEM_ADMIN)
                                .contents("Yuuupp"),
                        fileAppend(UPDATE_ZIP_FILE).fee(0L).payingWith(GENESIS).content(new byte[0]));
    }

    // Temporarily changes system account keys
    @LeakyHapiTest
    final Stream<DynamicTest> systemAccountUpdatePrivilegesAsExpected() {
        final var tmpTreasury = "tmpTreasury";
        return defaultHapiSpec("systemAccountUpdatePrivilegesAsExpected")
                .given(newKeyNamed(tmpTreasury), newKeyNamed(NEW_88), cryptoCreate(CIVILIAN))
                .when(cryptoUpdate(ACCOUNT_88)
                        .payingWith(GENESIS)
                        .signedBy(GENESIS, NEW_88)
                        .key(NEW_88))
                .then(
                        cryptoUpdate(ACCOUNT_2)
                                .receiverSigRequired(true)
                                .payingWith(CIVILIAN)
                                .signedBy(CIVILIAN, GENESIS)
                                .hasPrecheck(AUTHORIZATION_FAILED),
                        cryptoUpdate(ACCOUNT_2)
                                .receiverSigRequired(true)
                                .payingWith(SYSTEM_ADMIN)
                                .signedBy(SYSTEM_ADMIN, GENESIS)
                                .hasPrecheck(AUTHORIZATION_FAILED),
                        cryptoUpdate(ACCOUNT_2)
                                .receiverSigRequired(false)
                                .payingWith(GENESIS)
                                .signedBy(GENESIS),
                        cryptoUpdate(ACCOUNT_2)
                                .key(tmpTreasury)
                                .payingWith(GENESIS)
                                .signedBy(GENESIS)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        cryptoUpdate(ACCOUNT_2)
                                .key(tmpTreasury)
                                .payingWith(GENESIS)
                                .signedBy(GENESIS, tmpTreasury)
                                .notUpdatingRegistryWithNewKey(),
                        cryptoUpdate(ACCOUNT_2)
                                .key(GENESIS)
                                .fee(ONE_HUNDRED_HBARS)
                                .payingWith(GENESIS)
                                .signedBy(GENESIS, tmpTreasury)
                                .notUpdatingRegistryWithNewKey(),
                        cryptoUpdate(ACCOUNT_88)
                                .key(GENESIS)
                                .payingWith(CIVILIAN)
                                .signedBy(CIVILIAN, NEW_88, GENESIS),
                        cryptoUpdate(ACCOUNT_88)
                                .payingWith(GENESIS)
                                .signedBy(GENESIS, NEW_88)
                                .key(NEW_88),
                        cryptoUpdate(ACCOUNT_88)
                                .key(GENESIS)
                                .payingWith(SYSTEM_ADMIN)
                                .signedBy(SYSTEM_ADMIN, GENESIS));
    }

    @LeakyHapiTest(requirement = THROTTLE_OVERRIDES)
    final Stream<DynamicTest> systemAccountsAreNeverThrottled() {
        return hapiTest(flattened(
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1_000_000_000_000L))
                        .fee(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, SYSTEM_ADMIN, 1_000_000_000_000L))
                        .fee(ONE_HUNDRED_HBARS),
                overridingThrottles("testSystemFiles/only-mint-allowed.json"),
                transferBurstFn.apply(SYSTEM_ADMIN),
                transferBurstFn.apply(ADDRESS_BOOK_CONTROL),
                miscTxnBurstFn.apply(SYSTEM_ADMIN),
                miscTxnBurstFn.apply(ADDRESS_BOOK_CONTROL),
                hcsTxnBurstFn.apply(SYSTEM_ADMIN),
                hcsTxnBurstFn.apply(ADDRESS_BOOK_CONTROL),
                inParallel(miscQueryBurstFn.apply(SYSTEM_ADMIN)),
                inParallel(miscQueryBurstFn.apply(ADDRESS_BOOK_CONTROL)),
                createTopic("misc"),
                inParallel(hcsQueryBurstFn.apply(SYSTEM_ADMIN)),
                inParallel(hcsQueryBurstFn.apply(ADDRESS_BOOK_CONTROL))));
    }
}
