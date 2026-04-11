// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static org.hiero.hapi.support.fees.Extra.HOOK_EXECUTION;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@OrderedInIsolation
@HapiTestLifecycle
public class CryptoCreateWithHooksSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true", "hooks.hooksEnabled", "true"));
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
                validateChargedUsdWithinWithTxnSize(
                        "cryptoCreateTxn",
                        txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                SIGNATURES, 1L,
                                HOOK_EXECUTION, 1L,
                                PROCESSING_BYTES, (long) txnSize)),
                        0.0001));
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
                validateChargedUsdWithinWithTxnSize(
                        "cryptoCreateTxn",
                        txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                SIGNATURES, 1L,
                                KEYS, 1L,
                                HOOK_EXECUTION, 1L,
                                PROCESSING_BYTES, (long) txnSize)),
                        0.0001));
    }

    @HapiTest
    @DisplayName("CryptoCreate - with extra hooks, signatures and keys - full charging without extras")
    Stream<DynamicTest> cryptoCreateWithExtraHookSigAndKey() {
        final KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
        final SigControl validSig = keyShape.signedWith(sigs(ON, ON));
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
                validateChargedUsdWithinWithTxnSize(
                        "cryptoCreateTxn",
                        txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                SIGNATURES, 2L,
                                KEYS, 2L,
                                HOOK_EXECUTION, 2L,
                                PROCESSING_BYTES, (long) txnSize)),
                        0.0001));
    }
}
