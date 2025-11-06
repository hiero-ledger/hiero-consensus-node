// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDeleteAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoApproveAllowance.MISSING_OWNER;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of CryptoServiceFeesSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm the fees are the same
@HapiTestLifecycle
class AtomicCryptoServiceFeesSuite {

    private static final double BASE_FEE_CRYPTO_CREATE = 0.05;
    private static final double BASE_FEE_CRYPTO_DELETE = 0.005;
    private static final double BASE_FEE_CRYPTO_DELETE_ALLOWANCE = 0.05;
    private static final double BASE_FEE_CRYPTO_UPDATE = 0.000214;
    private static final double BASE_FEE_WITH_EXPIRY_CRYPTO_UPDATE = 0.00022;
    private static final double BASE_FEE_HBAR_CRYPTO_TRANSFER = 0.0001;
    private static final double BASE_FEE_HTS_CRYPTO_TRANSFER = 0.001;
    private static final double BASE_FEE_NFT_CRYPTO_TRANSFER = 0.001;

    private static final String CIVILIAN = "civilian";
    private static final String FEES_ACCOUNT = "feesAccount";
    private static final String OWNER = "owner";
    private static final String SPENDER = "spender";
    private static final String SECOND_SPENDER = "spender2";
    private static final String SENDER = "sender";
    private static final String RECEIVER = "receiver";
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String ATOMIC_BATCH = "atomicBatch";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(
                cryptoCreate(FEES_ACCOUNT).balance(5 * ONE_HUNDRED_HBARS),
                cryptoCreate(CIVILIAN).balance(5 * ONE_HUNDRED_HBARS).key(FEES_ACCOUNT));
    }

    @HapiTest
    @DisplayName("CryptoCreate transaction has expected base fee")
    final Stream<DynamicTest> cryptoCreateBaseUSDFee() {
        final var cryptoCreate = "cryptoCreate";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                atomicBatch(cryptoCreate(cryptoCreate)
                                .key(CIVILIAN)
                                .via(cryptoCreate)
                                .blankMemo()
                                .signedBy(CIVILIAN)
                                .payingWith(CIVILIAN)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(cryptoCreate, ATOMIC_BATCH, BASE_FEE_CRYPTO_CREATE));
    }

    @HapiTest
    @DisplayName("CryptoDelete transaction has expected base fee")
    final Stream<DynamicTest> cryptoDeleteBaseUSDFee() {
        final var cryptoCreate = "cryptoCreate";
        final var cryptoDelete = "cryptoDelete";
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                cryptoCreate(BATCH_OPERATOR),
                cryptoCreate(cryptoCreate).balance(5 * ONE_HUNDRED_HBARS).key(CIVILIAN),
                atomicBatch(cryptoDelete(cryptoCreate)
                                .via(cryptoDelete)
                                .payingWith(CIVILIAN)
                                .signedBy(CIVILIAN)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(cryptoDelete, ATOMIC_BATCH, BASE_FEE_CRYPTO_DELETE, 5));
    }

    @HapiTest
    @DisplayName("CryptoDeleteAllowance transaction has expected base fee")
    @Tag(MATS)
    final Stream<DynamicTest> cryptoDeleteAllowanceBaseUSDFee() {
        final String token = "token";
        final String nft = "nft";
        final String supplyKey = "supplyKey";
        final String baseDeleteNft = "baseDeleteNft";
        final String baseDeleteNft2 = "baseDeleteNft2";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR),
                newKeyNamed(supplyKey),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(supplyKey)
                        .initialSupply(10L)
                        .maxSupply(1000L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(supplyKey)
                        .initialSupply(0)
                        .maxSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, token),
                tokenAssociate(OWNER, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .addTokenAllowance(OWNER, token, SPENDER, 100L)
                        .addNftAllowance(OWNER, nft, SPENDER, false, List.of(1L, 2L, 3L)),
                /* without specifying owner */
                atomicBatch(
                                cryptoDeleteAllowance()
                                        .payingWith(OWNER)
                                        .blankMemo()
                                        .addNftDeleteAllowance(MISSING_OWNER, nft, List.of(1L))
                                        .via(baseDeleteNft)
                                        .batchKey(BATCH_OPERATOR),
                                cryptoDeleteAllowance()
                                        .payingWith(OWNER)
                                        .blankMemo()
                                        .addNftDeleteAllowance(OWNER, nft, List.of(1L))
                                        .via(baseDeleteNft2)
                                        .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(baseDeleteNft, ATOMIC_BATCH, BASE_FEE_CRYPTO_DELETE_ALLOWANCE, 5),
                cryptoApproveAllowance().payingWith(OWNER).addNftAllowance(OWNER, nft, SPENDER, false, List.of(1L)),
                validateInnerTxnChargedUsd(baseDeleteNft2, ATOMIC_BATCH, BASE_FEE_CRYPTO_DELETE_ALLOWANCE, 5));
    }

    private HapiSpecOperation[] cryptoApproveAllowanceSetup() {
        final String SUPPLY_KEY = "supplyKeyApproveAllowance";
        final String ANOTHER_SPENDER = "spender1";
        final String FUNGIBLE_TOKEN = "fungible";
        final String NON_FUNGIBLE_TOKEN = "nonFungible";
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR),
            newKeyNamed(SUPPLY_KEY),
            cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
            cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
            cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
            cryptoCreate(SECOND_SPENDER).balance(ONE_HUNDRED_HBARS),
            cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
            tokenCreate(FUNGIBLE_TOKEN)
                    .tokenType(TokenType.FUNGIBLE_COMMON)
                    .supplyType(TokenSupplyType.FINITE)
                    .supplyKey(SUPPLY_KEY)
                    .maxSupply(1000L)
                    .initialSupply(10L)
                    .treasury(TOKEN_TREASURY),
            tokenCreate(NON_FUNGIBLE_TOKEN)
                    .maxSupply(10L)
                    .initialSupply(0)
                    .supplyType(TokenSupplyType.FINITE)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .supplyKey(SUPPLY_KEY)
                    .treasury(TOKEN_TREASURY),
            tokenAssociate(OWNER, FUNGIBLE_TOKEN),
            tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
            mintToken(
                    NON_FUNGIBLE_TOKEN,
                    List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"), ByteString.copyFromUtf8("c"))),
            mintToken(FUNGIBLE_TOKEN, 500L),
            cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER))
        };
    }

    @HapiTest
    @DisplayName("CryptoApproveAllowance approve crypto transaction has expected base fee")
    final Stream<DynamicTest> cryptoApproveCryptoAllowanceBaseUSDFee() {
        return hapiTest(flattened(
                cryptoApproveAllowanceSetup(),
                atomicBatch(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER, 100L)
                                .via("approve")
                                .fee(ONE_HBAR)
                                .blankMemo()
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("approve", ATOMIC_BATCH, 0.05, 5)));
    }

    @HapiTest
    @DisplayName("CryptoApproveAllowance approve token transaction has expected base fee")
    final Stream<DynamicTest> cryptoApproveTokenAllowanceBaseUSDFee() {
        final String FUNGIBLE_TOKEN = "fungible";
        return hapiTest(flattened(
                cryptoApproveAllowanceSetup(),
                atomicBatch(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                                .via("approveTokenTxn")
                                .fee(ONE_HBAR)
                                .blankMemo()
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("approveTokenTxn", ATOMIC_BATCH, 0.05012, 5)));
    }

    @HapiTest
    @DisplayName("CryptoApproveAllowance approve NFT transaction has expected base fee")
    final Stream<DynamicTest> cryptoApproveNFTAllowanceBaseUSDFee2() {
        final String NON_FUNGIBLE_TOKEN = "nonFungible";
        return hapiTest(flattened(
                cryptoApproveAllowanceSetup(),
                atomicBatch(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                                .via("approveNftTxn")
                                .fee(ONE_HBAR)
                                .blankMemo()
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("approveNftTxn", ATOMIC_BATCH, 0.050101, 5)));
    }

    @HapiTest
    @DisplayName("CryptoApproveAllowance approve all NFT transaction has expected base fee")
    final Stream<DynamicTest> cryptoApproveAllNFTAllowanceBaseUSDFee2() {
        final String ANOTHER_SPENDER = "spender1";
        final String NON_FUNGIBLE_TOKEN = "nonFungible";
        return hapiTest(flattened(
                cryptoApproveAllowanceSetup(),
                atomicBatch(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, ANOTHER_SPENDER, true, List.of())
                                .via("approveForAllNftTxn")
                                .fee(ONE_HBAR)
                                .blankMemo()
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("approveForAllNftTxn", ATOMIC_BATCH, 0.05, 5)));
    }

    @HapiTest
    @DisplayName("CryptoApproveAllowance approve all types transaction has expected base fee")
    final Stream<DynamicTest> cryptoApproveAllTypesAllowanceBaseUSDFee2() {
        final String APPROVE_TXN = "approveTxn";
        final String FUNGIBLE_TOKEN = "fungible";
        final String NON_FUNGIBLE_TOKEN = "nonFungible";
        return hapiTest(flattened(
                cryptoApproveAllowanceSetup(),
                atomicBatch(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SECOND_SPENDER, 100L)
                                .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SECOND_SPENDER, 100L)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SECOND_SPENDER, false, List.of(1L))
                                .via(APPROVE_TXN)
                                .fee(ONE_HBAR)
                                .blankMemo()
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(APPROVE_TXN, ATOMIC_BATCH, 0.05238, 5)));
    }

    @HapiTest
    @DisplayName("CryptoApproveAllowance approve modify crypto transaction has expected base fee")
    final Stream<DynamicTest> cryptoApproveModifyCryptoAllowanceBaseUSDFee2() {
        return hapiTest(flattened(
                cryptoApproveAllowanceSetup(),
                atomicBatch(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SECOND_SPENDER, 200L)
                                .via("approveModifyCryptoTxn")
                                .fee(ONE_HBAR)
                                .blankMemo()
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("approveModifyCryptoTxn", ATOMIC_BATCH, 0.049375, 5)));
    }

    @HapiTest
    @DisplayName("CryptoApproveAllowance approve modify NFT transaction has expected base fee")
    final Stream<DynamicTest> cryptoApproveModifyNFTAllowanceBaseUSDFee2() {
        final String ANOTHER_SPENDER = "spender1";
        final String NON_FUNGIBLE_TOKEN = "nonFungible";
        return hapiTest(flattened(
                cryptoApproveAllowanceSetup(),
                atomicBatch(cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, ANOTHER_SPENDER, false, List.of())
                                .via("approveModifyNftTxn")
                                .fee(ONE_HBAR)
                                .blankMemo()
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("approveModifyNftTxn", ATOMIC_BATCH, 0.049375, 5)));
    }

    private HapiSpecOperation[] cryptoUpdateSetup() {
        final var canonicalAccount = "canonicalAccount";
        final var payer = "payer";
        final var autoAssocTarget = "autoAssocTarget";
        return new HapiSpecOperation[] {
            overridingTwo(
                    "ledger.maxAutoAssociations", "5000",
                    "entities.maxLifetime", "3153600000"),
            cryptoCreate(BATCH_OPERATOR),
            newKeyNamed("key").shape(SIMPLE),
            cryptoCreate(payer).key("key").balance(1_000 * ONE_HBAR),
            cryptoCreate(canonicalAccount)
                    .key("key")
                    .balance(100 * ONE_HBAR)
                    .autoRenewSecs(THREE_MONTHS_IN_SECONDS)
                    .blankMemo()
                    .payingWith(payer),
            cryptoCreate(autoAssocTarget)
                    .key("key")
                    .balance(100 * ONE_HBAR)
                    .autoRenewSecs(THREE_MONTHS_IN_SECONDS)
                    .blankMemo()
                    .payingWith(payer)
        };
    }

    @LeakyHapiTest(overrides = {"entities.maxLifetime", "ledger.maxAutoAssociations"})
    @DisplayName("CryptoUpdate expiring transaction has expected base fee")
    final Stream<DynamicTest> cryptoUpdateExpiringAccountBaseUSDFee() {
        final var baseTxn = "baseTxn";
        final var canonicalAccount = "canonicalAccount";
        AtomicLong expiration = new AtomicLong();
        return hapiTest(flattened(
                cryptoUpdateSetup(),
                getAccountInfo(canonicalAccount).exposingExpiry(expiration::set),
                atomicBatch(cryptoUpdate(canonicalAccount)
                                .payingWith(canonicalAccount)
                                .expiring(expiration.get() + THREE_MONTHS_IN_SECONDS)
                                .blankMemo()
                                .via(baseTxn)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_EXPIRATION_TIME))
                        .hasKnownStatusFrom(INNER_TRANSACTION_FAILED)
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(canonicalAccount).hasMaxAutomaticAssociations(0).logged(),
                validateInnerTxnChargedUsd(baseTxn, ATOMIC_BATCH, BASE_FEE_WITH_EXPIRY_CRYPTO_UPDATE, 10)));
    }

    @LeakyHapiTest(overrides = {"entities.maxLifetime", "ledger.maxAutoAssociations"})
    @DisplayName("CryptoUpdate 1 max auto associations transaction has expected base fee")
    final Stream<DynamicTest> cryptoUpdateOneMaxAutoAssocBaseUSDFee() {
        final var plusOneTxn = "plusOneTxn";
        final var autoAssocTarget = "autoAssocTarget";
        return hapiTest(flattened(
                cryptoUpdateSetup(),
                atomicBatch(cryptoUpdate(autoAssocTarget)
                                .payingWith(autoAssocTarget)
                                .blankMemo()
                                .maxAutomaticAssociations(1)
                                .via(plusOneTxn)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(autoAssocTarget).hasMaxAutomaticAssociations(1).logged(),
                validateInnerTxnChargedUsd(plusOneTxn, ATOMIC_BATCH, BASE_FEE_CRYPTO_UPDATE, 10)));
    }

    @LeakyHapiTest(overrides = {"entities.maxLifetime", "ledger.maxAutoAssociations"})
    @DisplayName("CryptoUpdate 11 max auto associations transaction has expected base fee")
    final Stream<DynamicTest> cryptoUpdateElevenMaxAutoAssocBaseUSDFee() {
        final var plusTenTxn = "plusTenTxn";
        final var autoAssocTarget = "autoAssocTarget";
        return hapiTest(flattened(
                cryptoUpdateSetup(),
                atomicBatch(cryptoUpdate(autoAssocTarget)
                                .payingWith(autoAssocTarget)
                                .blankMemo()
                                .maxAutomaticAssociations(11)
                                .via(plusTenTxn)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(autoAssocTarget).hasMaxAutomaticAssociations(11).logged(),
                validateInnerTxnChargedUsd(plusTenTxn, ATOMIC_BATCH, BASE_FEE_CRYPTO_UPDATE, 10)));
    }

    @LeakyHapiTest(overrides = {"entities.maxLifetime", "ledger.maxAutoAssociations"})
    @DisplayName("CryptoUpdate 5k max auto associations transaction has expected base fee")
    final Stream<DynamicTest> cryptoUpdate5kMaxAutoAssocBaseUSDFee() {
        final var plusFiveKTxn = "plusFiveKTxn";
        final var autoAssocTarget = "autoAssocTarget";
        return hapiTest(flattened(
                cryptoUpdateSetup(),
                atomicBatch(cryptoUpdate(autoAssocTarget)
                                .payingWith(autoAssocTarget)
                                .blankMemo()
                                .maxAutomaticAssociations(5000)
                                .via(plusFiveKTxn)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(autoAssocTarget)
                        .hasMaxAutomaticAssociations(5000)
                        .logged(),
                validateInnerTxnChargedUsd(plusFiveKTxn, ATOMIC_BATCH, BASE_FEE_CRYPTO_UPDATE, 10)));
    }

    @LeakyHapiTest(overrides = {"entities.maxLifetime", "ledger.maxAutoAssociations"})
    @DisplayName("CryptoUpdate negative max auto associations transaction has expected base fee")
    final Stream<DynamicTest> cryptoUpdateNegativeMaxAutoAssocBaseUSDFee() {
        final var validNegativeTxn = "validNegativeTxn";
        final var autoAssocTarget = "autoAssocTarget";
        return hapiTest(flattened(
                cryptoUpdateSetup(),
                atomicBatch(cryptoUpdate(autoAssocTarget)
                                .payingWith(autoAssocTarget)
                                .blankMemo()
                                .maxAutomaticAssociations(-1)
                                .via(validNegativeTxn)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                getAccountInfo(autoAssocTarget).hasMaxAutomaticAssociations(-1).logged(),
                validateInnerTxnChargedUsd(validNegativeTxn, ATOMIC_BATCH, BASE_FEE_CRYPTO_UPDATE, 10)));
    }

    private HapiSpecOperation[] cryptoTransferSetup() {
        final String SUPPLY_KEY = "supplyKeyCryptoTransfer";
        final var transferAmount = 1L;
        final var customFeeCollector = "customFeeCollector";
        final var nonTreasurySender = "nonTreasurySender";
        final var fungibleToken = "fungibleToken";
        final var fungibleTokenWithCustomFee = "fungibleTokenWithCustomFee";
        final var nonFungibleToken = "nonFungibleToken";
        final var nonFungibleTokenWithCustomFee = "nonFungibleTokenWithCustomFee";
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR),
            cryptoCreate(nonTreasurySender).balance(ONE_HUNDRED_HBARS),
            cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
            cryptoCreate(RECEIVER),
            cryptoCreate(customFeeCollector),
            tokenCreate(fungibleToken)
                    .treasury(SENDER)
                    .tokenType(FUNGIBLE_COMMON)
                    .initialSupply(100L),
            tokenCreate(fungibleTokenWithCustomFee)
                    .treasury(SENDER)
                    .tokenType(FUNGIBLE_COMMON)
                    .withCustom(fixedHbarFee(transferAmount, customFeeCollector))
                    .initialSupply(100L),
            tokenAssociate(RECEIVER, fungibleToken, fungibleTokenWithCustomFee),
            newKeyNamed(SUPPLY_KEY),
            tokenCreate(nonFungibleToken)
                    .initialSupply(0)
                    .supplyKey(SUPPLY_KEY)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .treasury(SENDER),
            tokenCreate(nonFungibleTokenWithCustomFee)
                    .initialSupply(0)
                    .supplyKey(SUPPLY_KEY)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .withCustom(fixedHbarFee(transferAmount, customFeeCollector))
                    .treasury(SENDER),
            tokenAssociate(nonTreasurySender, List.of(fungibleTokenWithCustomFee, nonFungibleTokenWithCustomFee)),
            mintToken(nonFungibleToken, List.of(copyFromUtf8("memo1"))),
            mintToken(nonFungibleTokenWithCustomFee, List.of(copyFromUtf8("memo2"))),
            tokenAssociate(RECEIVER, nonFungibleToken, nonFungibleTokenWithCustomFee),
            cryptoTransfer(movingUnique(nonFungibleTokenWithCustomFee, 1).between(SENDER, nonTreasurySender))
                    .payingWith(SENDER),
            cryptoTransfer(moving(1, fungibleTokenWithCustomFee).between(SENDER, nonTreasurySender))
                    .payingWith(SENDER)
        };
    }

    @HapiTest
    @DisplayName("CryptoTransfer HBAR transaction has expected base fee")
    final Stream<DynamicTest> cryptoHBARTransferBaseUSDFee() {
        final var hbarXferTxn = "hbarXferTxn";
        return hapiTest(flattened(
                cryptoTransferSetup(),
                atomicBatch(cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 100L))
                                .payingWith(SENDER)
                                .blankMemo()
                                .via(hbarXferTxn)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(hbarXferTxn, ATOMIC_BATCH, BASE_FEE_HBAR_CRYPTO_TRANSFER, 5)));
    }

    @HapiTest
    @DisplayName("CryptoTransfer HTS transaction has expected base fee")
    final Stream<DynamicTest> cryptoHTSTransferBaseUSDFee() {
        final var fungibleToken = "fungibleToken";
        final var htsXferTxn = "htsXferTxn";
        return hapiTest(flattened(
                cryptoTransferSetup(),
                atomicBatch(cryptoTransfer(moving(1, fungibleToken).between(SENDER, RECEIVER))
                                .blankMemo()
                                .payingWith(SENDER)
                                .via(htsXferTxn)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(htsXferTxn, ATOMIC_BATCH, BASE_FEE_HTS_CRYPTO_TRANSFER, 5)));
    }

    @HapiTest
    @DisplayName("CryptoTransfer NFT transaction has expected base fee")
    @Tag(MATS)
    final Stream<DynamicTest> cryptoNFTTransferBaseUSDFee() {
        final var nonFungibleToken = "nonFungibleToken";
        final var nftXferTxn = "nftXferTxn";
        return hapiTest(flattened(
                cryptoTransferSetup(),
                atomicBatch(cryptoTransfer(movingUnique(nonFungibleToken, 1).between(SENDER, RECEIVER))
                                .blankMemo()
                                .payingWith(SENDER)
                                .via(nftXferTxn)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(nftXferTxn, ATOMIC_BATCH, BASE_FEE_NFT_CRYPTO_TRANSFER, 5)));
    }

    @HapiTest
    @DisplayName("CryptoTransfer HTS custom fee transaction has expected base fee")
    final Stream<DynamicTest> cryptoHTSCustomFeeTransferBaseUSDFee() {
        final var expectedHtsXferWithCustomFeePriceUsd = 0.002;
        final var nonTreasurySender = "nonTreasurySender";
        final var fungibleTokenWithCustomFee = "fungibleTokenWithCustomFee";
        final var htsXferTxnWithCustomFee = "htsXferTxnWithCustomFee";
        return hapiTest(flattened(
                cryptoTransferSetup(),
                atomicBatch(cryptoTransfer(moving(1, fungibleTokenWithCustomFee).between(nonTreasurySender, RECEIVER))
                                .blankMemo()
                                .fee(ONE_HBAR)
                                .payingWith(nonTreasurySender)
                                .via(htsXferTxnWithCustomFee)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        htsXferTxnWithCustomFee, ATOMIC_BATCH, expectedHtsXferWithCustomFeePriceUsd, 5)));
    }

    @HapiTest
    @DisplayName("CryptoTransfer NFT custom fee transaction has expected base fee")
    final Stream<DynamicTest> cryptoNFTCustomFeeTransferBaseUSDFee() {
        final var expectedNftXferWithCustomFeePriceUsd = 0.002;
        final var nonTreasurySender = "nonTreasurySender";
        final var nonFungibleTokenWithCustomFee = "nonFungibleTokenWithCustomFee";
        final var nftXferTxnWithCustomFee = "nftXferTxnWithCustomFee";
        return hapiTest(flattened(
                cryptoTransferSetup(),
                atomicBatch(cryptoTransfer(movingUnique(nonFungibleTokenWithCustomFee, 1)
                                        .between(nonTreasurySender, RECEIVER))
                                .blankMemo()
                                .fee(ONE_HBAR)
                                .payingWith(nonTreasurySender)
                                .via(nftXferTxnWithCustomFee)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        nftXferTxnWithCustomFee, ATOMIC_BATCH, expectedNftXferWithCustomFeePriceUsd, 5)));
    }
}
