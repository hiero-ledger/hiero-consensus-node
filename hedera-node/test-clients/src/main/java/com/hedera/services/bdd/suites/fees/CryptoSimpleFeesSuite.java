// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Test suite for Crypto operations (Create, Update, Delete) with simple fees (HIP-1261).
 * Compares old fee model vs new simple fee model to ensure reasonable fee ranges.
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoSimpleFeesSuite {
    private static final String PAYER = "payer";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    static Stream<DynamicTest> runBeforeAfter(@NonNull final SpecOperation... ops) {
        List<SpecOperation> opsList = new ArrayList<>();
        opsList.add(overriding("fees.simpleFeesEnabled", "false"));
        opsList.addAll(Arrays.asList(ops));
        opsList.add(overriding("fees.simpleFeesEnabled", "true"));
        opsList.addAll(Arrays.asList(ops));
        return hapiTest(opsList.toArray(new SpecOperation[opsList.size()]));
    }

    // CryptoCreate Tests (extracted from SimpleFeesSuite)

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare crypto create plain")
    final Stream<DynamicTest> cryptoCreatePlainComparison() {
        return runBeforeAfter(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS).via("create-payer-txn"),
                cryptoCreate("newAccount")
                        .balance(0L)
                        .payingWith(PAYER)
                        .fee(ONE_HBAR)
                        .via("createAccountTxn"),
                validateChargedUsdWithin("createAccountTxn", 0.05, 1.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare crypto create with key")
    final Stream<DynamicTest> cryptoCreateWithKeyComparison() {
        return runBeforeAfter(
                newKeyNamed("accountKey"),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("newAccount")
                        .balance(0L)
                        .key("accountKey")
                        .payingWith(PAYER)
                        .fee(ONE_HBAR)
                        .via("createAccountKeyTxn"),
                validateChargedUsdWithin("createAccountKeyTxn", 0.05, 1.0));
    }

    // CryptoDelete Tests (extracted from SimpleFeesSuite)

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare crypto delete plain")
    final Stream<DynamicTest> cryptoDeletePlainComparison() {
        return runBeforeAfter(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("accountToDelete")
                        .balance(ONE_HBAR)
                        .payingWith(PAYER)
                        .fee(ONE_HBAR),
                cryptoDelete("accountToDelete")
                        .transfer(PAYER)
                        .payingWith("accountToDelete")
                        .signedBy("accountToDelete")
                        .blankMemo()
                        .fee(ONE_HBAR)
                        .via("deleteAccountTxn"),
                validateChargedUsdWithin("deleteAccountTxn", 0.005, 1.0));
    }

    // CryptoUpdate Tests (new)

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare crypto update basic (no key change)")
    final Stream<DynamicTest> cryptoUpdateBasicComparison() {
        return runBeforeAfter(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("accountToUpdate")
                        .balance(ONE_HBAR)
                        .payingWith(PAYER)
                        .fee(ONE_HBAR),
                cryptoUpdate("accountToUpdate")
                        .memo("Updated memo")
                        .payingWith("accountToUpdate")
                        .signedBy("accountToUpdate")
                        .fee(ONE_HBAR)
                        .via("updateAccountBasicTxn"),
                validateChargedUsdWithin("updateAccountBasicTxn", 0.0006, 1.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare crypto update with key change")
    final Stream<DynamicTest> cryptoUpdateWithKeyComparison() {
        return runBeforeAfter(
                newKeyNamed("newAccountKey"),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("accountToUpdate")
                        .balance(ONE_HBAR)
                        .payingWith(PAYER)
                        .fee(ONE_HBAR),
                cryptoUpdate("accountToUpdate")
                        .key("newAccountKey")
                        .payingWith("accountToUpdate")
                        .signedBy("accountToUpdate", "newAccountKey")
                        .fee(ONE_HBAR)
                        .via("updateAccountKeyTxn"),
                validateChargedUsdWithin("updateAccountKeyTxn", 0.0016, 1.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare crypto update memo only")
    final Stream<DynamicTest> cryptoUpdateMemoComparison() {
        return runBeforeAfter(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("accountToUpdate")
                        .balance(ONE_HBAR)
                        .memo("Original memo")
                        .payingWith(PAYER)
                        .fee(ONE_HBAR),
                cryptoUpdate("accountToUpdate")
                        .memo("Updated memo text")
                        .payingWith("accountToUpdate")
                        .signedBy("accountToUpdate")
                        .fee(ONE_HBAR)
                        .via("updateAccountMemoTxn"),
                validateChargedUsdWithin("updateAccountMemoTxn", 0.0006, 1.0));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("compare crypto update combined (key + memo)")
    final Stream<DynamicTest> cryptoUpdateCombinedComparison() {
        return runBeforeAfter(
                newKeyNamed("combinedKey"),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("accountToUpdate")
                        .balance(ONE_HBAR)
                        .memo("Original")
                        .payingWith(PAYER)
                        .fee(ONE_HBAR),
                cryptoUpdate("accountToUpdate")
                        .key("combinedKey")
                        .memo("Updated with key")
                        .payingWith("accountToUpdate")
                        .signedBy("accountToUpdate", "combinedKey")
                        .fee(ONE_HBAR)
                        .via("updateAccountCombinedTxn"),
                validateChargedUsdWithin("updateAccountCombinedTxn", 0.0016, 1.0));
    }
}
