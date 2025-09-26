// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNonfungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCancelAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingNFTAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.RECEIVER;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.LAZY_MEMO;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_PENDING_AIRDROP_ID_LIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PENDING_AIRDROP_ID_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PENDING_AIRDROP_ID_REPEATED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountBalance;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenAirdrop;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
@HapiTestLifecycle
@DisplayName("Claim token airdrop")
public class TokenClaimAirdropTest extends TokenAirdropBase {
    private static final String OWNER_2 = "owner2";
    private static final String FUNGIBLE_TOKEN_1 = "fungibleToken1";
    private static final String FUNGIBLE_TOKEN_2 = "fungibleToken2";
    private static final String FUNGIBLE_TOKEN_3 = "fungibleToken3";
    private static final String FUNGIBLE_TOKEN_4 = "fungibleToken4";
    private static final String FUNGIBLE_TOKEN_5 = "fungibleToken5";
    private static final String FUNGIBLE_TOKEN_6 = "fungibleToken6";
    private static final String FUNGIBLE_TOKEN_7 = "fungibleToken7";
    private static final String FUNGIBLE_TOKEN_8 = "fungibleToken8";
    private static final String FUNGIBLE_TOKEN_9 = "fungibleToken9";
    private static final String FUNGIBLE_TOKEN_10 = "fungibleToken10";
    private static final String FUNGIBLE_TOKEN_11 = "fungibleToken11";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String NFT_SUPPLY_KEY = "supplyKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // override and preserve old values
        lifecycle.overrideInClass(Map.of(
                "entities.unlimitedAutoAssociationsEnabled", "false",
                "tokens.airdrops.enabled", "false",
                "tokens.airdrops.claim.enabled", "false",
                "tokens.airdrops.cancel.enabled", "false"));
        // create some entities with disabled airdrops
        lifecycle.doAdhoc(setUpEntitiesPreHIP904());
        // enable airdrops
        lifecycle.doAdhoc(
                overriding("entities.unlimitedAutoAssociationsEnabled", "true"),
                overriding("tokens.airdrops.enabled", "true"),
                overriding("tokens.airdrops.claim.enabled", "true"),
                overriding("tokens.airdrops.cancel.enabled", "true"));
    }

    @HapiTest
    @DisplayName("Claim token airdrop after dissociation")
    final Stream<DynamicTest> claimTokenAirdropAfterDissociation() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),

                // do token association and dissociation
                tokenAssociate(RECEIVER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                tokenDissociate(RECEIVER, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),

                // do pending airdrop
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                        .payingWith(OWNER),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                        .payingWith(OWNER),

                // do claim
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN),
                                pendingNFTAirdrop(OWNER, RECEIVER, NON_FUNGIBLE_TOKEN, 1))
                        .payingWith(RECEIVER)
                        .via("claimTxn"),
                getTxnRecord("claimTxn")
                        .hasPriority(recordWith()
                                .tokenTransfers(includingFungibleMovement(
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)))
                                .tokenTransfers(includingNonfungibleMovement(
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER)))),
                validateChargedUsd("claimTxn", 0.001, 1),

                // assert balances
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 10),
                getAccountBalance(RECEIVER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),

                // assert token associations
                getAccountInfo(RECEIVER).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                getAccountInfo(RECEIVER).hasToken(relationshipWith(NON_FUNGIBLE_TOKEN))));
    }

    @HapiTest
    @DisplayName("fails gracefully with null parameters")
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(OWNER),
                submitModified(withSuccessivelyVariedBodyIds(), () -> tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN_1))
                        .signedBy(DEFAULT_PAYER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)));
    }

    @HapiTest
    @DisplayName("single token claim success that receiver paying for it")
    final Stream<DynamicTest> singleTokenClaimSuccessThatReceiverPayingForIt() {
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN_1))
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN_1, 999),
                getAccountInfo(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasToken(relationshipWith(FUNGIBLE_TOKEN_1)));
    }

    @HapiTest
    @DisplayName("single nft claim success that receiver paying for it")
    final Stream<DynamicTest> singleNFTTransfer() {
        return hapiTest(
                cryptoCreate(OWNER_2).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                newKeyNamed(NFT_SUPPLY_KEY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .name(NON_FUNGIBLE_TOKEN)
                        .treasury(OWNER_2)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(NFT_SUPPLY_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER_2, RECEIVER))
                        .payingWith(OWNER_2),
                tokenClaimAirdrop(pendingNFTAirdrop(OWNER_2, RECEIVER, NON_FUNGIBLE_TOKEN, 1))
                        .payingWith(RECEIVER));
    }

    @HapiTest
    @DisplayName("not enough Hbar to claim and than enough")
    @Tag(MATS)
    final Stream<DynamicTest> notEnoughHbarToClaimAndThanEnough() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(0L).maxAutomaticTokenAssociations(0),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                cryptoCreate(BOB).balance(ONE_MILLION_HBARS),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(OWNER, ALICE)).payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, ALICE, FUNGIBLE_TOKEN_1))
                        .payingWith(ALICE)
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                cryptoTransfer(tinyBarsFromTo(BOB, ALICE, ONE_HBAR)),
                tokenClaimAirdrop(pendingAirdrop(OWNER, ALICE, FUNGIBLE_TOKEN_1))
                        .payingWith(ALICE));
    }

    @HapiTest
    @DisplayName("token transfer fails but claim will pass")
    final Stream<DynamicTest> tokenTransferFailsButClaimWillPass() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                cryptoTransfer(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB))
                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1)).payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1));
    }

    @HapiTest
    @DisplayName("change a treasury owner")
    final Stream<DynamicTest> tokenTChangeTreasuryOwner() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        final String CAROL = "CAROL";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(CAROL).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                tokenCreate(FUNGIBLE_TOKEN_1)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .adminKey(ALICE),
                tokenAssociate(CAROL, FUNGIBLE_TOKEN_1),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenUpdate(FUNGIBLE_TOKEN_1).treasury(CAROL).payingWith(ALICE),
                cryptoTransfer(moving(20, FUNGIBLE_TOKEN_1).between(CAROL, ALICE)),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1)).payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1));
    }

    @HapiTest
    @DisplayName("token association after the token airdrop")
    final Stream<DynamicTest> tokenAssociationAfterTokenAirdrop() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                tokenCreate(FUNGIBLE_TOKEN_1)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .adminKey(ALICE),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenAssociate(BOB, FUNGIBLE_TOKEN_1),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1)).payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1));
    }

    @HapiTest
    @DisplayName("multiple token airdrops one NFT and Than another NFT and claims them separately")
    final Stream<DynamicTest> twoAirdropsNFTSendTwoClaims() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                newKeyNamed(NFT_SUPPLY_KEY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .name(NON_FUNGIBLE_TOKEN)
                        .treasury(ALICE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(NFT_SUPPLY_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(ALICE, BOB))
                        .payingWith(ALICE),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 2).between(ALICE, BOB))
                        .payingWith(ALICE),
                tokenClaimAirdrop(pendingNFTAirdrop(ALICE, BOB, NON_FUNGIBLE_TOKEN, 1))
                        .payingWith(BOB),
                tokenClaimAirdrop(pendingNFTAirdrop(ALICE, BOB, NON_FUNGIBLE_TOKEN, 2))
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2));
    }

    @HapiTest
    @DisplayName("token airdrop 10 FT and claim them")
    @SuppressWarnings("unchecked")
    final Stream<DynamicTest> tokenClaimOfTenFT() {
        final var recipient = "BOB";
        return hapiTest(
                cryptoCreate(recipient).maxAutomaticTokenAssociations(0),
                inParallel(
                        mapNTokens(token -> createFT(token, DEFAULT_PAYER, 1000L), SpecOperation.class, "ft", 1, 10)),
                tokenAirdrop(mapNTokens(
                        token -> moving(1, token).between(DEFAULT_PAYER, recipient), TokenMovement.class, "ft", 1, 5)),
                tokenAirdrop(mapNTokens(
                        token -> moving(1, token).between(DEFAULT_PAYER, recipient), TokenMovement.class, "ft", 6, 10)),
                claimAndFailToReclaim(() -> tokenClaimAirdrop(mapNTokens(
                                token -> pendingAirdrop(DEFAULT_PAYER, recipient, token), Function.class, "ft", 1, 10))
                        .payingWith(recipient)),
                inParallel(mapNTokens(
                        token -> getAccountBalance(recipient).hasTokenBalance(token, 1),
                        SpecOperation.class,
                        "ft",
                        1,
                        10)));
    }

    @HapiTest
    @DisplayName("token airdrop 10 FT and NFT claim by two receivers")
    final Stream<DynamicTest> tokenClaimOfTenFTAndNFTToTwoReceivers() {
        final String BOB = "BOB";
        final String CAROL = "CAROL";
        return hapiTest(
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                cryptoCreate(CAROL).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                inParallel(mapNTokens(
                        token -> createFT(token, DEFAULT_PAYER, 1000L), SpecOperation.class, "fungibleToken", 1, 9)),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .name(NON_FUNGIBLE_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(DEFAULT_PAYER),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                tokenAirdrop(
                        moving(1, FUNGIBLE_TOKEN_1).between(DEFAULT_PAYER, BOB),
                        moving(1, FUNGIBLE_TOKEN_2).between(DEFAULT_PAYER, BOB),
                        moving(1, FUNGIBLE_TOKEN_3).between(DEFAULT_PAYER, BOB),
                        moving(1, FUNGIBLE_TOKEN_4).between(DEFAULT_PAYER, BOB),
                        movingUnique(NON_FUNGIBLE_TOKEN, 1).between(DEFAULT_PAYER, BOB)),
                tokenAirdrop(
                        moving(1, FUNGIBLE_TOKEN_6).between(DEFAULT_PAYER, CAROL),
                        moving(1, FUNGIBLE_TOKEN_7).between(DEFAULT_PAYER, CAROL),
                        moving(1, FUNGIBLE_TOKEN_8).between(DEFAULT_PAYER, CAROL),
                        moving(1, FUNGIBLE_TOKEN_9).between(DEFAULT_PAYER, CAROL),
                        movingUnique(NON_FUNGIBLE_TOKEN, 2).between(DEFAULT_PAYER, CAROL)),
                claimAndFailToReclaim(() -> tokenClaimAirdrop(
                                pendingAirdrop(DEFAULT_PAYER, BOB, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(DEFAULT_PAYER, BOB, FUNGIBLE_TOKEN_2),
                                pendingAirdrop(DEFAULT_PAYER, BOB, FUNGIBLE_TOKEN_3),
                                pendingAirdrop(DEFAULT_PAYER, BOB, FUNGIBLE_TOKEN_4),
                                pendingNFTAirdrop(DEFAULT_PAYER, BOB, NON_FUNGIBLE_TOKEN, 1))
                        .payingWith(BOB)),
                claimAndFailToReclaim(() -> tokenClaimAirdrop(
                                pendingAirdrop(DEFAULT_PAYER, CAROL, FUNGIBLE_TOKEN_6),
                                pendingAirdrop(DEFAULT_PAYER, CAROL, FUNGIBLE_TOKEN_7),
                                pendingAirdrop(DEFAULT_PAYER, CAROL, FUNGIBLE_TOKEN_8),
                                pendingAirdrop(DEFAULT_PAYER, CAROL, FUNGIBLE_TOKEN_9),
                                pendingNFTAirdrop(DEFAULT_PAYER, CAROL, NON_FUNGIBLE_TOKEN, 2))
                        .payingWith(CAROL)),
                inParallel(mapNTokens(
                        token -> getAccountBalance(BOB).hasTokenBalance(token, 1),
                        HapiGetAccountBalance.class,
                        "fungibleToken",
                        1,
                        4)),
                getAccountBalance(BOB).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),
                inParallel(mapNTokens(
                        token -> getAccountBalance(CAROL).hasTokenBalance(token, 1),
                        HapiGetAccountBalance.class,
                        "fungibleToken",
                        6,
                        9)),
                getAccountBalance(CAROL).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1));
    }

    private static final AtomicInteger NUM_CLAIMS = new AtomicInteger(0);

    private SpecOperation claimAndFailToReclaim(@NonNull final Supplier<HapiTokenClaimAirdrop> claimFn) {
        final var claimNum = NUM_CLAIMS.incrementAndGet();
        return blockingOrder(
                claimFn.get().via("claimTxn" + claimNum),
                claimFn.get().hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                validateChargedUsd("claimTxn" + claimNum, 0.0010031904, 1));
    }

    @HapiTest
    @DisplayName("token airdrop 10 FT and claim them to different receivers")
    final Stream<DynamicTest> tokenClaimByFiveDifferentReceivers() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        final String CAROL = "CAROL";
        final String YULIA = "YULIA";
        final String TOM = "TOM";
        final String STEVE = "STEVE";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                cryptoCreate(CAROL).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                cryptoCreate(YULIA).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                cryptoCreate(TOM).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                cryptoCreate(STEVE).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                inParallel(mapNTokens(
                        token -> createFT(token, ALICE, 1000L), SpecOperation.class, "fungibleToken", 1, 10)),
                tokenAirdrop(
                                moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB),
                                moving(1, FUNGIBLE_TOKEN_2).between(ALICE, CAROL),
                                moving(1, FUNGIBLE_TOKEN_3).between(ALICE, YULIA),
                                moving(1, FUNGIBLE_TOKEN_4).between(ALICE, TOM),
                                moving(1, FUNGIBLE_TOKEN_5).between(ALICE, STEVE))
                        .payingWith(ALICE),
                tokenAirdrop(
                                moving(1, FUNGIBLE_TOKEN_6).between(ALICE, BOB),
                                moving(1, FUNGIBLE_TOKEN_7).between(ALICE, CAROL),
                                moving(1, FUNGIBLE_TOKEN_8).between(ALICE, YULIA),
                                moving(1, FUNGIBLE_TOKEN_9).between(ALICE, TOM),
                                moving(1, FUNGIBLE_TOKEN_10).between(ALICE, STEVE))
                        .payingWith(ALICE),
                tokenClaimAirdrop(
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(ALICE, CAROL, FUNGIBLE_TOKEN_2),
                                pendingAirdrop(ALICE, YULIA, FUNGIBLE_TOKEN_3),
                                pendingAirdrop(ALICE, TOM, FUNGIBLE_TOKEN_4),
                                pendingAirdrop(ALICE, STEVE, FUNGIBLE_TOKEN_5),
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_6),
                                pendingAirdrop(ALICE, CAROL, FUNGIBLE_TOKEN_7),
                                pendingAirdrop(ALICE, YULIA, FUNGIBLE_TOKEN_8),
                                pendingAirdrop(ALICE, TOM, FUNGIBLE_TOKEN_9),
                                pendingAirdrop(ALICE, STEVE, FUNGIBLE_TOKEN_10))
                        .payingWith(BOB)
                        .signedByPayerAnd(BOB, CAROL, YULIA, TOM, STEVE)
                        .via("claimTxn"),
                validateChargedUsd("claimTxn", 0.0010159536, 1),
                tokenClaimAirdrop(
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(ALICE, CAROL, FUNGIBLE_TOKEN_2),
                                pendingAirdrop(ALICE, YULIA, FUNGIBLE_TOKEN_3),
                                pendingAirdrop(ALICE, TOM, FUNGIBLE_TOKEN_4),
                                pendingAirdrop(ALICE, STEVE, FUNGIBLE_TOKEN_5),
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_6),
                                pendingAirdrop(ALICE, CAROL, FUNGIBLE_TOKEN_7),
                                pendingAirdrop(ALICE, YULIA, FUNGIBLE_TOKEN_8),
                                pendingAirdrop(ALICE, TOM, FUNGIBLE_TOKEN_9),
                                pendingAirdrop(ALICE, STEVE, FUNGIBLE_TOKEN_10))
                        .signedBy(BOB, CAROL, YULIA, TOM, STEVE)
                        .payingWith(BOB)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1).hasTokenBalance(FUNGIBLE_TOKEN_6, 1),
                getAccountInfo(BOB)
                        .hasNoTokenRelationship(FUNGIBLE_TOKEN_2)
                        .hasNoTokenRelationship(FUNGIBLE_TOKEN_3)
                        .hasNoTokenRelationship(FUNGIBLE_TOKEN_4)
                        .hasNoTokenRelationship(FUNGIBLE_TOKEN_5)
                        .hasNoTokenRelationship(FUNGIBLE_TOKEN_7)
                        .hasNoTokenRelationship(FUNGIBLE_TOKEN_8)
                        .hasNoTokenRelationship(FUNGIBLE_TOKEN_9)
                        .hasNoTokenRelationship(FUNGIBLE_TOKEN_10),
                getAccountBalance(CAROL).hasTokenBalance(FUNGIBLE_TOKEN_2, 1).hasTokenBalance(FUNGIBLE_TOKEN_7, 1),
                getAccountBalance(YULIA).hasTokenBalance(FUNGIBLE_TOKEN_3, 1).hasTokenBalance(FUNGIBLE_TOKEN_8, 1),
                getAccountBalance(TOM).hasTokenBalance(FUNGIBLE_TOKEN_4, 1).hasTokenBalance(FUNGIBLE_TOKEN_9, 1),
                getAccountBalance(STEVE).hasTokenBalance(FUNGIBLE_TOKEN_5, 1).hasTokenBalance(FUNGIBLE_TOKEN_10, 1));
    }

    @HapiTest
    @DisplayName("multiple token airdrops one NFT and Than another NFT and claims together")
    final Stream<DynamicTest> twoAirdropsNFTSendOneClaims() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                newKeyNamed(NFT_SUPPLY_KEY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .name(NON_FUNGIBLE_TOKEN)
                        .treasury(ALICE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(NFT_SUPPLY_KEY),
                mintToken(NON_FUNGIBLE_TOKEN, List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(ALICE, BOB))
                        .payingWith(ALICE),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 2).between(ALICE, BOB))
                        .payingWith(ALICE),
                tokenClaimAirdrop(
                                pendingNFTAirdrop(ALICE, BOB, NON_FUNGIBLE_TOKEN, 1),
                                pendingNFTAirdrop(ALICE, BOB, NON_FUNGIBLE_TOKEN, 2))
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2));
    }

    @HapiTest
    @DisplayName("sending two transaction with FT A and FT B and claim them")
    final Stream<DynamicTest> sendingTwoFTTransactionWithSameTokens() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                tokenCreate(FUNGIBLE_TOKEN_1)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .adminKey(ALICE),
                tokenCreate(FUNGIBLE_TOKEN_2)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .adminKey(ALICE),
                tokenAirdrop(moving(5, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenAirdrop(
                                moving(5, FUNGIBLE_TOKEN_1).between(ALICE, BOB),
                                moving(5, FUNGIBLE_TOKEN_2).between(ALICE, BOB))
                        .payingWith(ALICE),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1)).payingWith(BOB),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_2)).payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 10),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_2, 5));
    }

    @HapiTest
    @DisplayName("fails when not enough fungible token exist in the owner account")
    final Stream<DynamicTest> failsNotEnoughBalanceInSenderAccountOfFT() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        final String CAROL = "CAROL";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                cryptoCreate(CAROL).balance(ONE_HUNDRED_HBARS),
                tokenCreate(FUNGIBLE_TOKEN_1)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .adminKey(ALICE),
                tokenAssociate(CAROL, FUNGIBLE_TOKEN_1),
                tokenAirdrop(moving(1000, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                cryptoTransfer(moving(1000, FUNGIBLE_TOKEN_1).between(ALICE, CAROL)),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1))
                        .payingWith(BOB)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE));
    }

    @HapiTest
    @DisplayName("fails attempt to claim by deleted account")
    final Stream<DynamicTest> attemptToClaimByDeletedAccount() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        final String CAROL = "CAROL";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                cryptoCreate(CAROL).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                tokenCreate(FUNGIBLE_TOKEN_1)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .adminKey(ALICE),
                tokenAirdrop(
                                moving(1, FUNGIBLE_TOKEN_1).between(ALICE, CAROL),
                                moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB))
                        .payingWith(ALICE),
                cryptoDelete(BOB),
                tokenClaimAirdrop(
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(ALICE, CAROL, FUNGIBLE_TOKEN_1))
                        .signedBy(CAROL, BOB)
                        .payingWith(CAROL)
                        .hasPrecheck(ACCOUNT_DELETED));
    }

    @HapiTest
    @DisplayName("multiple FT token but not enough balance to transfer twice")
    final Stream<DynamicTest> multipleFTButNotEnoughBalanceToTransferTwice() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 2L),
                createFT(FUNGIBLE_TOKEN_2, ALICE, 2L),
                tokenAirdrop(moving(2, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenAirdrop(
                                moving(2, FUNGIBLE_TOKEN_1).between(ALICE, BOB),
                                moving(2, FUNGIBLE_TOKEN_2).between(ALICE, BOB))
                        .payingWith(ALICE),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1))
                        .payingWith(BOB)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE));
    }

    @HapiTest
    @DisplayName("FT token paused during airdrop")
    final Stream<DynamicTest> tokenPausedDuringAirdrop() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                newKeyNamed("pauseKey"),
                tokenCreate(FUNGIBLE_TOKEN_1)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .pauseKey("pauseKey")
                        .initialSupply(15L),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenPause(FUNGIBLE_TOKEN_1),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1))
                        .payingWith(BOB)
                        .hasKnownStatus(TOKEN_IS_PAUSED));
    }

    @HapiTest
    @DisplayName("Claim token airdrop - 2nd account pays")
    @Tag(MATS)
    final Stream<DynamicTest> claimTokenAirdropOtherAccountPays() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(OWNER_2).balance(ONE_HUNDRED_HBARS),

                // do pending airdrop
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)).payingWith(OWNER),

                // do claim
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN))
                        .signedBy(OWNER_2, RECEIVER)
                        .payingWith(OWNER_2)
                        .via("claimTxn"),
                getTxnRecord("claimTxn")
                        .hasPriority(recordWith()
                                .tokenTransfers(includingFungibleMovement(
                                        moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)))),
                validateChargedUsd("claimTxn", 0.001, 1),

                // assert token associations
                getAccountInfo(RECEIVER).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 1)));
    }

    @HapiTest
    @DisplayName("Claim token airdrop - sender account pays")
    @Tag(MATS)
    final Stream<DynamicTest> claimTokenAirdropSenderAccountPays() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(0L),

                // do pending airdrop
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)).payingWith(OWNER),

                // do claim
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN))
                        .signedBy(OWNER, RECEIVER)
                        .payingWith(OWNER)
                        .via("claimTxn"),
                getTxnRecord("claimTxn")
                        .hasPriority(recordWith()
                                .tokenTransfers(includingFungibleMovement(
                                        moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER)))),
                validateChargedUsd("claimTxn", 0.001, 1),

                // assert token associations
                getAccountInfo(RECEIVER).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 1)));
    }

    @HapiTest
    @DisplayName("both associated and not associated FTs and receiver sig required")
    final Stream<DynamicTest> withTwoTokensAndReceiverSigReq() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(flattened(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(0)
                        .receiverSigRequired(true),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                createFT(FUNGIBLE_TOKEN_2, ALICE, 1000L),
                tokenAssociate(BOB, FUNGIBLE_TOKEN_1),
                tokenAirdrop(
                                moving(10, FUNGIBLE_TOKEN_1).between(ALICE, BOB),
                                moving(10, FUNGIBLE_TOKEN_2).between(ALICE, BOB))
                        .payingWith(ALICE),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 0),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_2, 0),
                tokenClaimAirdrop(
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_2))
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 10),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_2, 10)));
    }

    @HapiTest
    @DisplayName("multiple FT airdrops to same receiver")
    final Stream<DynamicTest> multipleFtAirdropsSameReceiver() {
        final String BOB = "BOB";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, BOB)).payingWith(OWNER),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, BOB)).payingWith(OWNER),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, BOB)).payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, BOB, FUNGIBLE_TOKEN))
                        .signedBy(BOB)
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN, 30)));
    }

    @HapiTest
    @DisplayName("not associated FT and receiver sig required")
    final Stream<DynamicTest> notAssociatedFTAndReceiverSigRequired() {
        final String BOB = "BOB";
        final String ALICE = "ALICE";
        return hapiTest(flattened(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(5)
                        .receiverSigRequired(true),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1))
                        .signedBy(BOB)
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 10)));
    }

    @HapiTest
    @DisplayName("multiple pending transfers in one airdrop same token different receivers")
    final Stream<DynamicTest> multiplePendingInOneAirdropDifferentReceivers() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        final String CAROL = "CAROL";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                cryptoCreate(CAROL).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                tokenAirdrop(
                                moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB),
                                moving(1, FUNGIBLE_TOKEN_1).between(ALICE, CAROL))
                        .payingWith(ALICE),
                tokenClaimAirdrop(
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(ALICE, CAROL, FUNGIBLE_TOKEN_1))
                        .signedBy(BOB, CAROL)
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(CAROL).hasTokenBalance(FUNGIBLE_TOKEN_1, 1));
    }

    @HapiTest
    @DisplayName("multiple pending transfers in one airdrop different token same receivers with max auto association")
    final Stream<DynamicTest> multiplePendingInOneAirdropSameReceiverDifferentTokensWithMaxAutoAssociation() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                createFT(FUNGIBLE_TOKEN_2, ALICE, 1000L),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_2).between(ALICE, BOB)).payingWith(ALICE),
                tokenClaimAirdrop(
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_2))
                        .signedBy(BOB)
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_2, 1));
    }

    @HapiTest
    @DisplayName("multiple pending transfers in one airdrop different token same receivers with specific association")
    final Stream<DynamicTest> multiplePendingInOneAirdropSameReceiverDifferentTokensWithSpecificAssociation() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                createFT(FUNGIBLE_TOKEN_2, ALICE, 1000L),
                tokenAssociate(BOB, FUNGIBLE_TOKEN_1).payingWith(ALICE),
                tokenAirdrop(
                                moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB),
                                moving(1, FUNGIBLE_TOKEN_2).between(ALICE, BOB))
                        .payingWith(ALICE),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_2, 0),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_2))
                        .signedBy(BOB)
                        .payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_2, 1));
    }

    @HapiTest
    @DisplayName("token claim with no pending airdrop should fail")
    final Stream<DynamicTest> tokenClaimWithNoPendingAirdrop() {
        return hapiTest(tokenClaimAirdrop().hasPrecheck(EMPTY_PENDING_AIRDROP_ID_LIST));
    }

    @HapiTest
    @DisplayName("token claim with duplicate entries should fail")
    final Stream<DynamicTest> duplicateClaimAirdrop() {
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_1))
                        .payingWith(RECEIVER)
                        .hasPrecheck(PENDING_AIRDROP_ID_REPEATED));
    }

    @HapiTest
    @DisplayName("token claim with more than ten pending airdrops should fail")
    final Stream<DynamicTest> tokenClaimWithMoreThanTenPendingAirdropsShouldFail() {
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                inParallel(mapNTokens(
                        token -> createFT(token, OWNER, 1000L), SpecOperation.class, "fungibleToken", 1, 11)),
                inParallel(mapNTokens(
                        token -> airdropFT(token, OWNER, RECEIVER, 20), SpecOperation.class, "fungibleToken", 1, 11)),
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_2),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_3),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_4),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_5),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_6),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_7),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_8),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_9),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_10),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_11))
                        .payingWith(RECEIVER)
                        .hasKnownStatus(PENDING_AIRDROP_ID_LIST_TOO_LONG));
    }

    @HapiTest
    @DisplayName("Claim frozen token airdrop")
    final Stream<DynamicTest> claimFrozenToken() {
        final var tokenFreezeKey = "freezeKey";
        return defaultHapiSpec("should fail - ACCOUNT_FROZEN_FOR_TOKEN")
                .given(flattened(
                        setUpTokensAndAllReceivers(),
                        newKeyNamed(tokenFreezeKey),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .treasury(OWNER)
                                .freezeKey(tokenFreezeKey)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)))
                .when(
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                                .payingWith(OWNER),
                        tokenFreeze(FUNGIBLE_TOKEN, OWNER))
                .then(
                        getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                        tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN))
                                .payingWith(RECEIVER)
                                .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                        getAccountBalance(RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 0));
    }

    @HapiTest
    @DisplayName("hollow account with 0 free maxAutoAssociations")
    final Stream<DynamicTest> airdropToAliasWithNoFreeSlots() {
        final var validAlias = "validAlias";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, validAlias))
                        .payingWith(OWNER),
                // check if account is hollow (has empty key)
                getAliasedAccountInfo(validAlias).isHollow(),
                tokenClaimAirdrop(pendingAirdrop(OWNER, validAlias, FUNGIBLE_TOKEN))
                        .payingWith(validAlias)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(validAlias))
                        .via("claimTxn"),
                validateChargedUsd("claimTxn", 0.001, 1),

                // check if account was finalized and auto associations were not modified
                getAliasedAccountInfo(validAlias).isNotHollow().hasMaxAutomaticAssociations(0)));
    }

    @HapiTest
    @DisplayName("hollow account with 0 slots different payer")
    final Stream<DynamicTest> airdropToAliasWithNoFreeSlotsClaimWithDifferentPayer() {
        final var alias = "alias2.0";
        final var carol = "CAROL";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(carol).balance(ONE_HUNDRED_HBARS),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, alias)).payingWith(OWNER),
                // check if account is hollow (has empty key)
                getAliasedAccountInfo(alias).isHollow(),
                tokenClaimAirdrop(pendingAirdrop(OWNER, alias, FUNGIBLE_TOKEN))
                        .payingWith(carol)
                        .signedBy(carol, alias)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(alias))
                        .via("claimTxn"),
                validateChargedUsd("claimTxn", 0.001, 1),

                // check if account was finalized and auto associations were not modified
                getAliasedAccountInfo(alias).isNotHollow().hasMaxAutomaticAssociations(0)));
    }

    @HapiTest
    @DisplayName("given two same claims, second should fail")
    final Stream<DynamicTest> twoSameClaims() {
        // CLAIM_48
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .via("claimTxn1")
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .via("claimTxn2")
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                validateChargedUsd("claimTxn1", 0.001, 1),
                validateChargedUsd("claimTxn2", 0.001, 1)));
    }

    @HapiTest
    @DisplayName("missing pending airdrop, claim should fail")
    final Stream<DynamicTest> missingPendingClaim() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                // CLAIM_51
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID)));
    }

    @HapiTest
    @DisplayName("attempt co claim an airdrop, that was claimed by a different account")
    // CLAIM_49
    final Stream<DynamicTest> test() {
        var receiver = RECEIVER;
        var otherAccount = "otherAccount";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(otherAccount).balance(ONE_HUNDRED_HBARS),
                tokenAirdrop(TokenMovement.moving(1, FUNGIBLE_TOKEN).between(OWNER, otherAccount))
                        .payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, otherAccount, FUNGIBLE_TOKEN))
                        .payingWith(otherAccount),
                tokenClaimAirdrop(pendingAirdrop(OWNER, otherAccount, FUNGIBLE_TOKEN))
                        .payingWith(receiver)
                        .hasKnownStatus(INVALID_SIGNATURE)));
    }

    @HapiTest
    @DisplayName("signed by account not referenced as receiver_id in each pending airdrops")
    final Stream<DynamicTest> signedByAccountNotReferencedAsReceiverId() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                tokenAirdrop(
                                moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                                moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                        .payingWith(OWNER),
                // Payer is not receiver
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .via("claimTxn")
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .hasKnownStatus(INVALID_SIGNATURE),
                // Missing signature from second receiver
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .via("claimTxn1")
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .hasKnownStatus(INVALID_SIGNATURE),
                // Succeeds with all required signatures
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .signedBy(
                                DEFAULT_PAYER,
                                RECEIVER_WITH_0_AUTO_ASSOCIATIONS,
                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN, 1),
                getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN, 1),
                validateChargedUsd("claimTxn", 0.001, 1)));
    }

    @HapiTest
    @DisplayName("spender has insufficient balance")
    final Stream<DynamicTest> spenderHasInsufficientBalance() {
        var spender = "spenderWithInsufficientBalance";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(spender).balance(ONE_HBAR).maxAutomaticTokenAssociations(-1),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, spender)),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(spender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(spender),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(spender, OWNER)),
                tokenClaimAirdrop(pendingAirdrop(spender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .payingWith(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE)));
    }

    @HapiTest
    @DisplayName("claim after association edit while the account has pending airdrops")
    final Stream<DynamicTest> claimAfterAssociationEdit() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN, ALICE, 1001L),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(ALICE, BOB)).payingWith(ALICE),
                cryptoUpdate(BOB).maxAutomaticAssociations(-1),
                tokenAirdrop(moving(20, FUNGIBLE_TOKEN).between(ALICE, BOB)).payingWith(ALICE),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN, 20),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN)).payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN, 30));
    }

    @HapiTest
    @DisplayName("with max long token balance")
    final Stream<DynamicTest> maxLongTokenBalance() {
        var receiver = "receiverWithMaxLongTokenBalance";
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(receiver).balance(ONE_HBAR).maxAutomaticTokenAssociations(0),
                tokenCreate(FUNGIBLE_TOKEN).treasury(OWNER).initialSupply(Long.MAX_VALUE),
                tokenAirdrop(moving(Long.MAX_VALUE, FUNGIBLE_TOKEN).between(OWNER, receiver))
                        .payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, receiver, FUNGIBLE_TOKEN))
                        .payingWith(receiver)
                        .via("maxAmountClaimTxn"),
                getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, Long.MAX_VALUE));
    }

    @HapiTest
    @DisplayName("duplicate entries of Non Fungible Tokens should fail")
    final Stream<DynamicTest> duplicatedNFTFail() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, NON_FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER, NON_FUNGIBLE_TOKEN))
                        .payingWith(RECEIVER)
                        .hasPrecheck(PENDING_AIRDROP_ID_REPEATED)));
    }

    @HapiTest
    @DisplayName("containing up to 10 tokens and some duplicate entries should fail")
    final Stream<DynamicTest> duplicateAirdropsFail() {
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(0),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1001L),
                createFT(FUNGIBLE_TOKEN_2, OWNER, 1002L),
                createFT(FUNGIBLE_TOKEN_3, OWNER, 1003L),
                createFT(FUNGIBLE_TOKEN_4, OWNER, 1004L),
                createFT(FUNGIBLE_TOKEN_5, OWNER, 1005L),
                createFT(FUNGIBLE_TOKEN_6, OWNER, 1006L),
                createFT(FUNGIBLE_TOKEN_7, OWNER, 1007L),
                createFT(FUNGIBLE_TOKEN_8, OWNER, 1008L),
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_1),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_2),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_3),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_4),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_5),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_6),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_7),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_8))
                        .payingWith(RECEIVER)
                        .hasPrecheck(PENDING_AIRDROP_ID_REPEATED)));
    }

    @LeakyHapiTest(overrides = {"entities.unlimitedAutoAssociationsEnabled"})
    @DisplayName("account created with same alias should fail")
    final Stream<DynamicTest> accountCreatedWithSAmeAliasShouldFail() {
        final String ALIAS = "alias";
        return hapiTest(
                // add common entities, you can create your own ones
                flattened(
                        setUpTokensAndAllReceivers(),
                        logIt("preparation is over"),
                        // stop the unlimitedAutoAssociations, in order to create the account and send the airdrop to
                        overriding("entities.unlimitedAutoAssociationsEnabled", "false"),
                        // create key
                        newKeyNamed(ALIAS),
                        // create first aliased account
                        cryptoTransfer(tinyBarsFromAccountToAlias(OWNER, ALIAS, 1)),
                        // save aliased account into registry
                        withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)),
                        // airdrop
                        tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, ALIAS))
                                .payingWith(OWNER),
                        // airdrop should be pending, you can assert txn record too
                        getAccountBalance(ALIAS).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                        // delete the account
                        cryptoDelete(ALIAS),
                        // create new account with the same key
                        cryptoTransfer(tinyBarsFromAccountToAlias(OWNER, ALIAS, 1)),
                        withOpContext((spec, opLog) -> updateSpecFor(spec, ALIAS)),
                        // try to claim
                        tokenClaimAirdrop(pendingAirdrop(OWNER, ALIAS, FUNGIBLE_TOKEN))
                                .signedBy(ALIAS, DEFAULT_PAYER)
                                .hasKnownStatus(INVALID_PENDING_AIRDROP_ID)));
    }

    @HapiTest
    @DisplayName(
            "not signed by the account referenced by a receiver_id for each entry in the pending airdrops list should fail")
    final Stream<DynamicTest> notSignedByReceiverFail() {
        var RECEIVER2 = "RECEIVER2";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER2).balance(ONE_HUNDRED_HBARS),
                tokenClaimAirdrop(
                                pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN),
                                pendingAirdrop(OWNER, RECEIVER2, NON_FUNGIBLE_TOKEN))
                        .signedBy(RECEIVER)
                        .hasPrecheck(INVALID_SIGNATURE)));
    }

    @LeakyHapiTest(overrides = {"tokens.airdrops.claim.enabled"})
    @DisplayName("sign a tokenClaimAirdrop transaction when the feature flag is disabled")
    final Stream<DynamicTest> tokenClaimAirdropDisabledFail() {
        return hapiTest(
                overriding("tokens.airdrops.claim.enabled", "false"),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                tokenClaimAirdrop(pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN_1))
                        .payingWith(RECEIVER)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    @DisplayName("a hollow account receiver that sigs it with no HBAR fail")
    final Stream<DynamicTest> hollowNoHbarFail() {
        final var hollowAcnt = "hollowAcnt";
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, OWNER, 1000L),
                newKeyNamed(hollowAcnt).shape(SECP_256K1_SHAPE),
                createHollowAccountByFunToken(hollowAcnt, FUNGIBLE_TOKEN_1, OWNER),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN_1).between(OWNER, hollowAcnt))
                        .payingWith(OWNER),
                getAliasedAccountInfo(hollowAcnt)
                        .has(accountWith().hasEmptyKey().noAlias()),
                getAccountBalance(hollowAcnt).hasTinyBars(0L),
                tokenClaimAirdrop(pendingAirdrop(OWNER, hollowAcnt, FUNGIBLE_TOKEN_1))
                        .payingWith(hollowAcnt)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(hollowAcnt))
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE));
    }

    @HapiTest
    @DisplayName("pending airdrop does not exist")
    final Stream<DynamicTest> pendingAirdropDoesNotExist() {
        var receiver = "receiver";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0).balance(ONE_HUNDRED_HBARS),
                // canceled airdrop (CLAIM_47)
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, receiver)).payingWith(OWNER),
                tokenCancelAirdrop(pendingAirdrop(OWNER, receiver, FUNGIBLE_TOKEN)),
                tokenClaimAirdrop(pendingAirdrop(OWNER, receiver, FUNGIBLE_TOKEN))
                        .payingWith(receiver)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),

                // invalid airdrop (CLAIM_47)
                tokenClaimAirdrop(pendingAirdrop(receiver, receiver, FUNGIBLE_TOKEN))
                        .payingWith(receiver)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID)));
    }

    @HapiTest
    @DisplayName("Attempt to claim an airdrop by the sender")
    final Stream<DynamicTest> claimWithSenderAccount() {
        var receiver = "receiver";
        // CLAIM_50
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(receiver),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, receiver)).payingWith(OWNER),
                tokenClaimAirdrop(pendingAirdrop(OWNER, receiver, FUNGIBLE_TOKEN))
                        .payingWith(OWNER)
                        .signedBy(OWNER)
                        .hasKnownStatus(INVALID_SIGNATURE)));
    }

    @HapiTest
    @DisplayName("a hollow account with maxAutoAssociation 1 as receiver should be successful")
    final Stream<DynamicTest> hollowNoMaxAutoAssociationSuccess() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                createHollow(1, i -> BOB),
                getAliasedAccountInfo(BOB)
                        .has(accountWith().maxAutoAssociations(-1).hasEmptyKey().noAlias()),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB))
                        .signedBy(BOB, ALICE)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(BOB))
                        .payingWith(BOB),
                // Bob account is finalized with the token airdrop transaction
                getAliasedAccountInfo(BOB).has(accountWith().hasNonEmptyKey()),
                // no tokenClaimAirdrop needed here
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1));
    }

    @HapiTest
    @DisplayName("Hollow account should be created before implementation of HIP-904")
    @Tag(MATS)
    final Stream<DynamicTest> hollowAccountBehavior() {
        final String ALICE = "ALICE";
        final String BOB = "BOB";
        final String CAROL = "CAROL";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                createFT(FUNGIBLE_TOKEN_1, ALICE, 1000L),
                createHollow(1, i -> BOB),
                createHollow(1, i -> CAROL),
                cryptoUpdate(BOB).sigMapPrefixes(uniqueWithFullPrefixesFor(BOB)).maxAutomaticAssociations(0),
                cryptoUpdate(CAROL)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(CAROL))
                        .maxAutomaticAssociations(0),
                tokenAssociate(CAROL, FUNGIBLE_TOKEN_1).sigMapPrefixes(uniqueWithFullPrefixesFor(CAROL)),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, BOB)).payingWith(ALICE),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_1).between(ALICE, CAROL)).payingWith(ALICE),
                tokenClaimAirdrop(pendingAirdrop(ALICE, BOB, FUNGIBLE_TOKEN_1)).payingWith(BOB),
                getAccountBalance(BOB).hasTokenBalance(FUNGIBLE_TOKEN_1, 1),
                getAccountBalance(CAROL).hasTokenBalance(FUNGIBLE_TOKEN_1, 1));
    }

    private HapiTokenCreate createFT(String tokenName, String treasury, long amount) {
        return tokenCreate(tokenName)
                .treasury(treasury)
                .tokenType(FUNGIBLE_COMMON)
                .initialSupply(amount);
    }

    private HapiTokenAirdrop airdropFT(String tokenName, String sender, String receiver, int amountToMove) {
        return tokenAirdrop(moving(amountToMove, tokenName).between(sender, receiver))
                .payingWith(sender);
    }

    @SuppressWarnings("unchecked")
    private static <T> T[] mapNTokens(
            @NonNull final Function<String, T> f,
            @NonNull final Class<T> type,
            @NonNull final String token,
            final int lo,
            final int hi) {
        return IntStream.rangeClosed(lo, hi).mapToObj(i -> f.apply(token + i)).toArray(n ->
                (T[]) Array.newInstance(type, n));
    }

    private SpecOperation createHollowAccountByFunToken(String hollowAcnt, String token, String owner) {
        return withOpContext((spec, opLog) -> {
            final var ecdsaKey =
                    spec.registry().getKey(hollowAcnt).getECDSASecp256K1().toByteArray();
            final var evmAddress = ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
            final var op1 =
                    cryptoTransfer(moving(1, token).between(owner, evmAddress)).hasKnownStatus(SUCCESS);
            final var op2 = getAliasedAccountInfo(evmAddress)
                    .has(accountWith()
                            .hasEmptyKey()
                            .expectedBalanceWithChargedUsd(0L, 0, 0)
                            .autoRenew(THREE_MONTHS_IN_SECONDS)
                            .memo(LAZY_MEMO));
            allRunFor(spec, op1, op2);
            updateSpecFor(spec, hollowAcnt);
        });
    }
}
