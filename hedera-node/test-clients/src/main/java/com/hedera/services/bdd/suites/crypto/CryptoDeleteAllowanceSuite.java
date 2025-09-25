// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDeleteAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoApproveAllowance.MISSING_OWNER;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.APP_PROPERTIES;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.OWNER;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.SPENDER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_ALLOWANCES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_ALLOWANCES_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class CryptoDeleteAllowanceSuite {
    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(OWNER).maxAutomaticTokenAssociations(2),
                cryptoCreate("delegatingOwner").maxAutomaticTokenAssociations(1),
                cryptoCreate(SPENDER),
                tokenCreate("fungibleToken").initialSupply(123).treasury(TOKEN_TREASURY),
                tokenCreate("nonFungibleToken")
                        .treasury(TOKEN_TREASURY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey("supplyKey"),
                mintToken(
                        "nonFungibleToken",
                        List.of(
                                ByteString.copyFromUtf8("A"),
                                ByteString.copyFromUtf8("B"),
                                ByteString.copyFromUtf8("C"))),
                cryptoTransfer(
                        movingUnique("nonFungibleToken", 1L, 2L).between(TOKEN_TREASURY, OWNER),
                        moving(10, "fungibleToken").between(TOKEN_TREASURY, OWNER)),
                cryptoTransfer(movingUnique("nonFungibleToken", 3L).between(TOKEN_TREASURY, "delegatingOwner")),
                cryptoApproveAllowance()
                        .addNftAllowance("delegatingOwner", "nonFungibleToken", OWNER, true, List.of())
                        .signedBy(DEFAULT_PAYER, "delegatingOwner"),
                cryptoApproveAllowance()
                        .addNftAllowance(OWNER, "nonFungibleToken", SPENDER, false, List.of(1L))
                        .signedBy(DEFAULT_PAYER, OWNER),
                submitModified(withSuccessivelyVariedBodyIds(), () -> cryptoDeleteAllowance()
                        .addNftDeleteAllowance(OWNER, "nonFungibleToken", List.of(1L))
                        .signedBy(DEFAULT_PAYER, OWNER)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> canDeleteAllowanceForDeletedSpender() {
        final String owner = "owner";
        final String spender = "spender";
        final String nft = "nft";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY)
                        .balance(100 * ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(10)
                        .payingWith(GENESIS),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY)
                        .payingWith(GENESIS),
                tokenAssociate(owner, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint")
                        .payingWith(GENESIS),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, owner))
                        .payingWith(GENESIS),
                cryptoApproveAllowance()
                        .payingWith(owner)
                        .addNftAllowance(owner, nft, spender, true, List.of(3L))
                        .via("otherAdjustTxn"),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(1)),
                getTokenNftInfo(nft, 3L).hasSpenderID(spender),
                cryptoDelete(spender),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(3L))
                        .blankMemo()
                        .via("cryptoDeleteAllowanceTxn")
                        .logged(),
                getTxnRecord("cryptoDeleteAllowanceTxn").logged(),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(1)),
                getTokenNftInfo(nft, 3L).hasNoSpender());
    }

    @HapiTest
    final Stream<DynamicTest> duplicateEntriesDoesntThrow() {
        final String owner = "owner";
        final String spender = "spender";
        final String token = "token";
        final String nft = "nft";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(owner, token),
                tokenAssociate(owner, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, owner)),
                cryptoApproveAllowance()
                        .payingWith(owner)
                        .addCryptoAllowance(owner, spender, 100L)
                        .addTokenAllowance(owner, token, spender, 100L)
                        .addNftAllowance(owner, nft, spender, false, List.of(1L, 2L, 3L)),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(1)
                                .cryptoAllowancesContaining(spender, 100L)
                                .tokenAllowancesContaining(token, spender, 100L)),
                getTokenNftInfo(nft, 1L).hasSpenderID(spender),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1L, 2L, 2L, 2L, 2L))
                        .addNftDeleteAllowance(owner, nft, List.of(1L, 3L, 2L, 3L)),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(0)),
                getTokenNftInfo(nft, 1L).hasNoSpender(),
                getTokenNftInfo(nft, 2L).hasNoSpender(),
                getTokenNftInfo(nft, 3L).hasNoSpender(),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .addNftDeleteAllowance(owner, nft, List.of(2L)),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(0)),
                getTokenNftInfo(nft, 1L).hasNoSpender(),
                getTokenNftInfo(nft, 2L).hasNoSpender(),
                getTokenNftInfo(nft, 3L).hasNoSpender());
    }

    @HapiTest
    final Stream<DynamicTest> invalidOwnerFails() {
        final String owner = "owner";
        final String spender = "spender";
        final String token = "token";
        final String nft = "nft";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoApproveAllowance()
                        .payingWith("payer")
                        .addCryptoAllowance(owner, spender, 100L)
                        .signedBy("payer", owner)
                        .blankMemo(),
                cryptoDelete(owner),
                cryptoDeleteAllowance()
                        .payingWith("payer")
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .signedBy("payer", owner)
                        .via("baseDeleteTxn")
                        .blankMemo()
                        .hasPrecheck(INVALID_ALLOWANCE_OWNER_ID),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().deleted(true)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> feesAsExpected() {
        final String owner = "owner";
        final String spender = "spender";
        final String token = "token";
        final String nft = "nft";
        final String payer = "payer";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(payer).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("spender1").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("spender2").balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(owner, token),
                tokenAssociate(owner, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, owner)),
                cryptoApproveAllowance()
                        .payingWith(owner)
                        .addCryptoAllowance(owner, "spender2", 100L)
                        .addTokenAllowance(owner, token, "spender2", 100L)
                        .addNftAllowance(owner, nft, "spender2", false, List.of(1L, 2L, 3L)),
                /* without specifying owner */
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .blankMemo()
                        .addNftDeleteAllowance(MISSING_OWNER, nft, List.of(1L))
                        .via("baseDeleteNft"),
                validateChargedUsdWithin("baseDeleteNft", 0.05, 0.02),
                cryptoApproveAllowance().payingWith(owner).addNftAllowance(owner, nft, "spender2", false, List.of(1L)),
                /* with specifying owner */
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .blankMemo()
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .via("baseDeleteNft"),
                validateChargedUsdWithin("baseDeleteNft", 0.05, 0.02),

                /* with 2 serials */
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .blankMemo()
                        .addNftDeleteAllowance(owner, nft, List.of(2L, 3L))
                        .via("twoDeleteNft"),
                validateChargedUsdWithin("twoDeleteNft", 0.050101, 0.01),
                /* with 2 sigs */
                cryptoApproveAllowance().payingWith(owner).addNftAllowance(owner, nft, "spender2", false, List.of(1L)),
                cryptoDeleteAllowance()
                        .payingWith(payer)
                        .blankMemo()
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .signedBy(payer, owner)
                        .via("twoDeleteNft"),
                validateChargedUsdWithin("twoDeleteNft", 0.08124, 0.035));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> succeedsWhenTokenPausedFrozenKycRevoked() {
        final String owner = "owner";
        final String spender = "spender";
        final String spender1 = "spender1";
        final String token = "token";
        final String nft = "nft";
        return hapiTest(
                fileUpdate(APP_PROPERTIES)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .overridingProps(Map.of(
                                "hedera.allowances.maxTransactionLimit", "20",
                                "hedera.allowances.maxAccountLimit", "100")),
                newKeyNamed("supplyKey"),
                newKeyNamed("adminKey"),
                newKeyNamed("freezeKey"),
                newKeyNamed("kycKey"),
                newKeyNamed("pauseKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(spender1).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .kycKey("kycKey")
                        .adminKey("adminKey")
                        .freezeKey("freezeKey")
                        .pauseKey("pauseKey")
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .kycKey("kycKey")
                        .adminKey("adminKey")
                        .freezeKey("freezeKey")
                        .pauseKey("pauseKey")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(owner, token),
                tokenAssociate(owner, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                grantTokenKyc(token, owner),
                grantTokenKyc(nft, owner),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, owner)),
                cryptoApproveAllowance().payingWith(owner).addNftAllowance(owner, nft, spender, false, List.of(1L)),
                revokeTokenKyc(nft, owner),
                cryptoDeleteAllowance().payingWith(owner).addNftDeleteAllowance(owner, nft, List.of(1L)),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().noAllowances()),
                cryptoApproveAllowance().payingWith(owner).addNftAllowance(owner, nft, spender, false, List.of(3L)),
                tokenPause(nft),
                cryptoDeleteAllowance().payingWith(owner).addNftDeleteAllowance(owner, nft, List.of(3L)),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().noAllowances()),
                tokenUnpause(nft),
                tokenFreeze(nft, owner),
                cryptoApproveAllowance().payingWith(owner).addNftAllowance(owner, nft, spender, false, List.of(2L)),
                cryptoDeleteAllowance().payingWith(owner).addNftDeleteAllowance(owner, nft, List.of(2L)),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().noAllowances()),
                // reset
                fileUpdate(APP_PROPERTIES)
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .overridingProps(Map.of(
                                "hedera.allowances.maxTransactionLimit", "20",
                                "hedera.allowances.maxAccountLimit", "100")));
    }

    @LeakyHapiTest(overrides = {"hedera.allowances.maxTransactionLimit"})
    final Stream<DynamicTest> exceedsTransactionLimit() {
        final String owner = "owner";
        final String spender = "spender";
        final String spender1 = "spender1";
        final String spender2 = "spender2";
        final String token = "token";
        final String nft = "nft";
        return hapiTest(
                overriding("hedera.allowances.maxTransactionLimit", "4"),
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(spender1).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(spender2).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(owner, token),
                tokenAssociate(owner, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, owner)),
                cryptoApproveAllowance().payingWith(owner).addNftAllowance(owner, nft, spender, false, List.of(1L, 2L)),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1L, 2L, 3L, 3L, 3L))
                        .hasPrecheck(MAX_ALLOWANCES_EXCEEDED),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1L, 1L, 1L, 1L, 1L))
                        .hasPrecheck(MAX_ALLOWANCES_EXCEEDED),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .addNftDeleteAllowance(owner, nft, List.of(2L))
                        .addNftDeleteAllowance(owner, nft, List.of(3L))
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .hasPrecheck(MAX_ALLOWANCES_EXCEEDED));
    }

    @HapiTest
    final Stream<DynamicTest> validatesSerialNums() {
        final String owner = "owner";
        final String spender = "spender";
        final String nft = "nft";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(owner, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                cryptoTransfer(movingUnique(nft, 1L, 2L).between(TOKEN_TREASURY, owner)),
                cryptoApproveAllowance().payingWith(owner).addNftAllowance(owner, nft, spender, false, List.of(1L, 2L)),
                cryptoDeleteAllowance().payingWith(owner).addNftDeleteAllowance(owner, nft, List.of(1L)),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(-1L))
                        .hasPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1000L))
                        .hasPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(3L))
                        .hasKnownStatus(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO),
                cryptoDeleteAllowance().payingWith(owner).addNftDeleteAllowance(owner, nft, List.of(1L, 1L, 2L)),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of())
                        .hasPrecheck(EMPTY_ALLOWANCES));
    }

    @HapiTest
    final Stream<DynamicTest> invalidTokenTypeFailsInDeleteAllowance() {
        final String owner = "owner";
        final String spender = "spender";
        final String token = "token";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(owner, token),
                mintToken(token, 500L).via("tokenMint"),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, token, List.of(1L))
                        .hasPrecheck(FUNGIBLE_TOKEN_IN_NFT_ALLOWANCES));
    }

    @HapiTest
    final Stream<DynamicTest> emptyAllowancesDeleteRejected() {
        final String owner = "owner";
        return hapiTest(
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoDeleteAllowance().hasPrecheck(EMPTY_ALLOWANCES));
    }

    @HapiTest
    final Stream<DynamicTest> tokenNotAssociatedToAccountFailsOnDeleteAllowance() {
        final String owner = "owner";
        final String spender = "spender";
        final String token = "token";
        final String nft = "nft";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .hasPrecheck(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(0)
                                .nftApprovedForAllAllowancesCount(0)
                                .tokenAllowancesCount(0)));
    }

    @HapiTest
    final Stream<DynamicTest> canDeleteMultipleOwners() {
        final String owner1 = "owner1";
        final String owner2 = "owner2";
        final String spender = "spender";
        final String token = "token";
        final String nft = "nft";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(owner1).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(owner2).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(10_000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(owner1, token, nft),
                tokenAssociate(owner2, token, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c"),
                                        ByteString.copyFromUtf8("d"),
                                        ByteString.copyFromUtf8("e"),
                                        ByteString.copyFromUtf8("f")))
                        .via("nftTokenMint"),
                mintToken(token, 1000L).via("tokenMint"),
                cryptoTransfer(
                        moving(500, token).between(TOKEN_TREASURY, owner1),
                        moving(500, token).between(TOKEN_TREASURY, owner2),
                        movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, owner1),
                        movingUnique(nft, 4L, 5L, 6L).between(TOKEN_TREASURY, owner2)),
                cryptoApproveAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addCryptoAllowance(owner1, spender, ONE_HBAR)
                        .addTokenAllowance(owner1, token, spender, 100L)
                        .addNftAllowance(owner1, nft, spender, false, List.of(1L))
                        .addCryptoAllowance(owner2, spender, 2 * ONE_HBAR)
                        .addTokenAllowance(owner2, token, spender, 300L)
                        .addNftAllowance(owner2, nft, spender, false, List.of(4L, 5L))
                        .signedBy(DEFAULT_PAYER, owner1, owner2)
                        .via("multiOwnerTxn"),
                getAccountDetails(owner1)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .tokenAllowancesContaining(token, spender, 100L)
                                .cryptoAllowancesContaining(spender, ONE_HBAR)
                                .nftApprovedForAllAllowancesCount(0)),
                getAccountDetails(owner2)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .tokenAllowancesContaining(token, spender, 300L)
                                .cryptoAllowancesContaining(spender, 2 * ONE_HBAR)
                                .nftApprovedForAllAllowancesCount(0)),
                getTokenNftInfo(nft, 1L).hasSpenderID(spender),
                getTokenNftInfo(nft, 4L).hasSpenderID(spender),
                getTokenNftInfo(nft, 5L).hasSpenderID(spender),
                cryptoDeleteAllowance()
                        .payingWith(DEFAULT_PAYER)
                        .addNftDeleteAllowance(owner1, nft, List.of(1L))
                        .addNftDeleteAllowance(owner2, nft, List.of(4L, 5L))
                        .signedBy(DEFAULT_PAYER, owner1, owner2)
                        .via("multiOwnerDeleteTxn"),
                getAccountDetails(owner1)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(0)),
                getAccountDetails(owner2)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(0)),
                getTokenNftInfo(nft, 1L).hasNoSpender(),
                getTokenNftInfo(nft, 4L).hasNoSpender(),
                getTokenNftInfo(nft, 5L).hasNoSpender());
    }

    @HapiTest
    final Stream<DynamicTest> noOwnerDefaultsToPayerInDeleteAllowance() {
        final String payer = "payer";
        final String spender = "spender";
        final String spender1 = "spender1";
        final String token = "token";
        final String nft = "nft";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(payer).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(spender1).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(payer, token),
                tokenAssociate(payer, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, payer)),
                cryptoApproveAllowance()
                        .payingWith(payer)
                        .addCryptoAllowance(payer, spender, 100L)
                        .addTokenAllowance(payer, token, spender, 100L)
                        .addNftAllowance(payer, nft, spender, true, List.of(1L))
                        .blankMemo()
                        .logged(),
                cryptoDeleteAllowance()
                        .payingWith(payer)
                        .addNftDeleteAllowance(MISSING_OWNER, nft, List.of(1L))
                        .via("deleteTxn")
                        .blankMemo()
                        .logged(),
                getTxnRecord("deleteTxn").logged(),
                getAccountDetails(payer)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(1)),
                getTokenNftInfo(nft, 1L).hasNoSpender());
    }

    @HapiTest
    final Stream<DynamicTest> approvedForAllNotAffectedOnDelete() {
        final String owner = "owner";
        final String spender = "spender";
        final String token = "token";
        final String nft = "nft";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(owner, token),
                tokenAssociate(owner, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, owner)),
                cryptoApproveAllowance()
                        .payingWith(owner)
                        .addCryptoAllowance(owner, spender, 100L)
                        .addTokenAllowance(owner, token, spender, 100L)
                        .addNftAllowance(owner, nft, spender, true, List.of(1L))
                        .via("otherAdjustTxn"),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(1)
                                .tokenAllowancesCount(1)
                                .cryptoAllowancesContaining(spender, 100L)
                                .tokenAllowancesContaining(token, spender, 100L)
                                .nftApprovedAllowancesContaining(nft, spender)),
                getTokenNftInfo(nft, 1L).hasSpenderID(spender),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(1L))
                        .blankMemo()
                        .via("cryptoDeleteAllowanceTxn")
                        .logged(),
                getTxnRecord("cryptoDeleteAllowanceTxn").logged(),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .tokenAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(1)
                                .nftApprovedAllowancesContaining(nft, spender))
                        .logged(),
                getTokenNftInfo(nft, 1L).hasNoSpender());
    }

    @HapiTest
    final Stream<DynamicTest> happyPathWorks() {
        final String owner = "owner";
        final String spender = "spender";
        final String spender1 = "spender1";
        final String token = "token";
        final String nft = "nft";
        return hapiTest(
                newKeyNamed("supplyKey"),
                cryptoCreate(owner).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(spender1).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey("supplyKey")
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(nft)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey("supplyKey")
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(owner, token),
                tokenAssociate(owner, nft),
                mintToken(
                                nft,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via("nftTokenMint"),
                mintToken(token, 500L).via("tokenMint"),
                cryptoTransfer(movingUnique(nft, 1L, 2L, 3L).between(TOKEN_TREASURY, owner)),
                cryptoApproveAllowance()
                        .payingWith(owner)
                        .addCryptoAllowance(owner, spender, 100L)
                        .addTokenAllowance(owner, token, spender, 100L)
                        .addNftAllowance(owner, nft, spender1, true, List.of(3L))
                        .via("otherAdjustTxn"),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith()
                                .cryptoAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(1)
                                .tokenAllowancesCount(1)
                                .cryptoAllowancesContaining(spender, 100L)
                                .tokenAllowancesContaining(token, spender, 100L)),
                getTokenNftInfo(nft, 3L).hasSpenderID(spender1),
                cryptoDeleteAllowance()
                        .payingWith(owner)
                        .addNftDeleteAllowance(owner, nft, List.of(3L))
                        .blankMemo()
                        .via("cryptoDeleteAllowanceTxn")
                        .logged(),
                getTxnRecord("cryptoDeleteAllowanceTxn").logged(),
                validateChargedUsdWithin("cryptoDeleteAllowanceTxn", 0.05, 0.01),
                getAccountDetails(owner)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().nftApprovedForAllAllowancesCount(1)),
                getTokenNftInfo(nft, 3L).hasNoSpender());
    }
}
