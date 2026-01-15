// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.SECP256K1;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Kitchen sink test suite that submits all transaction types with varying parameters
 * to test all combinations of "extras" from simpleFeesSchedules.json.
 *
 * <p>Each service has its own test method for easier debugging and isolation.
 * The summary output shows the emphasis with specific extra values for each transaction.
 *
 * <p>Extras tested per transaction type:
 * <ul>
 *   <li>CryptoCreate: KEYS (1-5), SIGNATURES (1-5)</li>
 *   <li>CryptoUpdate: KEYS (1-5), SIGNATURES (1-5)</li>
 *   <li>CryptoTransfer: ACCOUNTS (2-5), FUNGIBLE_TOKENS (0-3), NON_FUNGIBLE_TOKENS (0-2)</li>
 *   <li>CryptoApproveAllowance: ALLOWANCES (1-3)</li>
 *   <li>TokenCreate: KEYS (0-5), TOKEN_CREATE_WITH_CUSTOM_FEE (0-3)</li>
 *   <li>TokenMint: TOKEN_MINT_NFT (0-5)</li>
 *   <li>ConsensusCreateTopic: KEYS (0-2)</li>
 *   <li>ConsensusSubmitMessage: BYTES (10-2000)</li>
 *   <li>FileCreate/Update: KEYS (1-3), BYTES (100-5000)</li>
 *   <li>ScheduleCreate: KEYS (0-2), SCHEDULE_CREATE_CONTRACT_CALL_BASE (0-1)</li>
 *   <li>Contract: GAS (50k-500k)</li>
 * </ul>
 */
@Tag(SIMPLE_FEES)
@Tag(ONLY_EMBEDDED)
@HapiTestLifecycle
public class KitchenSinkFeeComparisonSuite {
    private static final Logger LOG = LogManager.getLogger(KitchenSinkFeeComparisonSuite.class);

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "lazyCreation.enabled", "true",
                "cryptoCreateWithAliasAndEvmAddress.enabled", "true"));
    }

    // Key names for different complexity levels
    private static final String SIMPLE_KEY = "simpleKey";
    private static final String LIST_KEY_2 = "listKey2";
    private static final String LIST_KEY_3 = "listKey3";
    private static final String LIST_KEY_5 = "listKey5";
    private static final String THRESH_KEY_2_OF_3 = "threshKey2of3";
    private static final String THRESH_KEY_3_OF_5 = "threshKey3of5";
    private static final String COMPLEX_KEY = "complexKey";
    private static final String ECDSA_KEY = "ecdsaKey";

    // Payer names
    private static final String PAYER = "payer";
    private static final String PAYER_COMPLEX = "payerComplex";
    private static final String TREASURY = "treasury";
    private static final String RECEIVER = "receiver";
    private static final String RECEIVER2 = "receiver2";
    private static final String RECEIVER3 = "receiver3";
    private static final String FEE_COLLECTOR = "feeCollector";
    private static final String BATCH_OPERATOR = "batchOperator";

    // Contract name
    private static final String STORAGE_CONTRACT = "Storage";

    // ==================== RECORD CLASS FOR FEE ENTRIES ====================

    private record FeeEntry(String txnName, String emphasis, long fee) {}

    // ==================== CRYPTO SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Crypto Service - extras combinations (KEYS, SIGNATURES, ACCOUNTS, ALLOWANCES)")
    final Stream<DynamicTest> cryptoServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(cryptoTransactionsWithExtras("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(cryptoTransactionsWithExtras("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("CRYPTO SERVICE", legacyFees, simpleFees));
    }

    // ==================== TOKEN SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Token Service - extras (KEYS, TOKEN_CREATE_WITH_CUSTOM_FEE, TOKEN_MINT_NFT, NFT_SERIALS)")
    final Stream<DynamicTest> tokenServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(tokenTransactionsWithExtras("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(tokenTransactionsWithExtras("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("TOKEN SERVICE", legacyFees, simpleFees));
    }

    // ==================== TOPIC/CONSENSUS SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Topic/Consensus Service - extras (KEYS, BYTES)")
    final Stream<DynamicTest> topicServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(topicTransactionsWithExtras("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(topicTransactionsWithExtras("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("TOPIC/CONSENSUS SERVICE", legacyFees, simpleFees));
    }

    // ==================== FILE SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("File Service - extras (KEYS, BYTES)")
    final Stream<DynamicTest> fileServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(fileTransactionsWithExtras("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(fileTransactionsWithExtras("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("FILE SERVICE", legacyFees, simpleFees));
    }

    // ==================== SCHEDULE SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Schedule Service - extras (KEYS)")
    final Stream<DynamicTest> scheduleServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(scheduleTransactionsWithExtras("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(scheduleTransactionsWithExtras("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("SCHEDULE SERVICE", legacyFees, simpleFees));
    }

    // ==================== CONTRACT SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Contract Service - extras (GAS)")
    final Stream<DynamicTest> contractServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                uploadInitCode(STORAGE_CONTRACT),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(contractTransactionsWithExtras("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(contractTransactionsWithExtras("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("CONTRACT SERVICE", legacyFees, simpleFees));
    }

    // ==================== BATCH SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Batch Service - fee comparison")
    final Stream<DynamicTest> batchServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(batchTransactionsWithExtras("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(batchTransactionsWithExtras("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("BATCH SERVICE", legacyFees, simpleFees));
    }

    // ==================== KEY CREATION ====================

    private static SpecOperation createAllKeys() {
        return blockingOrder(
                newKeyNamed(SIMPLE_KEY).shape(ED25519),
                newKeyNamed(LIST_KEY_2).shape(listOf(2)),
                newKeyNamed(LIST_KEY_3).shape(listOf(3)),
                newKeyNamed(LIST_KEY_5).shape(listOf(5)),
                newKeyNamed(THRESH_KEY_2_OF_3).shape(threshOf(2, 3)),
                newKeyNamed(THRESH_KEY_3_OF_5).shape(threshOf(3, 5)),
                newKeyNamed(COMPLEX_KEY).shape(threshOf(2, listOf(2), KeyShape.SIMPLE, threshOf(1, 2))),
                newKeyNamed(ECDSA_KEY).shape(SECP256K1));
    }

    // ==================== BASE ACCOUNT CREATION ====================
    // Payers with different signature requirements for testing SIGNATURES extra
    private static final String PAYER_SIG1 = "payerSig1"; // 1 signature (ED25519)
    private static final String PAYER_SIG2 = "payerSig2"; // 2 signatures (listOf(2))
    private static final String PAYER_SIG3 = "payerSig3"; // 3 signatures (listOf(3))
    private static final String PAYER_SIG5 = "payerSig5"; // 5 signatures (listOf(5))

    private static SpecOperation createBaseAccounts() {
        return blockingOrder(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SIMPLE_KEY),
                cryptoCreate(PAYER_COMPLEX).balance(ONE_MILLION_HBARS).key(COMPLEX_KEY),
                // Payers with different signature counts for SIGNATURES extra testing
                cryptoCreate(PAYER_SIG1).balance(ONE_MILLION_HBARS).key(SIMPLE_KEY),
                cryptoCreate(PAYER_SIG2).balance(ONE_MILLION_HBARS).key(LIST_KEY_2),
                cryptoCreate(PAYER_SIG3).balance(ONE_MILLION_HBARS).key(LIST_KEY_3),
                cryptoCreate(PAYER_SIG5).balance(ONE_MILLION_HBARS).key(LIST_KEY_5),
                cryptoCreate(TREASURY).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER2).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER3).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FEE_COLLECTOR).balance(ONE_HUNDRED_HBARS));
    }

    // ==================== CRYPTO TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // CryptoCreate extras: KEYS (includedCount=1), HOOK_UPDATES (includedCount=0)
    // CryptoUpdate extras: KEYS (includedCount=1), HOOK_UPDATES (includedCount=0)
    // CryptoTransfer extras: ACCOUNTS (includedCount=2), FUNGIBLE_TOKENS (1), NON_FUNGIBLE_TOKENS (1)
    // CryptoApproveAllowance extras: ALLOWANCES (includedCount=1)
    // CryptoDeleteAllowance extras: ALLOWANCES (includedCount=1)
    // CryptoDelete extras: none

    private static SpecOperation[] cryptoTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ========== CryptoCreate: KEYS + SIGNATURES combinations ==========
        // KEYS=1, SIGS=1 (both included)
        ops.add(cryptoCreate(prefix + "AccK1S1")
                .key(SIMPLE_KEY)
                .balance(ONE_HBAR)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateK1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateK1S1", "KEYS=1, SIGS=1 (both included)", feeMap));

        // KEYS=1, SIGS=2 (+1 sig extra)
        ops.add(cryptoCreate(prefix + "AccK1S2")
                .key(SIMPLE_KEY)
                .balance(ONE_HBAR)
                .payingWith(PAYER_SIG2)
                .signedBy(PAYER_SIG2)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateK1S2"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateK1S2", "KEYS=1, SIGS=2 (+1 sig)", feeMap));

        // KEYS=2, SIGS=1 (+1 key extra)
        ops.add(cryptoCreate(prefix + "AccK2S1")
                .key(LIST_KEY_2)
                .balance(ONE_HBAR)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateK2S1"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateK2S1", "KEYS=2 (+1), SIGS=1", feeMap));

        // KEYS=3, SIGS=3 (+2 key, +2 sig extra)
        ops.add(cryptoCreate(prefix + "AccK3S3")
                .key(LIST_KEY_3)
                .balance(ONE_HBAR)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateK3S3"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateK3S3", "KEYS=3 (+2), SIGS=3 (+2)", feeMap));

        // KEYS=5, SIGS=5 (+4 key, +4 sig extra)
        ops.add(cryptoCreate(prefix + "AccK5S5")
                .key(LIST_KEY_5)
                .balance(ONE_HBAR)
                .payingWith(PAYER_SIG5)
                .signedBy(PAYER_SIG5)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateK5S5"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateK5S5", "KEYS=5 (+4), SIGS=5 (+4)", feeMap));

        // ========== CryptoUpdate: KEYS + SIGNATURES combinations ==========
        // KEYS=1, SIGS=1 (both included)
        ops.add(cryptoUpdate(prefix + "AccK1S1")
                .memo("updated")
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateK1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoUpdateK1S1", "KEYS=1, SIGS=2 (+1 sig)", feeMap));

        // KEYS=3, SIGS=3 (+2 key, +2 sig extra) - update with new key
        ops.add(cryptoUpdate(prefix + "AccK2S1")
                .key(LIST_KEY_3)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3, LIST_KEY_2, LIST_KEY_3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateK3S3"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoUpdateK3S3", "KEYS=3 (+2), SIGS=8 (+7)", feeMap));

        // ========== CryptoTransfer: ACCOUNTS + SIGNATURES combinations ==========
        // ACCOUNTS=2, SIGS=1 (both included)
        ops.add(cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER_SIG1, RECEIVER))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferA2S1"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferA2S1", "ACCTS=2, SIGS=1 (included)", feeMap));

        // ACCOUNTS=2, SIGS=2 (+1 sig extra)
        ops.add(cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER_SIG2, RECEIVER))
                .payingWith(PAYER_SIG2)
                .signedBy(PAYER_SIG2)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferA2S2"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferA2S2", "ACCTS=2, SIGS=2 (+1 sig)", feeMap));

        // ACCOUNTS=3, SIGS=1 (+1 acct extra)
        ops.add(cryptoTransfer(
                        movingHbar(ONE_HBAR).between(PAYER_SIG1, RECEIVER),
                        movingHbar(ONE_HBAR).between(PAYER_SIG1, RECEIVER2))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferA3S1"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferA3S1", "ACCTS=3 (+1), SIGS=1", feeMap));

        // ACCOUNTS=4, SIGS=3 (+2 acct, +2 sig extra)
        ops.add(cryptoTransfer(
                        movingHbar(ONE_HBAR).between(PAYER_SIG3, RECEIVER),
                        movingHbar(ONE_HBAR).between(PAYER_SIG3, RECEIVER2),
                        movingHbar(ONE_HBAR).between(PAYER_SIG3, RECEIVER3))
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferA4S3"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferA4S3", "ACCTS=4 (+2), SIGS=3 (+2)", feeMap));

        // Auto-creation via ECDSA alias (hollow account)
        ops.add(newKeyNamed(prefix + "AutoKey").shape(SECP256K1));
        ops.add(cryptoTransfer(tinyBarsFromAccountToAlias(PAYER_SIG1, prefix + "AutoKey", ONE_HBAR))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferAutoCreate"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferAutoCreate", "ACCTS=2, SIGS=1, hollow", feeMap));

        // ========== CryptoApproveAllowance: ALLOWANCES + SIGNATURES combinations ==========
        // ALLOWANCES=1, SIGS=1 (both included)
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccK1S1", RECEIVER, ONE_HBAR)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ApproveA1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "ApproveA1S1", "ALLOW=1, SIGS=2 (+1 sig)", feeMap));

        // ALLOWANCES=2, SIGS=3 (+1 allow, +2 sig extra)
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccK3S3", RECEIVER, ONE_HBAR)
                .addCryptoAllowance(prefix + "AccK3S3", RECEIVER2, ONE_HBAR)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3, LIST_KEY_3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ApproveA2S3"));
        ops.add(captureFeeWithEmphasis(prefix + "ApproveA2S3", "ALLOW=2 (+1), SIGS=6 (+5)", feeMap));

        // ALLOWANCES=3, SIGS=5 (+2 allow, +4 sig extra)
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccK5S5", RECEIVER, ONE_HBAR)
                .addCryptoAllowance(prefix + "AccK5S5", RECEIVER2, ONE_HBAR)
                .addCryptoAllowance(prefix + "AccK5S5", RECEIVER3, ONE_HBAR)
                .payingWith(PAYER_SIG5)
                .signedBy(PAYER_SIG5, LIST_KEY_5)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ApproveA3S5"));
        ops.add(captureFeeWithEmphasis(prefix + "ApproveA3S5", "ALLOW=3 (+2), SIGS=10 (+9)", feeMap));

        // ========== CryptoDelete: SIGNATURES combinations ==========
        ops.add(cryptoCreate(prefix + "ToDelete1").balance(0L).payingWith(PAYER_SIG1).fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoDelete(prefix + "ToDelete1")
                .transfer(PAYER_SIG1)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoDeleteS1"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoDeleteS1", "SIGS=1 (included)", feeMap));

        ops.add(cryptoCreate(prefix + "ToDelete3")
                .balance(0L)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoDelete(prefix + "ToDelete3")
                .transfer(PAYER_SIG3)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoDeleteS3"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoDeleteS3", "SIGS=3 (+2)", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== TOKEN TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // TokenCreate extras: KEYS (includedCount=1), TOKEN_CREATE_WITH_CUSTOM_FEE (includedCount=0)
    // TokenMint extras: TOKEN_MINT_NFT (includedCount=0)
    // TokenUpdate extras: KEYS (includedCount=1)
    // Transfer extras: FUNGIBLE_TOKENS (1), NON_FUNGIBLE_TOKENS (1)

    private static SpecOperation[] tokenTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Setup accounts
        ops.add(cryptoCreate(prefix + "Acc1").key(SIMPLE_KEY).balance(ONE_HBAR).payingWith(GENESIS));
        ops.add(cryptoCreate(prefix + "Acc2").key(LIST_KEY_2).balance(ONE_HBAR).payingWith(GENESIS));

        // ========== TokenCreate: KEYS + SIGNATURES + CUSTOM_FEE combinations ==========
        // KEYS=0, SIGS=1 (no admin key)
        ops.add(tokenCreate(prefix + "FTK0S1")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateK0S1"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateK0S1", "KEYS=0, SIGS=2 (+1)", feeMap));

        // KEYS=1, SIGS=1 (both included)
        ops.add(tokenCreate(prefix + "FTK1S1")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateK1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateK1S1", "KEYS=1, SIGS=2 (+1)", feeMap));

        // KEYS=3, SIGS=3 (+2 key, +2 sig extra)
        ops.add(tokenCreate(prefix + "FTK3S3")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .supplyKey(SIMPLE_KEY)
                .freezeKey(SIMPLE_KEY)
                .freezeDefault(false)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateK3S3"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateK3S3", "KEYS=3 (+2), SIGS=4 (+3)", feeMap));

        // KEYS=5, SIGS=5 (+4 key, +4 sig extra)
        ops.add(tokenCreate(prefix + "FTK5S5")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .supplyKey(SIMPLE_KEY)
                .freezeKey(SIMPLE_KEY)
                .pauseKey(SIMPLE_KEY)
                .wipeKey(SIMPLE_KEY)
                .freezeDefault(false)
                .payingWith(PAYER_SIG5)
                .signedBy(PAYER_SIG5, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateK5S5"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateK5S5", "KEYS=5 (+4), SIGS=6 (+5)", feeMap));

        // TOKEN_CREATE_WITH_CUSTOM_FEE=1, SIGS=1
        ops.add(tokenCreate(prefix + "FTCust1S1")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateCust1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateCust1S1", "CUSTOM_FEE=1, SIGS=2 (+1)", feeMap));

        // TOKEN_CREATE_WITH_CUSTOM_FEE=2, SIGS=3
        ops.add(tokenCreate(prefix + "FTCust2S3")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                .withCustom(fractionalFee(1L, 100L, 1L, OptionalLong.of(10L), TREASURY))
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateCust2S3"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateCust2S3", "CUSTOM_FEE=2, SIGS=4 (+3)", feeMap));

        // ========== NFT with TOKEN_MINT_NFT + SIGNATURES ==========
        ops.add(tokenCreate(prefix + "NFT")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateNFT"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateNFT", "KEYS=1, NFT, SIGS=2 (+1)", feeMap));

        // NFT with royalty custom fee
        ops.add(tokenCreate(prefix + "NFTRoyalty")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .withCustom(royaltyFeeNoFallback(1, 10, FEE_COLLECTOR))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateNFTRoyalty"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateNFTRoyalty", "CUSTOM_FEE=1 (royalty), SIGS=2", feeMap));

        // ========== TokenMint: TOKEN_MINT_NFT + SIGNATURES ==========
        // Fungible mint, SIGS=1
        ops.add(mintToken(prefix + "FTK5S5", 10_000L)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "MintFTS1"));
        ops.add(captureFeeWithEmphasis(prefix + "MintFTS1", "MINT_NFT=0 (FT), SIGS=2 (+1)", feeMap));

        // Fungible mint, SIGS=3
        ops.add(mintToken(prefix + "FTK5S5", 5_000L)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "MintFTS3"));
        ops.add(captureFeeWithEmphasis(prefix + "MintFTS3", "MINT_NFT=0 (FT), SIGS=4 (+3)", feeMap));

        // NFT mint 1 serial, SIGS=1
        ops.add(mintToken(prefix + "NFT", List.of(ByteString.copyFromUtf8("N1")))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "MintNFT1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "MintNFT1S1", "MINT_NFT=1, SIGS=2 (+1)", feeMap));

        // NFT mint 3 serials, SIGS=3
        ops.add(mintToken(prefix + "NFT", List.of(
                        ByteString.copyFromUtf8("N2"),
                        ByteString.copyFromUtf8("N3"),
                        ByteString.copyFromUtf8("N4")))
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "MintNFT3S3"));
        ops.add(captureFeeWithEmphasis(prefix + "MintNFT3S3", "MINT_NFT=3, SIGS=4 (+3)", feeMap));

        // NFT mint 5 serials, SIGS=5
        ops.add(mintToken(prefix + "NFT", List.of(
                        ByteString.copyFromUtf8("N5"),
                        ByteString.copyFromUtf8("N6"),
                        ByteString.copyFromUtf8("N7"),
                        ByteString.copyFromUtf8("N8"),
                        ByteString.copyFromUtf8("N9")))
                .payingWith(PAYER_SIG5)
                .signedBy(PAYER_SIG5, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "MintNFT5S5"));
        ops.add(captureFeeWithEmphasis(prefix + "MintNFT5S5", "MINT_NFT=5, SIGS=6 (+5)", feeMap));

        // ========== Token transfers: FUNGIBLE_TOKENS + NON_FUNGIBLE_TOKENS + SIGNATURES ==========
        ops.add(tokenAssociate(prefix + "Acc1", prefix + "FTK0S1", prefix + "FTK1S1", prefix + "FTK3S3", prefix + "FTK5S5")
                .payingWith(PAYER_SIG1).signedBy(PAYER_SIG1, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS));
        ops.add(tokenAssociate(RECEIVER, prefix + "NFT").payingWith(PAYER_SIG1).signedBy(PAYER_SIG1).fee(ONE_HUNDRED_HBARS));

        // FUNGIBLE_TOKENS=1, SIGS=1
        ops.add(cryptoTransfer(moving(100L, prefix + "FTK0S1").between(TREASURY, prefix + "Acc1"))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferFT1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferFT1S1", "FT=1, SIGS=2 (+1)", feeMap));

        // FUNGIBLE_TOKENS=2, SIGS=3
        ops.add(cryptoTransfer(
                        moving(100L, prefix + "FTK0S1").between(TREASURY, prefix + "Acc1"),
                        moving(100L, prefix + "FTK1S1").between(TREASURY, prefix + "Acc1"))
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferFT2S3"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferFT2S3", "FT=2 (+1), SIGS=4 (+3)", feeMap));

        // NON_FUNGIBLE_TOKENS=1, SIGS=1
        ops.add(cryptoTransfer(movingUnique(prefix + "NFT", 1L).between(TREASURY, RECEIVER))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferNFT1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferNFT1S1", "NFT=1, SIGS=2 (+1)", feeMap));

        // ========== Other token operations with SIGNATURES ==========
        ops.add(tokenFreeze(prefix + "FTK5S5", prefix + "Acc1")
                .payingWith(PAYER_SIG1).signedBy(PAYER_SIG1, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "FreezeS1"));
        ops.add(captureFeeWithEmphasis(prefix + "FreezeS1", "SIGS=2 (+1)", feeMap));

        ops.add(tokenUnfreeze(prefix + "FTK5S5", prefix + "Acc1")
                .payingWith(PAYER_SIG3).signedBy(PAYER_SIG3, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "UnfreezeS3"));
        ops.add(captureFeeWithEmphasis(prefix + "UnfreezeS3", "SIGS=4 (+3)", feeMap));

        ops.add(tokenPause(prefix + "FTK5S5")
                .payingWith(PAYER_SIG1).signedBy(PAYER_SIG1, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "PauseS1"));
        ops.add(captureFeeWithEmphasis(prefix + "PauseS1", "SIGS=2 (+1)", feeMap));

        ops.add(tokenUnpause(prefix + "FTK5S5")
                .payingWith(PAYER_SIG3).signedBy(PAYER_SIG3, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "UnpauseS3"));
        ops.add(captureFeeWithEmphasis(prefix + "UnpauseS3", "SIGS=4 (+3)", feeMap));

        ops.add(burnToken(prefix + "FTK5S5", 1_000L)
                .payingWith(PAYER_SIG1).signedBy(PAYER_SIG1, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "BurnFTS1"));
        ops.add(captureFeeWithEmphasis(prefix + "BurnFTS1", "FT burn, SIGS=2 (+1)", feeMap));

        ops.add(burnToken(prefix + "NFT", List.of(4L))
                .payingWith(PAYER_SIG3).signedBy(PAYER_SIG3, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "BurnNFTS3"));
        ops.add(captureFeeWithEmphasis(prefix + "BurnNFTS3", "NFT burn, SIGS=4 (+3)", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== TOPIC TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // ConsensusCreateTopic extras: KEYS (includedCount=0), CONSENSUS_CREATE_TOPIC_WITH_CUSTOM_FEE (0)
    // ConsensusUpdateTopic extras: KEYS (includedCount=1)
    // ConsensusSubmitMessage extras: BYTES (includedCount=100), CONSENSUS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE (0)
    // ConsensusDeleteTopic extras: none

    private static SpecOperation[] topicTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ========== ConsensusCreateTopic: KEYS + SIGNATURES combinations ==========
        // KEYS=0, SIGS=1 (no keys)
        ops.add(createTopic(prefix + "TopicK0S1")
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateK0S1"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicCreateK0S1", "KEYS=0, SIGS=1 (included)", feeMap));

        // KEYS=0, SIGS=3 (+2 sig extra)
        ops.add(createTopic(prefix + "TopicK0S3")
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateK0S3"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicCreateK0S3", "KEYS=0, SIGS=3 (+2)", feeMap));

        // KEYS=1, SIGS=1 (+1 key extra)
        ops.add(createTopic(prefix + "TopicK1S1")
                .adminKeyName(SIMPLE_KEY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateK1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicCreateK1S1", "KEYS=1 (+1), SIGS=1", feeMap));

        // KEYS=2, SIGS=3 (+2 key, +2 sig extra)
        ops.add(createTopic(prefix + "TopicK2S3")
                .adminKeyName(SIMPLE_KEY)
                .submitKeyName(SIMPLE_KEY)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateK2S3"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicCreateK2S3", "KEYS=2 (+2), SIGS=3 (+2)", feeMap));

        // ========== ConsensusSubmitMessage: BYTES + SIGNATURES combinations ==========
        // BYTES=50, SIGS=1 (under included)
        ops.add(submitMessageTo(prefix + "TopicK0S1")
                .message("x".repeat(50))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitB50S1"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitB50S1", "BYTES=50, SIGS=1 (included)", feeMap));

        // BYTES=100, SIGS=3 (+2 sig extra)
        ops.add(submitMessageTo(prefix + "TopicK0S1")
                .message("x".repeat(100))
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitB100S3"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitB100S3", "BYTES=100, SIGS=3 (+2)", feeMap));

        // BYTES=500, SIGS=1 (+400 bytes extra)
        ops.add(submitMessageTo(prefix + "TopicK0S1")
                .message("x".repeat(500))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitB500S1"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitB500S1", "BYTES=500 (+400), SIGS=1", feeMap));

        // BYTES=1000, SIGS=5 (+900 bytes, +4 sig extra)
        ops.add(submitMessageTo(prefix + "TopicK0S1")
                .message("x".repeat(1000))
                .payingWith(PAYER_SIG5)
                .signedBy(PAYER_SIG5)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitB1000S5"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitB1000S5", "BYTES=1000 (+900), SIGS=5 (+4)", feeMap));

        // ========== ConsensusUpdateTopic: KEYS + SIGNATURES combinations ==========
        // KEYS=1, SIGS=1 (included)
        ops.add(updateTopic(prefix + "TopicK1S1")
                .topicMemo("updated1")
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicUpdateK1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicUpdateK1S1", "KEYS=1, SIGS=2 (+1)", feeMap));

        // KEYS=1, SIGS=3 (+2 sig extra)
        ops.add(updateTopic(prefix + "TopicK1S1")
                .topicMemo("updated2")
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicUpdateK1S3"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicUpdateK1S3", "KEYS=1, SIGS=4 (+3)", feeMap));

        // ========== ConsensusDeleteTopic: SIGNATURES combinations ==========
        // SIGS=1
        ops.add(deleteTopic(prefix + "TopicK1S1")
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicDeleteS1"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicDeleteS1", "SIGS=2 (+1)", feeMap));

        // SIGS=3
        ops.add(deleteTopic(prefix + "TopicK2S3")
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicDeleteS3"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicDeleteS3", "SIGS=4 (+3)", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== FILE TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // FileCreate extras: KEYS (includedCount=1), BYTES (includedCount=1000)
    // FileUpdate extras: KEYS (includedCount=1), BYTES (includedCount=1000)
    // FileAppend extras: BYTES (includedCount=1000)
    // FileDelete extras: none

    private static SpecOperation[] fileTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ========== FileCreate: KEYS + BYTES + SIGNATURES combinations ==========
        // KEYS=1, BYTES=100, SIGS=1 (under included)
        ops.add(fileCreate(prefix + "FileB100S1")
                .contents("x".repeat(100))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateB100S1"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateB100S1", "KEYS=1, BYTES=100, SIGS=1", feeMap));

        // KEYS=1, BYTES=1000, SIGS=3 (+2 sig extra)
        ops.add(fileCreate(prefix + "FileB1000S3")
                .contents("x".repeat(1000))
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateB1000S3"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateB1000S3", "KEYS=1, BYTES=1000, SIGS=3 (+2)", feeMap));

        // KEYS=1, BYTES=2000, SIGS=1 (+1000 bytes extra)
        ops.add(fileCreate(prefix + "FileB2000S1")
                .contents("x".repeat(2000))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateB2000S1"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateB2000S1", "KEYS=1, BYTES=2000 (+1000), SIGS=1", feeMap));

        // KEYS=1, BYTES=4000, SIGS=5 (+3000 bytes, +4 sig extra)
        ops.add(fileCreate(prefix + "FileB4000S5")
                .contents("x".repeat(4000))
                .payingWith(PAYER_SIG5)
                .signedBy(PAYER_SIG5)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateB4000S5"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateB4000S5", "KEYS=1, BYTES=4000 (+3000), SIGS=5 (+4)", feeMap));

        // ========== FileUpdate: KEYS + BYTES + SIGNATURES combinations ==========
        // KEYS=1, BYTES=500, SIGS=1
        ops.add(fileUpdate(prefix + "FileB100S1")
                .contents("x".repeat(500))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileUpdateB500S1"));
        ops.add(captureFeeWithEmphasis(prefix + "FileUpdateB500S1", "KEYS=1, BYTES=500, SIGS=1", feeMap));

        // KEYS=1, BYTES=3000, SIGS=3 (+2000 bytes, +2 sig extra)
        ops.add(fileUpdate(prefix + "FileB1000S3")
                .contents("x".repeat(3000))
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileUpdateB3000S3"));
        ops.add(captureFeeWithEmphasis(prefix + "FileUpdateB3000S3", "KEYS=1, BYTES=3000 (+2000), SIGS=3 (+2)", feeMap));

        // ========== FileAppend: BYTES + SIGNATURES combinations ==========
        // BYTES=500, SIGS=1
        ops.add(fileAppend(prefix + "FileB100S1")
                .content("y".repeat(500))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileAppendB500S1"));
        ops.add(captureFeeWithEmphasis(prefix + "FileAppendB500S1", "BYTES=500, SIGS=1", feeMap));

        // BYTES=2000, SIGS=3 (+1000 bytes, +2 sig extra)
        ops.add(fileAppend(prefix + "FileB100S1")
                .content("z".repeat(2000))
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileAppendB2000S3"));
        ops.add(captureFeeWithEmphasis(prefix + "FileAppendB2000S3", "BYTES=2000 (+1000), SIGS=3 (+2)", feeMap));

        // ========== FileDelete: SIGNATURES combinations ==========
        // SIGS=1
        ops.add(fileDelete(prefix + "FileB4000S5")
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileDeleteS1"));
        ops.add(captureFeeWithEmphasis(prefix + "FileDeleteS1", "SIGS=1 (included)", feeMap));

        // SIGS=3
        ops.add(fileDelete(prefix + "FileB2000S1")
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileDeleteS3"));
        ops.add(captureFeeWithEmphasis(prefix + "FileDeleteS3", "SIGS=3 (+2)", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== SCHEDULE TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // ScheduleCreate extras: KEYS (includedCount=1), SCHEDULE_CREATE_CONTRACT_CALL_BASE (0)
    // ScheduleSign extras: none
    // ScheduleDelete extras: none

    private static SpecOperation[] scheduleTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        final long amount = prefix.equals("simple") ? 1L : 2L;

        // ========== ScheduleCreate: KEYS + SIGNATURES combinations ==========
        // KEYS=0, SIGS=1 (no admin key)
        ops.add(scheduleCreate(
                        prefix + "SchedK0S1",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER_SIG1, amount)))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateK0S1"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleCreateK0S1", "KEYS=0, SIGS=1 (included)", feeMap));

        // KEYS=0, SIGS=3 (+2 sig extra)
        ops.add(scheduleCreate(
                        prefix + "SchedK0S3",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER_SIG3, amount + 1)))
                .payingWith(PAYER_SIG3)
                .signedBy(LIST_KEY_3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateK0S3"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleCreateK0S3", "KEYS=0, SIGS=3 (+2)", feeMap));

        // KEYS=1, SIGS=1 (included) - schedule for signing
        ops.add(scheduleCreate(
                        prefix + "SchedK1S1",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER_SIG1, amount + 2)))
                .adminKey(SIMPLE_KEY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateK1S1"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleCreateK1S1", "KEYS=1, SIGS=1 (included)", feeMap));

        // KEYS=2, SIGS=3 (+1 key, +2 sig extra) - schedule for deletion
        ops.add(scheduleCreate(
                        prefix + "SchedK2S3",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER_SIG3, amount + 3)))
                .adminKey(LIST_KEY_2)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateK2S3"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleCreateK2S3", "KEYS=2 (+1), SIGS=3 (+2)", feeMap));

        // ========== ScheduleSign: SIGNATURES combinations ==========
        // SIGS=1 - This will execute the schedule since RECEIVER signature completes it
        ops.add(scheduleSign(prefix + "SchedK1S1")
                .alsoSigningWith(RECEIVER)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, RECEIVER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleSignS1"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleSignS1", "SIGS=2 (+1)", feeMap));

        // SIGS=3
        ops.add(scheduleSign(prefix + "SchedK0S1")
                .alsoSigningWith(RECEIVER)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3, RECEIVER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleSignS3"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleSignS3", "SIGS=4 (+3)", feeMap));

        // ========== ScheduleDelete: SIGNATURES combinations ==========
        // SIGS=1 - Delete SchedK2S3 which hasn't been executed
        ops.add(scheduleDelete(prefix + "SchedK2S3")
                .signedBy(PAYER_SIG1, LIST_KEY_2)
                .payingWith(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleDeleteS1"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleDeleteS1", "SIGS=3 (+2)", feeMap));

        // SIGS=3 - Delete SchedK0S3 which hasn't been executed
        ops.add(scheduleDelete(prefix + "SchedK0S3")
                .signedBy(PAYER_SIG3)
                .payingWith(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .hasKnownStatus(com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_IS_IMMUTABLE)
                .via(prefix + "ScheduleDeleteS3Fail"));
        // Note: SchedK0S3 has no admin key so delete will fail - just testing signature count

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== CONTRACT TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // ContractCreate/Call extras: GAS (fee per unit)
    // ContractUpdate/Delete extras: none

    private static SpecOperation[] contractTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ========== ContractCreate: GAS + SIGNATURES combinations ==========
        // GAS=200000, SIGS=1
        ops.add(contractCreate(prefix + "Contract1")
                .bytecode(STORAGE_CONTRACT)
                .gas(200_000L)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreateS1"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractCreateS1", "GAS=200000, SIGS=1 (included)", feeMap));

        // GAS=200000, SIGS=3 (+2 sig extra)
        ops.add(contractCreate(prefix + "Contract2")
                .bytecode(STORAGE_CONTRACT)
                .adminKey(SIMPLE_KEY)
                .gas(200_000L)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreateS3"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractCreateS3", "GAS=200000, adminKey=1, SIGS=3 (+2)", feeMap));

        // ========== ContractCall: GAS + SIGNATURES combinations ==========
        final var storeAbi = getABIFor(FUNCTION, "store", STORAGE_CONTRACT);
        final var retrieveAbi = getABIFor(FUNCTION, "retrieve", STORAGE_CONTRACT);

        // GAS=50000, SIGS=1
        ops.add(contractCallWithFunctionAbi(prefix + "Contract1", storeAbi, BigInteger.valueOf(42))
                .gas(50_000L)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CallG50kS1"));
        ops.add(captureFeeWithEmphasis(prefix + "CallG50kS1", "GAS=50000, SIGS=1 (included)", feeMap));

        // GAS=100000, SIGS=3 (+2 sig extra)
        ops.add(contractCallWithFunctionAbi(prefix + "Contract1", storeAbi, BigInteger.valueOf(100))
                .gas(100_000L)
                .payingWith(PAYER_SIG3)
                .signedBy(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CallG100kS3"));
        ops.add(captureFeeWithEmphasis(prefix + "CallG100kS3", "GAS=100000, SIGS=3 (+2)", feeMap));

        // GAS=200000, SIGS=1
        ops.add(contractCallWithFunctionAbi(prefix + "Contract1", storeAbi, BigInteger.valueOf(200))
                .gas(200_000L)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CallG200kS1"));
        ops.add(captureFeeWithEmphasis(prefix + "CallG200kS1", "GAS=200000, SIGS=1", feeMap));

        // GAS=500000, SIGS=5 (+4 sig extra)
        ops.add(contractCallWithFunctionAbi(prefix + "Contract1", storeAbi, BigInteger.valueOf(500))
                .gas(500_000L)
                .payingWith(PAYER_SIG5)
                .signedBy(PAYER_SIG5)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CallG500kS5"));
        ops.add(captureFeeWithEmphasis(prefix + "CallG500kS5", "GAS=500000, SIGS=5 (+4)", feeMap));

        // Read-only call (retrieve), SIGS=1
        ops.add(contractCallWithFunctionAbi(prefix + "Contract1", retrieveAbi)
                .gas(50_000L)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CallRetrieveS1"));
        ops.add(captureFeeWithEmphasis(prefix + "CallRetrieveS1", "GAS=50000 (read), SIGS=1", feeMap));

        // ========== ContractUpdate: SIGNATURES combinations ==========
        // SIGS=1
        ops.add(contractUpdate(prefix + "Contract2")
                .newMemo("updated1")
                .signedBy(PAYER_SIG1, SIMPLE_KEY)
                .payingWith(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractUpdateS1"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractUpdateS1", "SIGS=2 (+1)", feeMap));

        // SIGS=3
        ops.add(contractUpdate(prefix + "Contract2")
                .newMemo("updated2")
                .signedBy(PAYER_SIG3, SIMPLE_KEY)
                .payingWith(PAYER_SIG3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractUpdateS3"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractUpdateS3", "SIGS=4 (+3)", feeMap));

        // ========== ContractDelete: SIGNATURES combinations ==========
        // SIGS=1
        ops.add(contractDelete(prefix + "Contract2")
                .transferAccount(PAYER_SIG1)
                .signedBy(PAYER_SIG1, SIMPLE_KEY)
                .payingWith(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractDeleteS1"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractDeleteS1", "SIGS=2 (+1)", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== BATCH TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // AtomicBatch extras: innerTxns count

    private static SpecOperation[] batchTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ========== AtomicBatch: innerTxns + SIGNATURES combinations ==========
        // innerTxns=2, SIGS=1
        ops.add(atomicBatch(
                        cryptoTransfer(movingHbar(1L).between(PAYER_SIG1, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(2L).between(PAYER_SIG1, RECEIVER))
                                .batchKey(BATCH_OPERATOR))
                .signedBy(BATCH_OPERATOR, PAYER_SIG1)
                .payingWith(BATCH_OPERATOR)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "Batch2S1"));
        ops.add(captureFeeWithEmphasis(prefix + "Batch2S1", "innerTxns=2, SIGS=2 (+1)", feeMap));

        // innerTxns=2, SIGS=3
        ops.add(atomicBatch(
                        cryptoTransfer(movingHbar(3L).between(PAYER_SIG3, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(4L).between(PAYER_SIG3, RECEIVER))
                                .batchKey(BATCH_OPERATOR))
                .signedBy(BATCH_OPERATOR, PAYER_SIG3)
                .payingWith(BATCH_OPERATOR)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "Batch2S3"));
        ops.add(captureFeeWithEmphasis(prefix + "Batch2S3", "innerTxns=2, SIGS=4 (+3)", feeMap));

        // innerTxns=3, SIGS=1
        ops.add(atomicBatch(
                        cryptoTransfer(movingHbar(5L).between(PAYER_SIG1, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(6L).between(PAYER_SIG1, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(7L).between(PAYER_SIG1, RECEIVER))
                                .batchKey(BATCH_OPERATOR))
                .signedBy(BATCH_OPERATOR, PAYER_SIG1)
                .payingWith(BATCH_OPERATOR)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "Batch3S1"));
        ops.add(captureFeeWithEmphasis(prefix + "Batch3S1", "innerTxns=3, SIGS=2 (+1)", feeMap));

        // innerTxns=5, SIGS=5
        ops.add(atomicBatch(
                        cryptoTransfer(movingHbar(8L).between(PAYER_SIG5, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(9L).between(PAYER_SIG5, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(10L).between(PAYER_SIG5, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(11L).between(PAYER_SIG5, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(12L).between(PAYER_SIG5, RECEIVER))
                                .batchKey(BATCH_OPERATOR))
                .signedBy(BATCH_OPERATOR, PAYER_SIG5)
                .payingWith(BATCH_OPERATOR)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "Batch5S5"));
        ops.add(captureFeeWithEmphasis(prefix + "Batch5S5", "innerTxns=5, SIGS=6 (+5)", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== FEE CAPTURE AND COMPARISON ====================

    private static SpecOperation captureFeeWithEmphasis(String txnName, String emphasis, Map<String, FeeEntry> feeMap) {
        return withOpContext((spec, opLog) -> {
            var recordOp = getTxnRecord(txnName);
            allRunFor(spec, recordOp);
            var record = recordOp.getResponseRecord();
            long fee = record.getTransactionFee();
            feeMap.put(txnName, new FeeEntry(txnName, emphasis, fee));
            LOG.info("Captured fee for {}: {} tinybars ({})", txnName, fee, emphasis);
        });
    }

    private static SpecOperation logFeeComparisonWithEmphasis(
            String serviceName, Map<String, FeeEntry> legacyFees, Map<String, FeeEntry> simpleFees) {
        return withOpContext((spec, opLog) -> {
            LOG.info("\n========== {} FEE COMPARISON ==========", serviceName);
            LOG.info(String.format(
                    "%-35s %-45s %15s %15s %15s %10s",
                    "Transaction", "Emphasis", "Legacy Fee", "Simple Fee", "Difference", "% Change"));
            LOG.info("=".repeat(140));

            for (String txnName : legacyFees.keySet()) {
                // Extract the base name (remove prefix)
                String baseName = txnName.startsWith("legacy") ? txnName.substring(6) : txnName;
                String simpleTxnName = "simple" + baseName;

                FeeEntry legacyEntry = legacyFees.get(txnName);
                FeeEntry simpleEntry = simpleFees.get(simpleTxnName);

                if (legacyEntry != null && simpleEntry != null) {
                    long diff = simpleEntry.fee() - legacyEntry.fee();
                    double pctChange = legacyEntry.fee() > 0 ? (diff * 100.0 / legacyEntry.fee()) : 0;
                    String emphasis = legacyEntry.emphasis();
                    if (emphasis.length() > 45) {
                        emphasis = emphasis.substring(0, 42) + "...";
                    }
                    LOG.info(String.format(
                            "%-35s %-45s %15d %15d %15d %9.2f%%",
                            baseName, emphasis, legacyEntry.fee(), simpleEntry.fee(), diff, pctChange));
                }
            }
            LOG.info("=".repeat(140));

            // Summary statistics
            long totalLegacy = legacyFees.values().stream().mapToLong(FeeEntry::fee).sum();
            long totalSimple = simpleFees.values().stream().mapToLong(FeeEntry::fee).sum();
            long totalDiff = totalSimple - totalLegacy;
            double totalPctChange = totalLegacy > 0 ? (totalDiff * 100.0 / totalLegacy) : 0;

            LOG.info(String.format(
                    "%-35s %-45s %15d %15d %15d %9.2f%%",
                    "TOTAL", "", totalLegacy, totalSimple, totalDiff, totalPctChange));
            LOG.info("=".repeat(140) + "\n");
        });
    }
}