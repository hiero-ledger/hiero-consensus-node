// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.issues;

import static com.hedera.services.bdd.junit.ContextRequirement.PERMISSION_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.API_PERMISSIONS;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class Issue2143Spec {
    @LeakyHapiTest(requirement = {PERMISSION_OVERRIDES})
    final Stream<DynamicTest> account55ControlCanUpdatePermissions() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1_000_000_000L)),
                fileUpdate(API_PERMISSIONS)
                        .overridingProps(Map.of("createFile", "0-100"))
                        .payingWith(ADDRESS_BOOK_CONTROL),
                fileUpdate(API_PERMISSIONS)
                        .overridingProps(Map.of("createFile", "0-*"))
                        .payingWith(ADDRESS_BOOK_CONTROL));
    }

    @LeakyHapiTest(requirement = {PERMISSION_OVERRIDES, PROPERTY_OVERRIDES})
    @Tag(MATS)
    final Stream<DynamicTest> account57ControlCanUpdatePropertiesAndPermissions() {
        return hapiTest(
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, 1_000_000_000L)),
                fileUpdate(APP_PROPERTIES).overridingProps(Map.of()).payingWith(EXCHANGE_RATE_CONTROL),
                fileUpdate(API_PERMISSIONS)
                        .overridingProps(Map.of("createFile", "0-100"))
                        .payingWith(EXCHANGE_RATE_CONTROL),
                fileUpdate(APP_PROPERTIES).overridingProps(Map.of()).payingWith(EXCHANGE_RATE_CONTROL),
                fileUpdate(API_PERMISSIONS)
                        .overridingProps(Map.of("createFile", "0-*"))
                        .payingWith(EXCHANGE_RATE_CONTROL));
    }
}
