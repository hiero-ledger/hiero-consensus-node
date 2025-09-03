// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.issues;

import static com.hedera.services.bdd.junit.ContextRequirement.SYSTEM_ACCOUNT_KEYS;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.API_PERMISSIONS;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class Issue2319Spec {
    private static final String NON_TREASURY_KEY = "nonTreasuryKey";
    private static final String NON_TREASURY_ADMIN_KEY = "nonTreasuryAdminKey";
    private static final String DEFAULT_ADMIN_KEY = "defaultAdminKey";

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_KEYS)
    final Stream<DynamicTest> propsPermissionsSigReqsWaivedForAddressBookAdmin() {
        return hapiTest(
                newKeyNamed(NON_TREASURY_KEY),
                newKeyListNamed(NON_TREASURY_ADMIN_KEY, List.of(NON_TREASURY_KEY)),
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1_000_000_000_000L)),
                fileUpdate(APP_PROPERTIES).payingWith(ADDRESS_BOOK_CONTROL).wacl(NON_TREASURY_ADMIN_KEY),
                fileUpdate(API_PERMISSIONS).payingWith(ADDRESS_BOOK_CONTROL).wacl(NON_TREASURY_ADMIN_KEY),
                fileUpdate(APP_PROPERTIES)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of())
                        .signedBy(ADDRESS_BOOK_CONTROL),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of())
                        .signedBy(ADDRESS_BOOK_CONTROL),
                fileAppend(APP_PROPERTIES)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .content(new byte[0])
                        .signedBy(ADDRESS_BOOK_CONTROL),
                fileAppend(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .content(new byte[0])
                        .signedBy(ADDRESS_BOOK_CONTROL),
                fileUpdate(APP_PROPERTIES).wacl(GENESIS),
                fileUpdate(API_PERMISSIONS).wacl(GENESIS));
    }

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_KEYS)
    final Stream<DynamicTest> sysFileImmutabilityWaivedForMasterAndTreasury() {
        return hapiTest(
                cryptoCreate("civilian"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, SYSTEM_ADMIN, 1_000_000_000_000L)),
                fileUpdate(EXCHANGE_RATES).payingWith(EXCHANGE_RATE_CONTROL).useEmptyWacl(),
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .wacl(GENESIS)
                        .payingWith(SYSTEM_ADMIN)
                        .signedBy(GENESIS),
                fileUpdate(EXCHANGE_RATES).payingWith(EXCHANGE_RATE_CONTROL).useEmptyWacl(),
                fileUpdate(EXCHANGE_RATES).wacl(GENESIS).payingWith(GENESIS).signedBy(GENESIS));
    }

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_KEYS)
    @Tag(MATS)
    final Stream<DynamicTest> sysAccountSigReqsWaivedForMasterAndTreasury() {
        return hapiTest(
                newKeyNamed(NON_TREASURY_KEY),
                newKeyListNamed(NON_TREASURY_ADMIN_KEY, List.of(NON_TREASURY_KEY)),
                newKeyListNamed(DEFAULT_ADMIN_KEY, List.of(GENESIS)),
                cryptoCreate("civilian"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, SYSTEM_ADMIN, 1_000_000_000_000L)),
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(NON_TREASURY_ADMIN_KEY).receiverSigRequired(true),
                cryptoUpdate(EXCHANGE_RATE_CONTROL)
                        .payingWith(SYSTEM_ADMIN)
                        .signedBy(GENESIS)
                        .receiverSigRequired(true),
                cryptoUpdate(EXCHANGE_RATE_CONTROL)
                        .payingWith(GENESIS)
                        .signedBy(GENESIS)
                        .receiverSigRequired(true),
                cryptoUpdate(EXCHANGE_RATE_CONTROL)
                        .payingWith("civilian")
                        .signedBy("civilian", GENESIS, NON_TREASURY_ADMIN_KEY)
                        .receiverSigRequired(true),

                // reset EXCHANGE_RATE_CONTROL to default state
                cryptoUpdate(EXCHANGE_RATE_CONTROL)
                        .key(DEFAULT_ADMIN_KEY)
                        .receiverSigRequired(false)
                        .payingWith(GENESIS)
                        .signedBy(GENESIS));
    }

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_KEYS)
    @Tag(MATS)
    final Stream<DynamicTest> sysFileSigReqsWaivedForMasterAndTreasury() {
        var validRates = new AtomicReference<ByteString>();

        return hapiTest(
                cryptoCreate("civilian"),
                newKeyNamed(NON_TREASURY_KEY),
                newKeyListNamed(NON_TREASURY_ADMIN_KEY, List.of(NON_TREASURY_KEY)),
                withOpContext((spec, opLog) -> {
                    var fetch = getFileContents(EXCHANGE_RATES);
                    CustomSpecAssert.allRunFor(spec, fetch);
                    validRates.set(fetch.getResponse()
                            .getFileGetContents()
                            .getFileContents()
                            .getContents());
                }),
                cryptoTransfer(tinyBarsFromTo(GENESIS, SYSTEM_ADMIN, 1_000_000_000_000L)),
                fileUpdate(EXCHANGE_RATES).payingWith(EXCHANGE_RATE_CONTROL).wacl(NON_TREASURY_ADMIN_KEY),
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(SYSTEM_ADMIN)
                        .signedBy(GENESIS)
                        .contents(ignore -> validRates.get()),
                fileUpdate(EXCHANGE_RATES).payingWith(GENESIS).signedBy(GENESIS).contents(ignore -> validRates.get()),
                fileUpdate(EXCHANGE_RATES)
                        .payingWith("civilian")
                        .signedBy("civilian", GENESIS, NON_TREASURY_ADMIN_KEY)
                        .contents(ignore -> validRates.get())
                        .hasPrecheck(AUTHORIZATION_FAILED),
                fileUpdate(EXCHANGE_RATES).payingWith(GENESIS).wacl(GENESIS));
    }
}
