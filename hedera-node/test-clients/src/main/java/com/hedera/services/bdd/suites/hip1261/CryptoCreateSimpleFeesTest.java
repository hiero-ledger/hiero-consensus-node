// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.node.app.workflows.prehandle.PreHandleWorkflow.log;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoCreateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsdWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static org.hiero.base.utility.CommonUtils.hex;
import static org.hiero.hapi.support.fees.Extra.HOOK_EXECUTION;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hyperledger.besu.crypto.Hash.keccak256;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class CryptoCreateSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String HOOK_CONTRACT = "TruePreHook";
    private static final String VALID_ALIAS_ED25519_KEY = "ValidAliasEd25519Key";
    private static final String DUPLICATE_TXN_ID = "duplicateTxnId";
    private static final String NEW_KEY = "newPayerKey";
    private static final String ECDSA_ALIAS_KEY = "ecdsaAliasKey";

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
        @DisplayName("CryptoCreate - base fees full charging without extras")
        Stream<DynamicTest> cryptoCreateWithIncludedSig() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("testAccount")
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .via("cryptoCreateTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "cryptoCreateTxn",
                            txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.0001));
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
                    validateChargedUsdWithinWithTxnSize(
                            "cryptoCreateTxn",
                            txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.0001));
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
                    validateChargedUsdWithinWithTxnSize(
                            "cryptoCreateTxn",
                            txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.0001));
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
                    validateChargedUsdWithinWithTxnSize(
                            "cryptoCreateTxn",
                            txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 4L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.0001));
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
                    validateChargedUsdWithinWithTxnSize(
                            "cryptoCreateTxn",
                            txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.0001));
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
                    validateChargedUsdWithinWithTxnSize(
                            "cryptoCreateTxn",
                            txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 2L,
                                    HOOK_EXECUTION, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.0001));
        }

        @HapiTest
        @DisplayName("CryptoCreate with alias - full charging without extras")
        Stream<DynamicTest> cryptoCreateWithAliasThresholdWithExtraSigAndKeys() {
            return hapiTest(
                    newKeyNamed(ECDSA_ALIAS_KEY).shape(KeyShape.SECP256K1),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    withOpContext((spec, opLog) -> {
                        // the ECDSA key from registry
                        var ecdsaKey = spec.registry().getKey(ECDSA_ALIAS_KEY);

                        // compressed secp2561 public key bytes
                        final byte[] compressedPubKey =
                                ecdsaKey.getECDSASecp256K1().toByteArray();
                        log.info("Compressed ECDSA key length: {}", compressedPubKey.length);

                        // decompress to uncompressed format
                        final var params = SECNamedCurves.getByName("secp256k1");
                        final var curve = params.getCurve();
                        final ECPoint point = curve.decodePoint(compressedPubKey);

                        // get uncompressed public key bytes
                        final byte[] uncompressed = point.getEncoded(false);
                        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
                            throw new IllegalStateException("Invalid uncompressed ECDSA public key");
                        }

                        // compute the EVM address from the uncompressed public key
                        final byte[] raw = Arrays.copyOfRange(uncompressed, 1, uncompressed.length);
                        final Bytes32 hash = keccak256(Bytes.wrap(raw));
                        final byte[] evmAddress = hash.slice(12, 20).toArray();
                        final ByteString alias = ByteString.copyFrom(evmAddress);

                        log.info("Uncompressed ECDSA length: {}", raw.length);
                        log.info("EVM alias (20 bytes) hex: 0x{}", hex(evmAddress));

                        final var txn = cryptoCreate("testAccount")
                                .key(ECDSA_ALIAS_KEY)
                                .alias(alias)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn");
                        allRunFor(spec, txn);
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            "cryptoCreateTxn",
                            txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.0001));
        }
    }

    @Nested
    @DisplayName("CryptoCreate Simple Fees Negative and Corner Test Cases")
    class CryptoCreateSimpleFeesNegativeAndCornerTestCases {
        @Nested
        @DisplayName("CryptoCreate Simple Fees Failures on Ingest")
        class CryptoCreateSimpleFeesFailuresOnIngest {
            @HapiTest
            @DisplayName("CryptoCreate - threshold with extra signatures and keys - invalid signature fails on ingest")
            Stream<DynamicTest> cryptoCreateThresholdWithExtraSigAndKeysInvalidSignatureFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create invalid signature with both simple keys signing
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
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
            @DisplayName(
                    "CryptoCreate - threshold with two extra signatures and keys - invalid signature fails on ingest")
            Stream<DynamicTest> cryptoCreateThresholdWithTwoExtraSigAndKeysInvalidSignatureFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

                // Create invalid signature with both simple keys signing
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
            @DisplayName("CryptoCreate - key list extra signatures and keys - invalid signature fails on ingest")
            Stream<DynamicTest> cryptoCreateKeyListExtraSigAndKeysInvalidSignatureFailsOnIngest() {
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
            @DisplayName("CryptoCreate - threshold with empty threshold key - fails on ingest")
            Stream<DynamicTest> cryptoCreateThresholdWithEmptyThresholdKeyFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(0, 0);

                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyNamed(NEW_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .key(NEW_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(KEY_REQUIRED),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - threshold with empty threshold nested key - fails on ingest")
            Stream<DynamicTest> cryptoCreateThresholdWithEmptyThresholdNestedKeyFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(3, listOf(0));

                return hapiTest(
                        newKeyNamed(PAYER_KEY),
                        newKeyNamed(NEW_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .key(NEW_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(KEY_REQUIRED),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate with insufficient txn fee fails on ingest")
            Stream<DynamicTest> cryptoCreateWithInsufficientTxnFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100000) // fee is too low
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - with insufficient payer balance fails on ingest")
            Stream<DynamicTest> cryptoCreateWithInsufficientPayerBalanceFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HBAR / 100000),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - with too long memo fails on ingest")
            Stream<DynamicTest> cryptoCreateWithTooLongMemoFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoCreate("testAccount")
                                .memo(LONG_MEMO)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(MEMO_TOO_LONG),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - expired transaction fails on ingest")
            Stream<DynamicTest> cryptoCreateExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredCreateTopic";
                final var oneHourPast = -3_600L; // 1 hour before
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .txnId(expiredTxnId)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(TRANSACTION_EXPIRED),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - with too far start time fails on ingest")
            Stream<DynamicTest> cryptoCreateWithTooFarStartTimeFailsOnIngest() {
                final var expiredTxnId = "expiredCreateTopic";
                final var oneHourPast = 3_600L; // 1 hour later
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .txnId(expiredTxnId)
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INVALID_TRANSACTION_START),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - with invalid duration time fails on ingest")
            Stream<DynamicTest> cryptoCreateWithInvalidDurationTimeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
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
                                .validDurationSecs(0) // invalid duration
                                .via("cryptoCreateTxn")
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate - duplicate txn fails on ingest")
            Stream<DynamicTest> cryptoCreateDuplicateTxnFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        // Successful first transaction
                        cryptoCreate("testAccount").fee(ONE_HBAR).via("cryptoCreateTxn"),
                        // Duplicate transaction
                        cryptoCreate("testAccountDuplicate")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .txnId("cryptoCreateTxn")
                                .via("cryptoCreateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate with ED25519 key and its key alias - fails on ingest")
            Stream<DynamicTest> cryptoCreateWithED25519AliasAndKeyAliasFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
                        newKeyNamed(VALID_ALIAS_ED25519_KEY).shape(KeyShape.ED25519),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        withOpContext((spec, opLog) -> {
                            var ed25519Key = spec.registry().getKey(VALID_ALIAS_ED25519_KEY);
                            final var txn = cryptoCreate("testAccount")
                                    .key(VALID_ALIAS_ED25519_KEY)
                                    .alias(ed25519Key.getEd25519())
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, VALID_ALIAS_ED25519_KEY)
                                    .fee(ONE_HBAR)
                                    .via("cryptoCreateTxn")
                                    .hasPrecheck(INVALID_ALIAS_KEY);
                            allRunFor(spec, txn);
                        }),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("CryptoCreate with ED25519 key and no key alias - fails on ingest")
            Stream<DynamicTest> cryptoCreateWithED25519AliasAndNoKeyFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                return hapiTest(
                        newKeyNamed(VALID_ALIAS_ED25519_KEY).shape(KeyShape.ED25519),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        withOpContext((spec, opLog) -> {
                            var ed25519Key = spec.registry().getKey(VALID_ALIAS_ED25519_KEY);
                            final var txn = cryptoCreate("testAccount")
                                    .alias(ed25519Key.getEd25519())
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, VALID_ALIAS_ED25519_KEY)
                                    .fee(ONE_HBAR)
                                    .via("cryptoCreateTxn")
                                    .hasPrecheck(INVALID_ALIAS_KEY);
                            allRunFor(spec, txn);
                        }),

                        // assert no txn record is created
                        getTxnRecord("cryptoCreateTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                        // Save balances and assert changes
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }
        }

        @Nested
        @DisplayName("CryptoCreate Simple Fees Failures on Pre-Handle")
        class CryptoCreateSimpleFailuresOnPreHandle {
            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("CryptoCreate with invalid signature fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithInvalidSignatureFailsOnPreHandleNetworkFeeChargedOnly() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4) // for skipping ingest
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedCryptoCreateNetworkFeeOnlyUsd(1));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(1L, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("CryptoCreate with insufficient txn fee fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithInsufficientTxnFeeFailsOnPreHandleNetworkFeeChargedOnly() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR / 100000) // fee is too low
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(INSUFFICIENT_TX_FEE),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedCryptoCreateNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("CryptoCreate with insufficient payer balance fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithInsufficientPayerBalanceFailsOnPreHandleNetworkFeeChargedOnly() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HBAR / 100000),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedCryptoCreateNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("CryptoCreate with too long memo fails on pre-handle and no signatures are charged")
            Stream<DynamicTest> cryptoCreateWithTooLongMemoFailsOnPreHandleNetworkFeeChargedOnlyNoSignaturesCharged() {
                final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        cryptoCreate("testAccount")
                                .memo(LONG_MEMO)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .via(INNER_ID)
                                .hasKnownStatus(MEMO_TOO_LONG),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedCryptoCreateNetworkFeeOnlyUsd(1));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(1L, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("CryptoCreate expired transaction fails on pre-handle")
            Stream<DynamicTest> cryptoCreateExpiredTransactionFailsOnPreHandleNetworkFeeChargedOnly() {
                final var oneHourBefore = -3_600L; // 1 hour before
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID)
                                .modifyValidStart(oneHourBefore)
                                .payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .txnId(INNER_ID)
                                .via(INNER_ID)
                                .hasKnownStatus(TRANSACTION_EXPIRED),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedCryptoCreateNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("CryptoCreate with too far start time fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithTooFarStartTimeFailsOnPreHandleNetworkFeeChargedOnly() {
                final var oneHourPast = 3_600L; // 1 hour later
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).modifyValidStart(oneHourPast).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .setNode(4)
                                .txnId(INNER_ID)
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_TRANSACTION_START),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedCryptoCreateNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                                0.01));
            }

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("CryptoCreate with invalid duration time fails on pre-handle")
            Stream<DynamicTest> cryptoCreateWithInvalidDurationTimeFailsOnPreHandleNetworkFeeChargedOnly() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "crypto-create-txn-inner-id";

                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                // Create a valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(INNER_ID).payerId(PAYER),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                        getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                        cryptoCreate("testAccount")
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .validDurationSecs(0) // invalid duration
                                .setNode(4)
                                .txnId(INNER_ID)
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_TRANSACTION_DURATION),

                        // Save balances after and assert payer was not charged
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                            log.info("Node balance change: {}", nodeDelta);
                            log.info("Recorded fee: {}", expectedCryptoCreateNetworkFeeOnlyUsd(2));
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                                0.01));
            }
        }

        @Nested
        @Tag(ONLY_SUBPROCESS)
        @DisplayName("CryptoCreate Simple Fees Failures on Handle")
        class CryptoCreateSimpleFeesFailuresOnHandle {
            @LeakyHapiTest
            @DisplayName("CryptoCreate with duplicate transaction fails on handle")
            Stream<DynamicTest> cryptoCreateWithDuplicateTransactionFailsOnHandlePayerChargedFullFee() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),

                        // Save balances before
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        getAccountBalance("3").exposingBalanceTo(initialNodeBalance::set),

                        // Register a TxnId for the inner txn
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),

                        // Submit duplicate transactions
                        cryptoCreate("testAccount")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .balance(0L)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via("cryptoCreateTxn")
                                .logged(),
                        cryptoCreate("testAccount")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HBAR)
                                .txnId(DUPLICATE_TXN_ID)
                                .balance(0L)
                                .setNode(3)
                                .via("cryptoCreateDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),

                        // Save balances after and assert node was not charged
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("3").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            long payerDelta = initialBalance.get() - afterBalance.get();
                            log.info("Payer balance change: {}", payerDelta);
                            log.info("Recorded fee: {}", expectedCryptoCreateFullFeeUsd(1, 1));
                            assertEquals(initialNodeBalance.get(), afterNodeBalance.get());
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsdWithTxnSize(
                                "cryptoCreateTxn",
                                initialBalance,
                                afterBalance,
                                txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.0001));
            }
        }

        @Nested
        @DisplayName("Corner Cases for CryptoCreate Simple Fees")
        class CornerCasesForCryptoCreateSimpleFees {
            @HapiTest
            @DisplayName(
                    "CryptoCreate - additional not required signature is not charged - full charging without extras")
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
                        validateChargedUsdWithinWithTxnSize(
                                "cryptoCreateTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.0001));
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
                        validateChargedUsdWithinWithTxnSize(
                                "cryptoCreateTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.0001));
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
                        validateChargedUsdWithinWithTxnSize(
                                "cryptoCreateTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        KEYS, 2L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.0001));
            }
        }
    }
}
