// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.issues;

import static com.hedera.services.bdd.junit.ContextRequirement.PERMISSION_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.API_PERMISSIONS;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class Issue2098Spec {
    private static final String CIVILIAN = "civilian";
    private static final String CRYPTO_TRANSFER = "cryptoTransfer";
    private static final String GET_TOPIC_INFO = "getTopicInfo";

    @LeakyHapiTest(requirement = PERMISSION_OVERRIDES)
    @Tag(MATS)
    final Stream<DynamicTest> txnApiPermissionsChangeImmediately() {
        return hapiTest(
                cryptoCreate(CIVILIAN),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of(CRYPTO_TRANSFER, "0-1")),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, 1L))
                        .payingWith(CIVILIAN)
                        .hasPrecheckFrom(NOT_SUPPORTED, OK)
                        .hasKnownStatus(UNAUTHORIZED),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of(CRYPTO_TRANSFER, "0-*")),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, 1L)).payingWith(CIVILIAN));
    }

    @LeakyHapiTest(requirement = PERMISSION_OVERRIDES)
    final Stream<DynamicTest> queryApiPermissionsChangeImmediately() {
        return hapiTest(
                cryptoCreate(CIVILIAN),
                createTopic("misc"),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of(GET_TOPIC_INFO, "0-1")),
                getTopicInfo("misc").payingWith(CIVILIAN).hasAnswerOnlyPrecheck(NOT_SUPPORTED),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of(GET_TOPIC_INFO, "0-*")),
                getTopicInfo("misc").payingWith(CIVILIAN));
    }

    @LeakyHapiTest(requirement = PERMISSION_OVERRIDES)
    final Stream<DynamicTest> adminsCanQueryNoMatterPermissions() {
        return hapiTest(
                cryptoCreate(CIVILIAN),
                createTopic("misc"),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of(GET_TOPIC_INFO, "0-1")),
                getTopicInfo("misc").payingWith(CIVILIAN).hasAnswerOnlyPrecheck(NOT_SUPPORTED),
                getTopicInfo("misc"),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of(GET_TOPIC_INFO, "0-*")));
    }

    @LeakyHapiTest(requirement = PERMISSION_OVERRIDES)
    @Tag(MATS)
    final Stream<DynamicTest> adminsCanTransactNoMatterPermissions() {
        return hapiTest(
                cryptoCreate(CIVILIAN),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of(CRYPTO_TRANSFER, "0-1")),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, 1L))
                        .payingWith(CIVILIAN)
                        .hasPrecheckFrom(NOT_SUPPORTED, OK)
                        .hasKnownStatus(UNAUTHORIZED),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                fileUpdate(API_PERMISSIONS)
                        .payingWith(ADDRESS_BOOK_CONTROL)
                        .overridingProps(Map.of(CRYPTO_TRANSFER, "0-*")));
    }
}
