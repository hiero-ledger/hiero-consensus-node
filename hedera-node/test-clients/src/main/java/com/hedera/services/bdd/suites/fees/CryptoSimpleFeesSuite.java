// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDeleteAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;
import static com.hedera.services.bdd.suites.HapiSuite.*;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.nodeFeeFromBytesUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_APPROVE_ALLOWANCE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_DELETE_ALLOWANCE_FEE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.CRYPTO_UPDATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.util.List.*;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Test suite for Crypto operations (Create, Update, Delete)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoSimpleFeesSuite {
    private static final String PAYER = "payer";
    private static final String HOOK_CONTRACT = "TruePreHook";

    /**
     * Simple fees formula for CryptoUpdate:
     * node    = NODE_BASE + SIGNATURE_FEE * extraSignatures + bytesOverage * SINGLE_BYTE_FEE
     * network = node * NETWORK_MULTIPLIER
     * service = CRYPTO_UPDATE_BASE_FEE_USD
     * total   = node + network + service
     */
    private static double cryptoUpdateSimpleFeeUsd(final long extraSignatures, final int signedTxnSize) {
        final double nodeFeeUsd =
                NODE_BASE_FEE_USD + (extraSignatures * SIGNATURE_FEE_USD) + nodeFeeFromBytesUsd(signedTxnSize);
        return CRYPTO_UPDATE_BASE_FEE_USD + nodeFeeUsd * (NETWORK_MULTIPLIER + 1);
    }

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto create plain")
    final Stream<DynamicTest> cryptoCreatePlain() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate("newAccount").payingWith(PAYER).via("createAccountTxn")),
                "createAccountTxn",
                0.05,
                1.0,
                0.05,
                1.0);
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
                0.05,
                1.0,
                0.05,
                1.0);
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
                0.005,
                1.0,
                0.005,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto update basic (no key change)")
    final Stream<DynamicTest> cryptoUpdateBasic() {
        // Extra signatures: payer only (node includes 1 signature).
        final var extraSignatures = 0L;
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                cryptoCreate("accountToUpdate").balance(ONE_HBAR),
                cryptoUpdate("accountToUpdate")
                        .memo("Updated memo")
                        .payingWith("accountToUpdate")
                        .signedBy("accountToUpdate")
                        .fee(ONE_HBAR)
                        .via("updateAccountBasicTxn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "updateAccountBasicTxn");
                    final var expectedFee = cryptoUpdateSimpleFeeUsd(extraSignatures, signedTxnSize);
                    allRunFor(
                            spec, validateChargedSimpleFees("Simple Fees", "updateAccountBasicTxn", expectedFee, 1.0));
                }),
                overriding("fees.simpleFeesEnabled", "false"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto update with key change")
    final Stream<DynamicTest> cryptoUpdateWithKey() {
        // Extra signatures: payer + new key (node includes 1 signature)
        final var extraSignatures = 1L;
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                newKeyNamed("newAccountKey"),
                cryptoCreate("accountToUpdate").balance(ONE_HBAR),
                cryptoUpdate("accountToUpdate")
                        .key("newAccountKey")
                        .payingWith("accountToUpdate")
                        .signedBy("accountToUpdate", "newAccountKey")
                        .fee(ONE_HBAR)
                        .via("updateAccountKeyTxn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "updateAccountKeyTxn");
                    final var expectedFee = cryptoUpdateSimpleFeeUsd(extraSignatures, signedTxnSize);
                    allRunFor(spec, validateChargedSimpleFees("Simple Fees", "updateAccountKeyTxn", expectedFee, 1.0));
                }),
                overriding("fees.simpleFeesEnabled", "false"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto update memo only")
    final Stream<DynamicTest> cryptoUpdateMemo() {
        // Extra signatures: payer only (node includes 1 signature).
        final var extraSignatures = 0L;
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                cryptoCreate("accountToUpdate").balance(ONE_HBAR).memo("Original memo"),
                cryptoUpdate("accountToUpdate")
                        .memo("Updated memo text")
                        .payingWith("accountToUpdate")
                        .signedBy("accountToUpdate")
                        .fee(ONE_HBAR)
                        .via("updateAccountMemoTxn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "updateAccountMemoTxn");
                    final var expectedFee = cryptoUpdateSimpleFeeUsd(extraSignatures, signedTxnSize);
                    allRunFor(spec, validateChargedSimpleFees("Simple Fees", "updateAccountMemoTxn", expectedFee, 1.0));
                }),
                overriding("fees.simpleFeesEnabled", "false"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto update combined (key + memo)")
    final Stream<DynamicTest> cryptoUpdateCombined() {
        // Extra signatures: payer + new key (node includes 1 signature)
        final var extraSignatures = 1L;
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                newKeyNamed("combinedKey"),
                cryptoCreate("accountToUpdate").balance(ONE_HBAR).memo("Original"),
                cryptoUpdate("accountToUpdate")
                        .key("combinedKey")
                        .memo("Updated with key")
                        .payingWith("accountToUpdate")
                        .signedBy("accountToUpdate", "combinedKey")
                        .fee(ONE_HBAR)
                        .via("updateAccountCombinedTxn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "updateAccountCombinedTxn");
                    final var expectedFee = cryptoUpdateSimpleFeeUsd(extraSignatures, signedTxnSize);
                    allRunFor(
                            spec,
                            validateChargedSimpleFees("Simple Fees", "updateAccountCombinedTxn", expectedFee, 1.0));
                }),
                overriding("fees.simpleFeesEnabled", "false"));
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
                1.05,
                1.0,
                1.05,
                1.0);
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
                2.05,
                1.0,
                2.05,
                1.0);
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
                5.05,
                1.0,
                5.05,
                1.0);
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
                2.05,
                1.0,
                2.05,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled", "hooks.hooksEnabled"})
    @DisplayName("crypto update with single hook creation")
    final Stream<DynamicTest> cryptoUpdateWithSingleHook() {
        return compareSimpleToOld(
                () -> Arrays.asList(
                        uploadInitCode(HOOK_CONTRACT),
                        contractCreate(HOOK_CONTRACT).gas(5_000_000),
                        cryptoCreate(PAYER).balance(THOUSAND_HBAR),
                        cryptoCreate("accountToUpdate").payingWith(PAYER),
                        cryptoUpdate("accountToUpdate")
                                .payingWith("accountToUpdate")
                                .signedBy("accountToUpdate")
                                .withHooks(accountAllowanceHook(100L, HOOK_CONTRACT))
                                .via("updateWithHookTxn")
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(SUCCESS)),
                "updateWithHookTxn",
                1.00022,
                1.0,
                1.00022,
                1.0);
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
                2.00022,
                1.0,
                2.00022,
                1.0);
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
                1.00022,
                1.0,
                1.00022,
                1.0);
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
                2.00022,
                1.0,
                2.00022,
                1.0);
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
                1.00122,
                1.0,
                1.00122,
                1.0);
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto approve allowance plain")
    final Stream<DynamicTest> cryptoApproveAllowancePlain() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("spender"),
                cryptoApproveAllowance()
                        .payingWith(PAYER)
                        .addCryptoAllowance(PAYER, "spender", 100L)
                        .via("approveTxn"),
                validateChargedUsd("approveTxn", CRYPTO_APPROVE_ALLOWANCE_FEE));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto approve allowance with multiple allowances")
    final Stream<DynamicTest> cryptoApproveAllowanceMultiple() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                cryptoCreate("spender1"),
                cryptoCreate("spender2"),
                cryptoCreate("spender3"),
                cryptoApproveAllowance()
                        .payingWith(PAYER)
                        .fee(THOUSAND_HBAR)
                        .addCryptoAllowance(PAYER, "spender1", 100L)
                        .addCryptoAllowance(PAYER, "spender2", 200L)
                        .addCryptoAllowance(PAYER, "spender3", 300L)
                        .via("approveMultipleTxn"),
                validateChargedUsd("approveMultipleTxn", 3 * CRYPTO_APPROVE_ALLOWANCE_FEE));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto delete allowance plain")
    final Stream<DynamicTest> cryptoDeleteAllowancePlain() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                tokenCreate("nft1")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .initialSupply(0L),
                mintToken("nft1", of(ByteString.copyFromUtf8("1"))),
                cryptoDeleteAllowance()
                        .payingWith(PAYER)
                        .addNftDeleteAllowance(PAYER, "nft1", of(1L))
                        .via("deleteTxn"),
                validateChargedUsd("deleteTxn", CRYPTO_DELETE_ALLOWANCE_FEE));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto delete allowance with multiple allowances")
    final Stream<DynamicTest> cryptoDeleteAllowanceMultiple() {
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS),
                tokenCreate("nft1")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .initialSupply(0L),
                tokenCreate("nft2")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .initialSupply(0L),
                tokenCreate("nft3")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .initialSupply(0L),
                mintToken("nft1", of(ByteString.copyFromUtf8("1"))),
                mintToken("nft2", of(ByteString.copyFromUtf8("2"))),
                mintToken("nft3", of(ByteString.copyFromUtf8("3"))),
                cryptoDeleteAllowance()
                        .payingWith(PAYER)
                        .fee(THOUSAND_HBAR)
                        .addNftDeleteAllowance(PAYER, "nft1", of(1L))
                        .addNftDeleteAllowance(PAYER, "nft2", of(1L))
                        .addNftDeleteAllowance(PAYER, "nft3", of(1L))
                        .via("deleteMultipleTxn"),
                validateChargedUsd("deleteMultipleTxn", 3 * CRYPTO_DELETE_ALLOWANCE_FEE));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto approve allowance with multiple signatures")
    final Stream<DynamicTest> cryptoApproveAllowanceMultipleSignatures() {
        return hapiTest(
                newKeyNamed("payerKey1"),
                newKeyNamed("payerKey2"),
                newKeyListNamed("payerKey", List.of("payerKey1", "payerKey2")),
                cryptoCreate(PAYER).key("payerKey").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("spender"),
                cryptoApproveAllowance()
                        .payingWith(PAYER)
                        .addCryptoAllowance(PAYER, "spender", 100L)
                        .signedBy("payerKey")
                        .via("approveMultiSigTxn"),
                validateChargedUsd(
                        "approveMultiSigTxn", CRYPTO_APPROVE_ALLOWANCE_FEE + SIGNATURE_FEE_AFTER_MULTIPLIER));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("crypto delete allowance with multiple signatures")
    final Stream<DynamicTest> cryptoDeleteAllowanceMultipleSignatures() {
        return hapiTest(
                newKeyNamed("payerKey1"),
                newKeyNamed("payerKey2"),
                newKeyListNamed("payerKey", List.of("payerKey1", "payerKey2")),
                cryptoCreate(PAYER).key("payerKey").balance(ONE_HUNDRED_HBARS),
                tokenCreate("nft1")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(PAYER)
                        .treasury(PAYER)
                        .initialSupply(0L),
                mintToken("nft1", of(ByteString.copyFromUtf8("1"))),
                cryptoDeleteAllowance()
                        .payingWith(PAYER)
                        .addNftDeleteAllowance(PAYER, "nft1", of(1L))
                        .signedBy("payerKey")
                        .via("deleteMultiSigTxn"),
                validateChargedUsd("deleteMultiSigTxn", CRYPTO_DELETE_ALLOWANCE_FEE + SIGNATURE_FEE_AFTER_MULTIPLIER));
    }
}
