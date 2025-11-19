// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;
import static com.hedera.services.bdd.suites.HapiSuite.*;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Test suite for Crypto operations (Create, Update, Delete)
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoSimpleFeesSuite {
    private static final String PAYER = "payer";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true")
        );
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto create plain")
    final Stream<DynamicTest> cryptoCreatePlain() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate("newAccount")
                                .payingWith(PAYER)
                                .via("createAccountTxn")),
                "createAccountTxn",
                0.05, 1.0,
                0.05, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto create with key")
    final Stream<DynamicTest> cryptoCreateWithKey() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed("accountKey"),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate("newAccount")
                                .key("accountKey")
                                .payingWith(PAYER)
                                .via("createAccountKeyTxn")),
                "createAccountKeyTxn",
                0.05, 1.0,
                0.05, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto delete plain")
    final Stream<DynamicTest> cryptoDeletePlain() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate("accountToDelete").payingWith(PAYER),
                        cryptoDelete("accountToDelete")
                                .transfer(PAYER)
                                .payingWith("accountToDelete")
                                .signedBy("accountToDelete")
                                .blankMemo()
                                .via("deleteAccountTxn")),
                "deleteAccountTxn",
                0.005, 1.0,
                0.005, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto update basic (no key change)")
    final Stream<DynamicTest> cryptoUpdateBasic() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        cryptoCreate("accountToUpdate")
                                .balance(ONE_HBAR),
                        cryptoUpdate("accountToUpdate")
                                .memo("Updated memo")
                                .payingWith("accountToUpdate")
                                .signedBy("accountToUpdate")
                                .fee(ONE_HBAR)
                                .via("updateAccountBasicTxn")),
                "updateAccountBasicTxn",
                0.00022, 1.0,
                0.00022, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto update with key change")
    final Stream<DynamicTest> cryptoUpdateWithKey() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed("newAccountKey"),
                        cryptoCreate("accountToUpdate")
                                .balance(ONE_HBAR),
                        cryptoUpdate("accountToUpdate")
                                .key("newAccountKey")
                                .payingWith("accountToUpdate")
                                .signedBy("accountToUpdate", "newAccountKey")
                                .fee(ONE_HBAR)
                                .via("updateAccountKeyTxn")),
                "updateAccountKeyTxn",
                0.00122, 1.0,
                0.00122, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto update memo only")
    final Stream<DynamicTest> cryptoUpdateMemo() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        cryptoCreate("accountToUpdate")
                                .balance(ONE_HBAR)
                                .memo("Original memo"),
                        cryptoUpdate("accountToUpdate")
                                .memo("Updated memo text")
                                .payingWith("accountToUpdate")
                                .signedBy("accountToUpdate")
                                .fee(ONE_HBAR)
                                .via("updateAccountMemoTxn")),
                "updateAccountMemoTxn",
                0.00022, 1.0,
                0.00022, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto update combined (key + memo)")
    final Stream<DynamicTest> cryptoUpdateCombined() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        newKeyNamed("combinedKey"),
                        cryptoCreate("accountToUpdate")
                                .balance(ONE_HBAR)
                                .memo("Original"),
                        cryptoUpdate("accountToUpdate")
                                .key("combinedKey")
                                .memo("Updated with key")
                                .payingWith("accountToUpdate")
                                .signedBy("accountToUpdate", "combinedKey")
                                .fee(ONE_HBAR)
                                .via("updateAccountCombinedTxn")),
                "updateAccountCombinedTxn",
                0.00122, 1.0,
                0.00122, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("crypto create with single hook")
    final Stream<DynamicTest> cryptoCreateWithSingleHook() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate("accountWithHook")
                                .balance(0L)
                                .payingWith(PAYER)
                                .withHooks(accountAllowanceHook(1L, HOOK_CONTRACT))
                                .via("createWithHookTxn")
                                .hasKnownStatus(SUCCESS)),
                "createWithHookTxn",
                1.05, 1.0,
                1.05, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("crypto create with two hooks")
    final Stream<DynamicTest> cryptoCreateWithTwoHooks() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate("accountWith2Hooks")
                                .payingWith(PAYER)
                                .withHooks(
                                        accountAllowanceHook(10L, HOOK_CONTRACT),
                                        accountAllowanceHook(11L, HOOK_CONTRACT))
                                .via("createWith2HooksTxn")
                                .hasKnownStatus(SUCCESS)),
                "createWith2HooksTxn",
                2.05, 1.0,
                2.05, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("crypto create with five hooks")
    final Stream<DynamicTest> cryptoCreateWithFiveHooks() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate(PAYER).balance(THOUSAND_HBAR),
                        cryptoCreate("accountWith5Hooks")
                                .balance(ONE_HUNDRED_HBARS)
                                .payingWith(PAYER)
                                .withHooks(
                                        accountAllowanceHook(20L, HOOK_CONTRACT),
                                        accountAllowanceHook(21L, HOOK_CONTRACT),
                                        accountAllowanceHook(22L, HOOK_CONTRACT),
                                        accountAllowanceHook(23L, HOOK_CONTRACT),
                                        accountAllowanceHook(24L, HOOK_CONTRACT))
                                .via("createWith5HooksTxn")
                                .hasKnownStatus(SUCCESS)),
                "createWith5HooksTxn",
                5.05, 1.0,
                5.05, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("crypto create with hooks and key")
    final Stream<DynamicTest> cryptoCreateWithHooksAndKeys() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        newKeyNamed("accountKey"),
                        cryptoCreate(PAYER).balance(THOUSAND_HBAR),
                        cryptoCreate("accountWithHooksKeys")
                                .key("accountKey")
                                .payingWith(PAYER)
                                .withHooks(
                                        accountAllowanceHook(30L, HOOK_CONTRACT),
                                        accountAllowanceHook(31L, HOOK_CONTRACT))
                                .via("createWithHooksKeysTxn")
                                .hasKnownStatus(SUCCESS)),
                "createWithHooksKeysTxn",
                2.05, 1.0,
                2.05, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("crypto update with single hook creation")
    final Stream<DynamicTest> cryptoUpdateWithSingleHook() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate(PAYER).balance(THOUSAND_HBAR),
                        cryptoCreate("accountToUpdate")
                                .payingWith(PAYER),
                        cryptoUpdate("accountToUpdate")
                                .payingWith("accountToUpdate")
                                .signedBy("accountToUpdate")
                                .withHooks(accountAllowanceHook(100L, HOOK_CONTRACT))
                                .via("updateWithHookTxn")
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS)),
                "updateWithHookTxn",
                1.00022, 1.0,
                1.00022, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("crypto update with multiple hook")
    final Stream<DynamicTest> cryptoUpdateWithMultipleHooks() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate("accountToUpdate").balance(THOUSAND_HBAR),
                        cryptoUpdate("accountToUpdate")
                                .payingWith("accountToUpdate")
                                .signedBy("accountToUpdate")
                                .fee(ONE_HUNDRED_HBARS)
                                .withHooks(
                                        accountAllowanceHook(101L, HOOK_CONTRACT),
                                        accountAllowanceHook(102L, HOOK_CONTRACT))
                                .via("updateWith2HooksTxn")
                                .hasKnownStatus(SUCCESS)),
                "updateWith2HooksTxn",
                2.00022, 1.0,
                2.00022, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("crypto update with hook deletion")
    final Stream<DynamicTest> cryptoUpdateWithHookDeletion() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate(PAYER).balance(THOUSAND_HBAR),
                        cryptoCreate("accountWithHook")
                                .payingWith(PAYER)
                                .withHooks(accountAllowanceHook(103L, HOOK_CONTRACT)),
                        cryptoUpdate("accountWithHook")
                                .payingWith("accountWithHook")
                                .signedBy("accountWithHook")
                                .removingHooks(103L)
                                .via("updateDeleteHookTxn")
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS)),
                "updateDeleteHookTxn",
                1.00022, 1.0,
                1.00022, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("crypto update with hook creation and deletion")
    final Stream<DynamicTest> cryptoUpdateWithHookCreationAndDeletion() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate("accountWithHook")
                                .balance(THOUSAND_HBAR)
                                .withHooks(accountAllowanceHook(104L, HOOK_CONTRACT)),
                        cryptoUpdate("accountWithHook")
                                .payingWith("accountWithHook")
                                .signedBy("accountWithHook")
                                .withHooks(accountAllowanceHook(105L, HOOK_CONTRACT))
                                .removingHooks(104L)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("updateCreateDeleteHookTxn")
                                .hasKnownStatus(SUCCESS)),
                "updateCreateDeleteHookTxn",
                2.00022, 1.0,
                2.00022, 1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("crypto update with hook and key change")
    final Stream<DynamicTest> cryptoUpdateWithHookAndKey() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        newKeyNamed("newKey"),
                        cryptoCreate("accountToUpdate").balance(THOUSAND_HBAR),
                        cryptoUpdate("accountToUpdate")
                                .key("newKey")
                                .payingWith("accountToUpdate")
                                .signedBy("accountToUpdate", "newKey")
                                .withHooks(accountAllowanceHook(106L, HOOK_CONTRACT))
                                .via("updateHookAndKeyTxn")
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS)),
                "updateHookAndKeyTxn",
                1.00122, 1.0,
                1.00122, 1.0);
    }
}
