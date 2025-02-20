// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.IntStream;

public class TokenAirdropBase {

    protected static final String OWNER = "owner";
    // receivers
    protected static final String RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS = "receiverWithUnlimitedAutoAssociations";
    protected static final String RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS = "receiverWithFreeAutoAssociations";
    protected static final String RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS = "receiverWithoutFreeAutoAssociations";
    protected static final String RECEIVER_WITH_0_AUTO_ASSOCIATIONS = "receiverWith0AutoAssociations";
    protected static final String ASSOCIATED_RECEIVER = "associatedReceiver";
    // tokens
    protected static final String FUNGIBLE_TOKEN = "fungibleToken";
    protected static final String FUNGIBLE_TOKEN2 = "fungibleToken2";
    protected static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    protected static final String FUNGIBLE_FREEZE_KEY = "fungibleTokenFreeze";
    protected static final String NFT_FOR_CONTRACT_TESTS = "nonFungibleTokens";
    // tokens with custom fees
    protected static final String FT_WITH_HBAR_FIXED_FEE = "fungibleTokenWithHbarCustomFee";
    protected static final String FT_WITH_HTS_FIXED_FEE = "fungibleTokenWithHtsCustomFee";
    protected static final String FT_WITH_FRACTIONAL_FEE = "fungibleTokenWithFractionalFee";
    protected static final String FT_WITH_FRACTIONAL_FEE_2 = "fungibleTokenWithFractionalFee2";
    protected static final String FT_WITH_FRACTIONAL_FEE_NET_OF_TRANSFERS = "ftWithFractionalFeeNetOfTransfers";
    protected static final String NFT_WITH_HTS_FIXED_FEE = "NftWithHtsFixedFee";
    protected static final String NFT_WITH_ROYALTY_FEE = "NftWithRoyaltyFee";
    protected static final String DENOM_TOKEN = "denomToken";
    protected static final String HTS_COLLECTOR = "htsCollector";
    protected static final String HBAR_COLLECTOR = "hbarCollector";
    protected static final String TREASURY_FOR_CUSTOM_FEE_TOKENS = "treasuryForCustomFeeTokens";
    protected static final String TREASURY_AS_SENDER = "treasuryAsSender";
    protected static final String TREASURY_AS_SENDER_TOKEN = "treasuryAsSenderToken";
    protected static final String OWNER_OF_TOKENS_WITH_CUSTOM_FEES = "ownerOfTokensWithCustomFees";

    // all collectors exempt
    protected static final String NFT_ALL_COLLECTORS_EXEMPT_OWNER = "nftAllCollectorsExemptOwner";
    protected static final String NFT_ALL_COLLECTORS_EXEMPT_RECEIVER = "nftAllCollectorsExemptReceiver";
    protected static final String NFT_ALL_COLLECTORS_EXEMPT_COLLECTOR = "nftAllCollectorsExemptCollector";
    protected static final String NFT_ALL_COLLECTORS_EXEMPT_TOKEN = "nftAllCollectorsExemptToken";
    protected static final String NFT_ALL_COLLECTORS_EXEMPT_KEY = "nftAllCollectorsExemptKey";

    protected static final String FT_ALL_COLLECTORS_EXEMPT_OWNER = "ftAllCollectorsExemptOwner";
    protected static final String FT_ALL_COLLECTORS_EXEMPT_RECEIVER = "ftAllCollectorsExemptReceiver";
    protected static final String FT_ALL_COLLECTORS_EXEMPT_COLLECTOR = "ftAllCollectorsExemptCollector";
    protected static final String FT_ALL_COLLECTORS_EXEMPT_TOKEN = "ftAllCollectorsExemptToken";

    // owner, receivers with 0 auto-associations, collectors and tokens for multiple tokens with all custom fees airdrop
    // treasury
    protected static final String TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS = "treasuryForAllCustomFeeTokens";
    // owner
    protected static final String OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES = "ownerOfTokensWithAllCustomFees";
    // receivers
    protected static final String RECEIVER_HBAR_FEE = "receiverHbarFee";
    protected static final String RECEIVER_FRACTIONAL_FEE = "receiverFractionalFee";
    protected static final String RECEIVER_HTS_FEE_SECOND = "receiverHtsFeeSecond";
    protected static final String RECEIVER_HTS_FEE = "receiverHtsFee";
    protected static final String RECEIVER_NFT_HBAR_FEE = "receiverNftHbarFee";
    protected static final String RECEIVER_NFT_HTS_FEE = "receiverNftHtsFee";
    protected static final String RECEIVER_NFT_ROYALTY_FEE = "receiverNftRoyaltyFee";
    // collectors
    protected static final String FT_HBAR_COLLECTOR = "ftHbarCollector";
    protected static final String FT_FRACTIONAL_COLLECTOR = "ftFractionalCollector";
    protected static final String FT_WITH_HTS_FEE_COLLECTOR = "ftWithHtsCollector";
    protected static final String NFT_HBAR_COLLECTOR = "nftHBarCollector";
    protected static final String NFT_HTS_COLLECTOR = "nftHtsCollector";
    protected static final String NFT_ROYALTY_FEE_COLLECTOR = "nftRoyaltyFeeCollector";
    // FT tokens
    protected static final String FT_WITH_HBAR_FEE = "ftWithHbarFee";
    protected static final String FT_WITH_FRACTIONAL_FEE_WITH_NET_OF_TRANSFERS =
            "ftWithFractionalFeeWithNetOfTransfers";
    protected static final String FT_WITH_HTS_FEE = "ftWithHtsFee";
    protected static final String DENOM_TOKEN_HTS = "denomTokenHts";
    // NFT tokens
    protected static final String NFT_WITH_HBAR_FEE = "nftWithHBarFee";
    protected static final String NFT_WITH_HTS_FEE = "nftWithHtsFee";
    protected static final String NFT_WITH_ROYALTY_FEE_NO_FALLBACK = "nftWithRoyaltyFeeNoFallback";

    protected TokenMovement defaultMovementOfToken(String token) {
        return moving(10, token).between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS);
    }

    protected TokenMovement moveFungibleTokensTo(String receiver) {
        return moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver);
    }

    /**
     * Create Fungible token and set up all scenario receivers
     * - receiver with unlimited auto associations
     * - associated receiver
     * - receiver with 0 auto associations
     * - receiver with free auto associations
     * - receiver with positive number associations, but without fee ones
     *
     * @return array of operations
     */
    public static SpecOperation[] setUpTokensAndAllReceivers() {
        var nftSupplyKey = "nftSupplyKey";
        final var t = new ArrayList<SpecOperation>(List.of(
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                // base tokens
                newKeyNamed(FUNGIBLE_FREEZE_KEY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .treasury(OWNER)
                        .tokenType(FUNGIBLE_COMMON)
                        .freezeKey(FUNGIBLE_FREEZE_KEY)
                        .initialSupply(1000L),
                tokenCreate(FUNGIBLE_TOKEN2)
                        .treasury(OWNER)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L),
                tokenCreate("dummy").treasury(OWNER).tokenType(FUNGIBLE_COMMON).initialSupply(100L),
                newKeyNamed(nftSupplyKey),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .treasury(OWNER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .name(NON_FUNGIBLE_TOKEN)
                        .supplyKey(nftSupplyKey),
                mintToken(
                        NON_FUNGIBLE_TOKEN,
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                mintToken(
                        NON_FUNGIBLE_TOKEN,
                        IntStream.range(10, 20)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                tokenCreate(NFT_FOR_CONTRACT_TESTS)
                        .treasury(OWNER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .name(NON_FUNGIBLE_TOKEN)
                        .supplyKey(nftSupplyKey),
                mintToken(
                        NFT_FOR_CONTRACT_TESTS,
                        IntStream.range(0, 10)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                mintToken(
                        NFT_FOR_CONTRACT_TESTS,
                        IntStream.range(10, 20)
                                .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                .toList()),
                // all kind of receivers
                cryptoCreate(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS).maxAutomaticTokenAssociations(-1),
                cryptoCreate(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).maxAutomaticTokenAssociations(0),
                cryptoCreate(RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS).maxAutomaticTokenAssociations(100),
                cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS).maxAutomaticTokenAssociations(1),
                // fill the auto associate slot
                cryptoTransfer(moving(10, "dummy").between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)),
                cryptoCreate(ASSOCIATED_RECEIVER),
                tokenAssociate(ASSOCIATED_RECEIVER, FUNGIBLE_TOKEN),
                tokenAssociate(ASSOCIATED_RECEIVER, NON_FUNGIBLE_TOKEN)));

        return t.toArray(new SpecOperation[0]);
    }

    protected static SpecOperation[] setUpTokensWithCustomFees(long tokenTotal, long hbarFee, long htsFee) {
        var nftWithCustomFeeSupplyKey = "nftWithCustomFeeSupplyKey";
        final var t = new ArrayList<SpecOperation>(List.of(
                // tokens with custom fees
                cryptoCreate(TREASURY_FOR_CUSTOM_FEE_TOKENS),
                cryptoCreate(HBAR_COLLECTOR).balance(0L),
                tokenCreate(FT_WITH_HBAR_FIXED_FEE)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHbarFee(hbarFee, HBAR_COLLECTOR)),
                cryptoCreate(HTS_COLLECTOR).balance(ONE_HUNDRED_HBARS),
                tokenCreate(DENOM_TOKEN)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                        .initialSupply(tokenTotal),
                tokenAssociate(HTS_COLLECTOR, DENOM_TOKEN),
                tokenCreate(FT_WITH_HTS_FIXED_FEE)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, DENOM_TOKEN, HTS_COLLECTOR)),
                tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                newKeyNamed(nftWithCustomFeeSupplyKey),
                tokenCreate(NFT_WITH_HTS_FIXED_FEE)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyKey(nftWithCustomFeeSupplyKey)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(htsFee, FT_WITH_HTS_FIXED_FEE, HTS_COLLECTOR)),
                mintToken(NFT_WITH_HTS_FIXED_FEE, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),
                tokenCreate(FT_WITH_FRACTIONAL_FEE)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                        .tokenType(FUNGIBLE_COMMON)
                        .withCustom(fractionalFee(1, 10L, 1L, OptionalLong.empty(), HTS_COLLECTOR))
                        .initialSupply(Long.MAX_VALUE)
                        .payingWith(HTS_COLLECTOR),
                tokenCreate(FT_WITH_FRACTIONAL_FEE_2)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                        .tokenType(FUNGIBLE_COMMON)
                        .withCustom(fractionalFee(1, 10L, 1L, OptionalLong.empty(), HTS_COLLECTOR))
                        .initialSupply(Long.MAX_VALUE)
                        .payingWith(HTS_COLLECTOR),
                tokenCreate(FT_WITH_FRACTIONAL_FEE_NET_OF_TRANSFERS)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                        .tokenType(FUNGIBLE_COMMON)
                        .withCustom(fractionalFeeNetOfTransfers(1, 10L, 1L, OptionalLong.of(100), HTS_COLLECTOR))
                        .initialSupply(Long.MAX_VALUE)
                        .payingWith(HTS_COLLECTOR),
                tokenCreate(NFT_WITH_ROYALTY_FEE)
                        .maxSupply(100L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(nftWithCustomFeeSupplyKey)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                        .withCustom(
                                royaltyFeeWithFallback(1, 2, fixedHbarFeeInheritingRoyaltyCollector(1), HTS_COLLECTOR)),
                tokenAssociate(HTS_COLLECTOR, NFT_WITH_ROYALTY_FEE),
                mintToken(NFT_WITH_ROYALTY_FEE, List.of(ByteStringUtils.wrapUnsafely("meta1".getBytes()))),

                // token treasury as sender
                cryptoCreate(TREASURY_AS_SENDER),
                tokenCreate(TREASURY_AS_SENDER_TOKEN)
                        .treasury(TREASURY_AS_SENDER)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(tokenTotal)
                        .withCustom(fixedHtsFee(htsFee, DENOM_TOKEN, HTS_COLLECTOR)),
                tokenAssociate(HTS_COLLECTOR, TREASURY_AS_SENDER_TOKEN),

                // all collectors exempt setup
                cryptoCreate(NFT_ALL_COLLECTORS_EXEMPT_OWNER),
                cryptoCreate(NFT_ALL_COLLECTORS_EXEMPT_RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(NFT_ALL_COLLECTORS_EXEMPT_COLLECTOR).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(NFT_ALL_COLLECTORS_EXEMPT_KEY),
                tokenCreate(NFT_ALL_COLLECTORS_EXEMPT_TOKEN)
                        .maxSupply(100L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(NFT_ALL_COLLECTORS_EXEMPT_KEY)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                        // setting a custom fee with allCollectorsExempt=true(see HIP-573)
                        .withCustom(royaltyFeeWithFallback(
                                1,
                                2,
                                fixedHbarFeeInheritingRoyaltyCollector(1),
                                NFT_ALL_COLLECTORS_EXEMPT_COLLECTOR,
                                true))
                        // set the receiver as a custom fee collector
                        .withCustom(royaltyFeeWithFallback(
                                1, 2, fixedHbarFeeInheritingRoyaltyCollector(1), NFT_ALL_COLLECTORS_EXEMPT_RECEIVER)),
                tokenAssociate(NFT_ALL_COLLECTORS_EXEMPT_OWNER, NFT_ALL_COLLECTORS_EXEMPT_TOKEN),
                tokenAssociate(NFT_ALL_COLLECTORS_EXEMPT_RECEIVER, NFT_ALL_COLLECTORS_EXEMPT_TOKEN),
                cryptoCreate(FT_ALL_COLLECTORS_EXEMPT_OWNER),
                cryptoCreate(FT_ALL_COLLECTORS_EXEMPT_RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FT_ALL_COLLECTORS_EXEMPT_COLLECTOR).balance(0L),
                tokenCreate(FT_ALL_COLLECTORS_EXEMPT_TOKEN)
                        .initialSupply(100L)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                        // setting a custom fee with allCollectorsExempt=true(see HIP-573)
                        .withCustom(fixedHbarFee(100, FT_ALL_COLLECTORS_EXEMPT_COLLECTOR, true))
                        // set the receiver as a custom fee collector
                        .withCustom(fixedHbarFee(100, FT_ALL_COLLECTORS_EXEMPT_RECEIVER)),
                tokenAssociate(FT_ALL_COLLECTORS_EXEMPT_OWNER, FT_ALL_COLLECTORS_EXEMPT_TOKEN),
                tokenAssociate(FT_ALL_COLLECTORS_EXEMPT_RECEIVER, FT_ALL_COLLECTORS_EXEMPT_TOKEN)));

        // mint 99 NFTs
        for (int i = 0; i < 99; i++) {
            t.add(mintToken(NFT_WITH_ROYALTY_FEE, List.of(ByteStringUtils.wrapUnsafely(("meta" + i).getBytes()))));
        }

        return t.toArray(new SpecOperation[0]);
    }

    /**
     * Create Fungible and Non-Fungible tokens and set up all scenario receivers and fee collector accounts
     * - all receivers are with 0 auto associations
     * - Fungible tokens with hBar, Fractional and HTS fees
     * - Non-Fungible tokens with hBar, HTS and Royalty fees
     * - different fee collector account for each token
     *
     * @return array of operations
     */
    protected static SpecOperation[] createAccountsAndTokensWithAllCustomFees(
            final long tokenTotal, final long hbarFee, final long htsFee) {
        var nftWithCustomFeeSupplyKey = "nftWithCustomFeeSupplyKey";
        final var initialBalance = 100 * ONE_HUNDRED_HBARS;
        final var t = new ArrayList<SpecOperation>(List.of(
                // create owner and receiver accounts
                cryptoCreate(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS),
                cryptoCreate(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES).balance(initialBalance),
                cryptoCreate(RECEIVER_HBAR_FEE).maxAutomaticTokenAssociations(0),
                cryptoCreate(RECEIVER_FRACTIONAL_FEE).maxAutomaticTokenAssociations(0),
                cryptoCreate(RECEIVER_HTS_FEE).maxAutomaticTokenAssociations(0),
                cryptoCreate(RECEIVER_NFT_HBAR_FEE).maxAutomaticTokenAssociations(0),
                cryptoCreate(RECEIVER_NFT_HTS_FEE).maxAutomaticTokenAssociations(0),
                cryptoCreate(RECEIVER_NFT_ROYALTY_FEE).maxAutomaticTokenAssociations(0),
                cryptoCreate(RECEIVER_HTS_FEE_SECOND).maxAutomaticTokenAssociations(0),
                // create collector accounts
                cryptoCreate(FT_HBAR_COLLECTOR).balance(0L),
                cryptoCreate(FT_FRACTIONAL_COLLECTOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FT_WITH_HTS_FEE_COLLECTOR).balance(0L),
                cryptoCreate(NFT_HBAR_COLLECTOR).balance(0L),
                cryptoCreate(NFT_HTS_COLLECTOR).balance(0L),
                cryptoCreate(NFT_ROYALTY_FEE_COLLECTOR).balance(0L),
                // create FT with HBAR fee
                tokenCreate(FT_WITH_HBAR_FEE)
                        .treasury(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS)
                        .tokenType(FUNGIBLE_COMMON)
                        .withCustom(fixedHbarFee(hbarFee, FT_HBAR_COLLECTOR)),
                // create FT with Fractional fee and Net of transfers
                tokenCreate(FT_WITH_FRACTIONAL_FEE_WITH_NET_OF_TRANSFERS)
                        .treasury(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS)
                        .tokenType(FUNGIBLE_COMMON)
                        .withCustom(
                                fractionalFeeNetOfTransfers(1, 10L, 1L, OptionalLong.of(100), FT_FRACTIONAL_COLLECTOR))
                        .initialSupply(Long.MAX_VALUE)
                        .payingWith(FT_FRACTIONAL_COLLECTOR),
                // create denom token for FT with HTS fee
                tokenCreate(DENOM_TOKEN_HTS)
                        .treasury(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS)
                        .initialSupply(tokenTotal),
                // create FT with HTS fee
                tokenAssociate(FT_WITH_HTS_FEE_COLLECTOR, DENOM_TOKEN_HTS),
                tokenCreate(FT_WITH_HTS_FEE)
                        .treasury(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS)
                        .tokenType(FUNGIBLE_COMMON)
                        .withCustom(fixedHtsFee(htsFee, DENOM_TOKEN_HTS, FT_WITH_HTS_FEE_COLLECTOR))
                        .initialSupply(tokenTotal),
                // create NFT with HBar fixed fee
                newKeyNamed(nftWithCustomFeeSupplyKey),
                tokenCreate(NFT_WITH_HBAR_FEE)
                        .treasury(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(nftWithCustomFeeSupplyKey)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHbarFee(hbarFee, NFT_HBAR_COLLECTOR)),
                mintToken(
                        NFT_WITH_HBAR_FEE, List.of(ByteStringUtils.wrapUnsafely("tokenWithHbarCustomFee".getBytes()))),
                // create NFT with HTS fixed fee - two layers of fees
                tokenAssociate(NFT_HTS_COLLECTOR, FT_WITH_HTS_FEE),
                newKeyNamed(nftWithCustomFeeSupplyKey),
                tokenCreate(NFT_WITH_HTS_FEE)
                        .treasury(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(nftWithCustomFeeSupplyKey)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(fixedHtsFee(htsFee, FT_WITH_HTS_FEE, NFT_HTS_COLLECTOR)),
                mintToken(NFT_WITH_HTS_FEE, List.of(ByteStringUtils.wrapUnsafely("tokenWithHtsCustomFee".getBytes()))),
                // create NFT with Royalty fee no fallback
                newKeyNamed(nftWithCustomFeeSupplyKey),
                tokenCreate(NFT_WITH_ROYALTY_FEE_NO_FALLBACK)
                        .treasury(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(nftWithCustomFeeSupplyKey)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .withCustom(royaltyFeeNoFallback(1, 2L, NFT_ROYALTY_FEE_COLLECTOR)),
                mintToken(
                        NFT_WITH_ROYALTY_FEE_NO_FALLBACK,
                        List.of(ByteStringUtils.wrapUnsafely("tokenWithRoyaltyFee".getBytes()))),
                // create owner of tokens with all kinds of custom fees and associate it to the tokens
                cryptoCreate(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES).balance(initialBalance),
                // HBAR fee
                tokenAssociate(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, FT_WITH_HBAR_FEE),
                cryptoTransfer(moving(1000, FT_WITH_HBAR_FEE)
                        .between(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES)),
                // FRACTIONAL fee
                tokenAssociate(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, FT_WITH_FRACTIONAL_FEE_WITH_NET_OF_TRANSFERS),
                cryptoTransfer(moving(1000, FT_WITH_FRACTIONAL_FEE_WITH_NET_OF_TRANSFERS)
                        .between(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES)),
                // HTS fee
                tokenAssociate(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, FT_WITH_HTS_FEE),
                tokenAssociate(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, DENOM_TOKEN_HTS),
                cryptoTransfer(
                        moving(1000, FT_WITH_HTS_FEE)
                                .between(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES),
                        moving(tokenTotal, DENOM_TOKEN_HTS)
                                .between(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES)),
                // NFT with HBAR fee
                tokenAssociate(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, NFT_WITH_HBAR_FEE),
                cryptoTransfer(movingUnique(NFT_WITH_HBAR_FEE, 1L)
                        .between(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES)),
                // NFT with HTS fee
                tokenAssociate(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, NFT_WITH_HTS_FEE),
                cryptoTransfer(movingUnique(NFT_WITH_HTS_FEE, 1L)
                        .between(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES)),
                // NFT with Royalty fee no fallback
                tokenAssociate(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, NFT_WITH_ROYALTY_FEE_NO_FALLBACK),
                cryptoTransfer(movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L)
                        .between(TREASURY_FOR_ALL_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES))));
        return t.toArray(new SpecOperation[0]);
    }

    protected static SpecOperation[] setUpEntitiesPreHIP904() {
        final var validAlias = "validAlias";
        final var aliasTwo = "alias2.0";
        final var validAliasForAirdrop = "validAliasForAirdrop";
        final var sponsor = "sponsor";
        final var t = new ArrayList<SpecOperation>(List.of(
                // create hollow account with 0 auto associations
                cryptoCreate(sponsor).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(validAlias).shape(SECP_256K1_SHAPE),
                newKeyNamed(aliasTwo).shape(SECP_256K1_SHAPE),
                newKeyNamed(validAliasForAirdrop).shape(SECP_256K1_SHAPE)));
        t.addAll(Arrays.stream(createHollowAccountFrom(validAlias)).toList());
        t.addAll(Arrays.stream(createHollowAccountFrom(aliasTwo)).toList());
        t.addAll(Arrays.stream(createHollowAccountFrom(validAliasForAirdrop)).toList());
        t.add(withOpContext((spec, opLog) -> updateSpecFor(spec, validAlias)));
        t.add(withOpContext((spec, opLog) -> updateSpecFor(spec, aliasTwo)));
        t.add(withOpContext((spec, opLog) -> updateSpecFor(spec, validAliasForAirdrop)));
        return t.toArray(new SpecOperation[0]);
    }

    /**
     * Create and mint NFT and set up all scenario receivers
     * - receiver with unlimited auto associations
     * - associated receiver
     * - receiver with 0 auto associations
     * - receiver with free auto associations
     * - receiver with positive number associations, but without fee ones
     *
     * @return array of operations
     */
    protected HapiTokenCreate createTokenWithName(String tokenName) {
        return tokenCreate(tokenName).tokenType(TokenType.FUNGIBLE_COMMON).treasury(OWNER);
    }

    protected SpecOperation[] deployMutableContract(String name, int maxAutoAssociations) {
        var t = List.of(
                newKeyNamed(name),
                uploadInitCode(name),
                contractCreate(name)
                        .maxAutomaticTokenAssociations(maxAutoAssociations)
                        .adminKey(name)
                        .gas(500_000L));

        return t.toArray(new SpecOperation[0]);
    }
}
