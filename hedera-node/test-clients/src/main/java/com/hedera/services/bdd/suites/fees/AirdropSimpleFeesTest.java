// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNonfungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCancelAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenCancelAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop;
import com.hedera.services.bdd.suites.hip904.TokenAirdropBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
public class AirdropSimpleFeesTest extends TokenAirdropBase {
    private static final double BASE_AIRDROP_FEE = 0.05;
    private static final double BASE_CLAIM_AIRDROP_FEE = 0.001;
    private static final double BASE_CANCEL_AIRDROP_FEE = 0.001;
    private static final double TOKEN_ASSOCIATION_FEE = 0.05;
    private static final double NFT_AIRDROP_FEE = 0.1;

    @HapiTest
    @Tag(MATS)
    @DisplayName("charge association fee for FT correctly")
    final Stream<DynamicTest> chargeAirdropAssociationFeeForFT() {
        var receiver = "receiver";
        return hapiTest(
                cryptoCreate("owner").balance(ONE_HUNDRED_HBARS),
                newKeyNamed("freezeKey"),
                tokenCreate("FT")
                        .treasury("owner")
                        .tokenType(FUNGIBLE_COMMON)
                        .freezeKey("freezeKey")
                        .initialSupply(1000L),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                tokenAirdrop(moving(1, "FT").between("owner", receiver))
                        .payingWith("owner")
                        .via("airdrop"),
                tokenAirdrop(moving(1, "FT").between("owner", receiver))
                        .payingWith("owner")
                        .via("second airdrop"),
                validateChargedUsd("airdrop", BASE_AIRDROP_FEE + TOKEN_ASSOCIATION_FEE),
                validateChargedUsd("second airdrop", BASE_AIRDROP_FEE));
    }

    @HapiTest
    @Tag(MATS)
    @DisplayName("charge association fee for NFT correctly")
    final Stream<DynamicTest> chargeAirdropAssociationFeeForNFT() {
        return hapiTest(
                cryptoCreate("owner").balance(ONE_HUNDRED_HBARS),
                newKeyNamed("nftSupplyKey"),
                tokenCreate("NFT")
                        .treasury("owner")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey("nftSupplyKey"),
                mintToken(
                        "NFT",
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                cryptoCreate("receiver").maxAutomaticTokenAssociations(0),
                tokenAirdrop(movingUnique("NFT", 1).between("owner", "receiver"))
                        .payingWith("owner")
                        .via("airdrop"),
                tokenAirdrop(movingUnique("NFT", 2).between("owner", "receiver"))
                        .payingWith("owner")
                        .via("second airdrop"),
                validateChargedUsd("airdrop", NFT_AIRDROP_FEE),
                validateChargedUsd("second airdrop", NFT_AIRDROP_FEE));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> claimFungibleTokenAirdropBaseFee() {
        final var nftSupplyKey = "nftSupplyKey";
        final var receiver = "receiver";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(receiver).balance(ONE_HUNDRED_HBARS),
                // do pending airdrop
                newKeyNamed(nftSupplyKey),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .treasury("owner")
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .supplyKey(nftSupplyKey),
                mintToken(
                        NON_FUNGIBLE_TOKEN,
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver))
                        .payingWith(OWNER),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER, receiver))
                        .payingWith(OWNER),

                // do claim
                tokenClaimAirdrop(
                        HapiTokenClaimAirdrop.pendingAirdrop(OWNER, receiver, FUNGIBLE_TOKEN),
                        HapiTokenClaimAirdrop.pendingNFTAirdrop(OWNER, receiver, NON_FUNGIBLE_TOKEN, 1))
                        .payingWith("receiver")
                        .via("claimTxn"), // assert txn record
                getTxnRecord("claimTxn")
                        .hasPriority(recordWith()
                                .tokenTransfers(includingFungibleMovement(
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver)))
                                .tokenTransfers(includingNonfungibleMovement(
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER, receiver)))),
                validateChargedUsd("claimTxn", BASE_CLAIM_AIRDROP_FEE, 3),
                // assert balance fungible tokens
                getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, 10),
                // assert balances NFT
                getAccountBalance(receiver).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),
                // assert token associations
                getAccountInfo(receiver).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                getAccountInfo(receiver).hasToken(relationshipWith(NON_FUNGIBLE_TOKEN))));
    }

    @HapiTest
    @Tag(MATS)
    @DisplayName("cancel airdrop FT happy path")
    final Stream<DynamicTest> cancelAirdropFungibleTokenHappyPath() {
        final var account = "account";
        return hapiTest(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(account),
                newKeyNamed(FUNGIBLE_FREEZE_KEY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .treasury(OWNER)
                        .tokenType(FUNGIBLE_COMMON)
                        .freezeKey(FUNGIBLE_FREEZE_KEY)
                        .initialSupply(1000L),
                tokenAssociate(account, FUNGIBLE_TOKEN),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account)),
                cryptoCreate(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).maxAutomaticTokenAssociations(0),
                // Create an airdrop in pending state
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(account)
                        .via("airdrop"),

                // Verify that a pending state is created and the correct usd is charged
                getTxnRecord("airdrop")
                        .hasPriority(recordWith()
                                .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, FUNGIBLE_TOKEN)
                                        .between(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS)))),
                getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                validateChargedUsd("airdrop", BASE_AIRDROP_FEE+TOKEN_ASSOCIATION_FEE, 1),

                // Cancel the airdrop
                tokenCancelAirdrop(pendingAirdrop(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                        .payingWith(account)
                        .via("cancelAirdrop"),

                // Verify that the receiver doesn't have the token
                getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                validateChargedUsd("cancelAirdrop", BASE_CANCEL_AIRDROP_FEE, 3));
    }
}
