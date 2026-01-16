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
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDeleteAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCancelAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenReject;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdateNfts;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingToken;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoUpdate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenUpdate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Kitchen sink test suite that exercises all simple-fees transaction types and
 * full permutations of their extras (plus node SIGNATURES) derived from simpleFeesSchedules.json.
 *
 * <p>Each service has its own test method for easier debugging and isolation.
 * The summary output shows the emphasis with specific extra values added for each transaction,
 * and the CSV includes a combined, final table across all services.
 *
 * <p>Extras are permuted across representative ranges to cover included, boundary, and
 * multi-extra combinations for each transaction type (e.g. keys, allowances, bytes, custom fees,
 * hook updates/executions, gas, innerTxns, token transfer bases).
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

        public void close() throws IOException {
            this.writer.flush();
            this.writer.close();
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
                "hooks.hooksEnabled", "true",
                "lazyCreation.enabled", "true",
                "cryptoCreateWithAliasAndEvmAddress.enabled", "true"));
        csv = new CSVWriter(new FileWriter("simple-fees-report.csv"));
        csv.write("Service, Transaction, Emphasis, Legacy Fee, Simple Fee, Difference, Change");
        csv.endLine();
    }

    @AfterAll
    static void afterAll() throws IOException {
        logFinalComparisonTable();
        if (csv != null) {
            csv.close();
        }
    }

    // Key names for different complexity levels
    private static final String SIMPLE_KEY = "simpleKey";
    private static final String LIST_KEY_2 = "listKey2";
    private static final String LIST_KEY_3 = "listKey3";
    private static final String LIST_KEY_4 = "listKey4";
    private static final String LIST_KEY_5 = "listKey5";
    private static final String THRESH_KEY_2_OF_3 = "threshKey2of3";
    private static final String THRESH_KEY_3_OF_5 = "threshKey3of5";
    private static final String COMPLEX_KEY = "complexKey";
    private static final String ECDSA_KEY = "ecdsaKey";

    // Payer names
    private static final String PAYER = "payer";
    private static final String PAYER_COMPLEX = "payerComplex";
    private static final String PAYER_SIG1 = "payerSig1"; // 1 signature (ED25519)
    private static final String PAYER_SIG2 = "payerSig2"; // 2 signatures (listOf(2))
    private static final String PAYER_SIG3 = "payerSig3"; // 3 signatures (listOf(3))
    private static final String PAYER_SIG4 = "payerSig4"; // 4 signatures (listOf(4))
    private static final String PAYER_SIG5 = "payerSig5"; // 5 signatures (listOf(5))
    private static final String TREASURY = "treasury";
    private static final String RECEIVER = "receiver";
    private static final String RECEIVER2 = "receiver2";
    private static final String RECEIVER3 = "receiver3";
    private static final String RECEIVER4 = "receiver4";
    private static final String FEE_COLLECTOR = "feeCollector";
    private static final String BATCH_OPERATOR = "batchOperator";

    // Contract name
    private static final String STORAGE_CONTRACT = "Storage";
    private static final String SCHEDULE_CONTRACT = "ScheduleStorage";
    private static final String HOOK_CONTRACT = "TruePreHook";

    private static final long[] HOOK_IDS = {1L, 2L};

    // Permutation ranges for extras
    private static final int[] SIGNATURE_COUNTS = {1, 2, 3, 4, 5};
    private static final int[] KEY_COUNTS = {1, 2, 3, 4, 5};
    private static final int[] TOKEN_KEY_COUNTS = {0, 1, 2, 3, 4, 5};
    private static final int[] HOOK_COUNTS = {0, 1, 2};
    private static final int[] ALLOWANCE_COUNTS = {1, 2, 3};
    private static final int[] TRANSFER_SIGNATURE_COUNTS = {1, 3, 5};
    private static final int[] TRANSFER_HOOK_COUNTS = {0, 1};
    private static final int[] TRANSFER_ACCOUNT_COUNTS = {2, 3, 5};
    private static final int[] TRANSFER_FT_COUNTS = {0, 1, 3};
    private static final int[] TRANSFER_NFT_COUNTS = {0, 1};
    private static final int TRANSFER_NFT_SERIALS = 200;
    private static final int NFT_MINT_BATCH_SIZE = 10;
    private static final int[] CUSTOM_FEE_COUNTS = {0, 1};
    private static final int[] NFT_MINT_COUNTS = {0, 1, 2, 3, 5};
    private static final int[] TOPIC_KEY_COUNTS = {0, 1, 2};
    private static final int[] TOPIC_CUSTOM_FEE_COUNTS = {0, 1};
    private static final int[] TOPIC_MESSAGE_BYTES = {50, 100, 500, 1000, 2000};
    private static final int[] FILE_KEY_COUNTS = {1, 2, 3};
    private static final int[] FILE_CREATE_BYTES = {100, 1000, 2000, 4000};
    private static final int[] FILE_UPDATE_BYTES = {500, 1000, 3000};
    private static final int[] FILE_APPEND_BYTES = {500, 1000, 2000};
    private static final int[] SCHEDULE_KEY_COUNTS = {0, 1, 2};
    private static final int[] CONTRACT_GAS = {50_000, 100_000, 200_000, 500_000};
    private static final int[] BATCH_INNER_COUNTS = {2, 3, 5};

    private static final List<String> RECEIVERS = List.of(RECEIVER, RECEIVER2, RECEIVER3, RECEIVER4);

    private static final Map<String, Integer> SIGNER_SIG_COUNTS = new HashMap<>();

    private static final List<FeeComparisonRow> ALL_ROWS = Collections.synchronizedList(new ArrayList<>());

    // ==================== RECORD CLASS FOR FEE ENTRIES ====================

    private record FeeEntry(String txnName, String emphasis, long fee) {}

    private record ExtraCount(String name, int included, int count) {
        int added() {
            return Math.max(0, count - included);
        }
    }

    private record FeeComparisonRow(
            String service, String txnName, String emphasis, long legacyFee, long simpleFee, long diff, double pct) {}

    static {
        SIGNER_SIG_COUNTS.put(SIMPLE_KEY, 1);
        SIGNER_SIG_COUNTS.put(LIST_KEY_2, 2);
        SIGNER_SIG_COUNTS.put(LIST_KEY_3, 3);
        SIGNER_SIG_COUNTS.put(LIST_KEY_4, 4);
        SIGNER_SIG_COUNTS.put(LIST_KEY_5, 5);
        SIGNER_SIG_COUNTS.put(PAYER_SIG1, 1);
        SIGNER_SIG_COUNTS.put(PAYER_SIG2, 2);
        SIGNER_SIG_COUNTS.put(PAYER_SIG3, 3);
        SIGNER_SIG_COUNTS.put(PAYER_SIG4, 4);
        SIGNER_SIG_COUNTS.put(PAYER_SIG5, 5);
        for (final int sigCount : SIGNATURE_COUNTS) {
            for (final int hookCount : HOOK_COUNTS) {
                SIGNER_SIG_COUNTS.put(hookedPayerName(sigCount, hookCount), sigCount);
            }
        }
        SIGNER_SIG_COUNTS.put(PAYER, 1);
        SIGNER_SIG_COUNTS.put(PAYER_COMPLEX, 3);
        SIGNER_SIG_COUNTS.put(TREASURY, 1);
        SIGNER_SIG_COUNTS.put(RECEIVER, 1);
        SIGNER_SIG_COUNTS.put(RECEIVER2, 1);
        SIGNER_SIG_COUNTS.put(RECEIVER3, 1);
        SIGNER_SIG_COUNTS.put(RECEIVER4, 1);
        SIGNER_SIG_COUNTS.put(FEE_COLLECTOR, 1);
        SIGNER_SIG_COUNTS.put(BATCH_OPERATOR, 1);
    }

    // ==================== CRYPTO SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Crypto Service - extras combinations (KEYS, SIGNATURES, ACCOUNTS, ALLOWANCES)")
    final Stream<DynamicTest> cryptoServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                createHookedPayers(),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(cryptoTransactionsWithExtras("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(cryptoTransactionsWithExtras("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("CRYPTO SERVICE", legacyFees, simpleFees));
    }

    // ==================== TOKEN SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Token Service - extras permutations")
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
                uploadInitCode(STORAGE_CONTRACT),
                contractCreate(SCHEDULE_CONTRACT).bytecode(STORAGE_CONTRACT).gas(2_000_000L),
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(scheduleTransactionsWithExtras("simple", simpleFees)),
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(scheduleTransactionsWithExtras("legacy", legacyFees)),
                logFeeComparisonWithEmphasis("SCHEDULE SERVICE", legacyFees, simpleFees));
    }

    // ==================== CONTRACT SERVICE TEST ====================

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Contract Service - extras (GAS, HOOK_STORE)")
    final Stream<DynamicTest> contractServiceFeeComparison() {
        final Map<String, FeeEntry> legacyFees = new LinkedHashMap<>();
        final Map<String, FeeEntry> simpleFees = new LinkedHashMap<>();

        return hapiTest(
                createAllKeys(),
                createBaseAccounts(),
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                createHookedPayers(),
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
                newKeyNamed(LIST_KEY_4).shape(listOf(4)),
                newKeyNamed(LIST_KEY_5).shape(listOf(5)),
                newKeyNamed(THRESH_KEY_2_OF_3).shape(threshOf(2, 3)),
                newKeyNamed(THRESH_KEY_3_OF_5).shape(threshOf(3, 5)),
                newKeyNamed(COMPLEX_KEY).shape(threshOf(2, listOf(2), KeyShape.SIMPLE, threshOf(1, 2))),
                newKeyNamed(ECDSA_KEY).shape(SECP256K1));
    }

    // ==================== BASE ACCOUNT CREATION ====================
    // Payers with different signature requirements for testing SIGNATURES extra

    private static SpecOperation createBaseAccounts() {
        return blockingOrder(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SIMPLE_KEY),
                cryptoCreate(PAYER_COMPLEX).balance(ONE_MILLION_HBARS).key(COMPLEX_KEY),
                // Payers with different signature counts for SIGNATURES extra testing
                cryptoCreate(PAYER_SIG1).balance(ONE_MILLION_HBARS).key(SIMPLE_KEY),
                cryptoCreate(PAYER_SIG2).balance(ONE_MILLION_HBARS).key(LIST_KEY_2),
                cryptoCreate(PAYER_SIG3).balance(ONE_MILLION_HBARS).key(LIST_KEY_3),
                cryptoCreate(PAYER_SIG4).balance(ONE_MILLION_HBARS).key(LIST_KEY_4),
                cryptoCreate(PAYER_SIG5).balance(ONE_MILLION_HBARS).key(LIST_KEY_5),
                cryptoCreate(TREASURY).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER2).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER3).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER4).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FEE_COLLECTOR).balance(ONE_HUNDRED_HBARS));
    }

    private static SpecOperation createHookedPayers() {
        List<SpecOperation> ops = new ArrayList<>();
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String keyName = keyForCount(sigCount);
            for (final int hookCount : HOOK_COUNTS) {
                final String payerName = hookedPayerName(sigCount, hookCount);
                final HapiCryptoCreate create = cryptoCreate(payerName)
                        .balance(ONE_MILLION_HBARS)
                        .key(keyName);
                applyHookUpdates(create, hookCount);
                ops.add(create);
            }
        }
        return blockingOrder(ops.toArray(new SpecOperation[0]));
    }

    private static String keyForCount(final int count) {
        return switch (count) {
            case 1 -> SIMPLE_KEY;
            case 2 -> LIST_KEY_2;
            case 3 -> LIST_KEY_3;
            case 4 -> LIST_KEY_4;
            case 5 -> LIST_KEY_5;
            default -> SIMPLE_KEY;
        };
    }

    private static String payerForSigCount(final int sigCount) {
        return switch (sigCount) {
            case 1 -> PAYER_SIG1;
            case 2 -> PAYER_SIG2;
            case 3 -> PAYER_SIG3;
            case 4 -> PAYER_SIG4;
            case 5 -> PAYER_SIG5;
            default -> PAYER_SIG1;
        };
    }

    private static String hookedPayerName(final int sigCount, final int hookCount) {
        return "payerSig" + sigCount + "Hooks" + hookCount;
    }

    private static int signatureCount(final String... signers) {
        return Arrays.stream(signers)
                .distinct()
                .mapToInt(s -> SIGNER_SIG_COUNTS.getOrDefault(s, 1))
                .sum();
    }

    private static String extrasEmphasis(final ExtraCount... extras) {
        final List<String> parts = new ArrayList<>();
        for (final var extra : extras) {
            if (extra.added() > 0) {
                parts.add(extra.name + "=" + extra.count + " (+" + extra.added() + ")");
            }
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private static void applyHookUpdates(final HapiCryptoCreate op, final int hookCount) {
        for (int i = 0; i < hookCount; i++) {
            op.withHook(accountAllowanceHook(HOOK_IDS[i], HOOK_CONTRACT));
        }
    }

    private static void applyHookUpdates(final HapiCryptoUpdate op, final int hookCount) {
        for (int i = 0; i < hookCount; i++) {
            op.withHook(accountAllowanceHook(HOOK_IDS[i], HOOK_CONTRACT));
        }
    }

    private static void applyHookExecutions(final HapiCryptoTransfer op, final String account, final int hookCount) {
        for (int i = 0; i < hookCount; i++) {
            op.withPreHookFor(account, HOOK_IDS[i], 5_000_000L, "");
        }
    }

    // ==================== CRYPTO TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // CryptoCreate extras: KEYS (includedCount=1), HOOK_UPDATES (includedCount=0)
    // CryptoUpdate extras: KEYS (includedCount=1), HOOK_UPDATES (includedCount=0)
    // CryptoTransfer extras: TOKEN_TRANSFER_BASE, TOKEN_TRANSFER_BASE_CUSTOM_FEES, HOOK_EXECUTION,
    // ACCOUNTS (includedCount=2), FUNGIBLE_TOKENS (1), NON_FUNGIBLE_TOKENS (1)
    // CryptoApproveAllowance extras: ALLOWANCES (includedCount=1)
    // CryptoDeleteAllowance extras: ALLOWANCES (includedCount=1)
    // CryptoDelete extras: none

    private static SpecOperation[] cryptoTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        ops.addAll(cryptoCreatePermutations(prefix, feeMap));
        ops.addAll(cryptoUpdatePermutations(prefix, feeMap));
        ops.addAll(cryptoTransferPermutations(prefix, feeMap));
        ops.addAll(cryptoApproveAllowancePermutations(prefix, feeMap));
        ops.addAll(cryptoDeleteAllowancePermutations(prefix, feeMap));
        ops.addAll(cryptoDeletePermutations(prefix, feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    private static List<SpecOperation> cryptoCreatePermutations(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();
        for (final int keyCount : KEY_COUNTS) {
            final String keyName = keyForCount(keyCount);
            for (final int sigCount : SIGNATURE_COUNTS) {
                final String payer = payerForSigCount(sigCount);
                for (final int hookCount : HOOK_COUNTS) {
                    final String baseName = String.format("CryptoCreate_K%d_S%d_H%d", keyCount, sigCount, hookCount);
                    final String account = prefix + "Acc_" + baseName;
                    final String txnName = prefix + baseName;

                    final HapiCryptoCreate op = cryptoCreate(account)
                            .key(keyName)
                            .balance(ONE_HBAR)
                            .payingWith(payer)
                            .signedBy(payer)
                            .fee(ONE_HUNDRED_HBARS)
                            .via(txnName);
                    applyHookUpdates(op, hookCount);
                    ops.add(op);

                    final String emphasis = extrasEmphasis(
                            new ExtraCount("KEYS", 1, keyCount),
                            new ExtraCount("HOOK_UPDATES", 0, hookCount),
                            new ExtraCount("SIGNATURES", 1, sigCount));
                    ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
                }
            }
        }
        return ops;
    }

    private static List<SpecOperation> cryptoUpdatePermutations(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();
        for (final int keyCount : KEY_COUNTS) {
            final String keyName = keyForCount(keyCount);
            for (final int sigCount : SIGNATURE_COUNTS) {
                final String payer = payerForSigCount(sigCount);
                for (final int hookCount : HOOK_COUNTS) {
                    final String baseName = String.format("CryptoUpdate_K%d_S%d_H%d", keyCount, sigCount, hookCount);
                    final String account = prefix + "UpdAcc_" + baseName;
                    final String txnName = prefix + baseName;

                    ops.add(cryptoCreate(account)
                            .key(SIMPLE_KEY)
                            .balance(ONE_HBAR)
                            .payingWith(payer)
                            .signedBy(payer)
                            .fee(ONE_HUNDRED_HBARS));

                    final HapiCryptoUpdate update = cryptoUpdate(account)
                            .key(keyName)
                            .memo("updated")
                            .payingWith(payer)
                            .signedBy(payer, SIMPLE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via(txnName);
                    applyHookUpdates(update, hookCount);
                    ops.add(update);

                    final int sigs = signatureCount(payer, SIMPLE_KEY);
                    final String emphasis = extrasEmphasis(
                            new ExtraCount("KEYS", 1, keyCount),
                            new ExtraCount("HOOK_UPDATES", 0, hookCount),
                            new ExtraCount("SIGNATURES", 1, sigs));
                    ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
                }
            }
        }
        return ops;
    }

    private static List<SpecOperation> cryptoTransferPermutations(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        final String ft1 = prefix + "FT1";
        final String ft2 = prefix + "FT2";
        final String ft3 = prefix + "FT3";
        final String ftCustom = prefix + "FTCustom";
        final String nft1 = prefix + "NFT1";
        final String nftCustom = prefix + "NFTCustom";

        ops.add(tokenCreate(ft1)
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(tokenCreate(ft2)
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(tokenCreate(ft3)
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(tokenCreate(ftCustom)
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(nft1)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(tokenCreate(nftCustom)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .withCustom(royaltyFeeNoFallback(1, 10, FEE_COLLECTOR))
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS));

        ops.addAll(mintNftSerials(nft1, prefix + "NFT1", TRANSFER_NFT_SERIALS, PAYER_SIG1));
        ops.addAll(mintNftSerials(nftCustom, prefix + "NFTC", TRANSFER_NFT_SERIALS, PAYER_SIG1));

        for (final String receiver : RECEIVERS) {
            ops.add(tokenAssociate(receiver, ft1, ft2, ft3, ftCustom, nft1, nftCustom)
                    .payingWith(PAYER_SIG1)
                    .signedBy(PAYER_SIG1, receiver)
                    .fee(ONE_HUNDRED_HBARS));
        }

        final List<String> standardFts = List.of(ft1, ft2, ft3);
        final List<String> customFts = List.of(ftCustom);
        final List<String> standardNfts = List.of(nft1);
        final List<String> customNfts = List.of(nftCustom);
        final Map<String, Integer> nextSerial = new HashMap<>();
        nextSerial.put(nft1, 1);
        nextSerial.put(nftCustom, 1);

        for (final int sigCount : TRANSFER_SIGNATURE_COUNTS) {
            for (final int hookCount : TRANSFER_HOOK_COUNTS) {
                final String payer = hookedPayerName(sigCount, hookCount);
                for (final int accountCount : TRANSFER_ACCOUNT_COUNTS) {
                    final List<String> receivers = RECEIVERS.subList(0, accountCount - 1);
                    final String tokenReceiver = receivers.get(0);
                    for (final int ftCount : TRANSFER_FT_COUNTS) {
                        for (final int nftCount : TRANSFER_NFT_COUNTS) {
                            for (final int hasCustom : new int[] {0, 1}) {
                                if (ftCount == 0 && nftCount == 0 && hasCustom == 1) {
                                    continue;
                                }
                                final boolean useCustom = hasCustom == 1 && (ftCount + nftCount) > 0;
                                final List<String> fts = pickTokens(standardFts, customFts, ftCount, useCustom);
                                final List<String> nfts =
                                        pickTokens(standardNfts, customNfts, nftCount, useCustom && fts.isEmpty());

                                final String baseName = String.format(
                                        "CryptoTransfer_A%d_FT%d_NFT%d_H%d_C%d_S%d",
                                        accountCount, ftCount, nftCount, hookCount, hasCustom, sigCount);
                                final String txnName = prefix + baseName;

                                final List<com.hedera.services.bdd.spec.transactions.token.TokenMovement> movements = new ArrayList<>();
                                for (final String receiver : receivers) {
                                    movements.add(movingHbar(1L).between(payer, receiver));
                                }
                                for (final String token : fts) {
                                    movements.add(moving(1L, token).between(TREASURY, tokenReceiver));
                                }
                                for (final String token : nfts) {
                                    final int serial = nextSerial.get(token);
                                    nextSerial.put(token, serial + 1);
                                    movements.add(movingUnique(token, serial).between(TREASURY, tokenReceiver));
                                }

                                final boolean hasTokens = (ftCount + nftCount) > 0;
                                final HapiCryptoTransfer transfer = cryptoTransfer(
                                                movements.toArray(new com.hedera.services.bdd.spec.transactions.token.TokenMovement[0]))
                                        .payingWith(payer)
                                        .signedBy(hasTokens ? new String[] {payer, TREASURY} : new String[] {payer})
                                        .fee(ONE_HUNDRED_HBARS)
                                        .via(txnName);
                                applyHookExecutions(transfer, payer, hookCount);
                                ops.add(transfer);

                                final int sigs = hasTokens ? signatureCount(payer, TREASURY) : signatureCount(payer);
                                final int accounts = countAccounts(payer, receivers, hasTokens);
                                final int ftExtraCount = fts.size();
                                final int nftExtraCount = nfts.size();
                                final int baseCustomExtra = (useCustom && hasTokens) ? 1 : 0;
                                final int baseTokenExtra = hasTokens && baseCustomExtra == 0 ? 1 : 0;
                                final String emphasis = extrasEmphasis(
                                        new ExtraCount("TOKEN_TRANSFER_BASE", 0, baseTokenExtra),
                                        new ExtraCount("TOKEN_TRANSFER_BASE_CUSTOM_FEES", 0, baseCustomExtra),
                                        new ExtraCount("HOOK_EXECUTION", 0, hookCount),
                                        new ExtraCount("ACCOUNTS", 2, accounts),
                                        new ExtraCount("FUNGIBLE_TOKENS", 1, ftExtraCount),
                                        new ExtraCount("NON_FUNGIBLE_TOKENS", 1, nftExtraCount),
                                        new ExtraCount("SIGNATURES", 1, sigs));
                                ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
                            }
                        }
                    }
                }
            }
        }
        return ops;
    }

    private static List<SpecOperation> cryptoApproveAllowancePermutations(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            for (final int allowanceCount : ALLOWANCE_COUNTS) {
                final String baseName = String.format("CryptoApproveAllowance_A%d_S%d", allowanceCount, sigCount);
                final String txnName = prefix + baseName;

                final var approve = cryptoApproveAllowance()
                        .payingWith(payer)
                        .signedBy(payer)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(txnName);
                if (allowanceCount >= 1) {
                    approve.addCryptoAllowance(payer, RECEIVER, ONE_HBAR);
                }
                if (allowanceCount >= 2) {
                    approve.addCryptoAllowance(payer, RECEIVER2, ONE_HBAR);
                }
                if (allowanceCount >= 3) {
                    approve.addCryptoAllowance(payer, RECEIVER3, ONE_HBAR);
                }
                ops.add(approve);

                final String emphasis = extrasEmphasis(
                        new ExtraCount("ALLOWANCES", 1, allowanceCount),
                        new ExtraCount("SIGNATURES", 1, sigCount));
                ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
            }
        }
        return ops;
    }

    private static List<SpecOperation> cryptoDeleteAllowancePermutations(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            for (final int allowanceCount : ALLOWANCE_COUNTS) {
                final String baseName = String.format("CryptoDeleteAllowance_A%d_S%d", allowanceCount, sigCount);
                final String txnName = prefix + baseName;

                final List<String> nftTokens = new ArrayList<>();
                for (int i = 0; i < allowanceCount; i++) {
                    final String token = prefix + "DelAllowNFT_" + sigCount + "_" + allowanceCount + "_" + i;
                    nftTokens.add(token);
                    ops.add(tokenCreate(token)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .treasury(payer)
                            .supplyKey(SIMPLE_KEY)
                            .payingWith(payer)
                            .fee(ONE_HUNDRED_HBARS));
                    ops.add(mintToken(token, List.of(ByteString.copyFromUtf8("DA" + i)))
                            .payingWith(payer)
                            .signedBy(payer, SIMPLE_KEY)
                            .fee(ONE_HUNDRED_HBARS));
                }

                final var approve = cryptoApproveAllowance()
                        .payingWith(payer)
                        .signedBy(payer)
                        .fee(ONE_HUNDRED_HBARS);
                for (final String token : nftTokens) {
                    approve.addNftAllowance(payer, token, RECEIVER, false, List.of(1L));
                }
                ops.add(approve);

                final var delete = cryptoDeleteAllowance()
                        .payingWith(payer)
                        .signedBy(payer)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(txnName);
                for (final String token : nftTokens) {
                    delete.addNftDeleteAllowance(payer, token, List.of(1L));
                }
                ops.add(delete);

                final String emphasis = extrasEmphasis(
                        new ExtraCount("ALLOWANCES", 1, allowanceCount),
                        new ExtraCount("SIGNATURES", 1, sigCount));
                ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
            }
        }
        return ops;
    }

    private static List<SpecOperation> cryptoDeletePermutations(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String baseName = String.format("CryptoDelete_S%d", sigCount);
            final String account = prefix + "ToDelete" + sigCount;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(account).balance(0L).payingWith(payer).fee(ONE_HUNDRED_HBARS));
            ops.add(cryptoDelete(account)
                    .transfer(payer)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigCount));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }
        return ops;
    }

    private static List<String> pickTokens(
            final List<String> standard, final List<String> custom, final int count, final boolean useCustom) {
        if (count <= 0) {
            return List.of();
        }
        final List<String> picked = new ArrayList<>();
        int remaining = count;
        if (useCustom && !custom.isEmpty()) {
            picked.add(custom.getFirst());
            remaining -= 1;
        }
        for (int i = 0; i < remaining && i < standard.size(); i++) {
            picked.add(standard.get(i));
        }
        return picked;
    }

    private static int countAccounts(final String payer, final List<String> receivers, final boolean hasTokens) {
        final Set<String> accounts = new HashSet<>();
        accounts.add(payer);
        accounts.addAll(receivers);
        if (hasTokens) {
            accounts.add(TREASURY);
        }
        return accounts.size();
    }

    private static void applyTokenCreateKeys(final HapiTokenCreate op, final int keyCount) {
        if (keyCount >= 1) {
            op.adminKey(SIMPLE_KEY);
        }
        if (keyCount >= 2) {
            op.supplyKey(SIMPLE_KEY);
        }
        if (keyCount >= 3) {
            op.freezeKey(SIMPLE_KEY).freezeDefault(false);
        }
        if (keyCount >= 4) {
            op.pauseKey(SIMPLE_KEY);
        }
        if (keyCount >= 5) {
            op.wipeKey(SIMPLE_KEY);
        }
    }

    private static void applyTokenCustomFees(final HapiTokenCreate op, final int feeCount) {
        if (feeCount >= 1) {
            op.withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR));
        }
        if (feeCount >= 2) {
            op.withCustom(fixedHbarFee(2 * ONE_HBAR, FEE_COLLECTOR));
        }
        if (feeCount >= 3) {
            op.withCustom(fractionalFee(1L, 100L, 1L, OptionalLong.of(10L), TREASURY));
        }
    }

    private static void applyTokenUpdateKeys(final HapiTokenUpdate op, final int keyCount) {
        if (keyCount >= 1) {
            op.adminKey(SIMPLE_KEY);
        }
        if (keyCount >= 2) {
            op.supplyKey(SIMPLE_KEY);
        }
        if (keyCount >= 3) {
            op.freezeKey(SIMPLE_KEY);
        }
        if (keyCount >= 4) {
            op.pauseKey(SIMPLE_KEY);
        }
        if (keyCount >= 5) {
            op.wipeKey(SIMPLE_KEY);
        }
    }

    private static List<ByteString> nftMetadata(final String prefix, final int count) {
        if (count <= 0) {
            return List.of();
        }
        final List<ByteString> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(ByteString.copyFromUtf8(prefix + "-" + i));
        }
        return list;
    }

    private static List<SpecOperation> mintNftSerials(
            final String token, final String prefix, final int total, final String payer) {
        final List<SpecOperation> ops = new ArrayList<>();
        int minted = 0;
        while (minted < total) {
            final int batch = Math.min(NFT_MINT_BATCH_SIZE, total - minted);
            ops.add(mintToken(token, nftMetadata(prefix + "-" + minted, batch))
                    .payingWith(payer)
                    .signedBy(payer, SIMPLE_KEY)
                    .fee(ONE_HUNDRED_HBARS));
            minted += batch;
        }
        return ops;
    }

    // ==================== TOKEN TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // TokenCreate extras: KEYS (includedCount=1), TOKEN_CREATE_WITH_CUSTOM_FEE (includedCount=0)
    // TokenMint extras: TOKEN_MINT_NFT (includedCount=0)
    // TokenUpdate extras: KEYS (includedCount=1)
    // TokenBurn/Delete/Freeze/Unfreeze/Pause/Unpause/Associate/Dissociate/GrantKyc/RevokeKyc/
    // TokenReject/TokenAccountWipe/TokenFeeScheduleUpdate/TokenUpdateNfts/TokenClaimAirdrop/TokenCancelAirdrop: none

    private static SpecOperation[] tokenTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        final String mintFungible = prefix + "MintFungible";
        final String mintNft = prefix + "MintNft";

        ops.add(tokenCreate(mintFungible)
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(tokenCreate(mintNft)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .payingWith(PAYER_SIG1)
                .signedBy(PAYER_SIG1, TREASURY)
                .fee(ONE_HUNDRED_HBARS));

        // ========== TokenCreate permutations: KEYS + CUSTOM_FEES + SIGNATURES ==========
        for (final int keyCount : TOKEN_KEY_COUNTS) {
            for (final int feeCount : CUSTOM_FEE_COUNTS) {
                for (final int sigCount : SIGNATURE_COUNTS) {
                    final String payer = payerForSigCount(sigCount);
                    final String baseName = String.format("TokenCreate_K%d_F%d_S%d", keyCount, feeCount, sigCount);
                    final String token = prefix + "FT_" + baseName;
                    final String txnName = prefix + baseName;

                    final HapiTokenCreate create = tokenCreate(token)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1_000_000L)
                            .treasury(TREASURY)
                            .payingWith(payer)
                            .signedBy(payer, TREASURY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via(txnName);
                    applyTokenCreateKeys(create, keyCount);
                    applyTokenCustomFees(create, feeCount);
                    ops.add(create);

                    final int sigs = signatureCount(payer, TREASURY);
                    final int customFeeExtra = feeCount > 0 ? 1 : 0;
                    final String emphasis = extrasEmphasis(
                            new ExtraCount("KEYS", 1, keyCount),
                            new ExtraCount("TOKEN_CREATE_WITH_CUSTOM_FEE", 0, customFeeExtra),
                            new ExtraCount("SIGNATURES", 1, sigs));
                    ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
                }
            }
        }

        // ========== TokenMint permutations: TOKEN_MINT_NFT + SIGNATURES ==========
        for (final int mintCount : NFT_MINT_COUNTS) {
            for (final int sigCount : SIGNATURE_COUNTS) {
                final String payer = payerForSigCount(sigCount);
                final String baseName = String.format("TokenMint_NFT%d_S%d", mintCount, sigCount);
                final String txnName = prefix + baseName;

                if (mintCount == 0) {
                    ops.add(mintToken(mintFungible, 1_000L)
                            .payingWith(payer)
                            .signedBy(payer, SIMPLE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via(txnName));
                } else {
                    ops.add(mintToken(mintNft, nftMetadata(prefix + "Mint", mintCount))
                            .payingWith(payer)
                            .signedBy(payer, SIMPLE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via(txnName));
                }

                final int sigs = signatureCount(payer, SIMPLE_KEY);
                final String emphasis = extrasEmphasis(
                        new ExtraCount("TOKEN_MINT_NFT", 0, mintCount),
                        new ExtraCount("SIGNATURES", 1, sigs));
                ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
            }
        }

        // ========== TokenUpdate permutations: KEYS + SIGNATURES ==========
        for (final int keyCount : KEY_COUNTS) {
            for (final int sigCount : SIGNATURE_COUNTS) {
                final String payer = payerForSigCount(sigCount);
                final String baseName = String.format("TokenUpdate_K%d_S%d", keyCount, sigCount);
                final String token = prefix + "Upd_" + baseName;
                final String txnName = prefix + baseName;

                ops.add(tokenCreate(token)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1_000_000L)
                        .treasury(TREASURY)
                        .adminKey(SIMPLE_KEY)
                        .payingWith(payer)
                        .signedBy(payer, TREASURY)
                        .fee(ONE_HUNDRED_HBARS));

                final HapiTokenUpdate update = tokenUpdate(token)
                        .payingWith(payer)
                        .signedBy(payer, SIMPLE_KEY)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(txnName);
                applyTokenUpdateKeys(update, keyCount);
                ops.add(update);

                final int sigs = signatureCount(payer, SIMPLE_KEY);
                final String emphasis = extrasEmphasis(
                        new ExtraCount("KEYS", 1, keyCount),
                        new ExtraCount("SIGNATURES", 1, sigs));
                ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
            }
        }

        // ========== TokenBurn permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenBurn_S%d", sigCount);
            final String token = prefix + "Burn_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(payer)
                    .supplyKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(burnToken(token, 1L)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenDelete permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenDelete_S%d", sigCount);
            final String token = prefix + "Del_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(payer)
                    .adminKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenDelete(token)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenFreeze permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenFreeze_S%d", sigCount);
            final String token = prefix + "Freeze_" + baseName;
            final String account = prefix + "FreezeAcct_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(account).balance(ONE_HUNDRED_HBARS));
            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(payer)
                    .freezeKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, token)
                    .payingWith(payer)
                    .signedBy(payer, account)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenFreeze(token, account)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenUnfreeze permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenUnfreeze_S%d", sigCount);
            final String token = prefix + "Unfreeze_" + baseName;
            final String account = prefix + "UnfreezeAcct_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(account).balance(ONE_HUNDRED_HBARS));
            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(payer)
                    .freezeKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, token)
                    .payingWith(payer)
                    .signedBy(payer, account)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenFreeze(token, account)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenUnfreeze(token, account)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenPause permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenPause_S%d", sigCount);
            final String token = prefix + "Pause_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(payer)
                    .pauseKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenPause(token)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenUnpause permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenUnpause_S%d", sigCount);
            final String token = prefix + "Unpause_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(payer)
                    .pauseKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenPause(token)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenUnpause(token)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenAssociate permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenAssociate_S%d", sigCount);
            final String account = prefix + "AssocAcct_" + baseName;
            final String token = prefix + "Assoc_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(account).key(keyName).balance(ONE_MILLION_HBARS));
            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(TREASURY)
                    .payingWith(PAYER_SIG1)
                    .signedBy(PAYER_SIG1, TREASURY)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenAssociate(account, token)
                    .payingWith(account)
                    .signedBy(account)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(keyName);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenDissociate permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenDissociate_S%d", sigCount);
            final String account = prefix + "DissocAcct_" + baseName;
            final String token = prefix + "Dissoc_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(account).key(keyName).balance(ONE_MILLION_HBARS));
            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(TREASURY)
                    .payingWith(PAYER_SIG1)
                    .signedBy(PAYER_SIG1, TREASURY)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, token)
                    .payingWith(account)
                    .signedBy(account)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenDissociate(account, token)
                    .payingWith(account)
                    .signedBy(account)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(keyName);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenGrantKyc permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenGrantKyc_S%d", sigCount);
            final String token = prefix + "Kyc_" + baseName;
            final String account = prefix + "KycAcct_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(account).balance(ONE_HUNDRED_HBARS));
            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(payer)
                    .kycKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, token)
                    .payingWith(payer)
                    .signedBy(payer, account)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(grantTokenKyc(token, account)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenRevokeKyc permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenRevokeKyc_S%d", sigCount);
            final String token = prefix + "Revoke_" + baseName;
            final String account = prefix + "RevokeAcct_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(account).balance(ONE_HUNDRED_HBARS));
            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(payer)
                    .kycKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, token)
                    .payingWith(payer)
                    .signedBy(payer, account)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(grantTokenKyc(token, account)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(revokeTokenKyc(token, account)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenReject permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenReject_S%d", sigCount);
            final String owner = prefix + "RejectOwner_" + baseName;
            final String account = prefix + "RejectAcct_" + baseName;
            final String token = prefix + "Reject_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(owner).balance(ONE_MILLION_HBARS));
            ops.add(cryptoCreate(account).key(keyName).balance(ONE_MILLION_HBARS));
            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(owner)
                    .payingWith(owner)
                    .signedBy(owner)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, token)
                    .payingWith(owner)
                    .signedBy(owner, account)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(cryptoTransfer(moving(10L, token).between(owner, account))
                    .payingWith(owner)
                    .signedBy(owner)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenReject(rejectingToken(token))
                    .payingWith(account)
                    .signedBy(account)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(keyName);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenAccountWipe permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenWipe_S%d", sigCount);
            final String token = prefix + "Wipe_" + baseName;
            final String account = prefix + "WipeAcct_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(account).balance(ONE_HUNDRED_HBARS));
            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(payer)
                    .supplyKey(keyName)
                    .wipeKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, token)
                    .payingWith(payer)
                    .signedBy(payer, account)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(cryptoTransfer(moving(10L, token).between(payer, account))
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(wipeTokenAccount(token, account, 5L)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenFeeScheduleUpdate permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenFeeScheduleUpdate_S%d", sigCount);
            final String token = prefix + "Fee_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(payer)
                    .feeScheduleKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenFeeScheduleUpdate(token)
                    .withCustom(fixedHbarFee(ONE_HBAR, FEE_COLLECTOR))
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenUpdateNfts permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenUpdateNfts_S%d", sigCount);
            final String token = prefix + "UpdNft_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(tokenCreate(token)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .initialSupply(0L)
                    .treasury(payer)
                    .supplyKey(keyName)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(mintToken(token, List.of(ByteString.copyFromUtf8(prefix + "Meta-" + sigCount)))
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenUpdateNfts(token, "updated-" + sigCount, List.of(1L))
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenClaimAirdrop permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenClaimAirdrop_S%d", sigCount);
            final String owner = prefix + "ClaimOwner_" + baseName;
            final String receiver = prefix + "ClaimReceiver_" + baseName;
            final String token = prefix + "Claim_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(owner).balance(ONE_MILLION_HBARS));
            ops.add(cryptoCreate(receiver).key(keyName).balance(ONE_MILLION_HBARS).maxAutomaticTokenAssociations(0));
            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(owner)
                    .payingWith(owner)
                    .signedBy(owner)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAirdrop(moving(10L, token).between(owner, receiver))
                    .payingWith(owner)
                    .signedBy(owner)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenClaimAirdrop(
                            com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop(
                                    owner, receiver, token))
                    .payingWith(receiver)
                    .signedBy(receiver)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(keyName);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== TokenCancelAirdrop permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String keyName = keyForCount(sigCount);
            final String baseName = String.format("TokenCancelAirdrop_S%d", sigCount);
            final String owner = prefix + "CancelOwner_" + baseName;
            final String receiver = prefix + "CancelReceiver_" + baseName;
            final String token = prefix + "Cancel_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(cryptoCreate(owner).key(keyName).balance(ONE_MILLION_HBARS));
            ops.add(cryptoCreate(receiver).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0));
            ops.add(tokenCreate(token)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(1_000L)
                    .treasury(owner)
                    .payingWith(owner)
                    .signedBy(owner)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAirdrop(moving(10L, token).between(owner, receiver))
                    .payingWith(owner)
                    .signedBy(owner)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(tokenCancelAirdrop(
                            com.hedera.services.bdd.spec.transactions.token.HapiTokenCancelAirdrop.pendingAirdrop(
                                    owner, receiver, token))
                    .payingWith(owner)
                    .signedBy(owner)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(keyName);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

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

        final Map<Integer, String> submitTopics = new HashMap<>();
        for (final int feeCount : TOPIC_CUSTOM_FEE_COUNTS) {
            final String topic = prefix + "SubmitTopic_F" + feeCount;
            final var create = createTopic(topic).payingWith(PAYER_SIG1).signedBy(PAYER_SIG1).fee(ONE_HUNDRED_HBARS);
            for (int i = 0; i < feeCount; i++) {
                create.withConsensusCustomFee(fixedConsensusHbarFee(i + 1L, FEE_COLLECTOR));
            }
            ops.add(create);
            submitTopics.put(feeCount, topic);
        }

        // ========== ConsensusCreateTopic permutations: KEYS + CUSTOM_FEES + SIGNATURES ==========
        for (final int keyCount : TOPIC_KEY_COUNTS) {
            for (final int feeCount : TOPIC_CUSTOM_FEE_COUNTS) {
                for (final int sigCount : SIGNATURE_COUNTS) {
                    final String payer = payerForSigCount(sigCount);
                    final String baseName = String.format("TopicCreate_K%d_F%d_S%d", keyCount, feeCount, sigCount);
                    final String topic = prefix + baseName;
                    final String txnName = prefix + baseName;

                    final var create = createTopic(topic)
                            .payingWith(payer)
                            .signedBy(payer)
                            .fee(ONE_HUNDRED_HBARS)
                            .via(txnName);
                    if (keyCount >= 1) {
                        create.adminKeyName(SIMPLE_KEY);
                    }
                    if (keyCount >= 2) {
                        create.submitKeyName(SIMPLE_KEY);
                    }
                    for (int i = 0; i < feeCount; i++) {
                        create.withConsensusCustomFee(fixedConsensusHbarFee(i + 1L, FEE_COLLECTOR));
                    }
                    ops.add(create);

                    final int customFeeExtra = feeCount > 0 ? 1 : 0;
                    final String emphasis = extrasEmphasis(
                            new ExtraCount("KEYS", 0, keyCount),
                            new ExtraCount("CONSENSUS_CREATE_TOPIC_WITH_CUSTOM_FEE", 0, customFeeExtra),
                            new ExtraCount("SIGNATURES", 1, sigCount));
                    ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
                }
            }
        }

        // ========== ConsensusSubmitMessage permutations: BYTES + CUSTOM_FEES + SIGNATURES ==========
        for (final int bytes : TOPIC_MESSAGE_BYTES) {
            for (final int feeCount : TOPIC_CUSTOM_FEE_COUNTS) {
                for (final int sigCount : SIGNATURE_COUNTS) {
                    final String payer = payerForSigCount(sigCount);
                    final String baseName = String.format("Submit_B%d_F%d_S%d", bytes, feeCount, sigCount);
                    final String txnName = prefix + baseName;
                    final String topic = submitTopics.get(feeCount);

                    ops.add(submitMessageTo(topic)
                            .message("x".repeat(bytes))
                            .payingWith(payer)
                            .signedBy(payer)
                            .fee(ONE_HUNDRED_HBARS)
                            .via(txnName));

                    final int customFeeExtra = feeCount > 0 ? 1 : 0;
                    final String emphasis = extrasEmphasis(
                            new ExtraCount("BYTES", 100, bytes),
                            new ExtraCount("CONSENSUS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE", 0, customFeeExtra),
                            new ExtraCount("SIGNATURES", 1, sigCount));
                    ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
                }
            }
        }

        // ========== ConsensusUpdateTopic permutations: KEYS + SIGNATURES ==========
        for (final int keyCount : new int[] {1, 2}) {
            for (final int sigCount : SIGNATURE_COUNTS) {
                final String payer = payerForSigCount(sigCount);
                final String baseName = String.format("TopicUpdate_K%d_S%d", keyCount, sigCount);
                final String topic = prefix + "Upd_" + baseName;
                final String txnName = prefix + baseName;

                ops.add(createTopic(topic)
                        .adminKeyName(SIMPLE_KEY)
                        .submitKeyName(SIMPLE_KEY)
                        .payingWith(payer)
                        .signedBy(payer)
                        .fee(ONE_HUNDRED_HBARS));

                final var update = updateTopic(topic)
                        .topicMemo("updated")
                        .payingWith(payer)
                        .signedBy(payer, SIMPLE_KEY)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(txnName);
                if (keyCount >= 1) {
                    update.adminKey(SIMPLE_KEY);
                }
                if (keyCount >= 2) {
                    update.submitKey(SIMPLE_KEY);
                }
                ops.add(update);

                final int sigs = signatureCount(payer, SIMPLE_KEY);
                final String emphasis = extrasEmphasis(
                        new ExtraCount("KEYS", 1, keyCount),
                        new ExtraCount("SIGNATURES", 1, sigs));
                ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
            }
        }

        // ========== ConsensusDeleteTopic permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String baseName = String.format("TopicDelete_S%d", sigCount);
            final String topic = prefix + "Del_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(createTopic(topic)
                    .adminKeyName(SIMPLE_KEY)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(deleteTopic(topic)
                    .payingWith(payer)
                    .signedBy(payer, SIMPLE_KEY)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer, SIMPLE_KEY);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

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

        // ========== FileCreate permutations: KEYS + BYTES + SIGNATURES ==========
        for (final int keyCount : FILE_KEY_COUNTS) {
            final String keyName = keyForCount(keyCount);
            for (final int bytes : FILE_CREATE_BYTES) {
                for (final int sigCount : SIGNATURE_COUNTS) {
                    final String payer = payerForSigCount(sigCount);
                    final String baseName = String.format("FileCreate_K%d_B%d_S%d", keyCount, bytes, sigCount);
                    final String file = prefix + baseName;
                    final String txnName = prefix + baseName;

                    ops.add(fileCreate(file)
                            .key(keyName)
                            .contents("x".repeat(bytes))
                            .payingWith(payer)
                            .signedBy(payer, keyName)
                            .fee(ONE_HUNDRED_HBARS)
                            .via(txnName));

                    final int sigs = signatureCount(payer, keyName);
                    final String emphasis = extrasEmphasis(
                            new ExtraCount("KEYS", 1, keyCount),
                            new ExtraCount("BYTES", 1000, bytes),
                            new ExtraCount("SIGNATURES", 1, sigs));
                    ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
                }
            }
        }

        // ========== FileUpdate permutations: KEYS + BYTES + SIGNATURES ==========
        for (final int keyCount : FILE_KEY_COUNTS) {
            final String keyName = keyForCount(keyCount);
            for (final int bytes : FILE_UPDATE_BYTES) {
                for (final int sigCount : SIGNATURE_COUNTS) {
                    final String payer = payerForSigCount(sigCount);
                    final String baseName = String.format("FileUpdate_K%d_B%d_S%d", keyCount, bytes, sigCount);
                    final String file = prefix + baseName;
                    final String txnName = prefix + baseName;

                    ops.add(fileCreate(file)
                            .key(keyName)
                            .contents("x".repeat(100))
                            .payingWith(payer)
                            .signedBy(payer, keyName)
                            .fee(ONE_HUNDRED_HBARS));

                    ops.add(fileUpdate(file)
                            .contents("y".repeat(bytes))
                            .payingWith(payer)
                            .signedBy(payer, keyName)
                            .fee(ONE_HUNDRED_HBARS)
                            .via(txnName));

                    final int sigs = signatureCount(payer, keyName);
                    final String emphasis = extrasEmphasis(
                            new ExtraCount("KEYS", 1, keyCount),
                            new ExtraCount("BYTES", 1000, bytes),
                            new ExtraCount("SIGNATURES", 1, sigs));
                    ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
                }
            }
        }

        // ========== FileAppend permutations: BYTES + SIGNATURES ==========
        for (final int bytes : FILE_APPEND_BYTES) {
            for (final int sigCount : SIGNATURE_COUNTS) {
                final String payer = payerForSigCount(sigCount);
                final String baseName = String.format("FileAppend_B%d_S%d", bytes, sigCount);
                final String file = prefix + baseName;
                final String txnName = prefix + baseName;

                ops.add(fileCreate(file)
                        .key(SIMPLE_KEY)
                        .contents("x".repeat(100))
                        .payingWith(payer)
                        .signedBy(payer, SIMPLE_KEY)
                        .fee(ONE_HUNDRED_HBARS));

                ops.add(fileAppend(file)
                        .content("z".repeat(bytes))
                        .payingWith(payer)
                        .signedBy(payer, SIMPLE_KEY)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(txnName));

                final int sigs = signatureCount(payer, SIMPLE_KEY);
                final String emphasis = extrasEmphasis(
                        new ExtraCount("BYTES", 1000, bytes),
                        new ExtraCount("SIGNATURES", 1, sigs));
                ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
            }
        }

        // ========== FileDelete permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String baseName = String.format("FileDelete_S%d", sigCount);
            final String file = prefix + baseName;
            final String txnName = prefix + baseName;

            ops.add(fileCreate(file)
                    .key(SIMPLE_KEY)
                    .contents("x".repeat(100))
                    .payingWith(payer)
                    .signedBy(payer, SIMPLE_KEY)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(fileDelete(file)
                    .payingWith(payer)
                    .signedBy(payer, SIMPLE_KEY)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer, SIMPLE_KEY);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== SCHEDULE TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // ScheduleCreate extras: KEYS (includedCount=1), SCHEDULE_CREATE_CONTRACT_CALL_BASE (0)
    // ScheduleSign extras: none
    // ScheduleDelete extras: none

    private static SpecOperation[] scheduleTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ========== ScheduleCreate permutations: KEYS + CONTRACT_CALL_BASE + SIGNATURES ==========
        for (final int keyCount : SCHEDULE_KEY_COUNTS) {
            final String keyName = keyCount == 0 ? null : keyForCount(keyCount);
            for (final int contractCallFlag : new int[] {0, 1}) {
                for (final int sigCount : SIGNATURE_COUNTS) {
                    final String payer = payerForSigCount(sigCount);
                    final String baseName = String.format("ScheduleCreate_K%d_C%d_S%d", keyCount, contractCallFlag, sigCount);
                    final String schedule = prefix + baseName;
                    final String txnName = prefix + baseName;

                    final var scheduledOp = contractCallFlag == 1
                            ? contractCall(SCHEDULE_CONTRACT, "store", BigInteger.valueOf(1L)).gas(50_000L)
                            : cryptoTransfer(tinyBarsFromTo(RECEIVER, payer, 1L));

                    final var create = scheduleCreate(schedule, scheduledOp)
                            .payingWith(payer)
                            .signedBy(payer)
                            .fee(ONE_HUNDRED_HBARS)
                            .via(txnName);
                    if (keyName != null) {
                        create.adminKey(keyName);
                    }
                    ops.add(create);

                    final int sigs = signatureCount(payer);
                    final String emphasis = extrasEmphasis(
                            new ExtraCount("KEYS", 1, keyCount),
                            new ExtraCount("SCHEDULE_CREATE_CONTRACT_CALL_BASE", 0, contractCallFlag),
                            new ExtraCount("SIGNATURES", 1, sigs));
                    ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
                }
            }
        }

        // ========== ScheduleSign permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String baseName = String.format("ScheduleSign_S%d", sigCount);
            final String schedule = prefix + "Sign_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(RECEIVER, payer, 1L)))
                    .adminKey(SIMPLE_KEY)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(scheduleSign(schedule)
                    .alsoSigningWith(RECEIVER)
                    .payingWith(payer)
                    .signedBy(payer, RECEIVER)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer, RECEIVER);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== ScheduleDelete permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String baseName = String.format("ScheduleDelete_S%d", sigCount);
            final String schedule = prefix + "Del_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(scheduleCreate(schedule, cryptoTransfer(tinyBarsFromTo(RECEIVER, payer, 1L)))
                    .adminKey(SIMPLE_KEY)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(scheduleDelete(schedule)
                    .signedBy(payer, SIMPLE_KEY)
                    .payingWith(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer, SIMPLE_KEY);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== CONTRACT TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // ContractCreate/Call extras: GAS (fee per unit)
    // ContractUpdate/Delete/HookStore extras: none

    
    private static SpecOperation[] contractTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        final String callContract = prefix + "CallContract";
        ops.add(contractCreate(callContract)
                .bytecode(STORAGE_CONTRACT)
                .adminKey(SIMPLE_KEY)
                .gas(200_000L)
                .payingWith(PAYER_SIG1)
                .fee(ONE_HUNDRED_HBARS));

        final var storeAbi = getABIFor(FUNCTION, "store", STORAGE_CONTRACT);

        // ========== HookStore permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = hookedPayerName(sigCount, 1);
            final String baseName = String.format("HookStore_S%d", sigCount);
            final String txnName = prefix + baseName;

            ops.add(accountEvmHookStore(payer, HOOK_IDS[0])
                    .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                    .payingWith(payer)
                    .signedBy(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== ContractCreate permutations: GAS + SIGNATURES ==========
        for (final int gas : CONTRACT_GAS) {
            for (final int sigCount : SIGNATURE_COUNTS) {
                final String payer = payerForSigCount(sigCount);
                final String baseName = String.format("ContractCreate_G%d_S%d", gas, sigCount);
                final String contract = prefix + baseName;
                final String txnName = prefix + baseName;

                ops.add(contractCreate(contract)
                        .bytecode(STORAGE_CONTRACT)
                        .gas(gas)
                        .payingWith(payer)
                        .signedBy(payer)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(txnName));

                final int sigs = signatureCount(payer);
                final String emphasis = extrasEmphasis(
                        new ExtraCount("GAS", 0, gas),
                        new ExtraCount("SIGNATURES", 1, sigs));
                ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
            }
        }

        // ========== ContractCall permutations: GAS + SIGNATURES ==========
        for (final int gas : CONTRACT_GAS) {
            for (final int sigCount : SIGNATURE_COUNTS) {
                final String payer = payerForSigCount(sigCount);
                final String baseName = String.format("ContractCall_G%d_S%d", gas, sigCount);
                final String txnName = prefix + baseName;

                ops.add(contractCallWithFunctionAbi(callContract, storeAbi, BigInteger.valueOf(gas))
                        .gas(gas)
                        .payingWith(payer)
                        .signedBy(payer)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(txnName));

                final int sigs = signatureCount(payer);
                final String emphasis = extrasEmphasis(
                        new ExtraCount("GAS", 0, gas),
                        new ExtraCount("SIGNATURES", 1, sigs));
                ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
            }
        }

        // ========== ContractUpdate permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String baseName = String.format("ContractUpdate_S%d", sigCount);
            final String contract = prefix + "Upd_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(contractCreate(contract)
                    .bytecode(STORAGE_CONTRACT)
                    .adminKey(SIMPLE_KEY)
                    .gas(200_000L)
                    .payingWith(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(contractUpdate(contract)
                    .newMemo("updated")
                    .signedBy(payer, SIMPLE_KEY)
                    .payingWith(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer, SIMPLE_KEY);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        // ========== ContractDelete permutations: SIGNATURES ==========
        for (final int sigCount : SIGNATURE_COUNTS) {
            final String payer = payerForSigCount(sigCount);
            final String baseName = String.format("ContractDelete_S%d", sigCount);
            final String contract = prefix + "Del_" + baseName;
            final String txnName = prefix + baseName;

            ops.add(contractCreate(contract)
                    .bytecode(STORAGE_CONTRACT)
                    .adminKey(SIMPLE_KEY)
                    .gas(200_000L)
                    .payingWith(payer)
                    .fee(ONE_HUNDRED_HBARS));

            ops.add(contractDelete(contract)
                    .transferAccount(payer)
                    .signedBy(payer, SIMPLE_KEY)
                    .payingWith(payer)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));

            final int sigs = signatureCount(payer, SIMPLE_KEY);
            final String emphasis = extrasEmphasis(new ExtraCount("SIGNATURES", 1, sigs));
            ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
        }

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== BATCH TRANSACTIONS WITH EXTRAS ====================
    // Node extras: SIGNATURES (includedCount=1)
    // AtomicBatch extras: innerTxns count

    private static SpecOperation[] batchTransactionsWithExtras(String prefix, Map<String, FeeEntry> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        for (final int innerCount : BATCH_INNER_COUNTS) {
            for (final int sigCount : SIGNATURE_COUNTS) {
                final String payer = payerForSigCount(sigCount);
                final String baseName = String.format("Batch_N%d_S%d", innerCount, sigCount);
                final String txnName = prefix + baseName;

                final List<com.hedera.services.bdd.spec.transactions.token.TokenMovement> movements = new ArrayList<>();
                for (int i = 0; i < innerCount; i++) {
                    movements.add(movingHbar(i + 1L).between(payer, RECEIVER));
                }

                final List<com.hedera.services.bdd.spec.transactions.HapiTxnOp<?>> batchOps = new ArrayList<>();
                for (final var movement : movements) {
                    batchOps.add(cryptoTransfer(movement).batchKey(BATCH_OPERATOR));
                }

                final var batch = atomicBatch(batchOps.toArray(new com.hedera.services.bdd.spec.transactions.HapiTxnOp<?>[0]))
                        .signedBy(BATCH_OPERATOR, payer)
                        .payingWith(BATCH_OPERATOR)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(txnName);
                ops.add(batch);

                final int sigs = signatureCount(BATCH_OPERATOR, payer);
                final String emphasis = extrasEmphasis(
                        new ExtraCount("INNER_TXNS", 0, innerCount),
                        new ExtraCount("SIGNATURES", 1, sigs));
                ops.add(captureFeeWithEmphasis(txnName, emphasis, feeMap));
            }
        }

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
                    final String fullEmphasis = legacyEntry.emphasis();
                    String logEmphasis = fullEmphasis;
                    if (logEmphasis.length() > 45) {
                        logEmphasis = logEmphasis.substring(0, 42) + "...";
                    }
                    LOG.info(String.format(
                            "%-35s %-45s %15d %15d %15d %9.2f%%",
                            baseName, logEmphasis, legacyEntry.fee(), simpleEntry.fee(), diff, pctChange));
                    csv.field(serviceName);
                    csv.field(baseName);
                    csv.field(fullEmphasis);
                    csv.field(legacyEntry.fee());
                    csv.field(simpleEntry.fee());
                    csv.field(diff);
                    csv.fieldPercentage(pctChange);
                    csv.endLine();

                    ALL_ROWS.add(new FeeComparisonRow(
                            serviceName, baseName, fullEmphasis, legacyEntry.fee(), simpleEntry.fee(), diff, pctChange));
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

            csv.field(serviceName);
            csv.field("TOTAL");
            csv.field("");
            csv.field(totalLegacy);
            csv.field(totalSimple);
            csv.field(totalDiff);
            csv.fieldPercentage(totalPctChange);
            csv.endLine();
        });
    }

    private static void logFinalComparisonTable() throws IOException {
        if (ALL_ROWS.isEmpty()) {
            return;
        }
        final List<FeeComparisonRow> rows;
        synchronized (ALL_ROWS) {
            rows = new ArrayList<>(ALL_ROWS);
        }
        rows.sort(Comparator.comparing(FeeComparisonRow::service).thenComparing(FeeComparisonRow::txnName));

        LOG.info("\n========== FINAL FEE COMPARISON (ALL SERVICES) ==========");
        LOG.info(String.format(
                "%-18s %-35s %-70s %15s %15s %15s %10s",
                "Service", "Transaction", "Emphasis", "Legacy Fee", "Simple Fee", "Difference", "% Change"));
        LOG.info("=".repeat(175));

        long totalLegacy = 0L;
        long totalSimple = 0L;
        for (final var row : rows) {
            totalLegacy += row.legacyFee();
            totalSimple += row.simpleFee();
            String logEmphasis = row.emphasis();
            if (logEmphasis.length() > 70) {
                logEmphasis = logEmphasis.substring(0, 67) + "...";
            }
            LOG.info(String.format(
                    "%-18s %-35s %-70s %15d %15d %15d %9.2f%%",
                    row.service(),
                    row.txnName(),
                    logEmphasis,
                    row.legacyFee(),
                    row.simpleFee(),
                    row.diff(),
                    row.pct()));
        }
        final long totalDiff = totalSimple - totalLegacy;
        final double totalPctChange = totalLegacy > 0 ? (totalDiff * 100.0 / totalLegacy) : 0;
        LOG.info("=".repeat(175));
        LOG.info(String.format(
                "%-18s %-35s %-70s %15d %15d %15d %9.2f%%",
                "ALL", "TOTAL", "", totalLegacy, totalSimple, totalDiff, totalPctChange));
        LOG.info("=".repeat(175) + "\n");

        if (csv != null) {
            csv.field("ALL");
            csv.field("TOTAL");
            csv.field("");
            csv.field(totalLegacy);
            csv.field(totalSimple);
            csv.field(totalDiff);
            csv.fieldPercentage(totalPctChange);
            csv.endLine();
        }
    }
}
