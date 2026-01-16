// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
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
 * (different number of signatures, key structures, memo lengths, etc.) and compares
 * fees charged with simple fees enabled vs disabled.
 *
 * <p>This test uses:
 * <ul>
 *   <li>{@code @LeakyHapiTest} - allows overriding network properties</li>
 *   <li>ED25519 keys only for consistency</li>
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
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    // Key names for different complexity levels
    private static final String SIMPLE_KEY = "simpleKey";
    private static final String LIST_KEY_2 = "listKey2";
    private static final String LIST_KEY_3 = "listKey3";
    private static final String LIST_KEY_5 = "listKey5";
    private static final String THRESH_KEY_2_OF_3 = "threshKey2of3";
    private static final String THRESH_KEY_3_OF_5 = "threshKey3of5";
    private static final String COMPLEX_KEY = "complexKey";

    // Payer names
    private static final String PAYER = "payer";
    private static final String PAYER_COMPLEX = "payerComplex";
    private static final String TREASURY = "treasury";
    private static final String RECEIVER = "receiver";

    // Memo variations (max memo length is 100 bytes)
    private static final String EMPTY_MEMO = "";
    private static final String SHORT_MEMO = "Short memo";
    private static final String MEDIUM_MEMO = "x".repeat(50);
    private static final String LONG_MEMO = "x".repeat(100); // Max allowed memo length

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
                logFeeComparison(legacyFees, simpleFees));
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
                logFeeComparison(legacyFees, simpleFees));
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
                newKeyNamed(COMPLEX_KEY).shape(threshOf(2, listOf(2), KeyShape.SIMPLE, threshOf(1, 2))));
    }

    // ==================== BASE ACCOUNT CREATION ====================

    private static SpecOperation createBaseAccounts() {
        return blockingOrder(
                cryptoCreate(PAYER).balance(ONE_MILLION_HBARS).key(SIMPLE_KEY),
                cryptoCreate(PAYER_COMPLEX).balance(ONE_MILLION_HBARS).key(COMPLEX_KEY),
                cryptoCreate(TREASURY).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS));
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
        // Contract transactions are commented out for now due to resource constraints
        // ops.addAll(contractTransactions(prefix, feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== CRYPTO TRANSACTIONS ====================

    private static List<SpecOperation> cryptoTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ===== CryptoCreate with varying KEYS extra =====
        // KEYS extra: fee=100000000 per key, includedCount=1
        ops.add(cryptoCreate(prefix + "AccSimple")
                .key(SIMPLE_KEY)
                .balance(ONE_HBAR)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateK1"));
        ops.add(captureFee(prefix + "CryptoCreateK1", "KEYS=1 (included)", feeMap));

        ops.add(cryptoCreate(prefix + "AccList2")
                .key(LIST_KEY_2)
                .balance(ONE_HBAR)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateK2"));
        ops.add(captureFee(prefix + "CryptoCreateK2", "KEYS=2 (+1 extra)", feeMap));

        ops.add(cryptoCreate(prefix + "AccList3")
                .key(LIST_KEY_3)
                .balance(ONE_HBAR)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateK3"));
        ops.add(captureFee(prefix + "CryptoCreateK3", "KEYS=3 (+2 extra)", feeMap));

        ops.add(cryptoCreate(prefix + "AccList5")
                .key(LIST_KEY_5)
                .balance(ONE_HBAR)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateK5"));
        ops.add(captureFee(prefix + "CryptoCreateK5", "KEYS=5 (+4 extra)", feeMap));

        // ===== CryptoUpdate with varying KEYS extra =====
        // KEYS extra: fee=100000000 per key, includedCount=1
        ops.add(cryptoUpdate(prefix + "AccSimple")
                .memo(SHORT_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateK1"));
        ops.add(captureFee(prefix + "CryptoUpdateK1", "KEYS=1 (included)", feeMap));

        ops.add(cryptoUpdate(prefix + "AccList3")
                .memo(SHORT_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateK3"));
        ops.add(captureFee(prefix + "CryptoUpdateK3", "KEYS=3 (+2 extra)", feeMap));

        // ===== CryptoUpdate with HOOK_UPDATES extra =====
        // HOOK_UPDATES extra: fee=10000000000 per hook update, includedCount=0
        // Need to create hook contract first for these tests
        ops.add(uploadInitCode(TRUE_HOOK_CONTRACT));
        ops.add(contractCreate(prefix + "CryptoUpdateHookContract")
                .bytecode(TRUE_HOOK_CONTRACT)
                .gas(5_000_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // CryptoUpdate adding 1 hook (HOOK_UPDATES=1)
        ops.add(cryptoUpdate(prefix + "AccList5")
                .withHooks(accountAllowanceHook(100L, prefix + "CryptoUpdateHookContract"))
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_5)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateHU1"));
        ops.add(captureFee(prefix + "CryptoUpdateHU1", "HOOK_UPDATES=1", feeMap));

        // CryptoUpdate adding 2 hooks (HOOK_UPDATES=2)
        ops.add(uploadInitCode("TruePrePostHook"));
        ops.add(contractCreate(prefix + "CryptoUpdateHookContract2")
                .bytecode("TruePrePostHook")
                .gas(5_000_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(cryptoCreate(prefix + "AccForHookUpdate")
                .balance(ONE_HBAR)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(cryptoUpdate(prefix + "AccForHookUpdate")
                .withHooks(
                        accountAllowanceHook(101L, prefix + "CryptoUpdateHookContract"),
                        accountAllowanceHook(102L, prefix + "CryptoUpdateHookContract2"))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateHU2"));
        ops.add(captureFee(prefix + "CryptoUpdateHU2", "HOOK_UPDATES=2 (+2 extra)", feeMap));

        // ===== CryptoTransfer with varying ACCOUNTS extra =====
        // ACCOUNTS extra: fee=1000000 per account, includedCount=2
        ops.add(cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                .payingWith(PAYER)
                .blankMemo()
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HbarTransferA2"));
        ops.add(captureFee(prefix + "HbarTransferA2", "ACCOUNTS=2 (included)", feeMap));

        ops.add(cryptoTransfer(
                        movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                        movingHbar(ONE_HBAR).between(PAYER, prefix + "AccSimple"))
                .payingWith(PAYER)
                .blankMemo()
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HbarTransferA3"));
        ops.add(captureFee(prefix + "HbarTransferA3", "ACCOUNTS=3 (+1 extra)", feeMap));

        ops.add(cryptoTransfer(
                        movingHbar(ONE_HBAR).between(PAYER, RECEIVER),
                        movingHbar(ONE_HBAR).between(PAYER, prefix + "AccSimple"),
                        movingHbar(ONE_HBAR).between(PAYER, prefix + "AccList2"))
                .payingWith(PAYER)
                .blankMemo()
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HbarTransferA4"));
        ops.add(captureFee(prefix + "HbarTransferA4", "ACCOUNTS=4 (+2 extra)", feeMap));

        // ===== CryptoApproveAllowance with varying ALLOWANCES extra =====
        // ALLOWANCES extra: fee=500000000 per allowance, includedCount=1
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccSimple", RECEIVER, ONE_HBAR)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoApproveA1"));
        ops.add(captureFee(prefix + "CryptoApproveA1", "ALLOWANCES=1 (included)", feeMap));

        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccList2", RECEIVER, ONE_HBAR * 5)
                .addCryptoAllowance(prefix + "AccList2", prefix + "AccSimple", ONE_HBAR * 2)
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_2)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoApproveA2"));
        ops.add(captureFee(prefix + "CryptoApproveA2", "ALLOWANCES=2 (+1 extra)", feeMap));

        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccList3", RECEIVER, ONE_HBAR)
                .addCryptoAllowance(prefix + "AccList3", prefix + "AccSimple", ONE_HBAR)
                .addCryptoAllowance(prefix + "AccList3", prefix + "AccList2", ONE_HBAR)
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoApproveA3"));
        ops.add(captureFee(prefix + "CryptoApproveA3", "ALLOWANCES=3 (+2 extra)", feeMap));

        // ===== CryptoDeleteAllowance with varying ALLOWANCES extra =====
        // ALLOWANCES extra: fee=500000000 per allowance, includedCount=1
        // Need to create NFTs and approve allowances first
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
                        ByteString.copyFromUtf8("NFT_A3")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));

        // Approve NFT allowances so we can delete them
        ops.add(cryptoApproveAllowance()
                .addNftAllowance(TREASURY, prefix + "NFTForAllowance", RECEIVER, false, List.of(1L))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(cryptoApproveAllowance()
                .addNftAllowance(TREASURY, prefix + "NFTForAllowance", RECEIVER, false, List.of(2L, 3L))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS));

        // CryptoDeleteAllowance with 1 NFT allowance (ALLOWANCES=1, included)
        ops.add(cryptoDeleteAllowance()
                .addNftDeleteAllowance(TREASURY, prefix + "NFTForAllowance", List.of(1L))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoDeleteAllowanceA1"));
        ops.add(captureFee(prefix + "CryptoDeleteAllowanceA1", "ALLOWANCES=1 (included)", feeMap));

        // CryptoDeleteAllowance with 2 NFT allowances (ALLOWANCES=2, +1 extra)
        ops.add(cryptoDeleteAllowance()
                .addNftDeleteAllowance(TREASURY, prefix + "NFTForAllowance", List.of(2L, 3L))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoDeleteAllowanceA2"));
        ops.add(captureFee(prefix + "CryptoDeleteAllowanceA2", "ALLOWANCES=2 (+1 extra)", feeMap));

        // ===== CryptoDelete =====
        ops.add(cryptoCreate(prefix + "ToDelete")
                .balance(0L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoDelete(prefix + "ToDelete")
                .transfer(PAYER)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoDelete"));
        ops.add(captureFee(prefix + "CryptoDelete", "no extras", feeMap));

        // ===== CryptoTransfer with TOKEN_TRANSFER_BASE extra =====
        // TOKEN_TRANSFER_BASE extra: fee=9000000 per token transfer, includedCount=0
        // Need to create fungible tokens and associate them first
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

        // CryptoTransfer with 1 fungible token transfer (FUNGIBLE_TOKENS=1, included)
        ops.add(cryptoTransfer(moving(100, prefix + "FungibleForTransfer1").between(TREASURY, RECEIVER))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferFT1"));
        ops.add(captureFee(prefix + "TokenTransferFT1", "FUNGIBLE_TOKENS=1 (included), TOKEN_TRANSFER_BASE=1", feeMap));

        // CryptoTransfer with 2 fungible token transfers (FUNGIBLE_TOKENS=2, +1 extra)
        ops.add(cryptoTransfer(
                        moving(100, prefix + "FungibleForTransfer1").between(TREASURY, RECEIVER),
                        moving(200, prefix + "FungibleForTransfer2").between(TREASURY, RECEIVER))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferFT2"));
        ops.add(captureFee(prefix + "TokenTransferFT2", "FUNGIBLE_TOKENS=2 (+1 extra), TOKEN_TRANSFER_BASE=2 (+2 extra)", feeMap));

        // ===== CryptoTransfer with NON_FUNGIBLE_TOKENS extra =====
        // NON_FUNGIBLE_TOKENS extra: fee=1000000 per NFT, includedCount=1
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
                        ByteString.copyFromUtf8("NFT3")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(tokenAssociate(RECEIVER, prefix + "NFTForTransfer")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // CryptoTransfer with 1 NFT (NON_FUNGIBLE_TOKENS=1, included)
        ops.add(cryptoTransfer(movingUnique(prefix + "NFTForTransfer", 1L).between(TREASURY, RECEIVER))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferNFT1"));
        ops.add(captureFee(prefix + "TokenTransferNFT1", "NON_FUNGIBLE_TOKENS=1 (included), TOKEN_TRANSFER_BASE=1", feeMap));

        // CryptoTransfer with 2 NFTs (NON_FUNGIBLE_TOKENS=2, +1 extra)
        ops.add(cryptoTransfer(
                        movingUnique(prefix + "NFTForTransfer", 2L).between(TREASURY, RECEIVER),
                        movingUnique(prefix + "NFTForTransfer", 3L).between(TREASURY, RECEIVER))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferNFT2"));
        ops.add(captureFee(prefix + "TokenTransferNFT2", "NON_FUNGIBLE_TOKENS=2 (+1 extra), TOKEN_TRANSFER_BASE=2 (+1 extra)", feeMap));

        // ===== CryptoTransfer with TOKEN_TRANSFER_BASE_CUSTOM_FEES extra =====
        // TOKEN_TRANSFER_BASE_CUSTOM_FEES extra: fee=19000000 per token transfer with custom fees, includedCount=0
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

        // CryptoTransfer with 1 token with custom fees
        ops.add(cryptoTransfer(moving(100, prefix + "FungibleWithCustomFee").between(TREASURY, RECEIVER))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferCF1"));
        ops.add(captureFee(prefix + "TokenTransferCF1", "TOKEN_TRANSFER_BASE_CUSTOM_FEES=1", feeMap));

        return ops;
    }



    // ==================== TOKEN TRANSACTIONS ====================

    private static List<SpecOperation> tokenTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ===== TokenCreate with varying KEYS extra =====
        // KEYS extra: fee=100000000 per key, includedCount=1
        // TOKEN_CREATE_WITH_CUSTOM_FEE extra: fee=10000000000 per custom fee, includedCount=0
        ops.add(tokenCreate(prefix + "FungibleK0")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateK0"));
        ops.add(captureFee(prefix + "TokenCreateK0", "KEYS=0 (no admin key)", feeMap));

        ops.add(tokenCreate(prefix + "FungibleK1")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateK1"));
        ops.add(captureFee(prefix + "TokenCreateK1", "KEYS=1 (included)", feeMap));

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
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateK3"));
        ops.add(captureFee(prefix + "TokenCreateK3", "KEYS=3 (+2 extra)", feeMap));

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
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateK5"));
        ops.add(captureFee(prefix + "TokenCreateK5", "KEYS=5 (+4 extra)", feeMap));

        // ===== TokenCreate with TOKEN_CREATE_WITH_CUSTOM_FEE extra =====
        ops.add(tokenCreate(prefix + "FungibleCF1")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .withCustom(fixedHbarFee(ONE_HBAR, TREASURY))
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateCF1"));
        ops.add(captureFee(prefix + "TokenCreateCF1", "CUSTOM_FEE=1 (hbar)", feeMap));

        ops.add(tokenCreate(prefix + "FungibleCF2")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .adminKey(SIMPLE_KEY)
                .withCustom(fixedHbarFee(ONE_HBAR, TREASURY))
                .withCustom(fractionalFee(1, 100, 1, OptionalLong.of(100), TREASURY))
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateCF2"));
        ops.add(captureFee(prefix + "TokenCreateCF2", "CUSTOM_FEE=2 (hbar+frac)", feeMap));

        // ===== TokenCreate - NFT with royalty fee =====
        ops.add(tokenCreate(prefix + "NFT")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateNFT"));
        ops.add(captureFee(prefix + "TokenCreateNFT", "KEYS=1 (supply)", feeMap));

        ops.add(tokenCreate(prefix + "NFTRoyalty")
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .initialSupply(0L)
                .treasury(TREASURY)
                .supplyKey(SIMPLE_KEY)
                .withCustom(royaltyFeeNoFallback(1, 10, TREASURY))
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateNFTRoyalty"));
        ops.add(captureFee(prefix + "TokenCreateNFTRoyalty", "CUSTOM_FEE=1 (royalty)", feeMap));

        // ===== TokenAssociate =====
        ops.add(tokenAssociate(prefix + "AccSimple", prefix + "FungibleK0", prefix + "FungibleK5")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenAssociate"));
        ops.add(captureFee(prefix + "TokenAssociate", "no extras", feeMap));

        // ===== TokenMint - Fungible (no TOKEN_MINT_NFT extra) =====
        ops.add(mintToken(prefix + "FungibleK5", 10_000L)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenMintFungible"));
        ops.add(captureFee(prefix + "TokenMintFungible", "TOKEN_MINT_NFT=0", feeMap));

        // ===== TokenMint - NFT with varying TOKEN_MINT_NFT extra =====
        // TOKEN_MINT_NFT extra: fee=199000000 per NFT, includedCount=0
        ops.add(mintToken(prefix + "NFT", List.of(ByteString.copyFromUtf8("NFT1")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenMintNFT1"));
        ops.add(captureFee(prefix + "TokenMintNFT1", "TOKEN_MINT_NFT=1", feeMap));

        ops.add(mintToken(
                        prefix + "NFT",
                        List.of(
                                ByteString.copyFromUtf8("NFT2"),
                                ByteString.copyFromUtf8("NFT3"),
                                ByteString.copyFromUtf8("NFT4")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenMintNFT3"));
        ops.add(captureFee(prefix + "TokenMintNFT3", "TOKEN_MINT_NFT=3", feeMap));

        ops.add(mintToken(
                        prefix + "NFT",
                        List.of(
                                ByteString.copyFromUtf8("NFT5"),
                                ByteString.copyFromUtf8("NFT6"),
                                ByteString.copyFromUtf8("NFT7"),
                                ByteString.copyFromUtf8("NFT8"),
                                ByteString.copyFromUtf8("NFT9")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenMintNFT5"));
        ops.add(captureFee(prefix + "TokenMintNFT5", "TOKEN_MINT_NFT=5", feeMap));

        // ===== Token transfer with FUNGIBLE_TOKENS extra =====
        // FUNGIBLE_TOKENS extra: fee=1000000 per token type, includedCount=1
        ops.add(cryptoTransfer(moving(100L, prefix + "FungibleK0").between(TREASURY, prefix + "AccSimple"))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferFT1"));
        ops.add(captureFee(prefix + "TokenTransferFT1", "FUNGIBLE_TOKENS=1 (incl)", feeMap));

        ops.add(cryptoTransfer(
                        moving(100L, prefix + "FungibleK0").between(TREASURY, prefix + "AccSimple"),
                        moving(100L, prefix + "FungibleK5").between(TREASURY, prefix + "AccSimple"))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferFT2"));
        ops.add(captureFee(prefix + "TokenTransferFT2", "FUNGIBLE_TOKENS=2 (+1)", feeMap));

        // ===== Token transfer - NFT with NON_FUNGIBLE_TOKENS extra =====
        // NON_FUNGIBLE_TOKENS extra: fee=1000000 per NFT, includedCount=1
        ops.add(tokenAssociate(RECEIVER, prefix + "NFT")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoTransfer(movingUnique(prefix + "NFT", 1L).between(TREASURY, RECEIVER))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferNFT1"));
        ops.add(captureFee(prefix + "TokenTransferNFT1", "NON_FUNGIBLE_TOKENS=1", feeMap));

        // ===== TokenUpdate with varying KEYS extra =====
        // KEYS extra: fee=100000000 per key, includedCount=1
        // TokenUpdate with 1 key (admin key)
        ops.add(tokenUpdate(prefix + "FungibleK1")
                .entityMemo("Updated memo")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenUpdateK1"));
        ops.add(captureFee(prefix + "TokenUpdateK1", "KEYS=1 (included)", feeMap));

        // TokenUpdate with 3 keys (admin, supply, freeze)
        ops.add(tokenUpdate(prefix + "FungibleK3")
                .entityMemo("Updated memo for K3")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenUpdateK3"));
        ops.add(captureFee(prefix + "TokenUpdateK3", "KEYS=3 (+2 extra)", feeMap));

        // TokenUpdate with 5 keys
        ops.add(tokenUpdate(prefix + "FungibleK5")
                .entityMemo("Updated memo for K5")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenUpdateK5"));
        ops.add(captureFee(prefix + "TokenUpdateK5", "KEYS=5 (+4 extra)", feeMap));

        // ===== TokenFreeze/Unfreeze (no extras) =====
        ops.add(tokenFreeze(prefix + "FungibleK5", prefix + "AccSimple")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenFreeze"));
        ops.add(captureFee(prefix + "TokenFreeze", "no extras", feeMap));

        ops.add(tokenUnfreeze(prefix + "FungibleK5", prefix + "AccSimple")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenUnfreeze"));
        ops.add(captureFee(prefix + "TokenUnfreeze", "no extras", feeMap));

        // ===== TokenDissociate (no extras) =====
        // First associate AccList2 with FungibleK0, then dissociate
        ops.add(tokenAssociate(prefix + "AccList2", prefix + "FungibleK0")
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_2)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(tokenDissociate(prefix + "AccList2", prefix + "FungibleK0")
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_2)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenDissociate"));
        ops.add(captureFee(prefix + "TokenDissociate", "no extras", feeMap));

        // ===== TokenBurn - Fungible (no extras) =====
        ops.add(burnToken(prefix + "FungibleK5", 1_000L)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenBurnFungible"));
        ops.add(captureFee(prefix + "TokenBurnFungible", "no extras", feeMap));

        // ===== TokenBurn - NFT (no extras) =====
        ops.add(burnToken(prefix + "NFT", List.of(9L))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenBurnNFT"));
        ops.add(captureFee(prefix + "TokenBurnNFT", "no extras", feeMap));

        // ===== TokenWipe - Fungible (no extras) =====
        ops.add(wipeTokenAccount(prefix + "FungibleK5", prefix + "AccSimple", 50L)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenWipeFungible"));
        ops.add(captureFee(prefix + "TokenWipeFungible", "no extras", feeMap));

        // ===== TokenPause (no extras) =====
        ops.add(tokenPause(prefix + "FungibleK5")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenPause"));
        ops.add(captureFee(prefix + "TokenPause", "no extras", feeMap));

        // ===== TokenUnpause (no extras) =====
        ops.add(tokenUnpause(prefix + "FungibleK5")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenUnpause"));
        ops.add(captureFee(prefix + "TokenUnpause", "no extras", feeMap));

        return ops;
    }

    // ==================== CONSENSUS TRANSACTIONS ====================

    private static List<SpecOperation> consensusTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ===== ConsensusCreateTopic with varying KEYS extra =====
        // KEYS extra: fee=100000000 per key, includedCount=0
        ops.add(createTopic(prefix + "TopicK0")
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateK0"));
        ops.add(captureFee(prefix + "TopicCreateK0", "KEYS=0 (included)", feeMap));

        ops.add(createTopic(prefix + "TopicK1")
                .adminKeyName(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateK1"));
        ops.add(captureFee(prefix + "TopicCreateK1", "KEYS=1 (+1 extra)", feeMap));

        ops.add(createTopic(prefix + "TopicK2")
                .adminKeyName(SIMPLE_KEY)
                .submitKeyName(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateK2"));
        ops.add(captureFee(prefix + "TopicCreateK2", "KEYS=2 (+2 extra)", feeMap));

        // ===== ConsensusCreateTopic with CONSENSUS_CREATE_TOPIC_WITH_CUSTOM_FEE extra =====
        // CONSENSUS_CREATE_TOPIC_WITH_CUSTOM_FEE extra: fee=19900000000, includedCount=0
        // This extra is charged when creating a topic with a custom fee schedule
        // Note: This is a placeholder - actual custom fee topics may not be fully implemented yet
        // For now, we'll just create a topic with admin key to test the base case
        ops.add(createTopic(prefix + "TopicWithCustomFee")
                .adminKeyName(SIMPLE_KEY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateCustomFee"));
        ops.add(captureFee(prefix + "TopicCreateCustomFee", "CONSENSUS_CREATE_TOPIC_WITH_CUSTOM_FEE=0 (not triggered)", feeMap));

        // ===== ConsensusSubmitMessage with varying BYTES extra =====
        // BYTES extra: fee=110000 per byte, includedCount=100
        ops.add(submitMessageTo(prefix + "TopicK0")
                .message("x".repeat(50))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgB50"));
        ops.add(captureFee(prefix + "SubmitMsgB50", "BYTES=50 (included)", feeMap));

        ops.add(submitMessageTo(prefix + "TopicK0")
                .message("x".repeat(100))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgB100"));
        ops.add(captureFee(prefix + "SubmitMsgB100", "BYTES=100 (included)", feeMap));

        ops.add(submitMessageTo(prefix + "TopicK0")
                .message("x".repeat(500))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgB500"));
        ops.add(captureFee(prefix + "SubmitMsgB500", "BYTES=500 (+400 extra)", feeMap));

        ops.add(submitMessageTo(prefix + "TopicK0")
                .message("x".repeat(1000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgB1000"));
        ops.add(captureFee(prefix + "SubmitMsgB1000", "BYTES=1000 (+900 extra)", feeMap));

        // ===== ConsensusSubmitMessage with CONSENSUS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE extra =====
        // CONSENSUS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE extra: fee=499000000, includedCount=0
        // This extra is charged when submitting a message to a topic with custom fees
        // Note: This is a placeholder - actual custom fee topics may not be fully implemented yet
        ops.add(submitMessageTo(prefix + "TopicWithCustomFee")
                .message("x".repeat(100))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgCustomFee"));
        ops.add(captureFee(prefix + "SubmitMsgCustomFee", "CONSENSUS_SUBMIT_MESSAGE_WITH_CUSTOM_FEE=0 (not triggered)", feeMap));

        // ===== ConsensusUpdateTopic with varying KEYS extra =====
        // KEYS extra: fee=100000000 per key, includedCount=1
        ops.add(updateTopic(prefix + "TopicK1")
                .topicMemo(SHORT_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicUpdateK1"));
        ops.add(captureFee(prefix + "TopicUpdateK1", "KEYS=1 (included)", feeMap));

        // ConsensusUpdateTopic with 2 keys (admin + submit)
        ops.add(updateTopic(prefix + "TopicK2")
                .topicMemo(SHORT_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicUpdateK2"));
        ops.add(captureFee(prefix + "TopicUpdateK2", "KEYS=2 (+1 extra)", feeMap));

        // ===== ConsensusDeleteTopic (no extras) =====
        ops.add(deleteTopic(prefix + "TopicK2")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicDelete"));
        ops.add(captureFee(prefix + "TopicDelete", "no extras", feeMap));

        return ops;
    }

    // ==================== FILE TRANSACTIONS ====================

    private static List<SpecOperation> fileTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // ===== FileCreate with varying BYTES extra =====
        // BYTES extra: fee=110000 per byte, includedCount=1000
        // KEYS extra: fee=100000000 per key, includedCount=1
        ops.add(fileCreate(prefix + "FileB100")
                .contents("x".repeat(100))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateB100"));
        ops.add(captureFee(prefix + "FileCreateB100", "BYTES=100 (included)", feeMap));

        ops.add(fileCreate(prefix + "FileB1000")
                .contents("x".repeat(1000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateB1000"));
        ops.add(captureFee(prefix + "FileCreateB1000", "BYTES=1000 (included)", feeMap));

        ops.add(fileCreate(prefix + "FileB2000")
                .contents("x".repeat(2000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateB2000"));
        ops.add(captureFee(prefix + "FileCreateB2000", "BYTES=2000 (+1000 extra)", feeMap));

        ops.add(fileCreate(prefix + "FileB4000")
                .contents("x".repeat(4000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateB4000"));
        ops.add(captureFee(prefix + "FileCreateB4000", "BYTES=4000 (+3000 extra)", feeMap));

        // ===== FileUpdate with varying BYTES extra =====
        // BYTES extra: fee=110000 per byte, includedCount=1000
        // KEYS extra: fee=100000000 per key, includedCount=1
        ops.add(fileUpdate(prefix + "FileB100")
                .contents("x".repeat(500))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileUpdateB500"));
        ops.add(captureFee(prefix + "FileUpdateB500", "BYTES=500 (included), KEYS=1 (included)", feeMap));

        ops.add(fileUpdate(prefix + "FileB1000")
                .contents("x".repeat(3000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileUpdateB3000"));
        ops.add(captureFee(prefix + "FileUpdateB3000", "BYTES=3000 (+2000 extra), KEYS=1 (included)", feeMap));

        // ===== FileUpdate with varying KEYS extra =====
        // Create a file with multiple keys to test KEYS extra on update
        ops.add(fileCreate(prefix + "FileWithKeys")
                .contents("test")
                .key(LIST_KEY_3)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(fileUpdate(prefix + "FileWithKeys")
                .contents("updated")
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_3)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileUpdateK3"));
        ops.add(captureFee(prefix + "FileUpdateK3", "KEYS=3 (+2 extra), BYTES=7 (included)", feeMap));

        // ===== FileAppend with varying BYTES extra =====
        // BYTES extra: fee=110000 per byte, includedCount=1000
        ops.add(fileAppend(prefix + "FileB100")
                .content("y".repeat(500))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileAppendB500"));
        ops.add(captureFee(prefix + "FileAppendB500", "BYTES=500 (included)", feeMap));

        ops.add(fileAppend(prefix + "FileB1000")
                .content("y".repeat(2000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileAppendB2000"));
        ops.add(captureFee(prefix + "FileAppendB2000", "BYTES=2000 (+1000 extra)", feeMap));

        // ===== FileDelete (no extras) =====
        ops.add(fileDelete(prefix + "FileB4000")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileDelete"));
        ops.add(captureFee(prefix + "FileDelete", "no extras", feeMap));

        return ops;
    }

    // ==================== SCHEDULE TRANSACTIONS ====================

    private static List<SpecOperation> scheduleTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Use different amounts based on prefix to ensure unique scheduled transactions
        // "simple" prefix uses 1 tinybar, "legacy" prefix uses 2 tinybars
        final long amount = prefix.equals("simple") ? 1L : 2L;

        // ===== ScheduleCreate with varying KEYS extra =====
        // KEYS extra: fee=100000000 per key, includedCount=1
        ops.add(scheduleCreate(
                        prefix + "ScheduleK0",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).blankMemo())
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateK0"));
        ops.add(captureFee(prefix + "ScheduleCreateK0", "KEYS=0 (no admin)", feeMap));

        ops.add(scheduleCreate(
                        prefix + "ScheduleK1",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).blankMemo())
                .adminKey(SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateK1"));
        ops.add(captureFee(prefix + "ScheduleCreateK1", "KEYS=1 (included)", feeMap));

        ops.add(scheduleCreate(
                        prefix + "ScheduleK2",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).blankMemo())
                .adminKey(LIST_KEY_2)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateK2"));
        ops.add(captureFee(prefix + "ScheduleCreateK2", "KEYS=2 (+1 extra)", feeMap));

        // ===== ScheduleCreate with SCHEDULE_CREATE_CONTRACT_CALL_BASE extra =====
        // SCHEDULE_CREATE_CONTRACT_CALL_BASE extra: fee=900000000, includedCount=0
        // This extra is charged when scheduling a contract call transaction
        // Note: For now, we'll just test the base case without actually triggering this extra
        // as it requires a contract with callable functions. The extra is defined in the JSON
        // but may not be fully implemented yet in the fee calculation logic.

        // ===== ScheduleSign (no extras) =====
        ops.add(scheduleSign(prefix + "ScheduleK1")
                .alsoSigningWith(RECEIVER)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleSign"));
        ops.add(captureFee(prefix + "ScheduleSign", "no extras", feeMap));

        // ===== ScheduleDelete (no extras) =====
        ops.add(scheduleDelete(prefix + "ScheduleK2")
                .signedBy(PAYER, LIST_KEY_2)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleDelete"));
        ops.add(captureFee(prefix + "ScheduleDelete", "no extras", feeMap));

        return ops;
    }

    // ==================== HOOK TRANSACTIONS ====================

    private static final String HOOK_CONTRACT = "PayableConstructor";
    private static final String TRUE_HOOK_CONTRACT = "TruePreHook";
    private static final String HOOK_OWNER = "hookOwner";
    private static final long HOOK_GAS_LIMIT = 25000L;

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
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateH1"));
        ops.add(captureFee(prefix + "CryptoCreateH1", "HOOK_UPDATES=1", feeMap));

        // ===== HookStore with varying storage slot updates =====
        // HookStore baseFee: 49000000
        ops.add(accountEvmHookStore(prefix + HOOK_OWNER, 1L)
                .putSlot(Bytes.wrap("slot1"), Bytes.wrap("value1"))
                .payingWith(PAYER)
                .signedBy(PAYER, prefix + HOOK_OWNER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HookStoreS1"));
        ops.add(captureFee(prefix + "HookStoreS1", "SLOTS=1", feeMap));

        ops.add(accountEvmHookStore(prefix + HOOK_OWNER, 1L)
                .putSlot(Bytes.wrap("slot2"), Bytes.wrap("value2"))
                .putSlot(Bytes.wrap("slot3"), Bytes.wrap("value3"))
                .payingWith(PAYER)
                .signedBy(PAYER, prefix + HOOK_OWNER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HookStoreS2"));
        ops.add(captureFee(prefix + "HookStoreS2", "SLOTS=2", feeMap));

        ops.add(accountEvmHookStore(prefix + HOOK_OWNER, 1L)
                .putSlot(Bytes.wrap("slot4"), Bytes.wrap("value4"))
                .putSlot(Bytes.wrap("slot5"), Bytes.wrap("value5"))
                .putSlot(Bytes.wrap("slot6"), Bytes.wrap("value6"))
                .putSlot(Bytes.wrap("slot7"), Bytes.wrap("value7"))
                .putSlot(Bytes.wrap("slot8"), Bytes.wrap("value8"))
                .payingWith(PAYER)
                .signedBy(PAYER, prefix + HOOK_OWNER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HookStoreS5"));
        ops.add(captureFee(prefix + "HookStoreS5", "SLOTS=5", feeMap));

        // ===== HookStore - remove slots =====
        ops.add(accountEvmHookStore(prefix + HOOK_OWNER, 1L)
                .removeSlot(Bytes.wrap("slot1"))
                .payingWith(PAYER)
                .signedBy(PAYER, prefix + HOOK_OWNER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HookStoreRemove1"));
        ops.add(captureFee(prefix + "HookStoreRemove1", "REMOVE_SLOTS=1", feeMap));

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
                .balance(ONE_HUNDRED_HBARS)
                .withHooks(accountAllowanceHook(10L, prefix + "TrueHookContract"))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // Create receiver account
        ops.add(cryptoCreate(prefix + "HookReceiver")
                .balance(ONE_HBAR)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // CryptoTransfer with 1 hook execution (pre-hook on sender)
        ops.add(cryptoTransfer(tinyBarsFromTo(prefix + "HookSender", prefix + "HookReceiver", ONE_HBAR))
                .withPreHookFor(prefix + "HookSender", 10L, HOOK_GAS_LIMIT, "")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoTransferHE1"));
        ops.add(captureFee(prefix + "CryptoTransferHE1", "HOOK_EXECUTION=1 (pre)", feeMap));

        // Create sender with pre-post hook for 2 hook executions
        ops.add(uploadInitCode("TruePrePostHook"));
        ops.add(contractCreate(prefix + "TruePrePostHookContract")
                .bytecode("TruePrePostHook")
                .gas(5_000_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        ops.add(cryptoCreate(prefix + "HookSender2")
                .balance(ONE_HUNDRED_HBARS)
                .withHooks(accountAllowanceHook(20L, prefix + "TruePrePostHookContract"))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));

        // CryptoTransfer with 2 hook executions (pre-post hook on sender)
        ops.add(cryptoTransfer(tinyBarsFromTo(prefix + "HookSender2", prefix + "HookReceiver", ONE_HBAR))
                .withPrePostHookFor(prefix + "HookSender2", 20L, HOOK_GAS_LIMIT, "")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoTransferHE2"));
        ops.add(captureFee(prefix + "CryptoTransferHE2", "HOOK_EXECUTION=2 (pre+post)", feeMap));

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

        return ops;
    }

    // ==================== FEE CAPTURE AND COMPARISON ====================

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
}