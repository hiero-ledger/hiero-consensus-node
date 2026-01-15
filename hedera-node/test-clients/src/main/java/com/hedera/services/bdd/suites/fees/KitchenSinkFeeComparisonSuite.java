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
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
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

    private static class CSVWriter {

        private final Writer writer;
        private int fieldCount;

        public CSVWriter(Writer fwriter) {
            this.writer = fwriter;
            this.fieldCount = 0;
        }

        private static String escapeCsv(String value) {
            if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                value = value.replace("\"", "\"\"");
                return "\"" + value + "\"";
            }
            return value;
        }

        public void write(String s) throws IOException {
            this.writer.write(s);
        }

        public void field(String value) throws IOException {
            if (this.fieldCount > 0) {
                this.write(",");
            }
            this.write(escapeCsv(value));
            this.fieldCount += 1;
        }

        public void endLine() throws IOException {
            this.write("\n");
            this.fieldCount = 0;
        }

        public void field(int i) throws IOException {
            this.field(i + "");
        }

        public void field(long fee) throws IOException {
            this.field(fee + "");
        }

        public void fieldPercentage(double diff) throws IOException {
            this.field(String.format("%9.2f%%", diff));
        }
    }

    private static CSVWriter csv;

    /**
     * Initialize the test class with simple fees enabled.
     * This ensures the SimpleFeeCalculator is initialized at startup,
     * which is required for switching between simple and legacy fees mid-test.
     */
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) throws IOException {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "lazyCreation.enabled", "true",
                "cryptoCreateWithAliasAndEvmAddress.enabled", "true"));
        csv = new CSVWriter(new FileWriter("simple-fees-report.csv"));
        csv.write("Transaction, Emphasis, Legacy Fee, Simple Fee, Difference, Change");
        csv.endLine();
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

    private static SpecOperation createBaseAccounts() {
        return blockingOrder(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SIMPLE_KEY),
                cryptoCreate(PAYER_COMPLEX).balance(ONE_MILLION_HBARS).key(COMPLEX_KEY),
                cryptoCreate(TREASURY).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER2).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER3).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FEE_COLLECTOR).balance(ONE_HUNDRED_HBARS));
    }

    // ==================== CRYPTO TRANSACTIONS WITH EXTRAS ====================
    // Extras: KEYS (includedCount=1), SIGNATURES, ACCOUNTS (includedCount=2), ALLOWANCES (includedCount=1)

    private static SpecOperation[] cryptoTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // === CryptoCreate: KEYS extra (includedCount=1) ===
        // KEYS=1 (included, no extra charge)
        ops.add(cryptoCreate(prefix + "AccKey1")
                .key(SIMPLE_KEY)
                .balance(ONE_HBAR)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateKey1"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateKey1", "KEYS=1 (included)", feeMap));

        // KEYS=2 (1 extra)
        ops.add(cryptoCreate(prefix + "AccKey2")
                .key(LIST_KEY_2)
                .balance(ONE_HBAR)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateKey2"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateKey2", "KEYS=2 (+1 extra)", feeMap));

        // KEYS=3 (2 extra)
        ops.add(cryptoCreate(prefix + "AccKey3")
                .key(LIST_KEY_3)
                .balance(ONE_HBAR)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateKey3"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateKey3", "KEYS=3 (+2 extra)", feeMap));

        // KEYS=5 (4 extra)
        ops.add(cryptoCreate(prefix + "AccKey5")
                .key(LIST_KEY_5)
                .balance(ONE_HBAR)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateKey5"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateKey5", "KEYS=5 (+4 extra)", feeMap));

        // === CryptoUpdate: KEYS extra (includedCount=1) ===
        // KEYS=1 (included)
        ops.add(cryptoUpdate(prefix + "AccKey1")
                .memo("updated")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateKey1"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoUpdateKey1", "KEYS=1 (included)", feeMap));

        // KEYS=3 (2 extra) - update with new key
        ops.add(cryptoUpdate(prefix + "AccKey2")
                .key(LIST_KEY_3)
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_2, LIST_KEY_3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateKey3"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoUpdateKey3", "KEYS=3 (+2 extra, new key)", feeMap));

        // === CryptoTransfer: ACCOUNTS extra (includedCount=2) ===
        // ACCOUNTS=2 (included)
        ops.add(cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferAcct2"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferAcct2", "ACCOUNTS=2 (included)", feeMap));

        // ACCOUNTS=3 (+1 extra)
        ops.add(cryptoTransfer(
                        movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                        movingHbar(ONE_HBAR).between(PAYER, RECEIVER2))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferAcct3"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferAcct3", "ACCOUNTS=3 (+1 extra)", feeMap));

        // ACCOUNTS=4 (+2 extra)
        ops.add(cryptoTransfer(
                        movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                        movingHbar(ONE_HBAR).between(PAYER, RECEIVER2),
                        movingHbar(ONE_HBAR).between(PAYER, RECEIVER3))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferAcct4"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferAcct4", "ACCOUNTS=4 (+2 extra)", feeMap));

        // Auto-creation via ECDSA alias (hollow account)
        ops.add(newKeyNamed(prefix + "AutoKey").shape(SECP256K1));
        ops.add(cryptoTransfer(tinyBarsFromAccountToAlias(PAYER, prefix + "AutoKey", ONE_HBAR))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferAutoCreate"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferAutoCreate", "ACCOUNTS=2, hollow creation", feeMap));

        // === CryptoApproveAllowance: ALLOWANCES extra (includedCount=1) ===
        // ALLOWANCES=1 (included)
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccKey1", RECEIVER, ONE_HBAR)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ApproveAllow1"));
        ops.add(captureFeeWithEmphasis(prefix + "ApproveAllow1", "ALLOWANCES=1 (included)", feeMap));

        // ALLOWANCES=2 (+1 extra)
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccKey3", RECEIVER, ONE_HBAR)
                .addCryptoAllowance(prefix + "AccKey3", RECEIVER2, ONE_HBAR)
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ApproveAllow2"));
        ops.add(captureFeeWithEmphasis(prefix + "ApproveAllow2", "ALLOWANCES=2 (+1 extra)", feeMap));

        // ALLOWANCES=3 (+2 extra)
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccKey5", RECEIVER, ONE_HBAR)
                .addCryptoAllowance(prefix + "AccKey5", RECEIVER2, ONE_HBAR)
                .addCryptoAllowance(prefix + "AccKey5", RECEIVER3, ONE_HBAR)
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_5)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ApproveAllow3"));
        ops.add(captureFeeWithEmphasis(prefix + "ApproveAllow3", "ALLOWANCES=3 (+2 extra)", feeMap));

        // Note: CryptoDeleteAllowance only supports NFT allowance deletion, tested in token section

        // === CryptoDelete ===
        ops.add(cryptoCreate(prefix + "ToDelete").balance(0L).payingWith(PAYER).fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoDelete(prefix + "ToDelete")
                .transfer(PAYER)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoDelete"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoDelete", "no extras", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== TOKEN TRANSACTIONS WITH EXTRAS ====================
    // Extras: KEYS (includedCount=1), TOKEN_CREATE_WITH_CUSTOM_FEE (0), TOKEN_MINT_NFT (0)
    // Transfer extras: TOKEN_TRANSFER_BASE, FUNGIBLE_TOKENS (1), NON_FUNGIBLE_TOKENS (1)

    private static SpecOperation[] tokenTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Setup accounts
        ops.add(cryptoCreate(prefix + "Acc1").key(SIMPLE_KEY).balance(ONE_HBAR).payingWith(GENESIS));
        ops.add(cryptoCreate(prefix + "Acc2").key(LIST_KEY_2).balance(ONE_HBAR).payingWith(GENESIS));

        // === TokenCreate: KEYS extra (includedCount=1) ===
        // KEYS=0 (no admin key)
        ops.add(tokenCreate(prefix + "FTKey0")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateKey0"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateKey0", "KEYS=0, TOKEN_CREATE_WITH_CUSTOM_FEE=0", feeMap));

        // KEYS=1 (included)
        ops.add(tokenCreate(prefix + "FTKey1")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateKey1"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateKey1", "KEYS=1 (included)", feeMap));

        // KEYS=3 (+2 extra: admin, supply, freeze)
        ops.add(tokenCreate(prefix + "FTKey3")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .supplyKey(SIMPLE_KEY)
                .freezeKey(SIMPLE_KEY)
                .freezeDefault(false)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateKey3"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateKey3", "KEYS=3 (+2 extra)", feeMap));

        // KEYS=5 (+4 extra: admin, supply, freeze, pause, wipe)
        ops.add(tokenCreate(prefix + "FTKey5")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .supplyKey(SIMPLE_KEY)
                .freezeKey(SIMPLE_KEY)
                .pauseKey(SIMPLE_KEY)
                .wipeKey(SIMPLE_KEY)
                .freezeDefault(false)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateKey5"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateKey5", "KEYS=5 (+4 extra)", feeMap));

        // === TokenCreate: TOKEN_CREATE_WITH_CUSTOM_FEE extra (includedCount=0) ===
        // 1 custom fee (fixedHbar)
        ops.add(tokenCreate(prefix + "FTCustom1")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateCustom1"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateCustom1", "TOKEN_CREATE_WITH_CUSTOM_FEE=1 (fixedHbar)", feeMap));

        // 2 custom fees (fixedHbar + fractional)
        ops.add(tokenCreate(prefix + "FTCustom2")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                .withCustom(fractionalFee(1L, 100L, 1L, OptionalLong.of(10L), TREASURY))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateCustom2"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateCustom2", "TOKEN_CREATE_WITH_CUSTOM_FEE=2 (hbar+frac)", feeMap));

        // === NFT with TOKEN_MINT_NFT extra ===
        ops.add(tokenCreate(prefix + "NFT")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateNFT"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateNFT", "KEYS=1, type=NFT", feeMap));

        // NFT with royalty custom fee
        ops.add(tokenCreate(prefix + "NFTRoyalty")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .withCustom(royaltyFeeNoFallback(1, 10, FEE_COLLECTOR))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateNFTRoyalty"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateNFTRoyalty", "TOKEN_CREATE_WITH_CUSTOM_FEE=1 (royalty)", feeMap));

        // === TokenMint: TOKEN_MINT_NFT extra (includedCount=0) ===
        // Fungible mint (no NFT extra)
        ops.add(mintToken(prefix + "FTKey5", 10_000L)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "MintFT"));
        ops.add(captureFeeWithEmphasis(prefix + "MintFT", "TOKEN_MINT_NFT=0 (fungible)", feeMap));

        // NFT mint 1 serial
        ops.add(mintToken(prefix + "NFT", List.of(ByteString.copyFromUtf8("N1")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "MintNFT1"));
        ops.add(captureFeeWithEmphasis(prefix + "MintNFT1", "TOKEN_MINT_NFT=1", feeMap));

        // NFT mint 3 serials
        ops.add(mintToken(prefix + "NFT", List.of(
                        ByteString.copyFromUtf8("N2"),
                        ByteString.copyFromUtf8("N3"),
                        ByteString.copyFromUtf8("N4")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "MintNFT3"));
        ops.add(captureFeeWithEmphasis(prefix + "MintNFT3", "TOKEN_MINT_NFT=3", feeMap));

        // NFT mint 5 serials
        ops.add(mintToken(prefix + "NFT", List.of(
                        ByteString.copyFromUtf8("N5"),
                        ByteString.copyFromUtf8("N6"),
                        ByteString.copyFromUtf8("N7"),
                        ByteString.copyFromUtf8("N8"),
                        ByteString.copyFromUtf8("N9")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "MintNFT5"));
        ops.add(captureFeeWithEmphasis(prefix + "MintNFT5", "TOKEN_MINT_NFT=5", feeMap));

        // === Token transfers: FUNGIBLE_TOKENS, NON_FUNGIBLE_TOKENS extras ===
        ops.add(tokenAssociate(prefix + "Acc1", prefix + "FTKey0", prefix + "FTKey1", prefix + "FTKey3", prefix + "FTKey5")
                .payingWith(PAYER).signedBy(PAYER, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS));
        ops.add(tokenAssociate(RECEIVER, prefix + "NFT").payingWith(PAYER).fee(ONE_HUNDRED_HBARS));

        // FUNGIBLE_TOKENS=1 (included)
        ops.add(cryptoTransfer(moving(100L, prefix + "FTKey0").between(TREASURY, prefix + "Acc1"))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferFT1"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferFT1", "FUNGIBLE_TOKENS=1 (included)", feeMap));

        // FUNGIBLE_TOKENS=2 (+1 extra)
        ops.add(cryptoTransfer(
                        moving(100L, prefix + "FTKey0").between(TREASURY, prefix + "Acc1"),
                        moving(100L, prefix + "FTKey1").between(TREASURY, prefix + "Acc1"))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferFT2"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferFT2", "FUNGIBLE_TOKENS=2 (+1 extra)", feeMap));

        // NON_FUNGIBLE_TOKENS=1 (included)
        ops.add(cryptoTransfer(movingUnique(prefix + "NFT", 1L).between(TREASURY, RECEIVER))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TransferNFT1"));
        ops.add(captureFeeWithEmphasis(prefix + "TransferNFT1", "NON_FUNGIBLE_TOKENS=1 (included)", feeMap));

        // === Other token operations (no variable extras) ===
        ops.add(tokenFreeze(prefix + "FTKey5", prefix + "Acc1")
                .payingWith(PAYER).signedBy(PAYER, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "Freeze"));
        ops.add(captureFeeWithEmphasis(prefix + "Freeze", "no extras", feeMap));

        ops.add(tokenUnfreeze(prefix + "FTKey5", prefix + "Acc1")
                .payingWith(PAYER).signedBy(PAYER, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "Unfreeze"));
        ops.add(captureFeeWithEmphasis(prefix + "Unfreeze", "no extras", feeMap));

        ops.add(tokenPause(prefix + "FTKey5")
                .payingWith(PAYER).signedBy(PAYER, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "Pause"));
        ops.add(captureFeeWithEmphasis(prefix + "Pause", "no extras", feeMap));

        ops.add(tokenUnpause(prefix + "FTKey5")
                .payingWith(PAYER).signedBy(PAYER, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "Unpause"));
        ops.add(captureFeeWithEmphasis(prefix + "Unpause", "no extras", feeMap));

        ops.add(burnToken(prefix + "FTKey5", 1_000L)
                .payingWith(PAYER).signedBy(PAYER, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "BurnFT"));
        ops.add(captureFeeWithEmphasis(prefix + "BurnFT", "no extras (fungible)", feeMap));

        ops.add(burnToken(prefix + "NFT", List.of(4L))
                .payingWith(PAYER).signedBy(PAYER, SIMPLE_KEY).fee(ONE_HUNDRED_HBARS).via(prefix + "BurnNFT"));
        ops.add(captureFeeWithEmphasis(prefix + "BurnNFT", "no extras (NFT)", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== TOPIC TRANSACTIONS WITH EXTRAS ====================
    // Extras: KEYS (includedCount=0), BYTES (includedCount=100)

    private static SpecOperation[] topicTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // === ConsensusCreateTopic: KEYS extra (includedCount=0) ===
        // KEYS=0 (no keys)
        ops.add(createTopic(prefix + "TopicKey0")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateKey0"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicCreateKey0", "KEYS=0 (no extra)", feeMap));

        // KEYS=1 (+1 extra: admin only)
        ops.add(createTopic(prefix + "TopicKey1")
                .adminKeyName(SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateKey1"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicCreateKey1", "KEYS=1 (+1 extra)", feeMap));

        // KEYS=2 (+2 extra: admin + submit)
        ops.add(createTopic(prefix + "TopicKey2")
                .adminKeyName(SIMPLE_KEY)
                .submitKeyName(SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateKey2"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicCreateKey2", "KEYS=2 (+2 extra)", feeMap));

        // === ConsensusSubmitMessage: BYTES extra (includedCount=100) ===
        // BYTES=50 (under included, no extra)
        ops.add(submitMessageTo(prefix + "TopicKey0")
                .message("x".repeat(50))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitBytes50"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitBytes50", "BYTES=50 (under 100 included)", feeMap));

        // BYTES=100 (exactly included)
        ops.add(submitMessageTo(prefix + "TopicKey0")
                .message("x".repeat(100))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitBytes100"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitBytes100", "BYTES=100 (included)", feeMap));

        // BYTES=500 (+400 extra)
        ops.add(submitMessageTo(prefix + "TopicKey0")
                .message("x".repeat(500))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitBytes500"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitBytes500", "BYTES=500 (+400 extra)", feeMap));

        // BYTES=1000 (+900 extra) - max topic message size is 1024
        ops.add(submitMessageTo(prefix + "TopicKey0")
                .message("x".repeat(1000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitBytes1000"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitBytes1000", "BYTES=1000 (+900 extra)", feeMap));

        // === ConsensusUpdateTopic: KEYS extra (includedCount=1) ===
        ops.add(updateTopic(prefix + "TopicKey1")
                .topicMemo("updated")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicUpdate"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicUpdate", "KEYS=1 (included)", feeMap));

        // === ConsensusDeleteTopic: no extras ===
        ops.add(deleteTopic(prefix + "TopicKey1")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicDelete"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicDelete", "no extras", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== FILE TRANSACTIONS WITH EXTRAS ====================
    // Extras: KEYS (includedCount=1), BYTES (includedCount=1000)

    private static SpecOperation[] fileTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // === FileCreate: KEYS=1 (included), BYTES variations ===
        // BYTES=100 (under 1000 included)
        ops.add(fileCreate(prefix + "FileBytes100")
                .contents("x".repeat(100))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateBytes100"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateBytes100", "KEYS=1, BYTES=100 (under 1000)", feeMap));

        // BYTES=1000 (exactly included)
        ops.add(fileCreate(prefix + "FileBytes1000")
                .contents("x".repeat(1000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateBytes1000"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateBytes1000", "KEYS=1, BYTES=1000 (included)", feeMap));

        // BYTES=2000 (+1000 extra)
        ops.add(fileCreate(prefix + "FileBytes2000")
                .contents("x".repeat(2000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateBytes2000"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateBytes2000", "KEYS=1, BYTES=2000 (+1000 extra)", feeMap));

        // BYTES=4000 (+3000 extra)
        ops.add(fileCreate(prefix + "FileBytes4000")
                .contents("x".repeat(4000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateBytes4000"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateBytes4000", "KEYS=1, BYTES=4000 (+3000 extra)", feeMap));

        // === FileUpdate: KEYS=1 (included), BYTES variations ===
        ops.add(fileUpdate(prefix + "FileBytes100")
                .contents("x".repeat(500))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileUpdateBytes500"));
        ops.add(captureFeeWithEmphasis(prefix + "FileUpdateBytes500", "KEYS=1, BYTES=500 (under 1000)", feeMap));

        ops.add(fileUpdate(prefix + "FileBytes1000")
                .contents("x".repeat(3000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileUpdateBytes3000"));
        ops.add(captureFeeWithEmphasis(prefix + "FileUpdateBytes3000", "KEYS=1, BYTES=3000 (+2000 extra)", feeMap));

        // === FileAppend: BYTES (includedCount=1000) ===
        ops.add(fileAppend(prefix + "FileBytes100")
                .content("y".repeat(500))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileAppendBytes500"));
        ops.add(captureFeeWithEmphasis(prefix + "FileAppendBytes500", "BYTES=500 (under 1000)", feeMap));

        ops.add(fileAppend(prefix + "FileBytes100")
                .content("z".repeat(2000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileAppendBytes2000"));
        ops.add(captureFeeWithEmphasis(prefix + "FileAppendBytes2000", "BYTES=2000 (+1000 extra)", feeMap));

        // === FileDelete: no extras ===
        ops.add(fileDelete(prefix + "FileBytes4000")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileDelete"));
        ops.add(captureFeeWithEmphasis(prefix + "FileDelete", "no extras", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== SCHEDULE TRANSACTIONS WITH EXTRAS ====================
    // Extras: KEYS (includedCount=1)

    private static SpecOperation[] scheduleTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        final long amount = prefix.equals("simple") ? 1L : 2L;

        // === ScheduleCreate: KEYS extra (includedCount=1) ===
        // KEYS=0 (no admin key, under included)
        ops.add(scheduleCreate(
                        prefix + "SchedKey0",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateKey0"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleCreateKey0", "KEYS=0 (under 1 included)", feeMap));

        // KEYS=1 (included) - schedule for signing (will be executed)
        ops.add(scheduleCreate(
                        prefix + "SchedKey1",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount + 1)))
                .adminKey(SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateKey1"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleCreateKey1", "KEYS=1 (included)", feeMap));

        // KEYS=2 (+1 extra) - schedule for deletion (won't be executed)
        ops.add(scheduleCreate(
                        prefix + "SchedKey2",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount + 2)))
                .adminKey(LIST_KEY_2)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateKey2"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleCreateKey2", "KEYS=2 (+1 extra)", feeMap));

        // === ScheduleSign: no variable extras ===
        // This will execute the schedule since RECEIVER signature completes it
        ops.add(scheduleSign(prefix + "SchedKey1")
                .alsoSigningWith(RECEIVER)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleSign"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleSign", "no extras", feeMap));

        // === ScheduleDelete: no extras ===
        // Delete SchedKey2 which hasn't been executed
        ops.add(scheduleDelete(prefix + "SchedKey2")
                .signedBy(PAYER, LIST_KEY_2)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleDelete"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleDelete", "no extras", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== CONTRACT TRANSACTIONS WITH EXTRAS ====================
    // Extras: GAS (fee per unit)

    private static SpecOperation[] contractTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Create contracts for testing
        ops.add(contractCreate(prefix + "Contract1")
                .bytecode(STORAGE_CONTRACT)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreate"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractCreate", "GAS=200000", feeMap));

        ops.add(contractCreate(prefix + "Contract2")
                .bytecode(STORAGE_CONTRACT)
                .adminKey(SIMPLE_KEY)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreateAdmin"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractCreateAdmin", "GAS=200000, adminKey=1", feeMap));

        // === ContractCall: GAS extra variations ===
        final var storeAbi = getABIFor(FUNCTION, "store", STORAGE_CONTRACT);
        final var retrieveAbi = getABIFor(FUNCTION, "retrieve", STORAGE_CONTRACT);

        // GAS=50000
        ops.add(contractCallWithFunctionAbi(prefix + "Contract1", storeAbi, BigInteger.valueOf(42))
                .gas(50_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CallGas50k"));
        ops.add(captureFeeWithEmphasis(prefix + "CallGas50k", "GAS=50000", feeMap));

        // GAS=100000
        ops.add(contractCallWithFunctionAbi(prefix + "Contract1", storeAbi, BigInteger.valueOf(100))
                .gas(100_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CallGas100k"));
        ops.add(captureFeeWithEmphasis(prefix + "CallGas100k", "GAS=100000", feeMap));

        // GAS=200000
        ops.add(contractCallWithFunctionAbi(prefix + "Contract1", storeAbi, BigInteger.valueOf(200))
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CallGas200k"));
        ops.add(captureFeeWithEmphasis(prefix + "CallGas200k", "GAS=200000", feeMap));

        // GAS=500000
        ops.add(contractCallWithFunctionAbi(prefix + "Contract1", storeAbi, BigInteger.valueOf(500))
                .gas(500_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CallGas500k"));
        ops.add(captureFeeWithEmphasis(prefix + "CallGas500k", "GAS=500000", feeMap));

        // Read-only call (retrieve)
        ops.add(contractCallWithFunctionAbi(prefix + "Contract1", retrieveAbi)
                .gas(50_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CallRetrieve"));
        ops.add(captureFeeWithEmphasis(prefix + "CallRetrieve", "GAS=50000 (read-only)", feeMap));

        // === ContractUpdate: no GAS extra ===
        ops.add(contractUpdate(prefix + "Contract2")
                .newMemo("updated")
                .signedBy(PAYER, SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractUpdate"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractUpdate", "no GAS extra", feeMap));

        // === ContractDelete: no GAS extra ===
        ops.add(contractDelete(prefix + "Contract2")
                .transferAccount(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractDelete"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractDelete", "no GAS extra", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== BATCH TRANSACTIONS WITH EXTRAS ====================

    private static SpecOperation[] batchTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // AtomicBatch - 2 inner transactions
        ops.add(atomicBatch(
                        cryptoTransfer(movingHbar(1L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(2L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "Batch2"));
        ops.add(captureFeeWithEmphasis(prefix + "Batch2", "innerTxns=2", feeMap));

        // AtomicBatch - 3 inner transactions
        ops.add(atomicBatch(
                        cryptoTransfer(movingHbar(3L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(4L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(5L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "Batch3"));
        ops.add(captureFeeWithEmphasis(prefix + "Batch3", "innerTxns=3", feeMap));

        // AtomicBatch - 5 inner transactions
        ops.add(atomicBatch(
                        cryptoTransfer(movingHbar(6L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(7L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(8L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(9L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(10L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "Batch5"));
        ops.add(captureFeeWithEmphasis(prefix + "Batch5", "innerTxns=5", feeMap));

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
                    csv.field(baseName);
                    csv.field(emphasis);
                    csv.field(legacyEntry.fee());
                    csv.field(simpleEntry.fee());
                    csv.field(diff);
                    csv.fieldPercentage(pctChange);
                    csv.endLine();
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