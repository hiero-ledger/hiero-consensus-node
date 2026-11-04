// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.royaltyFeeWithoutFallbackInSchedule;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ROYALTY_FRACTION_CANNOT_EXCEED_ONE;

import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.junit.HapiTest;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Regression coverage for the royalty fraction sum check. Each royalty fraction on an NFT was
 * already capped at one individually, but nothing capped their sum, so a token could carry several
 * royalties totalling more than the exchanged value and charge the excess against the NFT sender's
 * own balance. Adapted from the reproduction tests supplied with the report.
 */
@Tag(CRYPTO)
public class RoyaltyFractionSumTest {
    private static final String NFT_KEY = "nftKey";
    private static final String FEE_SCHEDULE_KEY = "feeScheduleKey";
    private static final String NFT = "nft";
    private static final String COLLECTOR_A = "collectorA";
    private static final String COLLECTOR_B = "collectorB";
    private static final String TREASURY = "treasury";
    private static final String SELLER = "seller";
    private static final String BUYER = "buyer";

    /**
     * Every fraction here is individually at most one, so the per-fee check accepts each of them;
     * only their sum is out of range.
     */
    @HapiTest
    final Stream<DynamicTest> royaltyFractionsSummingAboveOneAreRejectedAtCreate() {
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(COLLECTOR_A),
                cryptoCreate(COLLECTOR_B),
                cryptoCreate(TREASURY),
                // 60% + 90%
                tokenCreate(NFT)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(6, 10, COLLECTOR_A))
                        .withCustom(royaltyFeeNoFallback(9, 10, COLLECTOR_B))
                        .hasKnownStatus(ROYALTY_FRACTION_CANNOT_EXCEED_ONE),
                // 100% + 100%
                tokenCreate(NFT)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 1, COLLECTOR_A))
                        .withCustom(royaltyFeeNoFallback(1, 1, COLLECTOR_B))
                        .hasKnownStatus(ROYALTY_FRACTION_CANNOT_EXCEED_ONE),
                // 50% + 50% + 0.1%, just over the line
                tokenCreate(NFT)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .withCustom(royaltyFeeNoFallback(1, 2, COLLECTOR_A))
                        .withCustom(royaltyFeeNoFallback(1, 2, COLLECTOR_B))
                        .withCustom(royaltyFeeNoFallback(1, 1000, COLLECTOR_A))
                        .hasKnownStatus(ROYALTY_FRACTION_CANNOT_EXCEED_ONE));
    }

    /**
     * The sum is also checked on fee schedule update, so a token acquired under a modest royalty
     * cannot later be raised above the whole exchanged value. A rejected update leaves the original
     * schedule in force, and an update to exactly one is still allowed.
     */
    @HapiTest
    final Stream<DynamicTest> royaltyFractionsSummingAboveOneAreRejectedAtFeeScheduleUpdate() {
        final var salePrice = 4 * ONE_HUNDRED_HBARS;
        final var sellerStartingBalance = 3 * ONE_HUNDRED_HBARS;
        return hapiTest(
                newKeyNamed(NFT_KEY),
                newKeyNamed(FEE_SCHEDULE_KEY),
                cryptoCreate(COLLECTOR_A).balance(0L),
                cryptoCreate(COLLECTOR_B).balance(0L),
                cryptoCreate(TREASURY),
                cryptoCreate(BUYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(SELLER).balance(sellerStartingBalance),
                tokenCreate(NFT)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        .feeScheduleKey(FEE_SCHEDULE_KEY)
                        .withCustom(royaltyFeeNoFallback(1, 10, COLLECTOR_A)),
                tokenAssociate(SELLER, NFT),
                tokenAssociate(BUYER, NFT),
                mintToken(NFT, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                // seller acquires the NFT while the royalty is 10%
                cryptoTransfer(movingUnique(NFT, 1L).between(TREASURY, SELLER)),
                // raising the total to 150% is rejected
                tokenFeeScheduleUpdate(NFT)
                        .signedByPayerAnd(FEE_SCHEDULE_KEY)
                        .withCustom(royaltyFeeNoFallback(6, 10, COLLECTOR_A))
                        .withCustom(royaltyFeeNoFallback(9, 10, COLLECTOR_B))
                        .hasKnownStatus(ROYALTY_FRACTION_CANNOT_EXCEED_ONE),
                // the original 10% schedule is untouched: a sale charges exactly 10%
                cryptoTransfer(
                        movingUnique(NFT, 1L).between(SELLER, BUYER),
                        movingHbar(salePrice).between(BUYER, SELLER)),
                getAccountDetails(COLLECTOR_A)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(salePrice / 10)),
                getAccountDetails(COLLECTOR_B)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(0L)),
                getAccountDetails(SELLER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(sellerStartingBalance + salePrice - salePrice / 10)),
                getTokenNftInfo(NFT, 1L).hasAccountID(BUYER),
                // exactly 100% across two collectors is still a valid schedule, and it lands
                tokenFeeScheduleUpdate(NFT)
                        .signedByPayerAnd(FEE_SCHEDULE_KEY)
                        .withCustom(royaltyFeeNoFallback(6, 10, COLLECTOR_A))
                        .withCustom(royaltyFeeNoFallback(4, 10, COLLECTOR_B)),
                getTokenInfo(NFT)
                        .hasCustom(royaltyFeeWithoutFallbackInSchedule(6, 10, COLLECTOR_A))
                        .hasCustom(royaltyFeeWithoutFallbackInSchedule(4, 10, COLLECTOR_B)));
    }

    /**
     * One hundred percent is the documented maximum, and it still works: two royalties summing to
     * exactly one are accepted, and a sale redirects the whole price to the collectors without ever
     * reaching into the seller's own balance.
     */
    @HapiTest
    final Stream<DynamicTest> royaltyFractionsSummingToExactlyOneAreStillCharged() {
        final var salePrice = 4 * ONE_HUNDRED_HBARS;
        final var sellerStartingBalance = 3 * ONE_HUNDRED_HBARS;
        return hapiTest(
                newKeyNamed(NFT_KEY),
                cryptoCreate(COLLECTOR_A).balance(0L),
                cryptoCreate(COLLECTOR_B).balance(0L),
                cryptoCreate(TREASURY),
                cryptoCreate(BUYER).balance(ONE_MILLION_HBARS),
                cryptoCreate(SELLER).balance(sellerStartingBalance),
                tokenCreate(NFT)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0)
                        .supplyKey(NFT_KEY)
                        .supplyType(TokenSupplyType.INFINITE)
                        // 60% + 40%
                        .withCustom(royaltyFeeNoFallback(6, 10, COLLECTOR_A))
                        .withCustom(royaltyFeeNoFallback(4, 10, COLLECTOR_B)),
                tokenAssociate(SELLER, NFT),
                tokenAssociate(BUYER, NFT),
                mintToken(NFT, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                cryptoTransfer(movingUnique(NFT, 1L).between(TREASURY, SELLER)),
                cryptoTransfer(
                        movingUnique(NFT, 1L).between(SELLER, BUYER),
                        movingHbar(salePrice).between(BUYER, SELLER)),
                // 240 + 160 = the full 400 hbar sale price, and not a tinybar more
                getAccountDetails(COLLECTOR_A)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(6 * salePrice / 10)),
                getAccountDetails(COLLECTOR_B)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(4 * salePrice / 10)),
                // seller received the price and paid all of it out, so ends exactly where it started
                getAccountDetails(SELLER)
                        .payingWith(GENESIS)
                        .has(accountDetailsWith().balance(sellerStartingBalance)),
                getTokenNftInfo(NFT, 1L).hasAccountID(BUYER));
    }
}
