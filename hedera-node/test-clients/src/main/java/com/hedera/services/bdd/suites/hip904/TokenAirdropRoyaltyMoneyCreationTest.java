// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class TokenAirdropRoyaltyMoneyCreationTest {

    private static final String NFT_KEY = "nftKey";
    private static final long PRICE = 100L;

    @HapiTest
    final Stream<DynamicTest> airdropRoyaltySelfDealing() {
        final var seller = "seller";
        final var buyer = "buyer";
        final var receiver = buyer; // buyer plays the NFT-receiver role
        final var collector = receiver; // ...and the fee-collector role too
        final var paymentTreasury = "paymentTreasury";
        final var nftTreasury = "nftTreasury";
        final var paymentToken = "paymentToken";
        final var royaltyNft = "royaltyNft";

        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(seller).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(buyer).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(paymentTreasury),
                cryptoCreate(nftTreasury),
                tokenCreate(paymentToken).treasury(paymentTreasury).initialSupply(PRICE),
                tokenAssociate(seller, paymentToken),
                tokenAssociate(buyer, paymentToken),
                cryptoTransfer(moving(PRICE, paymentToken).between(paymentTreasury, buyer)),
                tokenCreate(royaltyNft)
                        .treasury(nftTreasury)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeWithFallback(1, 1, fixedHbarFeeInheritingRoyaltyCollector(1), collector)),
                tokenAssociate(seller, royaltyNft),
                tokenAssociate(receiver, royaltyNft),
                mintToken(royaltyNft, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(royaltyNft, 1L).between(nftTreasury, seller)),
                getAccountBalance(buyer).hasTokenBalance(paymentToken, PRICE).logged(),
                getAccountBalance(seller).hasTokenBalance(paymentToken, 0),

                // buyer "pays" seller 100 for the NFT; the NFT receiver IS the fee collector.
                tokenAirdrop(
                                moving(PRICE, paymentToken).between(buyer, seller),
                                movingUnique(royaltyNft, 1L).between(seller, receiver))
                        .via("theAirdrop")
                        .signedByPayerAnd(buyer, seller),
                getTxnRecord("theAirdrop").logged(),

                // seller correctly receives nothing (100% royalty). buyer was never
                // actually debited, yet still collects the royalty credit: 100 -> 200.
                getAccountBalance(seller).hasTokenBalance(paymentToken, 0),
                getAccountBalance(buyer)
                        .hasTokenBalance(paymentToken, 2 * PRICE)
                        .logged(),
                getTokenInfo(paymentToken).hasTotalSupply(PRICE));
    }
}
