// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCancelAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenReject;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenCancelAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenCancelAirdrop.pendingNFTAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingNFT;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingToken;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_HAS_PENDING_AIRDROPS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_PENDING_AIRDROP_ID_LIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PENDING_AIRDROP_ID_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PENDING_AIRDROP_ID_REPEATED;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
@HapiTestLifecycle
@DisplayName("Token cancel airdrop")
public class TokenCancelAirdropTest extends TokenAirdropBase {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of(
                "tokens.airdrops.enabled",
                "true",
                "tokens.airdrops.cancel.enabled",
                "true",
                "tokens.airdrops.claim.enabled",
                "true",
                "entities.unlimitedAutoAssociationsEnabled",
                "true"));
        lifecycle.doAdhoc(setUpTokensAndAllReceivers());
    }

    @HapiTest
    @DisplayName("fails gracefully with null parameters")
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        final var account = "account";
        return hapiTest(
                cryptoCreate(account),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account)),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(account),
                submitModified(withSuccessivelyVariedBodyIds(), () -> tokenCancelAirdrop(
                                pendingAirdrop(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .payingWith(account)));
    }

    @HapiTest
    @DisplayName("not created NFT pending airdrop")
    final Stream<DynamicTest> cancelNotCreatedNFTPendingAirdrop() {
        return hapiTest(
                tokenCancelAirdrop(pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 5L))
                        .payingWith(OWNER)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID));
    }

    @HapiTest
    @DisplayName("not created FT pending airdrop")
    final Stream<DynamicTest> cancelNotCreatedFTPendingAirdrop() {
        final var receiver = "receiver";
        return hapiTest(
                cryptoCreate(receiver),
                tokenCancelAirdrop(pendingAirdrop(OWNER, receiver, FUNGIBLE_TOKEN))
                        .payingWith(OWNER)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID));
    }

    @HapiTest
    @DisplayName("with an empty airdrop list")
    final Stream<DynamicTest> cancelWithAnEmptyAirdropList() {
        return hapiTest(tokenCancelAirdrop().payingWith(OWNER).hasPrecheck(EMPTY_PENDING_AIRDROP_ID_LIST));
    }

    @HapiTest
    @DisplayName("with exceeding airdrops")
    final Stream<DynamicTest> cancelWithExceedingAirdrops() {
        return hapiTest(tokenCancelAirdrop(
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L),
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 2L),
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 3L),
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 4L),
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 5L),
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 6L),
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 7L),
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 8L),
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 9L),
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 10L),
                        pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                .payingWith(OWNER)
                .hasKnownStatus(PENDING_AIRDROP_ID_LIST_TOO_LONG));
    }

    @HapiTest
    @DisplayName("with duplicated FT")
    final Stream<DynamicTest> cancelWithDuplicatedFT() {
        return hapiTest(tokenCancelAirdrop(
                        pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN),
                        pendingAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                .payingWith(OWNER)
                .hasPrecheck(PENDING_AIRDROP_ID_REPEATED));
    }

    @HapiTest
    @DisplayName("with duplicated NFT")
    final Stream<DynamicTest> cancelWithDuplicatedNFT() {
        return hapiTest(tokenCancelAirdrop(
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L),
                        pendingNFTAirdrop(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L))
                .payingWith(OWNER)
                .hasPrecheck(PENDING_AIRDROP_ID_REPEATED));
    }

    @HapiTest
    @DisplayName("FT not signed by the owner")
    final Stream<DynamicTest> cancelFTNotSingedByTheOwner() {
        var account = "account";
        var randomAccount = "randomAccount";
        return hapiTest(
                // setup initial account
                cryptoCreate(account),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account)),
                cryptoCreate(randomAccount),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(account),
                tokenCancelAirdrop(pendingAirdrop(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .signedBy(randomAccount)
                        .hasPrecheck(INVALID_SIGNATURE));
    }

    @HapiTest
    @DisplayName("NFT not signed by the owner")
    final Stream<DynamicTest> cancelNFTNotSingedByTheOwner() {
        var account = "account";
        var randomAccount = "randomAccount";
        return hapiTest(
                // setup initial account
                cryptoCreate(account),
                tokenAssociate(account, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(OWNER, account)),
                cryptoCreate(randomAccount),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(account),
                tokenCancelAirdrop(
                                pendingNFTAirdrop(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L))
                        .signedBy(randomAccount)
                        .hasPrecheck(INVALID_SIGNATURE));
    }

    @HapiTest
    @DisplayName("cannot delete account when pending airdrop present")
    final Stream<DynamicTest> cannotDeleteAccountWhenPendingAirdropPresent() {
        final var account = "account";
        return hapiTest(
                cryptoCreate(account),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account)),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(account),
                cryptoDelete(account).hasKnownStatus(ACCOUNT_HAS_PENDING_AIRDROPS),
                tokenCancelAirdrop(pendingAirdrop(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .payingWith(account),
                tokenReject(rejectingToken(FUNGIBLE_TOKEN)).payingWith(account).signedBy(account),
                cryptoDelete(account));
    }

    @HapiTest
    @DisplayName("cannot delete account when pending airdrop present with two airdrops")
    final Stream<DynamicTest> cannotDeleteAccountWhenPendingAirdropPresentTwoAirdrops() {
        final var account = "account";
        return hapiTest(
                cryptoCreate(account),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                tokenAssociate(account, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 12L).between(OWNER, account)),
                tokenAirdrop(
                                moving(10, FUNGIBLE_TOKEN).between(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                                movingUnique(NON_FUNGIBLE_TOKEN, 12L)
                                        .between(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(account),
                cryptoDelete(account).hasKnownStatus(ACCOUNT_HAS_PENDING_AIRDROPS),
                tokenCancelAirdrop(pendingAirdrop(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .payingWith(account),
                cryptoDelete(account).hasKnownStatus(ACCOUNT_HAS_PENDING_AIRDROPS),
                tokenCancelAirdrop(
                                pendingNFTAirdrop(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 12L))
                        .payingWith(account),
                tokenReject(rejectingToken(FUNGIBLE_TOKEN)).payingWith(account).signedBy(account),
                tokenReject(rejectingNFT(NON_FUNGIBLE_TOKEN, 12L))
                        .payingWith(account)
                        .signedBy(account),
                cryptoDelete(account));
    }

    @HapiTest
    @DisplayName("and then claim should fail for FT")
    final Stream<DynamicTest> claimCanceledFTAirdrop() {
        final var account = "account";
        final var receiver = "receiver";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account)),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(account, receiver))
                        .payingWith(account),
                tokenCancelAirdrop(pendingAirdrop(account, receiver, FUNGIBLE_TOKEN))
                        .payingWith(account),
                tokenClaimAirdrop(pendingAirdrop(account, receiver, FUNGIBLE_TOKEN))
                        .payingWith(receiver)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID));
    }

    @HapiTest
    @DisplayName("and then claim should fail for NFT")
    final Stream<DynamicTest> claimCanceledNFTAirdrop() {
        final var account = "account";
        final var receiver = "receiver";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                tokenAssociate(account, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 3L).between(OWNER, account)),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 3L).between(account, receiver))
                        .payingWith(account),
                tokenCancelAirdrop(pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 3L))
                        .payingWith(account),
                tokenClaimAirdrop(pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 3L))
                        .payingWith(receiver)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID));
    }

    @HapiTest
    @DisplayName("with multiple NFTs")
    @Tag(MATS)
    final Stream<DynamicTest> multipleNFTs() {
        final var account = "account";
        final var receiver = "receiver";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                tokenAssociate(account, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 4L).between(OWNER, account)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 5L).between(OWNER, account)),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 4L).between(account, receiver))
                        .payingWith(account),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 5L).between(account, receiver))
                        .payingWith(account),
                tokenCancelAirdrop(
                                pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 4L),
                                pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 5L))
                        .payingWith(account));
    }

    @HapiTest
    @DisplayName("when receiver with 0 HBARs pays for FT")
    final Stream<DynamicTest> receiverWith0HBARsPaysFT() {
        final var account = "account";
        final var receiver = "receiver";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0).balance(0L),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account)),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(account, receiver))
                        .payingWith(account),
                tokenCancelAirdrop(pendingAirdrop(account, receiver, FUNGIBLE_TOKEN))
                        .signedBy(account)
                        .payingWith(receiver)
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE));
    }

    @HapiTest
    @DisplayName("when receiver with 0 HBARs pays for NFT")
    final Stream<DynamicTest> receiverWith0HBARsPaysNFT() {
        final var account = "account";
        final var receiver = "receiver";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0).balance(0L),
                tokenAssociate(account, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 6L).between(OWNER, account)),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 6L).between(account, receiver))
                        .payingWith(account),
                tokenCancelAirdrop(pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 6L))
                        .signedBy(account)
                        .payingWith(receiver)
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE));
    }

    @HapiTest
    @DisplayName("when receiver with enough HBARs pays for FT")
    final Stream<DynamicTest> receiverWithEnoughHBARsPaysFT() {
        final var account = "account";
        final var receiver = "receiver";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0).balance(FIVE_HBARS),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account)),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(account, receiver))
                        .payingWith(account),
                tokenCancelAirdrop(pendingAirdrop(account, receiver, FUNGIBLE_TOKEN))
                        .signedBy(account)
                        .payingWith(receiver));
    }

    @HapiTest
    @DisplayName("when receiver with enough HBARs pays for NFT")
    final Stream<DynamicTest> receiverWithEnoughHBARsPaysNFT() {
        final var account = "account";
        final var receiver = "receiver";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0).balance(FIVE_HBARS),
                tokenAssociate(account, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 7L).between(OWNER, account)),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 7L).between(account, receiver))
                        .payingWith(account),
                tokenCancelAirdrop(pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 7L))
                        .signedBy(account)
                        .payingWith(receiver));
    }

    @HapiTest
    @DisplayName("with two separate FT airdrops")
    final Stream<DynamicTest> twoSeparateFTAirdrops() {
        final var account = "account";
        final var receiver = "receiver";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account)),
                tokenAirdrop(moving(5, FUNGIBLE_TOKEN).between(account, receiver))
                        .payingWith(account),
                tokenAirdrop(moving(5, FUNGIBLE_TOKEN).between(account, receiver))
                        .payingWith(account),

                // We first cancel and see that the cancel is success. When we cancel the second time we verify that
                // the response code is INVALID_PENDING_AIRDROP_ID because we've cancelled the two airdrops
                tokenCancelAirdrop(pendingAirdrop(account, receiver, FUNGIBLE_TOKEN))
                        .payingWith(account),
                tokenCancelAirdrop(pendingAirdrop(account, receiver, FUNGIBLE_TOKEN))
                        .payingWith(account)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID));
    }

    @HapiTest
    @DisplayName("with multiple NFTs cancel one")
    final Stream<DynamicTest> multipleNFTsCancelOne() {
        final var account = "account";
        final var receiver = "receiver";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                tokenAssociate(account, NON_FUNGIBLE_TOKEN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 8L).between(OWNER, account)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 9L).between(OWNER, account)),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 8L).between(account, receiver))
                        .payingWith(account),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 9L).between(account, receiver))
                        .payingWith(account),
                tokenCancelAirdrop(pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 8L))
                        .payingWith(account),

                // When we cancel the first NFT we can't claim it. We can claim only the second one
                tokenClaimAirdrop(pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 8L))
                        .payingWith(receiver)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                tokenClaimAirdrop(pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 9L))
                        .payingWith(receiver));
    }

    @HapiTest
    @DisplayName("with FT and NFT to the same receiver")
    final Stream<DynamicTest> FTAndNFTToSameReceiver() {
        final var account = "account";
        final var receiver = "receiver";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                tokenAssociate(account, NON_FUNGIBLE_TOKEN),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 10L).between(OWNER, account)),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 10L).between(account, receiver))
                        .payingWith(account),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(account, receiver))
                        .payingWith(account),
                tokenCancelAirdrop(
                                pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 10L),
                                pendingAirdrop(account, receiver, FUNGIBLE_TOKEN))
                        .payingWith(account),
                tokenClaimAirdrop(pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 10L))
                        .payingWith(receiver)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                tokenClaimAirdrop(pendingAirdrop(account, receiver, FUNGIBLE_TOKEN))
                        .payingWith(receiver)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID));
    }

    @HapiTest
    @DisplayName("with FT and NFT to different receivers")
    final Stream<DynamicTest> FTAndNFTToDifferentReceivers() {
        final var account = "account";
        final var receiver = "receiver";
        final var receiver2 = "receiver2";
        return hapiTest(
                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                cryptoCreate(receiver2).maxAutomaticTokenAssociations(0),
                tokenAssociate(account, NON_FUNGIBLE_TOKEN),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(11, FUNGIBLE_TOKEN).between(OWNER, account)),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 11L).between(OWNER, account)),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 11L).between(account, receiver))
                        .payingWith(account),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(account, receiver2))
                        .payingWith(account),
                tokenCancelAirdrop(
                                pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 11L),
                                pendingAirdrop(account, receiver2, FUNGIBLE_TOKEN))
                        .payingWith(account),
                tokenClaimAirdrop(pendingNFTAirdrop(account, receiver, NON_FUNGIBLE_TOKEN, 11L))
                        .payingWith(receiver)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                tokenClaimAirdrop(pendingAirdrop(account, receiver2, FUNGIBLE_TOKEN))
                        .payingWith(receiver2)
                        .hasKnownStatus(INVALID_PENDING_AIRDROP_ID));
    }

    @HapiTest
    @DisplayName("when treasury is changed")
    @Tag(MATS)
    final Stream<DynamicTest> treasuryIsChanged() {
        final var account = "account";
        final var receiver = "receiver";
        final var newTreasury = "newTreasury";
        final var nft = "nft";
        final var nftAdminKey = "nftAdminKey";
        return hapiTest(
                newKeyNamed(nftAdminKey),
                tokenCreate(nft)
                        .treasury(OWNER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .name(nft)
                        .supplyKey(nftAdminKey)
                        .adminKey(nftAdminKey),
                mintToken(nft, List.of(ByteString.copyFromUtf8("a"))),

                // setup initial accounts
                cryptoCreate(account),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                cryptoCreate(newTreasury),
                tokenAssociate(account, nft),
                tokenAssociate(newTreasury, nft),
                cryptoTransfer(movingUnique(nft, 1L).between(OWNER, account)),
                tokenAirdrop(movingUnique(nft, 1L).between(account, receiver)).payingWith(account),
                tokenUpdate(nft).treasury(newTreasury).signedByPayerAnd(nftAdminKey, newTreasury),

                // When treasury is changed it can't cancel the airdrop. Only the sender can.
                tokenCancelAirdrop(pendingNFTAirdrop(account, receiver, nft, 1L))
                        .signedBy(newTreasury)
                        .hasPrecheck(INVALID_SIGNATURE),
                tokenCancelAirdrop(pendingNFTAirdrop(account, receiver, nft, 1L))
                        .payingWith(account));
    }
}
