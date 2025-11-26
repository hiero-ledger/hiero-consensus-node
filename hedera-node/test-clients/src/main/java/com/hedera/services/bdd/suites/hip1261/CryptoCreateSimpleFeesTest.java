// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.CryptoSimpleFees.expectedCryptoCreateUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoCreateSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("CryptoCreate Simple Fees Positive Test Cases")
    class CryptoCreateSimpleFeesPositiveTestCases {
        @HapiTest
        @DisplayName("CryptoCreate - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithIncludedSig() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1, 0), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - one included signature and one included key - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithIncludedSigAndKey() {
            return hapiTest(
                    newKeyNamed(ADMIN_KEY),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .payingWith(PAYER)
                            .key(ADMIN_KEY)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1L, 1L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - threshold with extra signatures and keys - full charging with extras")
        Stream<DynamicTest> cryptoCreateThresholdWithExtraSigAndKeys() {
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(2L, 2L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - threshold with two extra signatures and keys - full charging with extras")
        Stream<DynamicTest> cryptoCreateThresholdWithTwoExtraSigAndKeys() {
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .memo("Test")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(3L, 4L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - key list extra signatures and keys - full charging with extras")
        Stream<DynamicTest> cryptoCreateKeyListExtraSigAndKeys() {
            return hapiTest(
                    newKeyNamed("firstKey"),
                    newKeyNamed("secondKey"),
                    newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(2L, 2L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - with hook creation details - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithIncludedSigAndHook() {
            return hapiTest(
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .withHooks(accountAllowanceHook(1L, HOOK_CONTRACT))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1L, 0L, 1L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - with included hook, signature and key - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithIncludedHookSigAndKey() {
            return hapiTest(
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    cryptoCreate("testAccount")
                            .withHooks(accountAllowanceHook(1L, HOOK_CONTRACT))
                            .key(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1L, 1L, 1L), 0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate - with extra hooks, signatures and keys - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithExtraHookSigAndKey() {
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));
            return hapiTest(
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .memo("Test")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .withHooks(accountAllowanceHook(2L, HOOK_CONTRACT), accountAllowanceHook(3L, HOOK_CONTRACT))
                            .payingWith(PAYER)
                            .signedBy(PAYER_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(2L, 2L, 2L), 0.0001));
        }
    }

    @Nested
    @DisplayName("CryptoCreate Simple Fees Negative and Corner Test Cases")
    class CryptoCreateSimpleFeesNegativeAndCornerTestCases {
        @HapiTest
        @DisplayName("CryptoCreate - threshold with extra signatures and keys - invalid signature")
        Stream<DynamicTest> cryptoCreateThresholdWithExtraSigAndKeysInvalidSignature() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();

            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, OFF));
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn")
                            .hasPrecheck(INVALID_SIGNATURE),

                    // assert no txn record is created
                    getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                    // Save balances and assert changes
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                    }));
        }

        @HapiTest
        @DisplayName("CryptoCreate - threshold with two extra signatures and keys - invalid signature")
        Stream<DynamicTest> cryptoCreateThresholdWithTwoExtraSigAndKeysInvalidSignature() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();

            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

            // Create an invalid signature with both simple keys signing
            SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, invalidSig))
                            .balance(ONE_HUNDRED_HBARS),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, invalidSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn")
                            .hasPrecheck(INVALID_SIGNATURE),

                    // assert no txn record is created
                    getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                    // Save balances and assert changes
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                    }));
        }

        @HapiTest
        @DisplayName("CryptoCreate - key list extra signatures and keys - invalid signature")
        Stream<DynamicTest> cryptoCreateKeyListExtraSigAndKeysInvalidSignature() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            return hapiTest(
                    newKeyNamed("firstKey"),
                    newKeyNamed("secondKey"),
                    newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .payingWith(PAYER)
                            .signedBy("firstKey")
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn")
                            .hasPrecheck(INVALID_SIGNATURE),

                    // assert no txn record is created
                    getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                    // Save balances and assert changes
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                    }));
        }

        @HapiTest
        @DisplayName("CryptoCreate - additional not required signature is not charged - full charging without extras")
        Stream<DynamicTest> cryptoCreateOneAdditionalSigIsNotCharged() {
            return hapiTest(
                    newKeyNamed(ADMIN_KEY),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .payingWith(PAYER)
                            .key(ADMIN_KEY)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1L, 1L), 0.0001));
        }

        @HapiTest
        @DisplayName(
                "CryptoCreate - multiple additional not required signatures are not charged - full charging without extras")
        Stream<DynamicTest> cryptoCreateMultipleAdditionalSigIsNotCharged() {
            return hapiTest(
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed("extraKey1"),
                    newKeyNamed("extraKey2"),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .payingWith(PAYER)
                            .key(ADMIN_KEY)
                            .signedBy(PAYER, ADMIN_KEY, "extraKey1", "extraKey2")
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(1L, 1L), 0.0001));
        }

        @HapiTest
        @DisplayName(
                "CryptoCreate - threshold payer key with multiple additional not required signatures are not charged - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithThresholdKeyAndMultipleAdditionalSigIsNotCharged() {
            // Define a threshold submit key that requires two simple keys signatures
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

            // Create a valid signature with both simple keys signing
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    newKeyNamed("extraKey1"),
                    newKeyNamed("extraKey2"),
                    cryptoCreate(PAYER)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER_KEY, "extraKey1", "extraKey2")
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithin("cryptoCreateTxn", expectedCryptoCreateUsd(2L, 2L), 0.0001));
        }
    }
}
