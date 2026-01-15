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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
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
 * (different number of signatures, key structures, memo lengths, etc.) and compares
 * fees charged with simple fees enabled vs disabled.
 *
 * <p>Each service has its own test method for easier debugging and isolation.
 * The summary output shows the emphasis (key type, memo length, etc.) for each transaction.
 *
 * <p>This test uses:
 * <ul>
 *   <li>{@code @LeakyHapiTest} - allows overriding network properties</li>
 *   <li>ED25519 keys only for consistency (except for auto-creation tests)</li>
 *   <li>Fixed entity names with prefixes to avoid collisions between runs</li>
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
    private static final String FEE_COLLECTOR = "feeCollector";
    private static final String BATCH_OPERATOR = "batchOperator";

    // Memo variations (max memo length is 100 bytes)
    private static final String EMPTY_MEMO = "";
    private static final String SHORT_MEMO = "Short memo";
    private static final String MEDIUM_MEMO = "x".repeat(50);
    private static final String LONG_MEMO = "x".repeat(100); // Max allowed memo length

    // Contract name
    private static final String STORAGE_CONTRACT = "Storage";

    // ==================== RECORD CLASS FOR FEE ENTRIES ====================

    /**
     * Record to store fee information with emphasis description.
     */
    private record FeeEntry(String txnName, String emphasis, long fee) {}

    // ==================== CRYPTO SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Crypto Service - fee comparison")
    final Stream<DynamicTest> cryptoServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                // === RUN 1: Simple fees ===
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(cryptoTransactionsWithEmphasis("simple", simpleFees)),
                // === RUN 2: Legacy fees ===
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(cryptoTransactionsWithEmphasis("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("CRYPTO SERVICE", legacyFees, simpleFees));
    }

    // ==================== TOKEN SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Token Service - fee comparison (including custom fees)")
    final Stream<DynamicTest> tokenServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(tokenTransactionsWithEmphasis("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(tokenTransactionsWithEmphasis("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("TOKEN SERVICE", legacyFees, simpleFees));
    }

    // ==================== TOPIC/CONSENSUS SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Topic/Consensus Service - fee comparison")
    final Stream<DynamicTest> topicServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(topicTransactionsWithEmphasis("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(topicTransactionsWithEmphasis("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("TOPIC/CONSENSUS SERVICE", legacyFees, simpleFees));
    }

    // ==================== FILE SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("File Service - fee comparison")
    final Stream<DynamicTest> fileServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(fileTransactionsWithEmphasis("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(fileTransactionsWithEmphasis("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("FILE SERVICE", legacyFees, simpleFees));
    }

    // ==================== SCHEDULE SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Schedule Service - fee comparison")
    final Stream<DynamicTest> scheduleServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(scheduleTransactionsWithEmphasis("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(scheduleTransactionsWithEmphasis("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("SCHEDULE SERVICE", legacyFees, simpleFees));
    }

    // ==================== CONTRACT SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Contract Service - fee comparison")
    final Stream<DynamicTest> contractServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                uploadInitCode(STORAGE_CONTRACT),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(contractTransactionsWithEmphasis("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(contractTransactionsWithEmphasis("legacy", legacyFees)),
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
                blockingOrder(batchTransactionsWithEmphasis("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(batchTransactionsWithEmphasis("legacy", legacyFees)),
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
                cryptoCreate(FEE_COLLECTOR).balance(ONE_HUNDRED_HBARS));
    }

    // ==================== CRYPTO TRANSACTIONS WITH EMPHASIS ====================

    private static SpecOperation[] cryptoTransactionsWithEmphasis(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // CryptoCreate with varying key complexity and memo lengths
        ops.add(cryptoCreate(prefix + "AccSimple")
                .key(SIMPLE_KEY)
                .balance(ONE_HBAR)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateSimple"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateSimple", "key=ED25519, memo=empty", feeMap));

        ops.add(cryptoCreate(prefix + "AccList2")
                .key(LIST_KEY_2)
                .balance(ONE_HBAR)
                .memo(SHORT_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateList2"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateList2", "key=KeyList(2), memo=10chars", feeMap));

        ops.add(cryptoCreate(prefix + "AccList5")
                .key(LIST_KEY_5)
                .balance(ONE_HBAR)
                .memo(MEDIUM_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateList5"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateList5", "key=KeyList(5), memo=50chars", feeMap));

        ops.add(cryptoCreate(prefix + "AccThresh2of3")
                .key(THRESH_KEY_2_OF_3)
                .balance(ONE_HBAR)
                .memo(LONG_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateThresh2of3"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateThresh2of3", "key=Thresh(2/3), memo=100chars", feeMap));

        ops.add(cryptoCreate(prefix + "AccComplex")
                .key(COMPLEX_KEY)
                .balance(ONE_HBAR)
                .memo(LONG_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateComplex"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoCreateComplex", "key=Nested(Thresh+List), memo=100chars", feeMap));

        // CryptoUpdate with varying parameters
        ops.add(cryptoUpdate(prefix + "AccSimple")
                .memo(MEDIUM_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateSimple"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoUpdateSimple", "key=ED25519, memo=50chars", feeMap));

        ops.add(cryptoUpdate(prefix + "AccComplex")
                .memo(SHORT_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, COMPLEX_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateComplex"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoUpdateComplex", "key=Nested, sigs=4", feeMap));

        // CryptoTransfer with varying number of accounts
        ops.add(cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                .payingWith(PAYER)
                .memo(EMPTY_MEMO)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HbarTransfer2Acct"));
        ops.add(captureFeeWithEmphasis(prefix + "HbarTransfer2Acct", "accounts=2, memo=empty", feeMap));

        ops.add(cryptoTransfer(
                        movingHbar(ONE_HBAR).between(PAYER, prefix + "AccSimple"),
                        movingHbar(ONE_HBAR).between(PAYER, prefix + "AccList2"))
                .payingWith(PAYER)
                .memo(SHORT_MEMO)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HbarTransfer3Acct"));
        ops.add(captureFeeWithEmphasis(prefix + "HbarTransfer3Acct", "accounts=3, memo=10chars", feeMap));

        // Auto-creation via ECDSA alias transfer (hollow account creation)
        ops.add(newKeyNamed(prefix + "AutoCreateKey").shape(SECP256K1));
        ops.add(cryptoTransfer(tinyBarsFromAccountToAlias(PAYER, prefix + "AutoCreateKey", ONE_HBAR))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HbarTransferAutoCreate"));
        ops.add(captureFeeWithEmphasis(prefix + "HbarTransferAutoCreate", "hollow account creation via ECDSA alias", feeMap));

        // CryptoApproveAllowance - HBAR allowance
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccSimple", RECEIVER, ONE_HBAR)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoApproveHbar"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoApproveHbar", "allowances=1, key=ED25519", feeMap));

        // CryptoApproveAllowance - Multiple allowances
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccList2", RECEIVER, ONE_HBAR * 5)
                .addCryptoAllowance(prefix + "AccList2", prefix + "AccSimple", ONE_HBAR * 2)
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_2)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoApproveMultiple"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoApproveMultiple", "allowances=2, key=KeyList(2)", feeMap));

        // CryptoDelete
        ops.add(cryptoCreate(prefix + "ToDelete")
                .balance(0L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoDelete(prefix + "ToDelete")
                .transfer(PAYER)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoDelete"));
        ops.add(captureFeeWithEmphasis(prefix + "CryptoDelete", "transfer to payer", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== TOKEN TRANSACTIONS WITH EMPHASIS ====================

    private static SpecOperation[] tokenTransactionsWithEmphasis(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // First create accounts needed for token operations
        ops.add(cryptoCreate(prefix + "AccSimple").key(SIMPLE_KEY).balance(ONE_HBAR).payingWith(GENESIS));
        ops.add(cryptoCreate(prefix + "AccList2").key(LIST_KEY_2).balance(ONE_HBAR).payingWith(GENESIS));

        // TokenCreate - Fungible simple
        ops.add(tokenCreate(prefix + "FungibleSimple")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateFungible"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateFungible", "type=FT, keys=none, memo=empty", feeMap));

        // TokenCreate - Fungible with all keys
        ops.add(tokenCreate(prefix + "FungibleWithKeys")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .supplyKey(SIMPLE_KEY)
                .freezeKey(SIMPLE_KEY)
                .pauseKey(SIMPLE_KEY)
                .wipeKey(SIMPLE_KEY)
                .freezeDefault(false)
                .memo(MEDIUM_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateFungibleKeys"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateFungibleKeys", "type=FT, keys=5(admin,supply,freeze,pause,wipe)", feeMap));

        // TokenCreate - Fungible with fixed HBAR custom fee
        ops.add(tokenCreate(prefix + "FungibleWithHbarFee")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateWithHbarFee"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateWithHbarFee", "type=FT, customFee=fixedHbar(1)", feeMap));

        // TokenCreate - Fungible with fractional fee
        ops.add(tokenCreate(prefix + "FungibleWithFractionalFee")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .withCustom(fractionalFee(1L, 100L, 1L, OptionalLong.of(10L), TREASURY))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateWithFractionalFee"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateWithFractionalFee", "type=FT, customFee=fractional(1/100,min=1,max=10)", feeMap));

        // TokenCreate - NFT
        ops.add(tokenCreate(prefix + "NFT")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .memo(SHORT_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateNFT"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateNFT", "type=NFT, keys=supply", feeMap));

        // TokenCreate - NFT with royalty fee
        ops.add(tokenCreate(prefix + "NFTWithRoyalty")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .withCustom(royaltyFeeNoFallback(1, 10, FEE_COLLECTOR))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateNFTWithRoyalty"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenCreateNFTWithRoyalty", "type=NFT, customFee=royalty(1/10)", feeMap));

        // TokenAssociate - 2 tokens, simple key
        ops.add(tokenAssociate(prefix + "AccSimple", prefix + "FungibleSimple", prefix + "FungibleWithKeys")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenAssociate2Tokens"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenAssociate2Tokens", "tokens=2, key=ED25519", feeMap));

        // TokenAssociate - 2 tokens, key list
        ops.add(tokenAssociate(prefix + "AccList2", prefix + "FungibleSimple", prefix + "FungibleWithKeys")
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_2)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenAssociate2TokensKeyList"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenAssociate2TokensKeyList", "tokens=2, key=KeyList(2)", feeMap));

        // TokenMint - Fungible
        ops.add(mintToken(prefix + "FungibleWithKeys", 10_000L)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenMintFungible"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenMintFungible", "type=FT, amount=10000", feeMap));

        // TokenMint - NFT single
        ops.add(mintToken(prefix + "NFT", List.of(ByteString.copyFromUtf8("NFT1")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenMintNFT1"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenMintNFT1", "type=NFT, count=1, metadata=4bytes", feeMap));

        // TokenMint - NFT batch
        ops.add(mintToken(prefix + "NFT", List.of(
                        ByteString.copyFromUtf8("NFT2"),
                        ByteString.copyFromUtf8("NFT3"),
                        ByteString.copyFromUtf8("NFT4")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenMintNFT3"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenMintNFT3", "type=NFT, count=3, metadata=4bytes each", feeMap));

        // Token transfer - Fungible
        ops.add(cryptoTransfer(moving(100L, prefix + "FungibleSimple").between(TREASURY, prefix + "AccSimple"))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferFungible"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenTransferFungible", "type=FT, amount=100", feeMap));

        // Transfer for wipe test
        ops.add(cryptoTransfer(moving(500L, prefix + "FungibleWithKeys").between(TREASURY, prefix + "AccSimple"))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferForWipe"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenTransferForWipe", "type=FT, amount=500 (for wipe)", feeMap));

        // Token transfer - NFT
        ops.add(tokenAssociate(RECEIVER, prefix + "NFT").payingWith(PAYER).fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoTransfer(movingUnique(prefix + "NFT", 1L).between(TREASURY, RECEIVER))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferNFT"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenTransferNFT", "type=NFT, serial=1", feeMap));

        // TokenFreeze
        ops.add(tokenFreeze(prefix + "FungibleWithKeys", prefix + "AccSimple")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenFreeze"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenFreeze", "freezeKey=ED25519", feeMap));

        // TokenUnfreeze
        ops.add(tokenUnfreeze(prefix + "FungibleWithKeys", prefix + "AccSimple")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenUnfreeze"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenUnfreeze", "freezeKey=ED25519", feeMap));

        // TokenDissociate
        ops.add(tokenDissociate(prefix + "AccList2", prefix + "FungibleSimple")
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_2)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenDissociate"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenDissociate", "tokens=1, key=KeyList(2)", feeMap));

        // TokenBurn - Fungible
        ops.add(burnToken(prefix + "FungibleWithKeys", 1_000L)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenBurnFungible"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenBurnFungible", "type=FT, amount=1000", feeMap));

        // TokenBurn - NFT
        ops.add(burnToken(prefix + "NFT", List.of(4L))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenBurnNFT"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenBurnNFT", "type=NFT, serials=1", feeMap));

        // TokenWipe
        ops.add(wipeTokenAccount(prefix + "FungibleWithKeys", prefix + "AccSimple", 50L)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenWipeFungible"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenWipeFungible", "type=FT, amount=50", feeMap));

        // TokenPause
        ops.add(tokenPause(prefix + "FungibleWithKeys")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenPause"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenPause", "pauseKey=ED25519", feeMap));

        // TokenUnpause
        ops.add(tokenUnpause(prefix + "FungibleWithKeys")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenUnpause"));
        ops.add(captureFeeWithEmphasis(prefix + "TokenUnpause", "pauseKey=ED25519", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== TOPIC TRANSACTIONS WITH EMPHASIS ====================

    private static SpecOperation[] topicTransactionsWithEmphasis(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // CreateTopic - simple
        ops.add(createTopic(prefix + "TopicSimple")
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateSimple"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicCreateSimple", "keys=none, memo=empty", feeMap));

        // CreateTopic - with keys
        ops.add(createTopic(prefix + "TopicWithKeys")
                .adminKeyName(SIMPLE_KEY)
                .submitKeyName(SIMPLE_KEY)
                .topicMemo(MEDIUM_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateWithKeys"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicCreateWithKeys", "keys=admin+submit(ED25519), memo=50chars", feeMap));

        // CreateTopic - complex keys
        ops.add(createTopic(prefix + "TopicComplexKey")
                .adminKeyName(COMPLEX_KEY)
                .submitKeyName(THRESH_KEY_2_OF_3)
                .topicMemo(LONG_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateComplex"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicCreateComplex", "keys=admin(Nested)+submit(Thresh2/3), memo=100chars", feeMap));

        // SubmitMessage - short
        ops.add(submitMessageTo(prefix + "TopicSimple")
                .message("Short message")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgShort"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitMsgShort", "msg=13bytes, submitKey=none", feeMap));

        // SubmitMessage - medium
        ops.add(submitMessageTo(prefix + "TopicSimple")
                .message("x".repeat(1000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgMedium"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitMsgMedium", "msg=1000bytes, submitKey=none", feeMap));

        // SubmitMessage - with submit key
        ops.add(submitMessageTo(prefix + "TopicWithKeys")
                .message("x".repeat(100))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgWithKey"));
        ops.add(captureFeeWithEmphasis(prefix + "SubmitMsgWithKey", "msg=100bytes, submitKey=ED25519", feeMap));

        // UpdateTopic
        ops.add(updateTopic(prefix + "TopicWithKeys")
                .topicMemo(SHORT_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicUpdate"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicUpdate", "adminKey=ED25519, memo=10chars", feeMap));

        // DeleteTopic
        ops.add(deleteTopic(prefix + "TopicWithKeys")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicDelete"));
        ops.add(captureFeeWithEmphasis(prefix + "TopicDelete", "adminKey=ED25519", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== FILE TRANSACTIONS WITH EMPHASIS ====================

    private static SpecOperation[] fileTransactionsWithEmphasis(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // FileCreate - small
        ops.add(fileCreate(prefix + "FileSmall")
                .contents("Small file content")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateSmall"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateSmall", "content=18bytes, memo=empty", feeMap));

        // FileCreate - medium
        ops.add(fileCreate(prefix + "FileMedium")
                .contents("x".repeat(1000))
                .memo(MEDIUM_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateMedium"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateMedium", "content=1000bytes, memo=50chars", feeMap));

        // FileCreate - large
        ops.add(fileCreate(prefix + "FileLarge")
                .contents("x".repeat(4000))
                .memo(LONG_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateLarge"));
        ops.add(captureFeeWithEmphasis(prefix + "FileCreateLarge", "content=4000bytes, memo=100chars", feeMap));

        // FileUpdate
        ops.add(fileUpdate(prefix + "FileSmall")
                .contents("Updated content")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileUpdate"));
        ops.add(captureFeeWithEmphasis(prefix + "FileUpdate", "content=15bytes", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== SCHEDULE TRANSACTIONS WITH EMPHASIS ====================

    private static SpecOperation[] scheduleTransactionsWithEmphasis(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Use different amounts based on prefix to ensure unique scheduled transactions
        final long amount = prefix.equals("simple") ? 1L : 2L;

        // ScheduleCreate - simple
        ops.add(scheduleCreate(
                        prefix + "ScheduleSimple",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).memo(SHORT_MEMO))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateSimple"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleCreateSimple", "inner=CryptoTransfer, adminKey=none", feeMap));

        // ScheduleCreate - with admin key
        ops.add(scheduleCreate(
                        prefix + "ScheduleWithAdmin",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).memo(MEDIUM_MEMO))
                .adminKey(SIMPLE_KEY)
                .withEntityMemo(SHORT_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateWithAdmin"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleCreateWithAdmin", "inner=CryptoTransfer, adminKey=ED25519, memo=10chars", feeMap));

        // ScheduleCreate - with designated payer
        ops.add(scheduleCreate(
                        prefix + "ScheduleWithPayer",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)))
                .adminKey(SIMPLE_KEY)
                .designatingPayer(PAYER)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateWithPayer"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleCreateWithPayer", "inner=CryptoTransfer, designatedPayer=yes", feeMap));

        // ScheduleSign
        ops.add(scheduleSign(prefix + "ScheduleWithPayer")
                .alsoSigningWith(RECEIVER)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleSign"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleSign", "additionalSigs=1", feeMap));

        // ScheduleDelete
        ops.add(scheduleDelete(prefix + "ScheduleWithAdmin")
                .signedBy(PAYER, SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleDelete"));
        ops.add(captureFeeWithEmphasis(prefix + "ScheduleDelete", "adminKey=ED25519", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== CONTRACT TRANSACTIONS WITH EMPHASIS ====================

    private static SpecOperation[] contractTransactionsWithEmphasis(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ContractCreate - simple
        ops.add(contractCreate(prefix + "ContractSimple")
                .bytecode(STORAGE_CONTRACT)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreateSimple"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractCreateSimple", "gas=200k, adminKey=none", feeMap));

        // ContractCreate - with admin key
        ops.add(contractCreate(prefix + "ContractWithAdmin")
                .bytecode(STORAGE_CONTRACT)
                .adminKey(SIMPLE_KEY)
                .entityMemo(SHORT_MEMO)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreateWithAdmin"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractCreateWithAdmin", "gas=200k, adminKey=ED25519, memo=10chars", feeMap));

        // ContractCreate - with auto-renew account
        ops.add(contractCreate(prefix + "ContractWithAutoRenew")
                .bytecode(STORAGE_CONTRACT)
                .adminKey(SIMPLE_KEY)
                .autoRenewAccountId(PAYER)
                .entityMemo(MEDIUM_MEMO)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreateWithAutoRenew"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractCreateWithAutoRenew", "gas=200k, autoRenewAccount=yes", feeMap));

        // ContractCall - store function
        final var storeAbi = getABIFor(FUNCTION, "store", STORAGE_CONTRACT);
        ops.add(contractCallWithFunctionAbi(prefix + "ContractSimple", storeAbi, BigInteger.valueOf(42))
                .gas(50_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCallStore"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractCallStore", "gas=50k, function=store(uint256)", feeMap));

        // ContractCall - retrieve function
        final var retrieveAbi = getABIFor(FUNCTION, "retrieve", STORAGE_CONTRACT);
        ops.add(contractCallWithFunctionAbi(prefix + "ContractSimple", retrieveAbi)
                .gas(50_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCallRetrieve"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractCallRetrieve", "gas=50k, function=retrieve()", feeMap));

        // ContractUpdate
        ops.add(contractUpdate(prefix + "ContractWithAdmin")
                .newMemo(MEDIUM_MEMO)
                .signedBy(PAYER, SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractUpdate"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractUpdate", "adminKey=ED25519, memo=50chars", feeMap));

        // ContractDelete
        ops.add(contractDelete(prefix + "ContractWithAdmin")
                .transferAccount(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractDelete"));
        ops.add(captureFeeWithEmphasis(prefix + "ContractDelete", "adminKey=ED25519, transferTo=payer", feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== BATCH TRANSACTIONS WITH EMPHASIS ====================

    private static SpecOperation[] batchTransactionsWithEmphasis(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // AtomicBatch - 2 crypto transfers
        ops.add(atomicBatch(
                        cryptoTransfer(movingHbar(1L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(2L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "AtomicBatch2Transfers"));
        ops.add(captureFeeWithEmphasis(prefix + "AtomicBatch2Transfers", "innerTxns=2(CryptoTransfer)", feeMap));

        // AtomicBatch - 3 mixed transactions
        ops.add(atomicBatch(
                        cryptoTransfer(movingHbar(3L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(4L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR),
                        cryptoTransfer(movingHbar(5L).between(PAYER, RECEIVER))
                                .batchKey(BATCH_OPERATOR))
                .payingWith(BATCH_OPERATOR)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "AtomicBatch3Transfers"));
        ops.add(captureFeeWithEmphasis(prefix + "AtomicBatch3Transfers", "innerTxns=3(CryptoTransfer)", feeMap));

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