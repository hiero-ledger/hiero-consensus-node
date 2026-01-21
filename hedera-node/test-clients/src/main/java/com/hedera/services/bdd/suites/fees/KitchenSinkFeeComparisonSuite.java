// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.SECP256K1;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocalWithFunctionAbi;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getVersionInfo;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDeleteAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiPrng;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.nodeUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileUndelete;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingToken;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeAbort;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.FREEZE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_DELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_UNDELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.hip869.NodeCreateTest.generateX509Certificates;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCancelAirdrop;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.security.cert.CertificateEncodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
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
 * <p>This test uses:
 * <ul>
 *   <li>{@code @LeakyHapiTest} - allows overriding network properties</li>
 *   <li>ED25519 keys by default (plus a single SECP256K1 key for EthereumTransaction coverage)</li>
 *   <li>Fixed entity names with prefixes to avoid collisions between runs</li>
 * </ul>
 */
@Tag(SIMPLE_FEES)
@Tag(ONLY_EMBEDDED)
@HapiTestLifecycle
public class KitchenSinkFeeComparisonSuite {
    private static final Logger LOG = LogManager.getLogger(KitchenSinkFeeComparisonSuite.class);

    /**
     * Initialize the test class with simple fees enabled.
     * This ensures the SimpleFeeCalculator is initialized at startup,
     * which is required for switching between simple and legacy fees mid-test.
     */
    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) throws IOException, CertificateEncodingException {
        gossipCertBytes = generateX509Certificates(1).getFirst().getEncoded();
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true",
                "tokens.airdrops.enabled", "true",
                "tokens.airdrops.claim.enabled", "true",
                "tokens.airdrops.cancel.enabled", "true"));
    }

    // Key names for different complexity levels
    private static final String SIMPLE_KEY = "simpleKey";
    private static final String LIST_KEY_1 = "listKey1";
    private static final String LIST_KEY_2 = "listKey2";
    private static final String LIST_KEY_3 = "listKey3";
    private static final String LIST_KEY_5 = "listKey5";
    private static final String THRESH_KEY_2_OF_3 = "threshKey2of3";
    private static final String THRESH_KEY_3_OF_5 = "threshKey3of5";
    private static final String COMPLEX_KEY = "complexKey";
    private static final String SIG_KEY_1 = "sigKey1";
    private static final String SIG_KEY_2 = "sigKey2";
    private static final String BATCH_OPERATOR = "batchOperator";

    // Payer names
    private static final String PAYER = "payer";
    private static final String PAYER_COMPLEX = "payerComplex";
    private static final String TREASURY = "treasury";
    private static final String RECEIVER = "receiver";

    private static byte[] gossipCertBytes;

    // Memo variations (max memo length is 100 bytes)
    private static final String EMPTY_MEMO = "";
    private static final String SHORT_MEMO = "Short memo";
    private static final String MEDIUM_MEMO = "x".repeat(50);
    private static final String LONG_MEMO = "x".repeat(100); // Max allowed memo length

    private record SigVariant(String suffix, String... extraSigners) {
        String[] withRequired(final String... requiredSigners) {
            final Set<String> merged = new LinkedHashSet<>();
            merged.addAll(Arrays.asList(requiredSigners));
            merged.addAll(Arrays.asList(extraSigners));
            return merged.toArray(new String[0]);
        }
    }

    private record KeyVariant(String suffix, String label, String keyName) {}

    private static final List<SigVariant> SIG_VARIANTS =
            List.of(new SigVariant("S1"), new SigVariant("S2", SIG_KEY_1), new SigVariant("S3", SIG_KEY_1, SIG_KEY_2));

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Kitchen sink fee comparison - all transaction types with varying parameters")
    final Stream<DynamicTest> kitchenSinkFeeComparison() {
        // Maps to store fees from both runs
        final Map<String, Long> legacyFees = new LinkedHashMap<>();
        final Map<String, Long> simpleFees = new LinkedHashMap<>();

        // Note: We run simple fees FIRST because the SimpleFeeCalculator is only initialized
        // when simpleFeesEnabled=true at startup. If we start with false and switch to true,
        // the SimpleFeeCalculator will be null and cause a NullPointerException.
        return hapiTest(
                // === SETUP: Create keys with varying complexity ===
                createAllKeys(),
                // === SETUP: Create base accounts ===
                createBaseAccounts(),
                // === RUN 1: Simple fees (simpleFeesEnabled=true) - must run first ===
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(runCryptoTransactions("simple", simpleFees)),
                // === RUN 2: Legacy fees (simpleFeesEnabled=false) ===
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(runCryptoTransactions("legacy", legacyFees)),
                // === COMPARE AND LOG RESULTS ===
                logFeeComparison(legacyFees, simpleFees),
                // === WRITE RESULTS TO CSV ===
                writeFeeComparisonToCsv(legacyFees, simpleFees, "fee-comparison-crypto.csv"));
    }

    /**
     * Runs only crypto transactions for a simpler test.
     */
    private static SpecOperation[] runCryptoTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();
        ops.addAll(cryptoTransactions(prefix, feeMap));
        return ops.toArray(new SpecOperation[0]);
    }

    /**
     * Full kitchen sink test with all transaction types.
     * This is a separate test method to allow running just crypto transactions first.
     */
    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Kitchen sink fee comparison - full suite with all transaction types")
    final Stream<DynamicTest> kitchenSinkFeeComparisonFull() {
        // Maps to store fees from both runs
        final Map<String, Long> legacyFees = new LinkedHashMap<>();
        final Map<String, Long> simpleFees = new LinkedHashMap<>();

        // Note: We run simple fees FIRST because the SimpleFeeCalculator is only initialized
        // when simpleFeesEnabled=true at startup. If we start with false and switch to true,
        // the SimpleFeeCalculator will be null and cause a NullPointerException.
        return hapiTest(
                // === SETUP: Create keys with varying complexity ===
                createAllKeys(),
                // === SETUP: Create base accounts ===
                createBaseAccounts(),
                // === RUN 1: Simple fees (simpleFeesEnabled=true) - must run first ===
                overriding("fees.simpleFeesEnabled", "true"),
                blockingOrder(runAllTransactions("simple", simpleFees)),
                // === RUN 2: Legacy fees (simpleFeesEnabled=false) ===
                overriding("fees.simpleFeesEnabled", "false"),
                blockingOrder(runAllTransactions("legacy", legacyFees)),
                // === COMPARE AND LOG RESULTS ===
                logFeeComparison(legacyFees, simpleFees),
                // === WRITE RESULTS TO CSV ===
                writeFeeComparisonToCsv(legacyFees, simpleFees, "fee-comparison-full.csv"));
    }

    // ==================== KEY CREATION ====================

    private static SpecOperation createAllKeys() {
        return blockingOrder(
                newKeyNamed(SIMPLE_KEY).shape(ED25519),
                newKeyNamed(LIST_KEY_1).shape(listOf(1)),
                newKeyNamed(LIST_KEY_2).shape(listOf(2)),
                newKeyNamed(LIST_KEY_3).shape(listOf(3)),
                newKeyNamed(LIST_KEY_5).shape(listOf(5)),
                newKeyNamed(THRESH_KEY_2_OF_3).shape(threshOf(2, 3)),
                newKeyNamed(THRESH_KEY_3_OF_5).shape(threshOf(3, 5)),
                newKeyNamed(COMPLEX_KEY).shape(threshOf(2, listOf(2), KeyShape.SIMPLE, threshOf(1, 2))),
                newKeyNamed(SIG_KEY_1).shape(ED25519),
                newKeyNamed(SIG_KEY_2).shape(ED25519),
                newKeyNamed(BATCH_OPERATOR).shape(ED25519),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP256K1));
    }

    // ==================== BASE ACCOUNT CREATION ====================

    private static SpecOperation createBaseAccounts() {
        return blockingOrder(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SIMPLE_KEY),
                cryptoCreate(PAYER_COMPLEX).balance(ONE_MILLION_HBARS).key(COMPLEX_KEY),
                cryptoCreate(TREASURY).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS).key(SIMPLE_KEY),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS).key(BATCH_OPERATOR));
    }

    // ==================== TRANSACTION SUITE ====================

    private static SpecOperation[] runAllTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Add all transaction categories
        ops.addAll(cryptoTransactions(prefix, feeMap));
        ops.addAll(tokenTransactions(prefix, feeMap));
        ops.addAll(consensusTransactions(prefix, feeMap));
        ops.addAll(fileTransactions(prefix, feeMap));
        ops.addAll(scheduleTransactions(prefix, feeMap));
        ops.addAll(hookTransactions(prefix, feeMap));
        ops.addAll(contractTransactions(prefix, feeMap));
        ops.addAll(ethereumTransactions(prefix, feeMap));
        ops.addAll(networkTransactions(prefix, feeMap));
        ops.addAll(utilTransactions(prefix, feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== CRYPTO TRANSACTIONS ====================

    private static List<SpecOperation> cryptoTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ===== SETUP: Base accounts used by other crypto tests =====
        ops.add(cryptoCreate(prefix + "AccSimple")
                .key(SIMPLE_KEY)
                .balance(ONE_HBAR)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoCreate(prefix + "AccList2")
                .key(LIST_KEY_2)
                .balance(ONE_HBAR)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoCreate(prefix + "AccList3")
                .key(LIST_KEY_3)
                .balance(ONE_HBAR)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoCreate(prefix + "AccList5")
                .key(LIST_KEY_5)
                .balance(ONE_HBAR)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // ===== SETUP: Hook contracts for HOOK_UPDATES / HOOK_EXECUTION =====
        ops.add(uploadInitCode(TRUE_HOOK_CONTRACT));
        ops.add(contractCreate(prefix + "HookContract1")
                .bytecode(TRUE_HOOK_CONTRACT)
                .gas(5_000_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(uploadInitCode("TruePrePostHook"));
        ops.add(contractCreate(prefix + "HookContract2")
                .bytecode("TruePrePostHook")
                .gas(5_000_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // ===== CryptoCreate with varying KEYS and HOOK_UPDATES =====
        final var createKeyVariants = List.of(
                new KeyVariant("K1", "KEYS=1 (included)", SIMPLE_KEY),
                new KeyVariant("K3", "KEYS=3 (+2 extra)", LIST_KEY_3),
                new KeyVariant("K5", "KEYS=5 (+4 extra)", LIST_KEY_5));
        final int[] hookCounts = {0, 1, 2};
        for (final var keyVariant : createKeyVariants) {
            for (final int hookCount : hookCounts) {
                final String hookLabel = hookCount == 0
                        ? "HOOK_UPDATES=0"
                        : "HOOK_UPDATES=" + hookCount + " (+" + hookCount + " extra)";
                final String txnBase = prefix + "CryptoCreate" + keyVariant.suffix() + "H" + hookCount;
                addWithSigVariants(
                        ops,
                        txnBase,
                        joinEmphasis(keyVariant.label(), hookLabel),
                        feeMap,
                        new String[] {PAYER},
                        (txnName, signers) -> {
                            final var op = cryptoCreate(txnName + "Acct")
                                    .key(keyVariant.keyName())
                                    .balance(ONE_HBAR)
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .signedBy(signers)
                                    .fee(ONE_HUNDRED_HBARS);
                            if (hookCount == 1) {
                                op.withHooks(accountAllowanceHook(1L, prefix + "HookContract1"));
                            } else if (hookCount == 2) {
                                op.withHooks(
                                        accountAllowanceHook(1L, prefix + "HookContract1"),
                                        accountAllowanceHook(2L, prefix + "HookContract2"));
                            }
                            return op;
                        });
            }
        }

        // ===== CryptoUpdate with varying KEYS and HOOK_UPDATES =====
        final var updateKeyVariants = List.of(
                new KeyVariant("K1", "KEYS=1 (included)", SIMPLE_KEY),
                new KeyVariant("K3", "KEYS=3 (+2 extra)", LIST_KEY_3));
        for (final var keyVariant : updateKeyVariants) {
            for (final SigVariant sigVariant : SIG_VARIANTS) {
                final String txnName = prefix + "CryptoUpdate" + keyVariant.suffix() + sigVariant.suffix();
                final String account = txnName + "Acct";
                ops.add(cryptoCreate(account)
                        .key(SIMPLE_KEY)
                        .balance(ONE_HBAR)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS));
                final String[] signers = keyVariant.keyName().equals(SIMPLE_KEY)
                        ? sigVariant.withRequired(PAYER, account)
                        : sigVariant.withRequired(PAYER, account, keyVariant.keyName());
                ops.add(cryptoUpdate(account)
                        .key(keyVariant.keyName())
                        .memo(SHORT_MEMO)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(txnName));
                ops.add(captureFee(
                        txnName,
                        joinEmphasis(keyVariant.label(), "HOOK_UPDATES=0", sigEmphasis(signers)),
                        feeMap));
            }
        }

        for (final int hookCount : hookCounts) {
            if (hookCount == 0) {
                continue;
            }
            for (final SigVariant sigVariant : SIG_VARIANTS) {
                final String txnName = prefix + "CryptoUpdateH" + hookCount + sigVariant.suffix();
                final String account = txnName + "Acct";
                ops.add(cryptoCreate(account)
                        .key(SIMPLE_KEY)
                        .balance(ONE_HBAR)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS));
                final String[] signers = sigVariant.withRequired(PAYER, account);
                final var updateOp = cryptoUpdate(account)
                        .memo(SHORT_MEMO)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS);
                if (hookCount == 1) {
                    updateOp.withHooks(accountAllowanceHook(10L, prefix + "HookContract1"));
                } else {
                    updateOp.withHooks(
                            accountAllowanceHook(10L, prefix + "HookContract1"),
                            accountAllowanceHook(11L, prefix + "HookContract2"));
                }
                ops.add(updateOp.via(txnName));
                ops.add(captureFee(
                        txnName,
                        joinEmphasis(
                                "KEYS=0",
                                "HOOK_UPDATES=" + hookCount + " (+" + hookCount + " extra)",
                                sigEmphasis(signers)),
                        feeMap));
            }
        }

        for (final var keyVariant : updateKeyVariants) {
            for (final SigVariant sigVariant : SIG_VARIANTS) {
                final String txnName =
                        prefix + "CryptoUpdate" + keyVariant.suffix() + "H1" + sigVariant.suffix();
                final String account = txnName + "Acct";
                ops.add(cryptoCreate(account)
                        .key(SIMPLE_KEY)
                        .balance(ONE_HBAR)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS));
                final String[] signers = keyVariant.keyName().equals(SIMPLE_KEY)
                        ? sigVariant.withRequired(PAYER, account)
                        : sigVariant.withRequired(PAYER, account, keyVariant.keyName());
                ops.add(cryptoUpdate(account)
                        .key(keyVariant.keyName())
                        .withHooks(accountAllowanceHook(20L, prefix + "HookContract1"))
                        .memo(SHORT_MEMO)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(txnName));
                ops.add(captureFee(
                        txnName,
                        joinEmphasis(keyVariant.label(), "HOOK_UPDATES=1 (+1 extra)", sigEmphasis(signers)),
                        feeMap));
            }
        }

        // ===== CryptoTransfer with varying ACCOUNTS extra =====
        final String[] hbarSigners = new String[] {PAYER};
        addWithSigVariants(
                ops,
                prefix + "HbarTransferA2",
                "ACCOUNTS=2 (included)",
                feeMap,
                hbarSigners,
                (txnName, signers) -> cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                        .payingWith(PAYER)
                        .blankMemo()
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "HbarTransferA3",
                "ACCOUNTS=3 (+1 extra)",
                feeMap,
                hbarSigners,
                (txnName, signers) -> cryptoTransfer(
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                movingHbar(ONE_HBAR).between(PAYER, prefix + "AccSimple"))
                        .payingWith(PAYER)
                        .blankMemo()
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "HbarTransferA4",
                "ACCOUNTS=4 (+2 extra)",
                feeMap,
                hbarSigners,
                (txnName, signers) -> cryptoTransfer(
                                movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                                movingHbar(ONE_HBAR).between(PAYER, prefix + "AccSimple"),
                                movingHbar(ONE_HBAR).between(PAYER, prefix + "AccList2"))
                        .payingWith(PAYER)
                        .blankMemo()
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== CryptoTransfer with CREATED_ACCOUNTS extra =====
        addWithSigVariants(
                ops,
                prefix + "HbarTransferAliasCreate",
                "CREATED_ACCOUNTS=1 (included)",
                feeMap,
                new String[] {PAYER},
                txnName -> newKeyNamed(txnName + "AliasKey").shape(ED25519),
                (txnName, signers) -> cryptoTransfer(
                                tinyBarsFromAccountToAlias(PAYER, txnName + "AliasKey", ONE_HBAR))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== CryptoApproveAllowance with varying ALLOWANCES extra =====
        addWithSigVariants(
                ops,
                prefix + "CryptoApproveA1",
                "ALLOWANCES=1 (included)",
                feeMap,
                new String[] {PAYER, prefix + "AccSimple"},
                (txnName, signers) -> cryptoApproveAllowance()
                        .addCryptoAllowance(prefix + "AccSimple", RECEIVER, ONE_HBAR)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "CryptoApproveA2",
                "ALLOWANCES=2 (+1 extra)",
                feeMap,
                new String[] {PAYER, prefix + "AccSimple"},
                (txnName, signers) -> cryptoApproveAllowance()
                        .addCryptoAllowance(prefix + "AccSimple", RECEIVER, ONE_HBAR * 5)
                        .addCryptoAllowance(prefix + "AccSimple", prefix + "AccList2", ONE_HBAR * 2)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "CryptoApproveA3",
                "ALLOWANCES=3 (+2 extra)",
                feeMap,
                new String[] {PAYER, prefix + "AccSimple"},
                (txnName, signers) -> cryptoApproveAllowance()
                        .addCryptoAllowance(prefix + "AccSimple", RECEIVER, ONE_HBAR)
                        .addCryptoAllowance(prefix + "AccSimple", prefix + "AccList2", ONE_HBAR)
                        .addCryptoAllowance(prefix + "AccSimple", prefix + "AccList3", ONE_HBAR)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== CryptoDeleteAllowance with varying ALLOWANCES extra =====
        ops.add(tokenCreate(prefix + "NFTForAllowance")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(mintToken(prefix + "NFTForAllowance", List.of(
                        ByteString.copyFromUtf8("NFT_A1"),
                        ByteString.copyFromUtf8("NFT_A2"),
                        ByteString.copyFromUtf8("NFT_A3"),
                        ByteString.copyFromUtf8("NFT_A4"),
                        ByteString.copyFromUtf8("NFT_A5"),
                        ByteString.copyFromUtf8("NFT_A6"),
                        ByteString.copyFromUtf8("NFT_A7"),
                        ByteString.copyFromUtf8("NFT_A8"),
                        ByteString.copyFromUtf8("NFT_A9")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));

        for (int i = 0; i < SIG_VARIANTS.size(); i++) {
            final SigVariant sigVariant = SIG_VARIANTS.get(i);
            final long serialBase = 1L + (i * 3L);
            final String[] signers = sigVariant.withRequired(PAYER, TREASURY);

            // ALLOWANCES=1
            ops.add(cryptoApproveAllowance()
                    .addNftAllowance(TREASURY, prefix + "NFTForAllowance", RECEIVER, false, List.of(serialBase))
                    .payingWith(PAYER)
                    .signedBy(PAYER, TREASURY)
                    .fee(ONE_HUNDRED_HBARS));
            final String delA1 = prefix + "CryptoDeleteAllowanceA1" + sigVariant.suffix();
            ops.add(cryptoDeleteAllowance()
                    .addNftDeleteAllowance(TREASURY, prefix + "NFTForAllowance", List.of(serialBase))
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(delA1));
            ops.add(captureFee(
                    delA1,
                    joinEmphasis("ALLOWANCES=1 (included)", sigEmphasis(signers)),
                    feeMap));

            // ALLOWANCES=2
            ops.add(cryptoApproveAllowance()
                    .addNftAllowance(TREASURY, prefix + "NFTForAllowance", RECEIVER, false, List.of(serialBase + 1))
                    .addNftAllowance(TREASURY, prefix + "NFTForAllowance", RECEIVER, false, List.of(serialBase + 2))
                    .payingWith(PAYER)
                    .signedBy(PAYER, TREASURY)
                    .fee(ONE_HUNDRED_HBARS));
            final String delA2 = prefix + "CryptoDeleteAllowanceA2" + sigVariant.suffix();
            ops.add(cryptoDeleteAllowance()
                    .addNftDeleteAllowance(TREASURY, prefix + "NFTForAllowance", List.of(serialBase + 1))
                    .addNftDeleteAllowance(TREASURY, prefix + "NFTForAllowance", List.of(serialBase + 2))
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(delA2));
            ops.add(captureFee(
                    delA2,
                    joinEmphasis("ALLOWANCES=2 (+1 extra)", sigEmphasis(signers)),
                    feeMap));

            // ALLOWANCES=3
            ops.add(cryptoApproveAllowance()
                    .addNftAllowance(TREASURY, prefix + "NFTForAllowance", RECEIVER, false, List.of(serialBase))
                    .addNftAllowance(TREASURY, prefix + "NFTForAllowance", RECEIVER, false, List.of(serialBase + 1))
                    .addNftAllowance(TREASURY, prefix + "NFTForAllowance", RECEIVER, false, List.of(serialBase + 2))
                    .payingWith(PAYER)
                    .signedBy(PAYER, TREASURY)
                    .fee(ONE_HUNDRED_HBARS));
            final String delA3 = prefix + "CryptoDeleteAllowanceA3" + sigVariant.suffix();
            ops.add(cryptoDeleteAllowance()
                    .addNftDeleteAllowance(TREASURY, prefix + "NFTForAllowance", List.of(serialBase))
                    .addNftDeleteAllowance(TREASURY, prefix + "NFTForAllowance", List.of(serialBase + 1))
                    .addNftDeleteAllowance(TREASURY, prefix + "NFTForAllowance", List.of(serialBase + 2))
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(delA3));
            ops.add(captureFee(
                    delA3,
                    joinEmphasis("ALLOWANCES=3 (+2 extra)", sigEmphasis(signers)),
                    feeMap));
        }

        // ===== CryptoDelete =====
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final String txnName = prefix + "CryptoDelete" + sigVariant.suffix();
            final String account = txnName + "Acct";
            ops.add(cryptoCreate(account)
                    .balance(0L)
                    .payingWith(PAYER)
                    .fee(ONE_HUNDRED_HBARS));
            final String[] signers = sigVariant.withRequired(PAYER, account);
            ops.add(cryptoDelete(account)
                    .transfer(PAYER)
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(txnName, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));
        }

        // ===== CryptoTransfer with TOKEN_TRANSFER_BASE extra =====
        ops.add(tokenCreate(prefix + "FungibleForTransfer1")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "FungibleForTransfer2")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenAssociate(RECEIVER, prefix + "FungibleForTransfer1", prefix + "FungibleForTransfer2")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "TokenTransferFT1",
                "FUNGIBLE_TOKENS=1 (included), TOKEN_TRANSFER_BASE=1",
                feeMap,
                new String[] {PAYER, TREASURY},
                (txnName, signers) -> cryptoTransfer(
                                moving(100, prefix + "FungibleForTransfer1").between(TREASURY, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "TokenTransferFT2",
                "FUNGIBLE_TOKENS=2 (+1 extra), TOKEN_TRANSFER_BASE=1",
                feeMap,
                new String[] {PAYER, TREASURY},
                (txnName, signers) -> cryptoTransfer(
                                moving(100, prefix + "FungibleForTransfer1").between(TREASURY, RECEIVER),
                                moving(200, prefix + "FungibleForTransfer2").between(TREASURY, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== CryptoTransfer with CREATED_AUTO_ASSOCIATIONS extra =====
        final String autoAssocToken = prefix + "AutoAssocToken";
        final String autoAssocAccount = prefix + "AutoAssocAcct";
        ops.add(tokenCreate(autoAssocToken)
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoCreate(autoAssocAccount)
                .key(SIMPLE_KEY)
                .balance(ONE_HBAR)
                .maxAutomaticTokenAssociations(1)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        final String autoAssocTxn = prefix + "TokenTransferAutoAssoc";
        ops.add(cryptoTransfer(moving(10, autoAssocToken).between(TREASURY, autoAssocAccount))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(autoAssocTxn));
        ops.add(captureFee(
                autoAssocTxn,
                joinEmphasis(
                        "CREATED_AUTO_ASSOCIATIONS=1 (included), FUNGIBLE_TOKENS=1 (included), TOKEN_TRANSFER_BASE=1",
                        sigEmphasis(new String[] {PAYER, TREASURY})),
                feeMap));

        // ===== CryptoTransfer with NON_FUNGIBLE_TOKENS extra =====
        ops.add(tokenCreate(prefix + "NFTForTransfer")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(mintToken(prefix + "NFTForTransfer", List.of(
                        ByteString.copyFromUtf8("NFT1"),
                        ByteString.copyFromUtf8("NFT2"),
                        ByteString.copyFromUtf8("NFT3"),
                        ByteString.copyFromUtf8("NFT4"),
                        ByteString.copyFromUtf8("NFT5"),
                        ByteString.copyFromUtf8("NFT6"),
                        ByteString.copyFromUtf8("NFT7"),
                        ByteString.copyFromUtf8("NFT8"),
                        ByteString.copyFromUtf8("NFT9"),
                        ByteString.copyFromUtf8("NFT10")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(mintToken(prefix + "NFTForTransfer", List.of(
                        ByteString.copyFromUtf8("NFT11"),
                        ByteString.copyFromUtf8("NFT12")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenAssociate(RECEIVER, prefix + "NFTForTransfer")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        final long[] nftSingleSerials = {1L, 2L, 3L};
        int nftSingleIdx = 0;
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final long serial = nftSingleSerials[nftSingleIdx++];
            final String txnName = prefix + "TokenTransferNFT1" + sigVariant.suffix();
            final String[] signers = sigVariant.withRequired(PAYER, TREASURY);
            ops.add(cryptoTransfer(
                            movingUnique(prefix + "NFTForTransfer", serial).between(TREASURY, RECEIVER))
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(
                    txnName,
                    joinEmphasis("NON_FUNGIBLE_TOKENS=1 (included), TOKEN_TRANSFER_BASE=1", sigEmphasis(signers)),
                    feeMap));
        }

        final long[][] nftPairSerials = {
                {4L, 5L},
                {6L, 7L},
                {8L, 9L}
        };
        int nftPairIdx = 0;
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final long[] serials = nftPairSerials[nftPairIdx++];
            final String txnName = prefix + "TokenTransferNFT2" + sigVariant.suffix();
            final String[] signers = sigVariant.withRequired(PAYER, TREASURY);
            ops.add(cryptoTransfer(
                            movingUnique(prefix + "NFTForTransfer", serials).between(TREASURY, RECEIVER))
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(
                    txnName,
                    joinEmphasis("NON_FUNGIBLE_TOKENS=2 (+1 extra), TOKEN_TRANSFER_BASE=1", sigEmphasis(signers)),
                    feeMap));
        }

        // ===== CryptoTransfer with NFT_SERIALS extra =====
        ops.add(tokenCreate(prefix + "NFTForSerials")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(mintToken(prefix + "NFTForSerials", List.of(
                        ByteString.copyFromUtf8("NS1"),
                        ByteString.copyFromUtf8("NS2"),
                        ByteString.copyFromUtf8("NS3"),
                        ByteString.copyFromUtf8("NS4"),
                        ByteString.copyFromUtf8("NS5"),
                        ByteString.copyFromUtf8("NS6"),
                        ByteString.copyFromUtf8("NS7"),
                        ByteString.copyFromUtf8("NS8"),
                        ByteString.copyFromUtf8("NS9")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(tokenAssociate(RECEIVER, prefix + "NFTForSerials")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        final long[][] nftSerialTriples = {
                {1L, 2L, 3L},
                {4L, 5L, 6L},
                {7L, 8L, 9L}
        };
        int nftTripleIdx = 0;
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final long[] serials = nftSerialTriples[nftTripleIdx++];
            final String txnName = prefix + "TokenTransferNFTSerials3" + sigVariant.suffix();
            final String[] signers = sigVariant.withRequired(PAYER, TREASURY);
            ops.add(cryptoTransfer(
                            movingUnique(prefix + "NFTForSerials", serials).between(TREASURY, RECEIVER))
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(
                    txnName,
                    joinEmphasis(
                            "NFT_SERIALS=3 (+2 extra), NON_FUNGIBLE_TOKENS=1 (included), TOKEN_TRANSFER_BASE=1",
                            sigEmphasis(signers)),
                    feeMap));
        }

        // ===== CryptoTransfer with FUNGIBLE + NON_FUNGIBLE combo =====
        final long[] nftFtComboSerials = {10L, 11L, 12L};
        int nftFtComboIdx = 0;
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final long serial = nftFtComboSerials[nftFtComboIdx++];
            final String txnName = prefix + "TokenTransferFTNFT" + sigVariant.suffix();
            final String[] signers = sigVariant.withRequired(PAYER, TREASURY);
            ops.add(cryptoTransfer(
                            moving(10, prefix + "FungibleForTransfer1").between(TREASURY, RECEIVER),
                            movingUnique(prefix + "NFTForTransfer", serial).between(TREASURY, RECEIVER))
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(
                    txnName,
                    joinEmphasis(
                            "FUNGIBLE_TOKENS=1 (included), NON_FUNGIBLE_TOKENS=1 (included), TOKEN_TRANSFER_BASE=1",
                            sigEmphasis(signers)),
                    feeMap));
        }

        // ===== CryptoTransfer with TOKEN_TRANSFER_BASE_CUSTOM_FEES extra =====
        ops.add(tokenCreate(prefix + "FungibleWithCustomFee")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .withCustom(fixedHbarFee(ONE_HBAR / 100, TREASURY))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenAssociate(RECEIVER, prefix + "FungibleWithCustomFee")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "TokenTransferCF1",
                "FUNGIBLE_TOKENS=1 (included), TOKEN_TRANSFER_BASE_CUSTOM_FEES=1",
                feeMap,
                new String[] {PAYER, TREASURY},
                (txnName, signers) -> cryptoTransfer(
                                moving(100, prefix + "FungibleWithCustomFee").between(TREASURY, RECEIVER))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== CryptoGetInfo / CryptoGetAccountRecords (queries) =====
        addQueryWithSigVariants(
                ops,
                prefix + "CryptoGetInfo",
                "no extras",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getAccountInfo(PAYER)
                        .payingWith(PAYER)
                        .signedBy(signers));

        addQueryWithSigVariants(
                ops,
                prefix + "CryptoGetAccountRecords",
                "no extras",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getAccountRecords(PAYER)
                        .payingWith(PAYER)
                        .signedBy(signers));

        addQueryWithSigVariants(
                ops,
                prefix + "CryptoGetAccountBalance",
                "no extras",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getAccountBalance(PAYER)
                        .payingWith(PAYER)
                        .signedBy(signers));

        return ops;
    }



    // ==================== TOKEN TRANSACTIONS ====================

    private static List<SpecOperation> tokenTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ===== SETUP: Base tokens used by other token ops =====
        ops.add(tokenCreate(prefix + "FungibleK0")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "FungibleK1")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "FungibleK3")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .supplyKey(SIMPLE_KEY)
                .freezeKey(SIMPLE_KEY)
                .freezeDefault(false)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "FungibleK5")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .supplyKey(SIMPLE_KEY)
                .freezeKey(SIMPLE_KEY)
                .pauseKey(SIMPLE_KEY)
                .wipeKey(SIMPLE_KEY)
                .freezeDefault(false)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "NFT")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "FungibleKyc")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .kycKey(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "FungibleReject")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "FungibleAirdrop")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "NFTMeta")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .metadataKey(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(mintToken(prefix + "NFTMeta", List.of(
                        ByteString.copyFromUtf8("META1"),
                        ByteString.copyFromUtf8("META2"),
                        ByteString.copyFromUtf8("META3")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));

        // ===== SETUP: Associations and balances for token ops =====
        ops.add(tokenAssociate(prefix + "AccSimple", prefix + "FungibleK0", prefix + "FungibleK5")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoTransfer(moving(100L, prefix + "FungibleK5").between(TREASURY, prefix + "AccSimple"))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS));

        // ===== TokenCreate with KEYS + TOKEN_CREATE_WITH_CUSTOM_FEE =====
        final int[] tokenCreateKeyCounts = {0, 1, 3, 5};
        for (final int keyCount : tokenCreateKeyCounts) {
            for (final boolean customFee : new boolean[] {false, true}) {
                final String keyLabel = keyCount == 0
                        ? "KEYS=0 (no keys)"
                        : keyCount == 1 ? "KEYS=1 (included)" : "KEYS=" + keyCount + " (+" + (keyCount - 1) + " extra)";
                final String cfLabel =
                        customFee ? "TOKEN_CREATE_WITH_CUSTOM_FEE=1" : "TOKEN_CREATE_WITH_CUSTOM_FEE=0";
                final String txnBase =
                        prefix + "TokenCreateK" + keyCount + (customFee ? "CF1" : "CF0");
                final String[] requiredSigners = keyCount >= 1
                        ? new String[] {PAYER, TREASURY, SIMPLE_KEY}
                        : new String[] {PAYER, TREASURY};
                addWithSigVariants(
                        ops,
                        txnBase,
                        joinEmphasis(keyLabel, cfLabel),
                        feeMap,
                        requiredSigners,
                        (txnName, signers) -> {
                            final var op = tokenCreate(txnName + "Token")
                                    .tokenType(FUNGIBLE_COMMON)
                                    .initialSupply(1_000_000L)
                                    .treasury(TREASURY)
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .signedBy(signers)
                                    .fee(ONE_HUNDRED_HBARS);
                            if (keyCount >= 1) {
                                op.adminKey(SIMPLE_KEY);
                            }
                            if (keyCount >= 3) {
                                op.supplyKey(SIMPLE_KEY).freezeKey(SIMPLE_KEY).freezeDefault(false);
                            }
                            if (keyCount >= 5) {
                                op.pauseKey(SIMPLE_KEY).wipeKey(SIMPLE_KEY);
                            }
                            if (customFee) {
                                op.withCustom(fixedHbarFee(ONE_HBAR, TREASURY));
                            }
                            return op;
                        });
            }
        }

        // ===== TokenMint - Fungible (TOKEN_MINT_NFT=0) =====
        addWithSigVariants(
                ops,
                prefix + "TokenMintFungible",
                "TOKEN_MINT_NFT=0",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                (txnName, signers) -> mintToken(prefix + "FungibleK5", 10_000L)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== TokenMint - NFT with varying TOKEN_MINT_NFT =====
        addWithSigVariants(
                ops,
                prefix + "TokenMintNFT1",
                "TOKEN_MINT_NFT=1",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                (txnName, signers) -> mintToken(prefix + "NFT", List.of(ByteString.copyFromUtf8("NFT1")))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "TokenMintNFT3",
                "TOKEN_MINT_NFT=3",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                (txnName, signers) -> mintToken(
                                prefix + "NFT",
                                List.of(
                                        ByteString.copyFromUtf8("NFT2"),
                                        ByteString.copyFromUtf8("NFT3"),
                                        ByteString.copyFromUtf8("NFT4")))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "TokenMintNFT5",
                "TOKEN_MINT_NFT=5",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                (txnName, signers) -> mintToken(
                                prefix + "NFT",
                                List.of(
                                        ByteString.copyFromUtf8("NFT5"),
                                        ByteString.copyFromUtf8("NFT6"),
                                        ByteString.copyFromUtf8("NFT7"),
                                        ByteString.copyFromUtf8("NFT8"),
                                        ByteString.copyFromUtf8("NFT9")))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== TokenUpdate with varying KEYS extra =====
        final int[] tokenUpdateKeyCounts = {1, 3, 5};
        for (final int keyCount : tokenUpdateKeyCounts) {
            for (final SigVariant sigVariant : SIG_VARIANTS) {
                final String txnName = prefix + "TokenUpdateK" + keyCount + sigVariant.suffix();
                final String token = txnName + "Token";
                final var createOp = tokenCreate(token)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(TREASURY)
                        .adminKey(SIMPLE_KEY)
                        .blankMemo()
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS);
                if (keyCount >= 3) {
                    createOp.supplyKey(SIMPLE_KEY).freezeKey(SIMPLE_KEY);
                }
                if (keyCount >= 5) {
                    createOp.pauseKey(SIMPLE_KEY).wipeKey(SIMPLE_KEY);
                }
                ops.add(createOp);
                final String[] signers = sigVariant.withRequired(PAYER, SIMPLE_KEY);
                final var updateOp = tokenUpdate(token)
                        .entityMemo("Updated memo " + keyCount)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS);
                if (keyCount >= 1) {
                    updateOp.adminKey(SIMPLE_KEY);
                }
                if (keyCount >= 3) {
                    updateOp.supplyKey(SIMPLE_KEY).freezeKey(SIMPLE_KEY);
                }
                if (keyCount >= 5) {
                    updateOp.pauseKey(SIMPLE_KEY).wipeKey(SIMPLE_KEY);
                }
                ops.add(updateOp.via(txnName));
                ops.add(captureFee(
                        txnName,
                        joinEmphasis(
                                "KEYS=" + keyCount + (keyCount == 1 ? " (included)" : " (+" + (keyCount - 1) + " extra)"),
                                sigEmphasis(signers)),
                        feeMap));
            }
        }

        // ===== TokenAssociate (no extras) =====
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final String txnName = prefix + "TokenAssociate" + sigVariant.suffix();
            final String account = txnName + "Acct";
            ops.add(cryptoCreate(account)
                    .key(SIMPLE_KEY)
                    .balance(ONE_HBAR)
                    .payingWith(PAYER)
                    .fee(ONE_HUNDRED_HBARS));
            final String[] signers = sigVariant.withRequired(PAYER, account);
            ops.add(tokenAssociate(account, prefix + "FungibleK0")
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(txnName, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));
        }

        // ===== TokenDissociate (no extras) =====
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final String txnName = prefix + "TokenDissociate" + sigVariant.suffix();
            final String account = txnName + "Acct";
            ops.add(cryptoCreate(account)
                    .key(SIMPLE_KEY)
                    .balance(ONE_HBAR)
                    .payingWith(PAYER)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, prefix + "FungibleK0")
                    .payingWith(PAYER)
                    .signedBy(PAYER, account)
                    .fee(ONE_HUNDRED_HBARS));
            final String[] signers = sigVariant.withRequired(PAYER, account);
            ops.add(tokenDissociate(account, prefix + "FungibleK0")
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(txnName, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));
        }

        // ===== TokenBurn - Fungible (no extras) =====
        addWithSigVariants(
                ops,
                prefix + "TokenBurnFungible",
                "no extras",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                (txnName, signers) -> burnToken(prefix + "FungibleK5", 1_000L)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== TokenWipe - Fungible (no extras) =====
        addWithSigVariants(
                ops,
                prefix + "TokenWipeFungible",
                "no extras",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                txnName -> blockingOrder(
                        cryptoCreate(txnName + "Acct")
                                .key(SIMPLE_KEY)
                                .balance(ONE_HBAR)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(txnName + "Acct", prefix + "FungibleK5")
                                .payingWith(PAYER)
                                .signedBy(PAYER, txnName + "Acct")
                                .fee(ONE_HUNDRED_HBARS),
                        cryptoTransfer(moving(100L, prefix + "FungibleK5").between(TREASURY, txnName + "Acct"))
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .fee(ONE_HUNDRED_HBARS)),
                (txnName, signers) -> wipeTokenAccount(prefix + "FungibleK5", txnName + "Acct", 50L)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== TokenFreeze / TokenUnfreeze (no extras) =====
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final String suffix = sigVariant.suffix();
            final String account = prefix + "FreezeAcct" + suffix;
            ops.add(cryptoCreate(account)
                    .key(SIMPLE_KEY)
                    .balance(ONE_HBAR)
                    .payingWith(PAYER)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, prefix + "FungibleK5")
                    .payingWith(PAYER)
                    .signedBy(PAYER, account)
                    .fee(ONE_HUNDRED_HBARS));
            final String[] signers = sigVariant.withRequired(PAYER, SIMPLE_KEY);
            final String freezeTxn = prefix + "TokenFreeze" + suffix;
            ops.add(tokenFreeze(prefix + "FungibleK5", account)
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(freezeTxn));
            ops.add(captureFee(freezeTxn, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));

            final String unfreezeTxn = prefix + "TokenUnfreeze" + suffix;
            ops.add(tokenUnfreeze(prefix + "FungibleK5", account)
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(unfreezeTxn));
            ops.add(captureFee(unfreezeTxn, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));
        }

        // ===== TokenPause / TokenUnpause (no extras) =====
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final String[] signers = sigVariant.withRequired(PAYER, SIMPLE_KEY);
            final String pauseTxn = prefix + "TokenPause" + sigVariant.suffix();
            ops.add(tokenPause(prefix + "FungibleK5")
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(pauseTxn));
            ops.add(captureFee(pauseTxn, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));

            final String unpauseTxn = prefix + "TokenUnpause" + sigVariant.suffix();
            ops.add(tokenUnpause(prefix + "FungibleK5")
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(unpauseTxn));
            ops.add(captureFee(unpauseTxn, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));
        }

        // ===== TokenDelete (no extras) =====
        addWithSigVariants(
                ops,
                prefix + "TokenDelete",
                "no extras",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                txnName -> tokenCreate(txnName + "Token")
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(TREASURY)
                        .adminKey(SIMPLE_KEY)
                        .blankMemo()
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                (txnName, signers) -> tokenDelete(txnName + "Token")
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== TokenFeeScheduleUpdate (no extras) =====
        addWithSigVariants(
                ops,
                prefix + "TokenFeeScheduleUpdate",
                "no extras",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                txnName -> tokenCreate(txnName + "Token")
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1_000L)
                        .treasury(TREASURY)
                        .feeScheduleKey(SIMPLE_KEY)
                        .blankMemo()
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                (txnName, signers) -> tokenFeeScheduleUpdate(txnName + "Token")
                        .withCustom(fixedHbarFee(ONE_HBAR / 10, TREASURY))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== TokenGrantKyc / TokenRevokeKyc (no extras) =====
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final String suffix = sigVariant.suffix();
            final String account = prefix + "KycAcct" + suffix;
            ops.add(cryptoCreate(account)
                    .key(SIMPLE_KEY)
                    .balance(ONE_HBAR)
                    .payingWith(PAYER)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, prefix + "FungibleKyc")
                    .payingWith(PAYER)
                    .signedBy(PAYER, account)
                    .fee(ONE_HUNDRED_HBARS));
            final String[] signers = sigVariant.withRequired(PAYER, SIMPLE_KEY);

            final String grantTxn = prefix + "TokenGrantKyc" + suffix;
            ops.add(grantTokenKyc(prefix + "FungibleKyc", account)
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(grantTxn));
            ops.add(captureFee(grantTxn, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));

            final String revokeTxn = prefix + "TokenRevokeKyc" + suffix;
            ops.add(revokeTokenKyc(prefix + "FungibleKyc", account)
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(revokeTxn));
            ops.add(captureFee(revokeTxn, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));
        }

        // ===== TokenReject (no extras) =====
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final String txnName = prefix + "TokenReject" + sigVariant.suffix();
            final String account = txnName + "Acct";
            ops.add(cryptoCreate(account)
                    .key(SIMPLE_KEY)
                    .balance(ONE_HBAR)
                    .payingWith(PAYER)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(tokenAssociate(account, prefix + "FungibleReject")
                    .payingWith(PAYER)
                    .signedBy(PAYER, account)
                    .fee(ONE_HUNDRED_HBARS));
            ops.add(cryptoTransfer(moving(50L, prefix + "FungibleReject").between(TREASURY, account))
                    .payingWith(PAYER)
                    .signedBy(PAYER, TREASURY)
                    .fee(ONE_HUNDRED_HBARS));
            final String[] signers = sigVariant.withRequired(PAYER, account);
            ops.add(tokenReject(account, rejectingToken(prefix + "FungibleReject"))
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(txnName, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));
        }

        // ===== TokenUpdateNfts (no extras) =====
        final long[] updateSerials = {1L, 2L, 3L};
        int updateIdx = 0;
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final long serial = updateSerials[updateIdx % updateSerials.length];
            updateIdx++;
            final String txnName = prefix + "TokenUpdateNfts" + sigVariant.suffix();
            final String[] signers = sigVariant.withRequired(PAYER, SIMPLE_KEY);
            ops.add(tokenUpdateNfts(prefix + "NFTMeta", "meta-update-" + serial, List.of(serial))
                    .metadataKey(SIMPLE_KEY)
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(txnName, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));
        }

        // ===== TokenAirdrop with varying AIRDROPS extra =====
        addWithSigVariants(
                ops,
                prefix + "TokenAirdropA1",
                "AIRDROPS=1 (included)",
                feeMap,
                new String[] {PAYER, TREASURY},
                txnName -> cryptoCreate(txnName + "R1")
                        .key(SIMPLE_KEY)
                        .balance(ONE_HBAR)
                        .maxAutomaticTokenAssociations(0)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                (txnName, signers) -> tokenAirdrop(
                                moving(10L, prefix + "FungibleAirdrop").between(TREASURY, txnName + "R1"))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "TokenAirdropA3",
                "AIRDROPS=3 (+2 extra)",
                feeMap,
                new String[] {PAYER, TREASURY},
                txnName -> blockingOrder(
                        cryptoCreate(txnName + "R1")
                                .key(SIMPLE_KEY)
                                .balance(ONE_HBAR)
                                .maxAutomaticTokenAssociations(0)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        cryptoCreate(txnName + "R2")
                                .key(SIMPLE_KEY)
                                .balance(ONE_HBAR)
                                .maxAutomaticTokenAssociations(0)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        cryptoCreate(txnName + "R3")
                                .key(SIMPLE_KEY)
                                .balance(ONE_HBAR)
                                .maxAutomaticTokenAssociations(0)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS)),
                (txnName, signers) -> tokenAirdrop(
                                moving(10L, prefix + "FungibleAirdrop").between(TREASURY, txnName + "R1"),
                                moving(10L, prefix + "FungibleAirdrop").between(TREASURY, txnName + "R2"),
                                moving(10L, prefix + "FungibleAirdrop").between(TREASURY, txnName + "R3"))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== TokenClaimAirdrop (no extras) =====
        addWithSigVariants(
                ops,
                prefix + "TokenClaimAirdrop",
                "no extras",
                feeMap,
                new String[] {PAYER, TREASURY},
                txnName -> blockingOrder(
                        cryptoCreate(txnName + "Receiver")
                                .key(SIMPLE_KEY)
                                .balance(ONE_HBAR)
                                .maxAutomaticTokenAssociations(0)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAirdrop(moving(10L, prefix + "FungibleAirdrop").between(TREASURY, txnName + "Receiver"))
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .fee(ONE_HUNDRED_HBARS)),
                (txnName, signers) -> tokenClaimAirdrop(
                                HapiTokenClaimAirdrop.pendingAirdrop(
                                        TREASURY, txnName + "Receiver", prefix + "FungibleAirdrop"))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== TokenCancelAirdrop (no extras) =====
        addWithSigVariants(
                ops,
                prefix + "TokenCancelAirdrop",
                "no extras",
                feeMap,
                new String[] {PAYER, TREASURY},
                txnName -> blockingOrder(
                        cryptoCreate(txnName + "Receiver")
                                .key(SIMPLE_KEY)
                                .balance(ONE_HBAR)
                                .maxAutomaticTokenAssociations(0)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAirdrop(moving(10L, prefix + "FungibleAirdrop").between(TREASURY, txnName + "Receiver"))
                                .payingWith(PAYER)
                                .signedBy(PAYER, TREASURY)
                                .fee(ONE_HUNDRED_HBARS)),
                (txnName, signers) -> tokenCancelAirdrop(
                                HapiTokenCancelAirdrop.pendingAirdrop(
                                        TREASURY, txnName + "Receiver", prefix + "FungibleAirdrop"))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== TokenGetInfo / TokenGetNftInfo (queries) =====
        addQueryWithSigVariants(
                ops,
                prefix + "TokenGetInfo",
                "no extras",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getTokenInfo(prefix + "FungibleK0")
                        .payingWith(PAYER)
                        .signedBy(signers));

        addQueryWithSigVariants(
                ops,
                prefix + "TokenGetNftInfo",
                "no extras",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getTokenNftInfo(prefix + "NFTMeta", 1L)
                        .payingWith(PAYER)
                        .signedBy(signers));

        return ops;
    }

    // ==================== CONSENSUS TRANSACTIONS ====================

    private static List<SpecOperation> consensusTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ===== SETUP: Base topics for submit/update =====
        ops.add(createTopic(prefix + "TopicBase")
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(createTopic(prefix + "TopicCustomFee")
                .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, TREASURY))
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(createTopic(prefix + "TopicUpdK1")
                .adminKeyName(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(createTopic(prefix + "TopicUpdK2")
                .adminKeyName(SIMPLE_KEY)
                .submitKeyName(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(createTopic(prefix + "TopicUpdK3")
                .adminKeyName(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // ===== ConsensusCreateTopic with KEYS + CUSTOM_FEE =====
        final int[] createKeyCounts = {0, 1, 2};
        for (final int keyCount : createKeyCounts) {
            for (final boolean customFee : new boolean[] {false, true}) {
                final String keyLabel = keyCount == 0
                        ? "KEYS=0 (included)"
                        : "KEYS=" + keyCount + " (+" + keyCount + " extra)";
                final String cfLabel = customFee
                        ? "CONSENSUS_CREATE_TOPIC_WITH_CUSTOM_FEE=1"
                        : "CONSENSUS_CREATE_TOPIC_WITH_CUSTOM_FEE=0";
                final String txnBase =
                        prefix + "TopicCreateK" + keyCount + (customFee ? "CF1" : "CF0");
                addWithSigVariants(
                        ops,
                        txnBase,
                        joinEmphasis(keyLabel, cfLabel),
                        feeMap,
                        new String[] {PAYER},
                        (txnName, signers) -> {
                            final var op = createTopic(txnName + "Topic")
                                    .blankMemo()
                                    .payingWith(PAYER)
                                    .signedBy(signers)
                                    .fee(ONE_HUNDRED_HBARS);
                            if (keyCount >= 1) {
                                op.adminKeyName(SIMPLE_KEY);
                            }
                            if (keyCount >= 2) {
                                op.submitKeyName(SIMPLE_KEY);
                            }
                            if (customFee) {
                                op.withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, TREASURY));
                            }
                            return op;
                        });
            }
        }

        // ===== ConsensusSubmitMessage with BYTES + CUSTOM_FEE =====
        final int[] msgSizes = {50, 100, 500};
        for (final int size : msgSizes) {
            final String bytesLabel = size <= 100
                    ? "BYTES=" + size + " (included)"
                    : "BYTES=" + size + " (+" + (size - 100) + " extra)";
            addWithSigVariants(
                    ops,
                    prefix + "SubmitMsgB" + size,
                    joinEmphasis(bytesLabel, "CONSENSUS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE=0"),
                    feeMap,
                    new String[] {PAYER},
                    (txnName, signers) -> submitMessageTo(prefix + "TopicBase")
                            .message("x".repeat(size))
                            .payingWith(PAYER)
                            .signedBy(signers)
                            .fee(ONE_HUNDRED_HBARS));
            addWithSigVariants(
                    ops,
                    prefix + "SubmitMsgB" + size + "CF",
                    joinEmphasis(bytesLabel, "CONSENSUS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE=1"),
                    feeMap,
                    new String[] {PAYER},
                    (txnName, signers) -> submitMessageTo(prefix + "TopicCustomFee")
                            .message("x".repeat(size))
                            .payingWith(PAYER)
                            .signedBy(signers)
                            .fee(ONE_HUNDRED_HBARS));
        }

        // ===== ConsensusUpdateTopic with varying KEYS =====
        addWithSigVariants(
                ops,
                prefix + "TopicUpdateK1",
                "KEYS=1 (included)",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                (txnName, signers) -> updateTopic(prefix + "TopicUpdK1")
                        .adminKey(SIMPLE_KEY)
                        .topicMemo(SHORT_MEMO)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "TopicUpdateK2",
                "KEYS=2 (+1 extra)",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                (txnName, signers) -> updateTopic(prefix + "TopicUpdK2")
                        .adminKey(SIMPLE_KEY)
                        .submitKey(SIMPLE_KEY)
                        .topicMemo(SHORT_MEMO)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "TopicUpdateK3",
                "KEYS=3 (+2 extra)",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                (txnName, signers) -> updateTopic(prefix + "TopicUpdK3")
                        .adminKey(SIMPLE_KEY)
                        .submitKey(SIMPLE_KEY)
                        .feeExemptKeys(SIMPLE_KEY)
                        .topicMemo(SHORT_MEMO)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== ConsensusDeleteTopic (no extras) =====
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final String txnName = prefix + "TopicDelete" + sigVariant.suffix();
            final String topic = txnName + "Topic";
            ops.add(createTopic(topic)
                    .adminKeyName(SIMPLE_KEY)
                    .blankMemo()
                    .payingWith(PAYER)
                    .fee(ONE_HUNDRED_HBARS));
            final String[] signers = sigVariant.withRequired(PAYER, SIMPLE_KEY);
            ops.add(deleteTopic(topic)
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(txnName, joinEmphasis("no extras", sigEmphasis(signers)), feeMap));
        }

        // ===== ConsensusGetTopicInfo (query) =====
        addQueryWithSigVariants(
                ops,
                prefix + "ConsensusGetTopicInfo",
                "no extras",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getTopicInfo(prefix + "TopicBase")
                        .payingWith(PAYER)
                        .signedBy(signers));

        return ops;
    }

    // ==================== FILE TRANSACTIONS ====================

    private static List<SpecOperation> fileTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ===== SETUP: Base files for append/delete =====
        ops.add(fileCreate(prefix + "FileBase100")
                .contents("x".repeat(100))
                .key(LIST_KEY_1)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(fileCreate(prefix + "FileBase1000")
                .contents("x".repeat(1000))
                .key(LIST_KEY_1)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(fileCreate(prefix + "FileQuery500")
                .contents("x".repeat(500))
                .key(LIST_KEY_1)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(fileCreate(prefix + "FileQuery2000")
                .contents("x".repeat(2000))
                .key(LIST_KEY_1)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // ===== FileCreate with KEYS + BYTES =====
        final var fileCreateKeyVariants = List.of(
                new KeyVariant("K0", "KEYS=0 (included)", ""),
                new KeyVariant("K1", "KEYS=1 (included)", LIST_KEY_1),
                new KeyVariant("K3", "KEYS=3 (+2 extra)", LIST_KEY_3));
        final int[] fileCreateSizes = {100, 2000};
        for (final var keyVariant : fileCreateKeyVariants) {
            for (final int size : fileCreateSizes) {
                final String bytesLabel = size <= 1000
                        ? "BYTES=" + size + " (included)"
                        : "BYTES=" + size + " (+" + (size - 1000) + " extra)";
                final String txnBase = prefix + "FileCreate" + keyVariant.suffix() + "B" + size;
                final String[] baseSigners = keyVariant.keyName().isEmpty()
                        ? new String[] {PAYER}
                        : new String[] {PAYER, keyVariant.keyName()};
                for (final SigVariant sigVariant : SIG_VARIANTS) {
                    final String[] signers = sigVariant.withRequired(baseSigners);
                    final String txnName = txnBase + sigVariant.suffix();
                    final var op = fileCreate(txnName + "File")
                            .contents("x".repeat(size))
                            .payingWith(PAYER)
                            .signedBy(signers)
                            .fee(ONE_HUNDRED_HBARS);
                    if (keyVariant.keyName().isEmpty()) {
                        op.unmodifiable();
                    } else {
                        op.key(keyVariant.keyName());
                    }
                    ops.add(op.via(txnName));
                    ops.add(captureFee(
                            txnName,
                            joinEmphasis(keyVariant.label(), bytesLabel, sigEmphasis(signers)),
                            feeMap));
                }
            }
        }

        // ===== FileUpdate with KEYS + BYTES =====
        final var fileUpdateKeyVariants = List.of(
                new KeyVariant("K1", "KEYS=1 (included)", LIST_KEY_1),
                new KeyVariant("K3", "KEYS=3 (+2 extra)", LIST_KEY_3));
        final int[] fileUpdateSizes = {500, 3000};
        for (final var keyVariant : fileUpdateKeyVariants) {
            for (final int size : fileUpdateSizes) {
                final String bytesLabel = size <= 1000
                        ? "BYTES=" + size + " (included)"
                        : "BYTES=" + size + " (+" + (size - 1000) + " extra)";
                final String txnBase = prefix + "FileUpdate" + keyVariant.suffix() + "B" + size;
                final String[] requiredSigners = keyVariant.keyName().equals(LIST_KEY_1)
                        ? new String[] {PAYER, LIST_KEY_1}
                        : new String[] {PAYER, LIST_KEY_1, keyVariant.keyName()};
                addWithSigVariants(
                        ops,
                        txnBase,
                        joinEmphasis(keyVariant.label(), bytesLabel),
                        feeMap,
                        requiredSigners,
                        txnName -> fileCreate(txnName + "File")
                                .contents("seed")
                                .key(LIST_KEY_1)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        (txnName, signers) -> fileUpdate(txnName + "File")
                                .contents("x".repeat(size))
                                .wacl(keyVariant.keyName())
                                .payingWith(PAYER)
                                .signedBy(signers)
                                .fee(ONE_HUNDRED_HBARS));
            }
        }

        // ===== FileAppend with varying BYTES =====
        addWithSigVariants(
                ops,
                prefix + "FileAppendB500",
                "BYTES=500 (included)",
                feeMap,
                new String[] {PAYER, LIST_KEY_1},
                (txnName, signers) -> fileAppend(prefix + "FileBase100")
                        .content("y".repeat(500))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "FileAppendB2000",
                "BYTES=2000 (+1000 extra)",
                feeMap,
                new String[] {PAYER, LIST_KEY_1},
                (txnName, signers) -> fileAppend(prefix + "FileBase1000")
                        .content("y".repeat(2000))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== FileDelete (no extras) =====
        addWithSigVariants(
                ops,
                prefix + "FileDelete",
                "no extras",
                feeMap,
                new String[] {PAYER, LIST_KEY_1},
                txnName -> fileCreate(txnName + "File")
                        .contents("x".repeat(100))
                        .key(LIST_KEY_1)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                (txnName, signers) -> fileDelete(txnName + "File")
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== FileGetContents (queries) =====
        addQueryWithSigVariants(
                ops,
                prefix + "FileGetContentsB500",
                "BYTES=500 (included)",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getFileContents(prefix + "FileQuery500")
                        .payingWith(PAYER)
                        .signedBy(signers));

        addQueryWithSigVariants(
                ops,
                prefix + "FileGetContentsB2000",
                "BYTES=2000 (+1000 extra)",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getFileContents(prefix + "FileQuery2000")
                        .payingWith(PAYER)
                        .signedBy(signers));

        // ===== FileGetInfo (query) =====
        addQueryWithSigVariants(
                ops,
                prefix + "FileGetInfo",
                "no extras",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getFileInfo(prefix + "FileBase100")
                        .payingWith(PAYER)
                        .signedBy(signers));

        return ops;
    }

    // ==================== SCHEDULE TRANSACTIONS ====================

    private static List<SpecOperation> scheduleTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Use different amounts based on prefix to ensure unique scheduled transactions
        final long amount = prefix.equals("simple") ? 1L : 2L;

        // ===== SETUP: Contract for scheduled contract call =====
        ops.add(uploadInitCode(STORAGE_CONTRACT));
        ops.add(contractCreate(prefix + "ScheduleContract")
                .bytecode(STORAGE_CONTRACT)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        final var storeAbi = getABIFor(FUNCTION, "store", STORAGE_CONTRACT);

        // ===== SETUP: Schedule for ScheduleGetInfo =====
        ops.add(scheduleCreate(
                        prefix + "ScheduleQuery",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).blankMemo())
                .adminKey(SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // ===== ScheduleCreate with KEYS + SCHEDULE_CREATE_CONTRACT_CALL_BASE =====
        addWithSigVariants(
                ops,
                prefix + "ScheduleCreateK0Xfer",
                "KEYS=0 (no admin), SCHEDULE_CREATE_CONTRACT_CALL_BASE=0",
                feeMap,
                new String[] {PAYER},
                (txnName, signers) -> scheduleCreate(
                                txnName + "Schedule",
                                cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).memo(txnName))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "ScheduleCreateK1Xfer",
                "KEYS=1 (included), SCHEDULE_CREATE_CONTRACT_CALL_BASE=0",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                (txnName, signers) -> scheduleCreate(
                                txnName + "Schedule",
                                cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).memo(txnName))
                        .adminKey(SIMPLE_KEY)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "ScheduleCreateK0Call",
                "KEYS=0 (no admin), SCHEDULE_CREATE_CONTRACT_CALL_BASE=1",
                feeMap,
                new String[] {PAYER},
                (txnName, signers) -> scheduleCreate(
                                txnName + "Schedule",
                                contractCallWithFunctionAbi(prefix + "ScheduleContract", storeAbi, BigInteger.valueOf(1))
                                        .gas(50_000L)
                                        .memo(txnName))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "ScheduleCreateK1Call",
                "KEYS=1 (included), SCHEDULE_CREATE_CONTRACT_CALL_BASE=1",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                (txnName, signers) -> scheduleCreate(
                                txnName + "Schedule",
                                contractCallWithFunctionAbi(prefix + "ScheduleContract", storeAbi, BigInteger.valueOf(2))
                                        .gas(50_000L)
                                        .memo(txnName))
                        .adminKey(SIMPLE_KEY)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== ScheduleSign (no extras) =====
        addWithSigVariants(
                ops,
                prefix + "ScheduleSign",
                "no extras",
                feeMap,
                new String[] {PAYER, RECEIVER},
                txnName -> scheduleCreate(
                                txnName + "Schedule",
                                cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).memo(txnName))
                        .adminKey(SIMPLE_KEY)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                (txnName, signers) -> scheduleSign(txnName + "Schedule")
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== ScheduleDelete (no extras) =====
        addWithSigVariants(
                ops,
                prefix + "ScheduleDelete",
                "no extras",
                feeMap,
                new String[] {PAYER, SIMPLE_KEY},
                txnName -> scheduleCreate(
                                txnName + "Schedule",
                                cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).memo(txnName))
                        .adminKey(SIMPLE_KEY)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                (txnName, signers) -> scheduleDelete(txnName + "Schedule")
                        .signedBy(signers)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== ScheduleGetInfo (query) =====
        addQueryWithSigVariants(
                ops,
                prefix + "ScheduleGetInfo",
                "no extras",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getScheduleInfo(prefix + "ScheduleQuery")
                        .payingWith(PAYER)
                        .signedBy(signers));

        return ops;
    }

    // ==================== HOOK TRANSACTIONS ====================

    private static final String HOOK_CONTRACT = "PayableConstructor";
    private static final String TRUE_HOOK_CONTRACT = "TruePreHook";
    private static final String HOOK_OWNER = "hookOwner";
    private static final long HOOK_GAS_LIMIT = 25000L;
    private static final long HOOK_GAS_LIMIT_HIGH = 75000L;

    private static List<SpecOperation> hookTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Upload hook contract bytecode and create hook owner account with a hook
        ops.add(uploadInitCode(HOOK_CONTRACT));
        ops.add(contractCreate(prefix + "HookContract")
                .bytecode(HOOK_CONTRACT)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // Create account with hook attached (tests HOOK_UPDATES extra on CryptoCreate)
        // HOOK_UPDATES extra: fee=10000000000 per hook update, includedCount=0
        ops.add(cryptoCreate(prefix + HOOK_OWNER)
                .balance(ONE_HUNDRED_HBARS)
                .withHooks(accountAllowanceHook(1L, prefix + "HookContract"))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // ===== HookStore with varying storage slot updates =====
        // HookStore baseFee: 49000000
        addWithSigVariants(
                ops,
                prefix + "HookStoreS1",
                "SLOTS=1",
                feeMap,
                new String[] {PAYER, prefix + HOOK_OWNER},
                (txnName, signers) -> accountEvmHookStore(prefix + HOOK_OWNER, 1L)
                        .putSlot(Bytes.wrap("slot1"), Bytes.wrap("value1"))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "HookStoreS2",
                "SLOTS=2",
                feeMap,
                new String[] {PAYER, prefix + HOOK_OWNER},
                (txnName, signers) -> accountEvmHookStore(prefix + HOOK_OWNER, 1L)
                        .putSlot(Bytes.wrap("slot2"), Bytes.wrap("value2"))
                        .putSlot(Bytes.wrap("slot3"), Bytes.wrap("value3"))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "HookStoreS5",
                "SLOTS=5",
                feeMap,
                new String[] {PAYER, prefix + HOOK_OWNER},
                (txnName, signers) -> accountEvmHookStore(prefix + HOOK_OWNER, 1L)
                        .putSlot(Bytes.wrap("slot4"), Bytes.wrap("value4"))
                        .putSlot(Bytes.wrap("slot5"), Bytes.wrap("value5"))
                        .putSlot(Bytes.wrap("slot6"), Bytes.wrap("value6"))
                        .putSlot(Bytes.wrap("slot7"), Bytes.wrap("value7"))
                        .putSlot(Bytes.wrap("slot8"), Bytes.wrap("value8"))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== HookStore - remove slots =====
        addWithSigVariants(
                ops,
                prefix + "HookStoreRemove1",
                "REMOVE_SLOTS=1",
                feeMap,
                new String[] {PAYER, prefix + HOOK_OWNER},
                (txnName, signers) -> accountEvmHookStore(prefix + HOOK_OWNER, 1L)
                        .removeSlot(Bytes.wrap("slot1"))
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== CryptoTransfer with HOOK_EXECUTION extra =====
        // HOOK_EXECUTION extra: fee=10000000000 per hook execution, includedCount=0
        // Upload TruePreHook contract for successful hook executions
        ops.add(uploadInitCode(TRUE_HOOK_CONTRACT));
        ops.add(contractCreate(prefix + "TrueHookContract")
                .bytecode(TRUE_HOOK_CONTRACT)
                .gas(5_000_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // Create sender account with TruePreHook attached
        ops.add(cryptoCreate(prefix + "HookSender")
                .key(SIMPLE_KEY)
                .balance(ONE_HUNDRED_HBARS)
                .withHooks(accountAllowanceHook(10L, prefix + "TrueHookContract"))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // Create receiver account
        ops.add(cryptoCreate(prefix + "HookReceiver")
                .key(SIMPLE_KEY)
                .balance(ONE_HBAR)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoCreate(prefix + "HookReceiver2")
                .key(SIMPLE_KEY)
                .balance(ONE_HBAR)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // ===== SETUP: Tokens for hook + token-transfer cross-product =====
        ops.add(tokenCreate(prefix + "HookFT")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(prefix + "HookSender")
                .blankMemo()
                .payingWith(PAYER)
                .signedBy(PAYER, prefix + "HookSender")
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "HookFTCF")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(prefix + "HookSender")
                .withCustom(fixedHbarFee(ONE_HBAR / 100, TREASURY))
                .blankMemo()
                .payingWith(PAYER)
                .signedBy(PAYER, prefix + "HookSender")
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "HookFTCF2")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(prefix + "HookSender")
                .withCustom(fixedHbarFee(ONE_HBAR / 100, TREASURY))
                .blankMemo()
                .payingWith(PAYER)
                .signedBy(PAYER, prefix + "HookSender")
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenCreate(prefix + "HookNFT")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(prefix + "HookSender")
                .supplyKey(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .signedBy(PAYER, prefix + "HookSender", SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(mintToken(prefix + "HookNFT", List.of(
                        ByteString.copyFromUtf8("HNFT1"),
                        ByteString.copyFromUtf8("HNFT2"),
                        ByteString.copyFromUtf8("HNFT3"),
                        ByteString.copyFromUtf8("HNFT4"),
                        ByteString.copyFromUtf8("HNFT5"),
                        ByteString.copyFromUtf8("HNFT6"),
                        ByteString.copyFromUtf8("HNFT7"),
                        ByteString.copyFromUtf8("HNFT8"),
                        ByteString.copyFromUtf8("HNFT9"),
                        ByteString.copyFromUtf8("HNFT10")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(mintToken(prefix + "HookNFT", List.of(
                        ByteString.copyFromUtf8("HNFT11"),
                        ByteString.copyFromUtf8("HNFT12")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenAssociate(prefix + "HookReceiver", prefix + "HookFT", prefix + "HookFTCF", prefix + "HookFTCF2", prefix + "HookNFT")
                .payingWith(PAYER)
                .signedBy(PAYER, prefix + "HookReceiver")
                .fee(ONE_HUNDRED_HBARS));
        ops.add(tokenAssociate(prefix + "HookReceiver2", prefix + "HookFT", prefix + "HookFTCF", prefix + "HookFTCF2", prefix + "HookNFT")
                .payingWith(PAYER)
                .signedBy(PAYER, prefix + "HookReceiver2")
                .fee(ONE_HUNDRED_HBARS));

        // CryptoTransfer with 1 hook execution (pre-hook on sender)
        addWithSigVariants(
                ops,
                prefix + "CryptoTransferHE1",
                "HOOK_EXECUTION=1 (pre), GAS=25000",
                feeMap,
                new String[] {PAYER, prefix + "HookSender"},
                (txnName, signers) -> cryptoTransfer(
                                tinyBarsFromTo(prefix + "HookSender", prefix + "HookReceiver", ONE_HBAR))
                        .withPreHookFor(prefix + "HookSender", 10L, HOOK_GAS_LIMIT, "")
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // CryptoTransfer with 1 hook execution and higher gas limit
        addWithSigVariants(
                ops,
                prefix + "CryptoTransferHE1G75",
                "HOOK_EXECUTION=1 (pre), GAS=75000 (+50000 extra)",
                feeMap,
                new String[] {PAYER, prefix + "HookSender"},
                (txnName, signers) -> cryptoTransfer(
                                tinyBarsFromTo(prefix + "HookSender", prefix + "HookReceiver", ONE_HBAR))
                        .withPreHookFor(prefix + "HookSender", 10L, HOOK_GAS_LIMIT_HIGH, "")
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // CryptoTransfer with hook execution + multiple token types + custom fees + extra accounts
        final long[] hookComboSerials = {1L, 2L, 3L};
        int hookComboIdx = 0;
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final long serial = hookComboSerials[hookComboIdx++];
            final String txnName = prefix + "CryptoTransferHookCombo" + sigVariant.suffix();
            final String[] signers = sigVariant.withRequired(PAYER, prefix + "HookSender");
            ops.add(cryptoTransfer(
                            movingHbar(ONE_HBAR).between(prefix + "HookSender", prefix + "HookReceiver"),
                            movingHbar(ONE_HBAR).between(prefix + "HookSender", prefix + "HookReceiver2"),
                            movingHbar(ONE_HBAR).between(prefix + "HookSender", PAYER),
                            moving(10, prefix + "HookFT").between(prefix + "HookSender", prefix + "HookReceiver"),
                            moving(10, prefix + "HookFTCF").between(prefix + "HookSender", prefix + "HookReceiver"),
                            movingUnique(prefix + "HookNFT", serial)
                                    .between(prefix + "HookSender", prefix + "HookReceiver"))
                    .withPreHookFor(prefix + "HookSender", 10L, HOOK_GAS_LIMIT_HIGH, "")
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(
                    txnName,
                    joinEmphasis(
                            "HOOK_EXECUTION=1 (pre), GAS=75000 (+50000 extra), ACCOUNTS=4 (+2 extra), "
                                    + "FUNGIBLE_TOKENS=2 (+1 extra), NON_FUNGIBLE_TOKENS=1 (included), "
                                    + "TOKEN_TRANSFER_BASE=1, TOKEN_TRANSFER_BASE_CUSTOM_FEES=1",
                            sigEmphasis(signers)),
                    feeMap));
        }

        final long[][] hookComboSerialTriples = {
                {4L, 5L, 6L},
                {7L, 8L, 9L},
                {10L, 11L, 12L}
        };
        int hookComboTripleIdx = 0;
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final long[] serials = hookComboSerialTriples[hookComboTripleIdx++];
            final String txnName = prefix + "CryptoTransferHookComboSerials3" + sigVariant.suffix();
            final String[] signers = sigVariant.withRequired(PAYER, prefix + "HookSender");
            ops.add(cryptoTransfer(
                            movingHbar(ONE_HBAR).between(prefix + "HookSender", prefix + "HookReceiver"),
                            movingHbar(ONE_HBAR).between(prefix + "HookSender", prefix + "HookReceiver2"),
                            movingHbar(ONE_HBAR).between(prefix + "HookSender", PAYER),
                            moving(10, prefix + "HookFT").between(prefix + "HookSender", prefix + "HookReceiver"),
                            moving(10, prefix + "HookFTCF").between(prefix + "HookSender", prefix + "HookReceiver"),
                            moving(10, prefix + "HookFTCF2").between(prefix + "HookSender", prefix + "HookReceiver"),
                            movingUnique(prefix + "HookNFT", serials)
                                    .between(prefix + "HookSender", prefix + "HookReceiver"))
                    .withPreHookFor(prefix + "HookSender", 10L, HOOK_GAS_LIMIT_HIGH, "")
                    .payingWith(PAYER)
                    .signedBy(signers)
                    .fee(ONE_HUNDRED_HBARS)
                    .via(txnName));
            ops.add(captureFee(
                    txnName,
                    joinEmphasis(
                            "HOOK_EXECUTION=1 (pre), GAS=75000 (+50000 extra), ACCOUNTS=4 (+2 extra), "
                                    + "FUNGIBLE_TOKENS=3 (+2 extra), NON_FUNGIBLE_TOKENS=1 (included), "
                                    + "NFT_SERIALS=3 (+2 extra), TOKEN_TRANSFER_BASE=1, "
                                    + "TOKEN_TRANSFER_BASE_CUSTOM_FEES=2 (+1 extra)",
                            sigEmphasis(signers)),
                    feeMap));
        }

        // Create sender with pre-post hook for 2 hook executions
        ops.add(uploadInitCode("TruePrePostHook"));
        ops.add(contractCreate(prefix + "TruePrePostHookContract")
                .bytecode("TruePrePostHook")
                .gas(5_000_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(cryptoCreate(prefix + "HookSender2")
                .key(SIMPLE_KEY)
                .balance(ONE_HUNDRED_HBARS)
                .withHooks(accountAllowanceHook(20L, prefix + "TruePrePostHookContract"))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // CryptoTransfer with 2 hook executions (pre-post hook on sender)
        addWithSigVariants(
                ops,
                prefix + "CryptoTransferHE2",
                "HOOK_EXECUTION=2 (pre+post), GAS=25000",
                feeMap,
                new String[] {PAYER, prefix + "HookSender2"},
                (txnName, signers) -> cryptoTransfer(
                                tinyBarsFromTo(prefix + "HookSender2", prefix + "HookReceiver", ONE_HBAR))
                        .withPrePostHookFor(prefix + "HookSender2", 20L, HOOK_GAS_LIMIT, "")
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        return ops;
    }

    // ==================== CONTRACT TRANSACTIONS ====================

    private static final String STORAGE_CONTRACT = "Storage";

    private static List<SpecOperation> contractTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Upload contract bytecode (only once, shared between runs)
        ops.add(uploadInitCode(STORAGE_CONTRACT));

        // ===== ContractCreate with varying GAS extra =====
        // GAS extra: fee=1 per gas unit
        ops.add(contractCreate(prefix + "ContractG200k")
                .bytecode(STORAGE_CONTRACT)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreateG200k"));
        ops.add(captureFee(prefix + "ContractCreateG200k", "GAS=200000", feeMap));

        // ===== ContractCall with varying GAS extra =====
        final var storeAbi = getABIFor(FUNCTION, "store", STORAGE_CONTRACT);
        ops.add(contractCallWithFunctionAbi(prefix + "ContractG200k", storeAbi, BigInteger.valueOf(42))
                .gas(50_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCallG50k"));
        ops.add(captureFee(prefix + "ContractCallG50k", "GAS=50000", feeMap));

        ops.add(contractCallWithFunctionAbi(prefix + "ContractG200k", storeAbi, BigInteger.valueOf(100))
                .gas(100_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCallG100k"));
        ops.add(captureFee(prefix + "ContractCallG100k", "GAS=100000", feeMap));

        ops.add(contractCallWithFunctionAbi(prefix + "ContractG200k", storeAbi, BigInteger.valueOf(200))
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCallG200k"));
        ops.add(captureFee(prefix + "ContractCallG200k", "GAS=200000", feeMap));

        // ===== ContractUpdate (no GAS extra) =====
        ops.add(contractCreate(prefix + "ContractWithAdmin")
                .bytecode(STORAGE_CONTRACT)
                .adminKey(SIMPLE_KEY)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(contractUpdate(prefix + "ContractWithAdmin")
                .newMemo(MEDIUM_MEMO)
                .signedBy(PAYER, SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractUpdate"));
        ops.add(captureFee(prefix + "ContractUpdate", "no GAS extra", feeMap));

        // ===== ContractDelete (no extras) =====
        ops.add(contractDelete(prefix + "ContractWithAdmin")
                .transferAccount(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractDelete"));
        ops.add(captureFee(prefix + "ContractDelete", "no extras", feeMap));

        // ===== ContractGetInfo (query) =====
        addQueryWithSigVariants(
                ops,
                prefix + "ContractGetInfo",
                "no extras",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getContractInfo(prefix + "ContractG200k")
                        .payingWith(PAYER)
                        .signedBy(signers));

        // ===== ContractCallLocal (query) =====
        final var retrieveAbi = getABIFor(FUNCTION, "retrieve", STORAGE_CONTRACT);
        addQueryWithSigVariants(
                ops,
                prefix + "ContractCallLocal",
                "BYTES=32 (included)",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> contractCallLocalWithFunctionAbi(prefix + "ContractG200k", retrieveAbi)
                        .payingWith(PAYER)
                        .signedBy(signers));

        // ===== ContractGetBytecode (query) =====
        addQueryWithSigVariants(
                ops,
                prefix + "ContractGetBytecode",
                "BYTES>0 (included)",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getContractBytecode(prefix + "ContractG200k")
                        .payingWith(PAYER)
                        .signedBy(signers));

        return ops;
    }

    // ==================== ETHEREUM TRANSACTIONS ====================

    private static List<SpecOperation> ethereumTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Ensure alias account exists and funded for Ethereum transactions
        ops.add(cryptoTransfer(tinyBarsFromAccountToAlias(PAYER, SECP_256K1_SOURCE_KEY, FIVE_HBARS))
                .payingWith(PAYER)
                .signedBy(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // ===== EthereumTransaction (value transfer, no calldata) =====
        addWithSigVariants(
                ops,
                prefix + "EthereumCryptoTransfer",
                "ETH_CALLDATA=0 (included)",
                feeMap,
                new String[] {RELAYER},
                (txnName, signers) -> ethereumCryptoTransfer(RECEIVER, ONE_HBAR)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .maxGasAllowance(FIVE_HBARS)
                        .gasLimit(2_000_000L)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        return ops;
    }

    // ==================== NETWORK TRANSACTIONS & QUERIES ====================

    private static List<SpecOperation> networkTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        if (prefix.startsWith("simple")) {
            ops.add(withOpContext((spec, opLog) -> LOG.warn(
                    "Skipping SystemDelete/SystemUndelete for simple fees; simpleFeesSchedules.json lacks entries.")));
        } else {
            // ===== SystemDelete (file) =====
            addWithSigVariants(
                    ops,
                    prefix + "SystemDelete",
                    "no extras",
                    feeMap,
                    new String[] {SYSTEM_DELETE_ADMIN},
                    txnName -> fileCreate(txnName + "SysFile")
                            .contents("sys-delete")
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    (txnName, signers) -> systemFileDelete(txnName + "SysFile")
                            .updatingExpiry(1L)
                            .payingWith(SYSTEM_DELETE_ADMIN)
                            .signedBy(signers)
                            .fee(ONE_HUNDRED_HBARS));

            // ===== SystemUndelete (file) =====
            addWithSigVariants(
                    ops,
                    prefix + "SystemUndelete",
                    "no extras",
                    feeMap,
                    new String[] {SYSTEM_UNDELETE_ADMIN},
                    txnName -> blockingOrder(
                            fileCreate(txnName + "SysFile")
                                    .contents("sys-undelete")
                                    .payingWith(PAYER)
                                    .fee(ONE_HUNDRED_HBARS),
                            systemFileDelete(txnName + "SysFile")
                                    .updatingExpiry(Instant.now().getEpochSecond() + THREE_MONTHS_IN_SECONDS)
                                    .payingWith(SYSTEM_DELETE_ADMIN)
                                    .fee(ONE_HUNDRED_HBARS)),
                    (txnName, signers) -> systemFileUndelete(txnName + "SysFile")
                            .payingWith(SYSTEM_UNDELETE_ADMIN)
                            .signedBy(signers)
                            .fee(ONE_HUNDRED_HBARS));
        }

        // ===== Freeze (abort) =====
        addWithSigVariants(
                ops,
                prefix + "FreezeAbort",
                "FREEZE_ABORT",
                feeMap,
                new String[] {FREEZE_ADMIN},
                (txnName, signers) -> freezeAbort()
                        .payingWith(FREEZE_ADMIN)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== NodeCreate =====
        addWithSigVariants(
                ops,
                prefix + "NodeCreate",
                "no extras",
                feeMap,
                new String[] {GENESIS, PAYER},
                txnName -> cryptoCreate(txnName + "NodeAccount")
                        .balance(ONE_HBAR)
                        .key(SIMPLE_KEY)
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS),
                (txnName, signers) -> nodeCreate(txnName + "Node", txnName + "NodeAccount")
                        .adminKey(PAYER)
                        .gossipCaCertificate(gossipCertBytes)
                        .payingWith(GENESIS)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== NodeUpdate =====
        addWithSigVariants(
                ops,
                prefix + "NodeUpdate",
                "no extras",
                feeMap,
                new String[] {PAYER},
                txnName -> blockingOrder(
                        cryptoCreate(txnName + "NodeAccount")
                                .balance(ONE_HBAR)
                                .key(SIMPLE_KEY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        nodeCreate(txnName + "Node", txnName + "NodeAccount")
                                .adminKey(PAYER)
                                .gossipCaCertificate(gossipCertBytes)
                                .payingWith(GENESIS)
                                .signedBy(GENESIS, PAYER)
                                .fee(ONE_HUNDRED_HBARS)),
                (txnName, signers) -> nodeUpdate(txnName + "Node")
                        .description("updated-" + txnName)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== NodeDelete =====
        addWithSigVariants(
                ops,
                prefix + "NodeDelete",
                "no extras",
                feeMap,
                new String[] {PAYER},
                txnName -> blockingOrder(
                        cryptoCreate(txnName + "NodeAccount")
                                .balance(ONE_HBAR)
                                .key(SIMPLE_KEY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        nodeCreate(txnName + "Node", txnName + "NodeAccount")
                                .adminKey(PAYER)
                                .gossipCaCertificate(gossipCertBytes)
                                .payingWith(GENESIS)
                                .signedBy(GENESIS, PAYER)
                                .fee(ONE_HUNDRED_HBARS)),
                (txnName, signers) -> nodeDelete(txnName + "Node")
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== NetworkGetVersionInfo (query) =====
        addQueryWithSigVariants(
                ops,
                prefix + "NetworkGetVersionInfo",
                "no extras",
                feeMap,
                new String[] {PAYER},
                (queryName, signers) -> getVersionInfo()
                        .payingWith(PAYER)
                        .signedBy(signers));

        // ===== TransactionGetRecord (query) =====
        addQueryWithSigVariants(
                ops,
                prefix + "TxnGetRecord",
                "no extras",
                feeMap,
                new String[] {PAYER},
                queryName -> cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1L))
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(queryName + "Txn"),
                (queryName, signers) -> getTxnRecord(queryName + "Txn")
                        .payingWith(PAYER)
                        .signedBy(signers));

        // ===== TransactionGetReceipt (query) =====
        addQueryWithSigVariants(
                ops,
                prefix + "TxnGetReceipt",
                "no extras",
                feeMap,
                new String[] {PAYER},
                queryName -> cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1L))
                        .payingWith(PAYER)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(queryName + "Txn"),
                (queryName, signers) -> getReceipt(queryName + "Txn")
                        .payingWith(PAYER)
                        .signedBy(signers));

        return ops;
    }

    // ==================== UTIL TRANSACTIONS ====================

    private static List<SpecOperation> utilTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ===== UtilPrng with and without range =====
        addWithSigVariants(
                ops,
                prefix + "PrngRange0",
                "RANGE=0",
                feeMap,
                new String[] {PAYER},
                (txnName, signers) -> hapiPrng()
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "PrngRange100",
                "RANGE=100 (+100 extra)",
                feeMap,
                new String[] {PAYER},
                (txnName, signers) -> hapiPrng(100)
                        .payingWith(PAYER)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        // ===== AtomicBatch with varying transaction count =====
        addWithSigVariants(
                ops,
                prefix + "AtomicBatchT1",
                "TRANSACTIONS=1 (included)",
                feeMap,
                new String[] {BATCH_OPERATOR},
                (txnName, signers) -> atomicBatch(
                                cryptoTransfer(tinyBarsFromTo(BATCH_OPERATOR, RECEIVER, 1L))
                                        .payingWith(BATCH_OPERATOR)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        addWithSigVariants(
                ops,
                prefix + "AtomicBatchT3",
                "TRANSACTIONS=3 (+2 extra)",
                feeMap,
                new String[] {BATCH_OPERATOR},
                (txnName, signers) -> atomicBatch(
                                cryptoTransfer(tinyBarsFromTo(BATCH_OPERATOR, RECEIVER, 1L))
                                        .payingWith(BATCH_OPERATOR)
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(tinyBarsFromTo(BATCH_OPERATOR, RECEIVER, 2L))
                                        .payingWith(BATCH_OPERATOR)
                                        .batchKey(BATCH_OPERATOR),
                                cryptoTransfer(tinyBarsFromTo(BATCH_OPERATOR, RECEIVER, 3L))
                                        .payingWith(BATCH_OPERATOR)
                                        .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .signedBy(signers)
                        .fee(ONE_HUNDRED_HBARS));

        return ops;
    }

    // ==================== FEE CAPTURE AND COMPARISON ====================

    private static String sigEmphasis(final String[] signers) {
        final int total = new LinkedHashSet<>(Arrays.asList(signers)).size();
        final int extra = Math.max(0, total - 1);
        return extra == 0 ? "SIGS=" + total + " (included)" : "SIGS=" + total + " (+" + extra + " extra)";
    }

    private static String joinEmphasis(final String... parts) {
        return Arrays.stream(parts).filter(p -> p != null && !p.isEmpty()).collect(Collectors.joining(", "));
    }

    private static <T extends HapiTxnOp<T>> void addWithSigVariants(
            final List<SpecOperation> ops,
            final String txnNameBase,
            final String emphasisBase,
            final Map<String, Long> feeMap,
            final String[] requiredSigners,
            final BiFunction<String, String[], T> opFactory) {
        addWithSigVariants(ops, txnNameBase, emphasisBase, feeMap, requiredSigners, null, opFactory);
    }

    private static <T extends HapiTxnOp<T>> void addWithSigVariants(
            final List<SpecOperation> ops,
            final String txnNameBase,
            final String emphasisBase,
            final Map<String, Long> feeMap,
            final String[] requiredSigners,
            final Function<String, SpecOperation> setupOpFactory,
            final BiFunction<String, String[], T> opFactory) {
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final String[] signers = sigVariant.withRequired(requiredSigners);
            final String txnName = txnNameBase + sigVariant.suffix();
            if (setupOpFactory != null) {
                ops.add(setupOpFactory.apply(txnName));
            }
            final T op = opFactory.apply(txnName, signers).via(txnName);
            ops.add(op);
            ops.add(captureFee(txnName, joinEmphasis(emphasisBase, sigEmphasis(signers)), feeMap));
        }
    }

    private static <T extends HapiQueryOp<T>> void addQueryWithSigVariants(
            final List<SpecOperation> ops,
            final String queryNameBase,
            final String emphasisBase,
            final Map<String, Long> feeMap,
            final String[] requiredSigners,
            final BiFunction<String, String[], T> queryFactory) {
        addQueryWithSigVariants(ops, queryNameBase, emphasisBase, feeMap, requiredSigners, null, queryFactory);
    }

    private static <T extends HapiQueryOp<T>> void addQueryWithSigVariants(
            final List<SpecOperation> ops,
            final String queryNameBase,
            final String emphasisBase,
            final Map<String, Long> feeMap,
            final String[] requiredSigners,
            final Function<String, SpecOperation> setupOpFactory,
            final BiFunction<String, String[], T> queryFactory) {
        for (final SigVariant sigVariant : SIG_VARIANTS) {
            final String[] signers = sigVariant.withRequired(requiredSigners);
            final String queryName = queryNameBase + sigVariant.suffix();
            if (setupOpFactory != null) {
                ops.add(setupOpFactory.apply(queryName));
            }
            final String emphasis = joinEmphasis(emphasisBase, sigEmphasis(signers));
            final T op = queryFactory.apply(queryName, signers).fee(ONE_HUNDRED_HBARS);
            if (signers.length > requiredSigners.length || queryName.contains("CryptoGetAccountRecords")) {
                op.hasAnswerOnlyPrecheckFrom(
                        com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK,
                        com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE);
            }
            op.exposingNodePaymentTo(captureQueryFee(queryName, emphasis, feeMap)).via(queryName);
            ops.add(op);
        }
    }

    /**
     * Captures the fee for a transaction and stores it in the fee map.
     * @param txnName The transaction name (used as key in the map)
     * @param feeMap The map to store the fee
     * @return A SpecOperation that captures the fee
     */
    private static SpecOperation captureFee(String txnName, Map<String, Long> feeMap) {
        return captureFee(txnName, "", feeMap);
    }

    /**
     * Captures the fee for a transaction with emphasis information.
     * @param txnName The transaction name (used as key in the map)
     * @param emphasis Description of which extras are being tested (e.g., "KEYS=3")
     * @param feeMap The map to store the fee
     * @return A SpecOperation that captures the fee
     */
    private static SpecOperation captureFee(String txnName, String emphasis, Map<String, Long> feeMap) {
        return withOpContext((spec, opLog) -> {
            var recordOp = getTxnRecord(txnName);
            allRunFor(spec, recordOp);
            var record = recordOp.getResponseRecord();
            long fee = record.getTransactionFee();
            // Store both fee and emphasis in the map key for later parsing
            String key = emphasis.isEmpty() ? txnName : txnName + "|" + emphasis;
            feeMap.put(key, fee);
            LOG.info("Captured fee for {} [{}]: {} tinybars", txnName, emphasis, fee);
        });
    }

    private static LongConsumer captureQueryFee(final String queryName, final String emphasis, final Map<String, Long> feeMap) {
        return fee -> {
            final String key = emphasis.isEmpty() ? queryName : queryName + "|" + emphasis;
            feeMap.put(key, fee);
            LOG.info("Captured query fee for {} [{}]: {} tinybars", queryName, emphasis, fee);
        };
    }

    private static SpecOperation logFeeComparison(Map<String, Long> legacyFees, Map<String, Long> simpleFees) {
        return withOpContext((spec, opLog) -> {
            LOG.info("\n========== FEE COMPARISON RESULTS ==========");
            LOG.info(String.format(
                    "%-40s %-30s %15s %15s %15s %10s",
                    "Transaction",
                    "Emphasis",
                    "Legacy Fee",
                    "Simple Fee",
                    "Difference",
                    "% Change"));
            LOG.info("=".repeat(130));

            for (String txnKey : legacyFees.keySet()) {
                // Parse the key to extract transaction name and emphasis
                String[] parts = txnKey.split("\\|", 2);
                String txnName = parts[0];
                String emphasis = parts.length > 1 ? parts[1] : "";

                // Extract the base name (remove prefix)
                String baseName = txnName.startsWith("legacy") ? txnName.substring(6) : txnName;
                String simpleTxnKey = "simple" + baseName + (emphasis.isEmpty() ? "" : "|" + emphasis);

                Long legacyFee = legacyFees.get(txnKey);
                Long simpleFee = simpleFees.get(simpleTxnKey);

                if (legacyFee != null && simpleFee != null) {
                    long diff = simpleFee - legacyFee;
                    double pctChange = legacyFee > 0 ? (diff * 100.0 / legacyFee) : 0;
                    LOG.info(String.format(
                            "%-40s %-30s %15d %15d %15d %9.2f%%",
                            baseName,
                            emphasis,
                            legacyFee,
                            simpleFee,
                            diff,
                            pctChange));
                }
            }
            LOG.info("=".repeat(130));

            // Also log summary statistics
            long totalLegacy = legacyFees.values().stream().mapToLong(Long::longValue).sum();
            long totalSimple = simpleFees.values().stream().mapToLong(Long::longValue).sum();
            long totalDiff = totalSimple - totalLegacy;
            double totalPctChange = totalLegacy > 0 ? (totalDiff * 100.0 / totalLegacy) : 0;

            LOG.info(String.format(
                    "%-40s %-30s %15d %15d %15d %9.2f%%",
                    "TOTAL",
                    "",
                    totalLegacy,
                    totalSimple,
                    totalDiff,
                    totalPctChange));
            LOG.info("=============================================\n");
        });
    }

    /**
     * Writes fee comparison results to a CSV file.
     * @param legacyFees Map of legacy fees
     * @param simpleFees Map of simple fees
     * @param filename Name of the CSV file to write
     * @return A SpecOperation that writes the CSV file
     */
    private static SpecOperation writeFeeComparisonToCsv(
            Map<String, Long> legacyFees, Map<String, Long> simpleFees, String filename) {
        return withOpContext((spec, opLog) -> {
            final var ratesProvider = spec.ratesProvider();
            final boolean hasRates = ratesProvider.hasRateSet();
            if (!hasRates) {
                LOG.warn("No exchange rates available; USD columns will be empty in {}", filename);
            }
            try (FileWriter writer = new FileWriter(filename)) {
                // Write CSV header
                writer.append("Transaction,Extras,Legacy Fee (tinybars),Legacy Fee (USD),Simple Fee (tinybars),Simple Fee (USD),Difference (tinybars),Difference (USD),% Change\n");

                // Write data rows
                for (String txnKey : legacyFees.keySet()) {
                    // Parse the key to extract transaction name and emphasis
                    String[] parts = txnKey.split("\\|", 2);
                    String txnName = parts[0];
                    String emphasis = parts.length > 1 ? parts[1] : "";

                    // Extract the base name (remove prefix)
                    String baseName = txnName.startsWith("legacy") ? txnName.substring(6) : txnName;
                    String simpleTxnKey = "simple" + baseName + (emphasis.isEmpty() ? "" : "|" + emphasis);

                    Long legacyFee = legacyFees.get(txnKey);
                    Long simpleFee = simpleFees.get(simpleTxnKey);

                    if (legacyFee != null && simpleFee != null) {
                        long diff = simpleFee - legacyFee;
                        double pctChange = legacyFee > 0 ? (diff * 100.0 / legacyFee) : 0;

                        String legacyUsd = "";
                        String simpleUsd = "";
                        String diffUsd = "";
                        if (hasRates) {
                            legacyUsd = String.format("%.5f", ratesProvider.toUsdWithActiveRates(legacyFee));
                            simpleUsd = String.format("%.5f", ratesProvider.toUsdWithActiveRates(simpleFee));
                            diffUsd = String.format("%.5f", ratesProvider.toUsdWithActiveRates(diff));
                        }

                        // Escape commas in transaction name and emphasis
                        String escapedBaseName = baseName.replace(",", ";");
                        String escapedEmphasis = emphasis.replace(",", ";");

                        writer.append(String.format(
                                "%s,%s,%d,%s,%d,%s,%d,%s,%.2f\n",
                                escapedBaseName,
                                escapedEmphasis,
                                legacyFee,
                                legacyUsd,
                                simpleFee,
                                simpleUsd,
                                diff,
                                diffUsd,
                                pctChange));
                    }
                }

                // Write summary row
                long totalLegacy = legacyFees.values().stream().mapToLong(Long::longValue).sum();
                long totalSimple = simpleFees.values().stream().mapToLong(Long::longValue).sum();
                long totalDiff = totalSimple - totalLegacy;
                double totalPctChange = totalLegacy > 0 ? (totalDiff * 100.0 / totalLegacy) : 0;

                String totalLegacyUsd = "";
                String totalSimpleUsd = "";
                String totalDiffUsd = "";
                if (hasRates) {
                    totalLegacyUsd = String.format("%.5f", ratesProvider.toUsdWithActiveRates(totalLegacy));
                    totalSimpleUsd = String.format("%.5f", ratesProvider.toUsdWithActiveRates(totalSimple));
                    totalDiffUsd = String.format("%.5f", ratesProvider.toUsdWithActiveRates(totalDiff));
                }

                writer.append(String.format(
                        "TOTAL,,%d,%s,%d,%s,%d,%s,%.2f\n",
                        totalLegacy,
                        totalLegacyUsd,
                        totalSimple,
                        totalSimpleUsd,
                        totalDiff,
                        totalDiffUsd,
                        totalPctChange));

                LOG.info("Fee comparison results written to: {}", filename);
            } catch (IOException e) {
                LOG.error("Failed to write CSV file: {}", filename, e);
                throw new RuntimeException("Failed to write CSV file: " + filename, e);
            }
        });
    }
}
