// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.misc;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileDelete;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_DELETE_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class CannotDeleteSystemEntitiesSuite {
    final int[] sysFileIds = {101, 102, 111, 112, 121, 122, 150};

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> genesisCannotDeleteSystemAccountsFrom1To100() {
        return systemUserCannotDeleteSystemAccounts(1, 100, GENESIS);
    }

    @HapiTest
    final Stream<DynamicTest> genesisCannotDeleteSystemAccountsFrom700To750() {
        return systemUserCannotDeleteSystemAccounts(700, 750, GENESIS);
    }

    @HapiTest
    final Stream<DynamicTest> systemAdminCannotDeleteSystemAccountsFrom1To100() {
        return systemUserCannotDeleteSystemAccounts(1, 100, SYSTEM_ADMIN);
    }

    @HapiTest
    final Stream<DynamicTest> systemAdminCannotDeleteSystemAccountsFrom700To750() {
        return systemUserCannotDeleteSystemAccounts(700, 750, SYSTEM_ADMIN);
    }

    @HapiTest
    final Stream<DynamicTest> systemDeleteAdminCannotDeleteSystemAccountsFrom1To100() {
        return systemUserCannotDeleteSystemAccounts(1, 100, SYSTEM_DELETE_ADMIN);
    }

    @HapiTest
    final Stream<DynamicTest> systemDeleteAdminCannotDeleteSystemAccountsFrom700To750() {
        return systemUserCannotDeleteSystemAccounts(700, 750, SYSTEM_DELETE_ADMIN);
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> normalUserCannotDeleteSystemAccountsFrom1To100() {
        return normalUserCannotDeleteSystemAccounts(1, 100);
    }

    @HapiTest
    final Stream<DynamicTest> normalUserCannotDeleteSystemAccountsFrom700To750() {
        return normalUserCannotDeleteSystemAccounts(700, 750);
    }

    @HapiTest
    final Stream<DynamicTest> genesisCannotDeleteSystemFileIds() {
        return systemUserCannotDeleteSystemFiles(sysFileIds, GENESIS);
    }

    @HapiTest
    final Stream<DynamicTest> systemAdminCannotDeleteSystemFileIds() {
        return systemUserCannotDeleteSystemFiles(sysFileIds, SYSTEM_ADMIN);
    }

    @HapiTest
    final Stream<DynamicTest> systemDeleteAdminCannotDeleteSystemFileIds() {
        return systemUserCannotDeleteSystemFiles(sysFileIds, SYSTEM_DELETE_ADMIN);
    }

    @HapiTest
    final Stream<DynamicTest> normalUserCannotDeleteSystemFileIds() {
        return normalUserCannotDeleteSystemFiles(sysFileIds);
    }

    @HapiTest
    final Stream<DynamicTest> genesisCannotSystemFileDeleteFileIds() {
        return systemDeleteCannotDeleteSystemFiles(sysFileIds, GENESIS);
    }

    @HapiTest
    final Stream<DynamicTest> systemAdminCannotSystemFileDeleteFileIds() {
        return systemDeleteCannotDeleteSystemFiles(sysFileIds, SYSTEM_ADMIN);
    }

    @HapiTest
    final Stream<DynamicTest> systemDeleteAdminCannotSystemFileDeleteFileIds() {
        return systemDeleteCannotDeleteSystemFiles(sysFileIds, SYSTEM_DELETE_ADMIN);
    }

    final Stream<DynamicTest> systemUserCannotDeleteSystemAccounts(int firstAccount, int lastAccount, String sysUser) {
        return hapiTest(
                cryptoCreate("unluckyReceiver").balance(0L),
                cryptoTransfer(movingHbar(100 * ONE_HUNDRED_HBARS)
                                .distributing(GENESIS, SYSTEM_ADMIN, SYSTEM_DELETE_ADMIN))
                        .payingWith(GENESIS),
                inParallel(IntStream.rangeClosed(firstAccount, lastAccount)
                        .mapToObj(id -> cryptoDelete("0.0." + id)
                                .transfer("unluckyReceiver")
                                .payingWith(sysUser)
                                .signedBy(sysUser)
                                .hasPrecheckFrom(ENTITY_NOT_ALLOWED_TO_DELETE))
                        .toArray(HapiSpecOperation[]::new)));
    }

    final Stream<DynamicTest> normalUserCannotDeleteSystemAccounts(int firstAccount, int lastAccount) {
        return hapiTest(
                newKeyNamed("normalKey"),
                cryptoCreate("unluckyReceiver").balance(0L),
                cryptoCreate("normalUser").key("normalKey").balance(1_000_000_000L),
                inParallel(IntStream.rangeClosed(firstAccount, lastAccount)
                        .mapToObj(id -> cryptoDelete("0.0." + id)
                                .transfer("unluckyReceiver")
                                .payingWith("normalUser")
                                .signedBy("normalKey")
                                .hasPrecheck(ENTITY_NOT_ALLOWED_TO_DELETE))
                        .toArray(HapiSpecOperation[]::new)));
    }

    final Stream<DynamicTest> systemUserCannotDeleteSystemFiles(int[] fileIds, String sysUser) {
        return hapiTest(
                cryptoTransfer(movingHbar(100 * ONE_HUNDRED_HBARS)
                                .distributing(GENESIS, SYSTEM_ADMIN, SYSTEM_DELETE_ADMIN))
                        .payingWith(GENESIS),
                inParallel(Arrays.stream(fileIds)
                        .mapToObj(id -> cryptoDelete("0.0." + id)
                                .payingWith(sysUser)
                                .signedBy(sysUser)
                                .hasPrecheck(ENTITY_NOT_ALLOWED_TO_DELETE))
                        .toArray(HapiSpecOperation[]::new)));
    }

    final Stream<DynamicTest> normalUserCannotDeleteSystemFiles(int[] fileIds) {
        return hapiTest(
                newKeyNamed("normalKey"),
                cryptoCreate("normalUser").key("normalKey").balance(1_000_000_000L),
                inParallel(Arrays.stream(fileIds)
                        .mapToObj(id -> fileDelete("0.0." + id)
                                .payingWith("normalUser")
                                .signedBy("normalKey")
                                .hasPrecheck(ENTITY_NOT_ALLOWED_TO_DELETE))
                        .toArray(HapiSpecOperation[]::new)));
    }

    final Stream<DynamicTest> systemDeleteCannotDeleteSystemFiles(int[] fileIds, String sysUser) {
        return hapiTest(
                cryptoTransfer(movingHbar(100 * ONE_HUNDRED_HBARS)
                                .distributing(GENESIS, SYSTEM_ADMIN, SYSTEM_DELETE_ADMIN))
                        .payingWith(GENESIS),
                inParallel(Arrays.stream(fileIds)
                        .mapToObj(id -> systemFileDelete("0.0." + id)
                                .payingWith(sysUser)
                                .signedBy(sysUser)
                                .hasPrecheck(ENTITY_NOT_ALLOWED_TO_DELETE))
                        .toArray(HapiSpecOperation[]::new)));
    }
}
