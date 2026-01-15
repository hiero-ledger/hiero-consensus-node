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
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
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
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
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
        // Contract transactions are commented out for now due to resource constraints
        // ops.addAll(contractTransactions(prefix, feeMap));

        return ops.toArray(new SpecOperation[0]);
    }

    // ==================== CRYPTO TRANSACTIONS ====================

    private static List<SpecOperation> cryptoTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // CryptoCreate with varying key complexity and memo lengths
        ops.add(cryptoCreate(prefix + "AccSimple")
                .key(SIMPLE_KEY)
                .balance(ONE_HBAR)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateSimple"));
        ops.add(captureFee(prefix + "CryptoCreateSimple", feeMap));

        ops.add(cryptoCreate(prefix + "AccList2")
                .key(LIST_KEY_2)
                .balance(ONE_HBAR)
                .memo(SHORT_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateList2"));
        ops.add(captureFee(prefix + "CryptoCreateList2", feeMap));

        ops.add(cryptoCreate(prefix + "AccList5")
                .key(LIST_KEY_5)
                .balance(ONE_HBAR)
                .memo(MEDIUM_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateList5"));
        ops.add(captureFee(prefix + "CryptoCreateList5", feeMap));

        ops.add(cryptoCreate(prefix + "AccThresh2of3")
                .key(THRESH_KEY_2_OF_3)
                .balance(ONE_HBAR)
                .memo(LONG_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateThresh2of3"));
        ops.add(captureFee(prefix + "CryptoCreateThresh2of3", feeMap));

        ops.add(cryptoCreate(prefix + "AccComplex")
                .key(COMPLEX_KEY)
                .balance(ONE_HBAR)
                .memo(LONG_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoCreateComplex"));
        ops.add(captureFee(prefix + "CryptoCreateComplex", feeMap));

        // CryptoUpdate with varying parameters
        ops.add(cryptoUpdate(prefix + "AccSimple")
                .memo(MEDIUM_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateSimple"));
        ops.add(captureFee(prefix + "CryptoUpdateSimple", feeMap));

        ops.add(cryptoUpdate(prefix + "AccComplex")
                .memo(SHORT_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, COMPLEX_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoUpdateComplex"));
        ops.add(captureFee(prefix + "CryptoUpdateComplex", feeMap));

        // CryptoTransfer with varying number of accounts
        ops.add(cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, RECEIVER))
                .payingWith(PAYER)
                .memo(EMPTY_MEMO)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HbarTransfer2Acct"));
        ops.add(captureFee(prefix + "HbarTransfer2Acct", feeMap));

        ops.add(cryptoTransfer(
                        movingHbar(ONE_HBAR).between(PAYER, prefix + "AccSimple"),
                        movingHbar(ONE_HBAR).between(PAYER, prefix + "AccList2"))
                .payingWith(PAYER)
                .memo(SHORT_MEMO)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "HbarTransfer3Acct"));
        ops.add(captureFee(prefix + "HbarTransfer3Acct", feeMap));

        // CryptoApproveAllowance - HBAR allowance
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccSimple", RECEIVER, ONE_HBAR)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoApproveHbar"));
        ops.add(captureFee(prefix + "CryptoApproveHbar", feeMap));

        // CryptoApproveAllowance - Multiple allowances
        ops.add(cryptoApproveAllowance()
                .addCryptoAllowance(prefix + "AccList2", RECEIVER, ONE_HBAR * 5)
                .addCryptoAllowance(prefix + "AccList2", prefix + "AccSimple", ONE_HBAR * 2)
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_2)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "CryptoApproveMultiple"));
        ops.add(captureFee(prefix + "CryptoApproveMultiple", feeMap));

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
        ops.add(captureFee(prefix + "CryptoDelete", feeMap));

        return ops;
    }



    // ==================== TOKEN TRANSACTIONS ====================

    private static List<SpecOperation> tokenTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // TokenCreate - Fungible with varying parameters
        ops.add(tokenCreate(prefix + "FungibleSimple")
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(1_000_000L)
                .treasury(TREASURY)
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenCreateFungible"));
        ops.add(captureFee(prefix + "TokenCreateFungible", feeMap));

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
        ops.add(captureFee(prefix + "TokenCreateFungibleKeys", feeMap));

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
        ops.add(captureFee(prefix + "TokenCreateNFT", feeMap));

        // TokenAssociate
        ops.add(tokenAssociate(prefix + "AccSimple", prefix + "FungibleSimple", prefix + "FungibleWithKeys")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenAssociate1"));
        ops.add(captureFee(prefix + "TokenAssociate1", feeMap));

        ops.add(tokenAssociate(prefix + "AccList2", prefix + "FungibleSimple", prefix + "FungibleWithKeys")
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_2)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenAssociate2"));
        ops.add(captureFee(prefix + "TokenAssociate2", feeMap));

        // TokenMint - Fungible
        ops.add(mintToken(prefix + "FungibleWithKeys", 10_000L)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenMintFungible"));
        ops.add(captureFee(prefix + "TokenMintFungible", feeMap));

        // TokenMint - NFT with varying metadata sizes
        ops.add(mintToken(prefix + "NFT", List.of(ByteString.copyFromUtf8("NFT1")))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenMintNFT1"));
        ops.add(captureFee(prefix + "TokenMintNFT1", feeMap));

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
        ops.add(captureFee(prefix + "TokenMintNFT3", feeMap));

        // Token transfer - Fungible
        ops.add(cryptoTransfer(moving(100L, prefix + "FungibleSimple").between(TREASURY, prefix + "AccSimple"))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferFungible"));
        ops.add(captureFee(prefix + "TokenTransferFungible", feeMap));

        // Transfer FungibleWithKeys to AccSimple (for wipe test later)
        ops.add(cryptoTransfer(moving(500L, prefix + "FungibleWithKeys").between(TREASURY, prefix + "AccSimple"))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferForWipe"));
        ops.add(captureFee(prefix + "TokenTransferForWipe", feeMap));

        // Token transfer - NFT
        ops.add(tokenAssociate(RECEIVER, prefix + "NFT")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS));
        ops.add(cryptoTransfer(movingUnique(prefix + "NFT", 1L).between(TREASURY, RECEIVER))
                .payingWith(PAYER)
                .signedBy(PAYER, TREASURY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenTransferNFT"));
        ops.add(captureFee(prefix + "TokenTransferNFT", feeMap));

        // TokenFreeze/Unfreeze
        ops.add(tokenFreeze(prefix + "FungibleWithKeys", prefix + "AccSimple")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenFreeze"));
        ops.add(captureFee(prefix + "TokenFreeze", feeMap));

        ops.add(tokenUnfreeze(prefix + "FungibleWithKeys", prefix + "AccSimple")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenUnfreeze"));
        ops.add(captureFee(prefix + "TokenUnfreeze", feeMap));

        // TokenDissociate
        ops.add(tokenDissociate(prefix + "AccList2", prefix + "FungibleSimple")
                .payingWith(PAYER)
                .signedBy(PAYER, LIST_KEY_2)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenDissociate"));
        ops.add(captureFee(prefix + "TokenDissociate", feeMap));

        // TokenBurn - Fungible
        ops.add(burnToken(prefix + "FungibleWithKeys", 1_000L)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenBurnFungible"));
        ops.add(captureFee(prefix + "TokenBurnFungible", feeMap));

        // TokenBurn - NFT
        ops.add(burnToken(prefix + "NFT", List.of(4L))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenBurnNFT"));
        ops.add(captureFee(prefix + "TokenBurnNFT", feeMap));

        // TokenWipe - Fungible (wipe from AccSimple which has tokens)
        ops.add(wipeTokenAccount(prefix + "FungibleWithKeys", prefix + "AccSimple", 50L)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenWipeFungible"));
        ops.add(captureFee(prefix + "TokenWipeFungible", feeMap));

        // TokenPause
        ops.add(tokenPause(prefix + "FungibleWithKeys")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenPause"));
        ops.add(captureFee(prefix + "TokenPause", feeMap));

        // TokenUnpause
        ops.add(tokenUnpause(prefix + "FungibleWithKeys")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TokenUnpause"));
        ops.add(captureFee(prefix + "TokenUnpause", feeMap));

        return ops;
    }

    // ==================== CONSENSUS TRANSACTIONS ====================

    private static List<SpecOperation> consensusTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // CreateTopic with varying parameters
        ops.add(createTopic(prefix + "TopicSimple")
                .blankMemo()
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateSimple"));
        ops.add(captureFee(prefix + "TopicCreateSimple", feeMap));

        ops.add(createTopic(prefix + "TopicWithKeys")
                .adminKeyName(SIMPLE_KEY)
                .submitKeyName(SIMPLE_KEY)
                .topicMemo(MEDIUM_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateWithKeys"));
        ops.add(captureFee(prefix + "TopicCreateWithKeys", feeMap));

        ops.add(createTopic(prefix + "TopicComplexKey")
                .adminKeyName(COMPLEX_KEY)
                .submitKeyName(THRESH_KEY_2_OF_3)
                .topicMemo(LONG_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicCreateComplex"));
        ops.add(captureFee(prefix + "TopicCreateComplex", feeMap));

        // SubmitMessage with varying message sizes
        ops.add(submitMessageTo(prefix + "TopicSimple")
                .message("Short message")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgShort"));
        ops.add(captureFee(prefix + "SubmitMsgShort", feeMap));

        ops.add(submitMessageTo(prefix + "TopicSimple")
                .message("x".repeat(1000))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgMedium"));
        ops.add(captureFee(prefix + "SubmitMsgMedium", feeMap));

        ops.add(submitMessageTo(prefix + "TopicWithKeys")
                .message("x".repeat(100))
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "SubmitMsgWithKey"));
        ops.add(captureFee(prefix + "SubmitMsgWithKey", feeMap));

        // UpdateTopic
        ops.add(updateTopic(prefix + "TopicWithKeys")
                .topicMemo(SHORT_MEMO)
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicUpdate"));
        ops.add(captureFee(prefix + "TopicUpdate", feeMap));

        // DeleteTopic
        ops.add(deleteTopic(prefix + "TopicWithKeys")
                .payingWith(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "TopicDelete"));
        ops.add(captureFee(prefix + "TopicDelete", feeMap));

        return ops;
    }

    // ==================== FILE TRANSACTIONS ====================

    private static List<SpecOperation> fileTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // FileCreate with varying content sizes
        ops.add(fileCreate(prefix + "FileSmall")
                .contents("Small file content")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateSmall"));
        ops.add(captureFee(prefix + "FileCreateSmall", feeMap));

        ops.add(fileCreate(prefix + "FileMedium")
                .contents("x".repeat(1000))
                .memo(MEDIUM_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateMedium"));
        ops.add(captureFee(prefix + "FileCreateMedium", feeMap));

        ops.add(fileCreate(prefix + "FileLarge")
                .contents("x".repeat(4000))
                .memo(LONG_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileCreateLarge"));
        ops.add(captureFee(prefix + "FileCreateLarge", feeMap));

        // FileUpdate
        ops.add(fileUpdate(prefix + "FileSmall")
                .contents("Updated content")
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "FileUpdate"));
        ops.add(captureFee(prefix + "FileUpdate", feeMap));

        return ops;
    }

    // ==================== SCHEDULE TRANSACTIONS ====================

    private static List<SpecOperation> scheduleTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Use different amounts based on prefix to ensure unique scheduled transactions
        // "simple" prefix uses 1 tinybar, "legacy" prefix uses 2 tinybars
        final long amount = prefix.equals("simple") ? 1L : 2L;

        // ScheduleCreate - simple scheduled crypto transfer (from RECEIVER to PAYER, so RECEIVER must sign)
        // This won't auto-execute because RECEIVER hasn't signed yet
        ops.add(scheduleCreate(
                        prefix + "ScheduleSimple",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).memo(SHORT_MEMO))
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateSimple"));
        ops.add(captureFee(prefix + "ScheduleCreateSimple", feeMap));

        // ScheduleCreate with admin key and memo (from RECEIVER to PAYER)
        ops.add(scheduleCreate(
                        prefix + "ScheduleWithAdmin",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)).memo(MEDIUM_MEMO))
                .adminKey(SIMPLE_KEY)
                .withEntityMemo(SHORT_MEMO)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateWithAdmin"));
        ops.add(captureFee(prefix + "ScheduleCreateWithAdmin", feeMap));

        // ScheduleCreate with designated payer (from RECEIVER to PAYER)
        // This won't auto-execute because RECEIVER hasn't signed yet
        ops.add(scheduleCreate(
                        prefix + "ScheduleWithPayer",
                        cryptoTransfer(tinyBarsFromTo(RECEIVER, PAYER, amount)))
                .adminKey(SIMPLE_KEY)
                .designatingPayer(PAYER)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleCreateWithPayer"));
        ops.add(captureFee(prefix + "ScheduleCreateWithPayer", feeMap));

        // ScheduleSign - sign the schedule with RECEIVER to trigger execution
        ops.add(scheduleSign(prefix + "ScheduleWithPayer")
                .alsoSigningWith(RECEIVER)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleSign"));
        ops.add(captureFee(prefix + "ScheduleSign", feeMap));

        // ScheduleDelete - delete the schedule with admin key (ScheduleWithAdmin hasn't been signed by RECEIVER)
        ops.add(scheduleDelete(prefix + "ScheduleWithAdmin")
                .signedBy(PAYER, SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ScheduleDelete"));
        ops.add(captureFee(prefix + "ScheduleDelete", feeMap));

        return ops;
    }

    // ==================== CONTRACT TRANSACTIONS ====================

    private static final String STORAGE_CONTRACT = "Storage";

    private static List<SpecOperation> contractTransactions(String prefix, Map<String, Long> feeMap) {
        List<SpecOperation> ops = new ArrayList<>();

        // Upload contract bytecode (only once, shared between runs)
        ops.add(uploadInitCode(STORAGE_CONTRACT));

        // ContractCreate - simple contract
        ops.add(contractCreate(prefix + "ContractSimple")
                .bytecode(STORAGE_CONTRACT)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreateSimple"));
        ops.add(captureFee(prefix + "ContractCreateSimple", feeMap));

        // ContractCreate with admin key and memo
        ops.add(contractCreate(prefix + "ContractWithAdmin")
                .bytecode(STORAGE_CONTRACT)
                .adminKey(SIMPLE_KEY)
                .entityMemo(SHORT_MEMO)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreateWithAdmin"));
        ops.add(captureFee(prefix + "ContractCreateWithAdmin", feeMap));

        // ContractCreate with auto-renew account
        ops.add(contractCreate(prefix + "ContractWithAutoRenew")
                .bytecode(STORAGE_CONTRACT)
                .adminKey(SIMPLE_KEY)
                .autoRenewAccountId(PAYER)
                .entityMemo(MEDIUM_MEMO)
                .gas(200_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCreateWithAutoRenew"));
        ops.add(captureFee(prefix + "ContractCreateWithAutoRenew", feeMap));

        // ContractCall - call the store function
        final var storeAbi = getABIFor(FUNCTION, "store", STORAGE_CONTRACT);
        ops.add(contractCallWithFunctionAbi(prefix + "ContractSimple", storeAbi, BigInteger.valueOf(42))
                .gas(50_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCallStore"));
        ops.add(captureFee(prefix + "ContractCallStore", feeMap));

        // ContractCall - call the retrieve function
        final var retrieveAbi = getABIFor(FUNCTION, "retrieve", STORAGE_CONTRACT);
        ops.add(contractCallWithFunctionAbi(prefix + "ContractSimple", retrieveAbi)
                .gas(50_000L)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractCallRetrieve"));
        ops.add(captureFee(prefix + "ContractCallRetrieve", feeMap));

        // ContractUpdate - update memo
        ops.add(contractUpdate(prefix + "ContractWithAdmin")
                .newMemo(MEDIUM_MEMO)
                .signedBy(PAYER, SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractUpdate"));
        ops.add(captureFee(prefix + "ContractUpdate", feeMap));

        // ContractDelete
        ops.add(contractDelete(prefix + "ContractWithAdmin")
                .transferAccount(PAYER)
                .signedBy(PAYER, SIMPLE_KEY)
                .payingWith(PAYER)
                .fee(ONE_HUNDRED_HBARS)
                .via(prefix + "ContractDelete"));
        ops.add(captureFee(prefix + "ContractDelete", feeMap));

        return ops;
    }

    // ==================== FEE CAPTURE AND COMPARISON ====================

    private static SpecOperation captureFee(String txnName, Map<String, Long> feeMap) {
        return withOpContext((spec, opLog) -> {
            var recordOp = getTxnRecord(txnName);
            allRunFor(spec, recordOp);
            var record = recordOp.getResponseRecord();
            long fee = record.getTransactionFee();
            feeMap.put(txnName, fee);
            LOG.info("Captured fee for {}: {} tinybars", txnName, fee);
        });
    }

    private static SpecOperation logFeeComparison(Map<String, Long> legacyFees, Map<String, Long> simpleFees) {
        return withOpContext((spec, opLog) -> {
            LOG.info("\n========== FEE COMPARISON RESULTS ==========");
            LOG.info(String.format(
                    "%-40s %15s %15s %15s %10s", "Transaction", "Legacy Fee", "Simple Fee", "Difference", "% Change"));
            LOG.info("=".repeat(100));

            for (String txnName : legacyFees.keySet()) {
                // Extract the base name (remove prefix)
                String baseName = txnName.startsWith("legacy") ? txnName.substring(6) : txnName;
                String simpleTxnName = "simple" + baseName;

                Long legacyFee = legacyFees.get(txnName);
                Long simpleFee = simpleFees.get(simpleTxnName);

                if (legacyFee != null && simpleFee != null) {
                    long diff = simpleFee - legacyFee;
                    double pctChange = legacyFee > 0 ? (diff * 100.0 / legacyFee) : 0;
                    LOG.info(String.format(
                            "%-40s %15d %15d %15d %9.2f%%", baseName, legacyFee, simpleFee, diff, pctChange));
                }
            }
            LOG.info("=".repeat(100));

            // Also log summary statistics
            long totalLegacy = legacyFees.values().stream().mapToLong(Long::longValue).sum();
            long totalSimple = simpleFees.values().stream().mapToLong(Long::longValue).sum();
            long totalDiff = totalSimple - totalLegacy;
            double totalPctChange = totalLegacy > 0 ? (totalDiff * 100.0 / totalLegacy) : 0;

            LOG.info(String.format(
                    "%-40s %15d %15d %15d %9.2f%%", "TOTAL", totalLegacy, totalSimple, totalDiff, totalPctChange));
            LOG.info("=============================================\n");
        });
    }
}