// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCancelAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenReject;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdateNfts;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenCancelAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenCancelAirdrop.pendingNFTAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingNFT;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingToken;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.RECEIVER;
import static com.hedera.services.bdd.suites.hip904.TokenAirdropBase.setUpTokensAndAllReceivers;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.metadata;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of TokenServiceFeesSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm the fees are the same
@Tag(TOKEN)
@HapiTestLifecycle
public class AtomicTokenServiceFeesSuite {

    private static final double ALLOWED_DIFFERENCE_PERCENTAGE = 0.01;
    private static final double ALLOWED_DIFFERENCE = 5;
    private static final String TOKEN_TREASURY = "treasury";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungible";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String METADATA_KEY = "metadataKey";
    private static final String ADMIN_KEY = "adminKey";
    private static final String PAUSE_KEY = "pauseKey";
    private static final String TREASURE_KEY = "treasureKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String FUNGIBLE_FREEZE_KEY = "fungibleTokenFreeze";
    private static final String KYC_KEY = "kycKey";
    private static final String MULTI_KEY = "multiKey";
    private static final String NAME = "012345678912";
    private static final String ALICE = "alice";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";

    private static final String UNFREEZE = "unfreeze";

    private static final String CIVILIAN_ACCT = "civilian";
    private static final String UNIQUE_TOKEN = "nftType";
    private static final String BASE_TXN = "baseTxn";

    private static final String WIPE_KEY = "wipeKey";
    private static final String NFT_TEST_METADATA = " test metadata";
    private static final String FUNGIBLE_COMMON_TOKEN = "f";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String RECEIVER_WITH_0_AUTO_ASSOCIATIONS = "receiverWith0AutoAssociations";

    private static final String TOKEN_UPDATE_METADATA = "tokenUpdateMetadata";

    private static final double EXPECTED_NFT_WIPE_PRICE_USD = 0.001;
    private static final double EXPECTED_FREEZE_PRICE_USD = 0.001;
    private static final double EXPECTED_UNFREEZE_PRICE_USD = 0.001;
    private static final double EXPECTED_NFT_BURN_PRICE_USD = 0.001;
    private static final double EXPECTED_GRANTKYC_PRICE_USD = 0.001;
    private static final double EXPECTED_REVOKEKYC_PRICE_USD = 0.001;
    private static final double EXPECTED_NFT_MINT_PRICE_USD = 0.02;
    private static final double EXPECTED_FUNGIBLE_MINT_PRICE_USD = 0.001;
    private static final double EXPECTED_FUNGIBLE_REJECT_PRICE_USD = 0.001;
    private static final double EXPECTED_NFT_REJECT_PRICE_USD = 0.001;

    private static final double EXPECTED_ASSOCIATE_TOKEN_PRICE = 0.05;
    private static final double EXPECTED_NFT_UPDATE_PRICE = 0.001;

    private static final String OWNER = "owner";

    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String ATOMIC_BATCH = "atomicBatch";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    @DisplayName("charge association fee for FT correctly")
    final Stream<DynamicTest> chargeAirdropAssociationFeeForFT() {
        var receiver = "receiver";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                newKeyNamed(FUNGIBLE_FREEZE_KEY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .treasury(OWNER)
                        .tokenType(FUNGIBLE_COMMON)
                        .freezeKey(FUNGIBLE_FREEZE_KEY)
                        .initialSupply(1000L)
                        .payingWith(BATCH_OPERATOR),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, receiver))
                                .payingWith(OWNER)
                                .via("airdrop")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("airdrop", ATOMIC_BATCH, 0.1, 1));
    }

    @HapiTest
    @DisplayName("charge association fee for NFT correctly")
    final Stream<DynamicTest> chargeAirdropAssociationFeeForNFT() {
        var receiver = "receiver";
        var nftSupplyKey = "nftSupplyKey";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                newKeyNamed(nftSupplyKey),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .treasury(OWNER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .name(NON_FUNGIBLE_TOKEN)
                        .supplyKey(nftSupplyKey)
                        .payingWith(BATCH_OPERATOR),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                IntStream.range(0, 10)
                                        .mapToObj(a -> copyFromUtf8(String.valueOf(a)))
                                        .toList())
                        .payingWith(BATCH_OPERATOR),
                cryptoCreate(receiver).maxAutomaticTokenAssociations(0).payingWith(BATCH_OPERATOR),
                atomicBatch(tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER, receiver))
                                .payingWith(OWNER)
                                .via("airdrop")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("airdrop", ATOMIC_BATCH, 0.1, 1));
    }

    @HapiTest
    final Stream<DynamicTest> claimFungibleTokenAirdropBaseFee() {
        var nftSupplyKey = "nftSupplyKey";
        return hapiTest(flattened(
                setUpTokensAndAllReceivers(),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                // do pending airdrop
                newKeyNamed(nftSupplyKey),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .treasury(OWNER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .name(NON_FUNGIBLE_TOKEN)
                        .supplyKey(nftSupplyKey)
                        .payingWith(BATCH_OPERATOR),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                IntStream.range(0, 10)
                                        .mapToObj(a -> copyFromUtf8(String.valueOf(a)))
                                        .toList())
                        .payingWith(BATCH_OPERATOR),
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER))
                        .payingWith(OWNER),
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER, RECEIVER))
                        .payingWith(OWNER),
                atomicBatch(tokenClaimAirdrop(
                                        HapiTokenClaimAirdrop.pendingAirdrop(OWNER, RECEIVER, FUNGIBLE_TOKEN),
                                        HapiTokenClaimAirdrop.pendingNFTAirdrop(OWNER, RECEIVER, NON_FUNGIBLE_TOKEN, 1))
                                .payingWith(RECEIVER)
                                .via("claimTxn")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("claimTxn", ATOMIC_BATCH, 0.001, 1)));
    }

    @HapiTest
    @DisplayName("cancel airdrop FT happy path")
    final Stream<DynamicTest> cancelAirdropFungibleTokenHappyPath() {
        final var account = "account";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(account),
                newKeyNamed(FUNGIBLE_FREEZE_KEY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .treasury(OWNER)
                        .tokenType(FUNGIBLE_COMMON)
                        .freezeKey(FUNGIBLE_FREEZE_KEY)
                        .initialSupply(1000L)
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(account, FUNGIBLE_TOKEN).payingWith(BATCH_OPERATOR),
                cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, account))
                        .payingWith(BATCH_OPERATOR),
                cryptoCreate(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .maxAutomaticTokenAssociations(0)
                        .payingWith(BATCH_OPERATOR),
                // Create an airdrop in pending state
                tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(account)
                        .via("airdrop"),

                // Cancel the airdrop
                atomicBatch(tokenCancelAirdrop(
                                        pendingAirdrop(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(account)
                                .via("cancelAirdrop")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),

                // Verify that the receiver doesn't have the token
                getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                validateInnerTxnChargedUsd("cancelAirdrop", ATOMIC_BATCH, 0.001, 1));
    }

    @HapiTest
    @DisplayName("cancel airdrop NFT happy path")
    final Stream<DynamicTest> cancelAirdropNftHappyPath() {
        var nftSupplyKey = "nftSupplyKey";
        final var account = "account";
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                cryptoCreate(account).payingWith(BATCH_OPERATOR),
                newKeyNamed(nftSupplyKey),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .treasury(OWNER)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0L)
                        .name(NON_FUNGIBLE_TOKEN)
                        .supplyKey(nftSupplyKey)
                        .payingWith(BATCH_OPERATOR),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                IntStream.range(0, 10)
                                        .mapToObj(a -> copyFromUtf8(String.valueOf(a)))
                                        .toList())
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(account, NON_FUNGIBLE_TOKEN).payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, account))
                        .payingWith(BATCH_OPERATOR),
                cryptoCreate(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)
                        .maxAutomaticTokenAssociations(0)
                        .payingWith(BATCH_OPERATOR),
                // Create an airdrop in pending state
                tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                        .payingWith(account)
                        .via("airdrop"),

                // Cancel the airdrop
                atomicBatch(tokenCancelAirdrop(pendingNFTAirdrop(
                                        account, RECEIVER_WITH_0_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L))
                                .payingWith(account)
                                .via("cancelAirdrop")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),

                // Verify that the receiver doesn't have the token
                getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0),
                validateInnerTxnChargedUsd("cancelAirdrop", ATOMIC_BATCH, 0.001, 1));
    }

    private HapiSpecOperation[] baseCommonTokenRejectSetup() {
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
            newKeyNamed(MULTI_KEY),
            cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
            cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
            tokenCreate(FUNGIBLE_COMMON_TOKEN)
                    .initialSupply(1000L)
                    .adminKey(MULTI_KEY)
                    .supplyKey(MULTI_KEY)
                    .treasury(TOKEN_TREASURY)
                    .payingWith(BATCH_OPERATOR),
            tokenCreate(UNIQUE_TOKEN)
                    .initialSupply(0)
                    .adminKey(MULTI_KEY)
                    .supplyKey(MULTI_KEY)
                    .treasury(TOKEN_TREASURY)
                    .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                    .payingWith(BATCH_OPERATOR),
            mintToken(
                            UNIQUE_TOKEN,
                            List.of(
                                    metadata("nemo the fish"),
                                    metadata("garfield the cat"),
                                    metadata("snoopy the dog")))
                    .payingWith(BATCH_OPERATOR),
            tokenAssociate(ALICE, FUNGIBLE_COMMON_TOKEN, UNIQUE_TOKEN).payingWith(BATCH_OPERATOR),
        };
    }

    @HapiTest
    final Stream<DynamicTest> baseCommonTokenNFTTransferChargedAsExpected() {
        return hapiTest(flattened(
                baseCommonTokenRejectSetup(),
                atomicBatch(cryptoTransfer(movingUnique(UNIQUE_TOKEN, 1L).between(TOKEN_TREASURY, ALICE))
                                .payingWith(TOKEN_TREASURY)
                                .via("nftTransfer")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        "nftTransfer", ATOMIC_BATCH, EXPECTED_NFT_REJECT_PRICE_USD, ALLOWED_DIFFERENCE)));
    }

    @HapiTest
    final Stream<DynamicTest> baseCommonTokenFTTransferChargedAsExpected() {
        return hapiTest(flattened(
                baseCommonTokenRejectSetup(),
                atomicBatch(cryptoTransfer(moving(100, FUNGIBLE_COMMON_TOKEN).between(TOKEN_TREASURY, ALICE))
                                .payingWith(TOKEN_TREASURY)
                                .via("fungibleTransfer")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        "fungibleTransfer", ATOMIC_BATCH, EXPECTED_FUNGIBLE_REJECT_PRICE_USD, ALLOWED_DIFFERENCE)));
    }

    @HapiTest
    final Stream<DynamicTest> baseCommonTokenRejectFungibleChargedAsExpected() {
        return hapiTest(flattened(
                baseCommonTokenRejectSetup(),
                atomicBatch(
                                cryptoTransfer(moving(100, FUNGIBLE_COMMON_TOKEN)
                                                .between(TOKEN_TREASURY, ALICE))
                                        .payingWith(TOKEN_TREASURY)
                                        .via("fungibleTransfer")
                                        .batchKey(BATCH_OPERATOR),
                                tokenReject(rejectingToken(FUNGIBLE_COMMON_TOKEN))
                                        .payingWith(ALICE)
                                        .via("rejectFungible")
                                        .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        "rejectFungible", ATOMIC_BATCH, EXPECTED_FUNGIBLE_REJECT_PRICE_USD, ALLOWED_DIFFERENCE)));
    }

    @HapiTest
    final Stream<DynamicTest> baseCommonTokenRejectNFTChargedAsExpected() {
        return hapiTest(flattened(
                baseCommonTokenRejectSetup(),
                atomicBatch(
                                cryptoTransfer(movingUnique(UNIQUE_TOKEN, 1L).between(TOKEN_TREASURY, ALICE))
                                        .payingWith(TOKEN_TREASURY)
                                        .via("nftTransfer")
                                        .batchKey(BATCH_OPERATOR),
                                tokenReject(rejectingNFT(UNIQUE_TOKEN, 1))
                                        .payingWith(ALICE)
                                        .via("rejectNft")
                                        .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        "rejectNft", ATOMIC_BATCH, EXPECTED_NFT_REJECT_PRICE_USD, ALLOWED_DIFFERENCE)));
    }

    private HapiSpecOperation[] baseCreationsSetup() {
        final var civilian = "NonExemptPayer";
        final var customFeeKey = "customFeeKey";

        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
            cryptoCreate(civilian).balance(ONE_HUNDRED_HBARS),
            cryptoCreate(TOKEN_TREASURY).balance(0L),
            cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
            newKeyNamed(ADMIN_KEY),
            newKeyNamed(SUPPLY_KEY),
            newKeyNamed(customFeeKey),
        };
    }

    @HapiTest
    final Stream<DynamicTest> baseCreationsCommonNoFeesHaveExpectedPrices() {
        final var civilian = "NonExemptPayer";
        final var expectedCommonNoCustomFeesPriceUsd = 1.00;
        final var commonNoFees = "commonNoFees";

        return hapiTest(flattened(
                baseCreationsSetup(),
                atomicBatch(tokenCreate(commonNoFees)
                                .blankMemo()
                                .entityMemo("")
                                .name(NAME)
                                .symbol("ABCD")
                                .payingWith(civilian)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .adminKey(ADMIN_KEY)
                                .via(txnFor(commonNoFees))
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(txnFor(commonNoFees), ATOMIC_BATCH, expectedCommonNoCustomFeesPriceUsd, 1)));
    }

    @HapiTest
    final Stream<DynamicTest> baseCreationsCommonWithFeesHaveExpectedPrices() {
        final var civilian = "NonExemptPayer";
        final var expectedCommonWithCustomFeesPriceUsd = 2.00;
        final var commonWithFees = "commonWithFees";
        final var customFeeKey = "customFeeKey";

        return hapiTest(flattened(
                baseCreationsSetup(),
                atomicBatch(tokenCreate(commonWithFees)
                                .blankMemo()
                                .entityMemo("")
                                .name(NAME)
                                .symbol("ABCD")
                                .payingWith(civilian)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .adminKey(ADMIN_KEY)
                                .withCustom(fixedHbarFee(ONE_HBAR, TOKEN_TREASURY))
                                .feeScheduleKey(customFeeKey)
                                .via(txnFor(commonWithFees))
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        txnFor(commonWithFees), ATOMIC_BATCH, expectedCommonWithCustomFeesPriceUsd, 1)));
    }

    @HapiTest
    final Stream<DynamicTest> baseCreationsUniqueNoFeesHaveExpectedPrices() {
        final var civilian = "NonExemptPayer";
        final var expectedUniqueNoCustomFeesPriceUsd = 1.00;
        final var uniqueNoFees = "uniqueNoFees";

        return hapiTest(flattened(
                baseCreationsSetup(),
                atomicBatch(tokenCreate(uniqueNoFees)
                                .payingWith(civilian)
                                .blankMemo()
                                .entityMemo("")
                                .name(NAME)
                                .symbol("ABCD")
                                .initialSupply(0L)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .adminKey(ADMIN_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .via(txnFor(uniqueNoFees))
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(txnFor(uniqueNoFees), ATOMIC_BATCH, expectedUniqueNoCustomFeesPriceUsd, 1)));
    }

    @HapiTest
    final Stream<DynamicTest> baseCreationsUniqueWithFeesHaveExpectedPrices() {
        final var civilian = "NonExemptPayer";
        final var expectedUniqueWithCustomFeesPriceUsd = 2.00;
        final var uniqueWithFees = "uniqueWithFees";
        final var customFeeKey = "customFeeKey";

        return hapiTest(flattened(
                baseCreationsSetup(),
                atomicBatch(tokenCreate(uniqueWithFees)
                                .payingWith(civilian)
                                .blankMemo()
                                .entityMemo("")
                                .name(NAME)
                                .symbol("ABCD")
                                .initialSupply(0L)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .adminKey(ADMIN_KEY)
                                .withCustom(fixedHbarFee(ONE_HBAR, TOKEN_TREASURY))
                                .supplyKey(SUPPLY_KEY)
                                .feeScheduleKey(customFeeKey)
                                .via(txnFor(uniqueWithFees))
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        txnFor(uniqueWithFees), ATOMIC_BATCH, expectedUniqueWithCustomFeesPriceUsd, 1)));
    }

    @HapiTest
    final Stream<DynamicTest> baseTokenOperationIsChargedExpectedFee() {
        final var htsAmount = 2_345L;
        final var targetToken = "immutableToken";
        final var feeDenom = "denom";
        final var htsCollector = "denomFee";
        final var feeScheduleKey = "feeSchedule";
        final var expectedBasePriceUsd = 0.001;

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                newKeyNamed(feeScheduleKey),
                cryptoCreate("civilian").key(feeScheduleKey).payingWith(BATCH_OPERATOR),
                cryptoCreate(htsCollector).payingWith(BATCH_OPERATOR),
                tokenCreate(feeDenom).treasury(htsCollector).payingWith(BATCH_OPERATOR),
                tokenCreate(targetToken)
                        .expiry(Instant.now().getEpochSecond() + THREE_MONTHS_IN_SECONDS)
                        .feeScheduleKey(feeScheduleKey)
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenFeeScheduleUpdate(targetToken)
                                .signedBy(feeScheduleKey)
                                .payingWith("civilian")
                                .blankMemo()
                                .withCustom(fixedHtsFee(htsAmount, feeDenom, htsCollector))
                                .via("baseFeeSchUpd")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("baseFeeSchUpd", ATOMIC_BATCH, expectedBasePriceUsd, 5));
    }

    @HapiTest
    final Stream<DynamicTest> baseFungibleMintOperationIsChargedExpectedFee() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(CIVILIAN_ACCT)
                        .balance(ONE_MILLION_HBARS)
                        .key(SUPPLY_KEY)
                        .payingWith(BATCH_OPERATOR),
                tokenCreate(FUNGIBLE_TOKEN)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(FUNGIBLE_COMMON)
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(mintToken(FUNGIBLE_TOKEN, 10)
                                .payingWith(CIVILIAN_ACCT)
                                .signedBy(SUPPLY_KEY)
                                .blankMemo()
                                .via("fungibleMint")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("fungibleMint", ATOMIC_BATCH, EXPECTED_FUNGIBLE_MINT_PRICE_USD, 10));
    }

    @HapiTest
    final Stream<DynamicTest> baseNftMintOperationIsChargedExpectedFee() {
        final var standard100ByteMetadata = copyFromUtf8(
                "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789");

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(CIVILIAN_ACCT)
                        .balance(ONE_MILLION_HBARS)
                        .key(SUPPLY_KEY)
                        .payingWith(BATCH_OPERATOR),
                tokenCreate(UNIQUE_TOKEN)
                        .initialSupply(0L)
                        .expiry(Instant.now().getEpochSecond() + THREE_MONTHS_IN_SECONDS)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(mintToken(UNIQUE_TOKEN, List.of(standard100ByteMetadata))
                                .payingWith(CIVILIAN_ACCT)
                                .signedBy(SUPPLY_KEY)
                                .blankMemo()
                                .fee(ONE_HUNDRED_HBARS)
                                .via(BASE_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(
                        BASE_TXN, ATOMIC_BATCH, EXPECTED_NFT_MINT_PRICE_USD, ALLOWED_DIFFERENCE_PERCENTAGE));
    }

    @HapiTest
    final Stream<DynamicTest> nftMintsScaleLinearlyBasedOnNumberOfSerialNumbers() {
        final var expectedFee = 10 * EXPECTED_NFT_MINT_PRICE_USD;
        final var standard100ByteMetadata = copyFromUtf8(
                "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789");

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(CIVILIAN_ACCT)
                        .balance(ONE_MILLION_HBARS)
                        .key(SUPPLY_KEY)
                        .payingWith(BATCH_OPERATOR),
                tokenCreate(UNIQUE_TOKEN)
                        .initialSupply(0L)
                        .expiry(Instant.now().getEpochSecond() + THREE_MONTHS_IN_SECONDS)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(mintToken(
                                        UNIQUE_TOKEN,
                                        List.of(
                                                standard100ByteMetadata,
                                                standard100ByteMetadata,
                                                standard100ByteMetadata,
                                                standard100ByteMetadata,
                                                standard100ByteMetadata,
                                                standard100ByteMetadata,
                                                standard100ByteMetadata,
                                                standard100ByteMetadata,
                                                standard100ByteMetadata,
                                                standard100ByteMetadata))
                                .payingWith(CIVILIAN_ACCT)
                                .signedBy(SUPPLY_KEY)
                                .blankMemo()
                                .fee(ONE_HUNDRED_HBARS)
                                .via(BASE_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(BASE_TXN, ATOMIC_BATCH, expectedFee, ALLOWED_DIFFERENCE_PERCENTAGE));
    }

    @HapiTest
    final Stream<DynamicTest> baseNftBurnOperationIsChargedExpectedFee() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                newKeyNamed(SUPPLY_KEY),
                cryptoCreate(CIVILIAN_ACCT).key(SUPPLY_KEY).payingWith(BATCH_OPERATOR),
                cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
                tokenCreate(UNIQUE_TOKEN)
                        .initialSupply(0)
                        .supplyKey(SUPPLY_KEY)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .payingWith(BATCH_OPERATOR),
                mintToken(UNIQUE_TOKEN, List.of(metadata("memo"))).payingWith(BATCH_OPERATOR),
                atomicBatch(burnToken(UNIQUE_TOKEN, List.of(1L))
                                .fee(ONE_HBAR)
                                .payingWith(CIVILIAN_ACCT)
                                .blankMemo()
                                .via(BASE_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(BASE_TXN, ATOMIC_BATCH, EXPECTED_NFT_BURN_PRICE_USD, 5));
    }

    private HapiSpecOperation[] baseKycSetup() {
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
            newKeyNamed(MULTI_KEY),
            cryptoCreate(TOKEN_TREASURY)
                    .balance(ONE_HUNDRED_HBARS)
                    .key(MULTI_KEY)
                    .payingWith(BATCH_OPERATOR),
            cryptoCreate(CIVILIAN_ACCT).payingWith(BATCH_OPERATOR),
            tokenCreate(FUNGIBLE_TOKEN)
                    .tokenType(FUNGIBLE_COMMON)
                    .kycKey(MULTI_KEY)
                    .payingWith(TOKEN_TREASURY)
                    .via(BASE_TXN),
            tokenAssociate(CIVILIAN_ACCT, FUNGIBLE_TOKEN).payingWith(BATCH_OPERATOR)
        };
    }

    @HapiTest
    final Stream<DynamicTest> baseGrantKycChargedAsExpected() {
        return hapiTest(flattened(
                baseKycSetup(),
                atomicBatch(grantTokenKyc(FUNGIBLE_TOKEN, CIVILIAN_ACCT)
                                .blankMemo()
                                .signedBy(MULTI_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .via("grantKyc")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("grantKyc", ATOMIC_BATCH, EXPECTED_GRANTKYC_PRICE_USD, 10)));
    }

    @HapiTest
    final Stream<DynamicTest> baseRevokeKycChargedAsExpected() {
        return hapiTest(flattened(
                baseKycSetup(),
                atomicBatch(revokeTokenKyc(FUNGIBLE_TOKEN, CIVILIAN_ACCT)
                                .blankMemo()
                                .signedBy(MULTI_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .via("revokeKyc")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("revokeKyc", ATOMIC_BATCH, EXPECTED_REVOKEKYC_PRICE_USD, 10)));
    }

    private HapiSpecOperation[] baseNftFreezeUnfreezeSetup() {
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
            newKeyNamed(TREASURE_KEY),
            newKeyNamed(ADMIN_KEY),
            newKeyNamed(KYC_KEY),
            cryptoCreate(TOKEN_TREASURY)
                    .balance(ONE_HUNDRED_HBARS)
                    .key(TREASURE_KEY)
                    .payingWith(BATCH_OPERATOR),
            cryptoCreate(CIVILIAN_ACCT).payingWith(BATCH_OPERATOR),
            tokenCreate(UNIQUE_TOKEN)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .supplyType(TokenSupplyType.INFINITE)
                    .initialSupply(0L)
                    .adminKey(ADMIN_KEY)
                    .freezeKey(TOKEN_TREASURY)
                    .kycKey(KYC_KEY)
                    .freezeDefault(false)
                    .treasury(TOKEN_TREASURY)
                    .payingWith(TOKEN_TREASURY)
                    .supplyKey(ADMIN_KEY)
                    .via(BASE_TXN)
                    .payingWith(BATCH_OPERATOR),
            tokenAssociate(CIVILIAN_ACCT, UNIQUE_TOKEN).payingWith(BATCH_OPERATOR)
        };
    }

    @HapiTest
    final Stream<DynamicTest> baseNftFreezeChargedAsExpected() {
        return hapiTest(flattened(
                baseNftFreezeUnfreezeSetup(),
                atomicBatch(tokenFreeze(UNIQUE_TOKEN, CIVILIAN_ACCT)
                                .blankMemo()
                                .signedBy(TOKEN_TREASURY)
                                .payingWith(TOKEN_TREASURY)
                                .via("freeze")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("freeze", ATOMIC_BATCH, EXPECTED_FREEZE_PRICE_USD, 5)));
    }

    @HapiTest
    final Stream<DynamicTest> baseNftFreezeUnfreezeChargedAsExpected() {
        return hapiTest(flattened(
                baseNftFreezeUnfreezeSetup(),
                atomicBatch(tokenUnfreeze(UNIQUE_TOKEN, CIVILIAN_ACCT)
                                .blankMemo()
                                .payingWith(TOKEN_TREASURY)
                                .signedBy(TOKEN_TREASURY)
                                .via(UNFREEZE)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(UNFREEZE, ATOMIC_BATCH, EXPECTED_UNFREEZE_PRICE_USD, 5)));
    }

    private HapiSpecOperation[] baseCommonFreezeUnfreezeSetup() {
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
            newKeyNamed(TREASURE_KEY),
            newKeyNamed(ADMIN_KEY),
            newKeyNamed(FREEZE_KEY),
            newKeyNamed(SUPPLY_KEY),
            newKeyNamed(WIPE_KEY),
            cryptoCreate(TOKEN_TREASURY)
                    .balance(ONE_HUNDRED_HBARS)
                    .key(TREASURE_KEY)
                    .payingWith(BATCH_OPERATOR),
            cryptoCreate(CIVILIAN_ACCT).payingWith(BATCH_OPERATOR),
            tokenCreate(FUNGIBLE_COMMON_TOKEN)
                    .adminKey(ADMIN_KEY)
                    .freezeKey(TOKEN_TREASURY)
                    .wipeKey(WIPE_KEY)
                    .supplyKey(SUPPLY_KEY)
                    .freezeDefault(false)
                    .treasury(TOKEN_TREASURY)
                    .payingWith(TOKEN_TREASURY),
            tokenAssociate(CIVILIAN_ACCT, FUNGIBLE_COMMON_TOKEN).payingWith(BATCH_OPERATOR),
        };
    }

    @HapiTest
    final Stream<DynamicTest> baseCommonFreezeChargedAsExpected() {
        return hapiTest(flattened(
                baseCommonFreezeUnfreezeSetup(),
                atomicBatch(tokenFreeze(FUNGIBLE_COMMON_TOKEN, CIVILIAN_ACCT)
                                .blankMemo()
                                .signedBy(TOKEN_TREASURY)
                                .payingWith(TOKEN_TREASURY)
                                .via("freeze")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("freeze", ATOMIC_BATCH, EXPECTED_FREEZE_PRICE_USD, 5)));
    }

    @HapiTest
    final Stream<DynamicTest> baseCommonUnfreezeChargedAsExpected() {
        return hapiTest(flattened(
                baseCommonFreezeUnfreezeSetup(),
                atomicBatch(tokenUnfreeze(FUNGIBLE_COMMON_TOKEN, CIVILIAN_ACCT)
                                .blankMemo()
                                .payingWith(TOKEN_TREASURY)
                                .signedBy(TOKEN_TREASURY)
                                .via(UNFREEZE)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(UNFREEZE, ATOMIC_BATCH, EXPECTED_UNFREEZE_PRICE_USD, 5)));
    }

    private HapiSpecOperation[] basePauseAndUnpauseSetup() {
        final var token = "token";
        final var civilian = "NonExemptPayer";
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
            cryptoCreate(TOKEN_TREASURY).payingWith(BATCH_OPERATOR),
            newKeyNamed(PAUSE_KEY),
            cryptoCreate(civilian).key(PAUSE_KEY).payingWith(BATCH_OPERATOR),
            tokenCreate(token).pauseKey(PAUSE_KEY).treasury(TOKEN_TREASURY).payingWith(civilian)
        };
    }

    @HapiTest
    final Stream<DynamicTest> basePauseHaveExpectedPrices() {
        final var expectedBaseFee = 0.001;
        final var token = "token";
        final var tokenPauseTransaction = "tokenPauseTxn";
        final var civilian = "NonExemptPayer";

        return hapiTest(flattened(
                basePauseAndUnpauseSetup(),
                atomicBatch(tokenPause(token)
                                .blankMemo()
                                .payingWith(civilian)
                                .via(tokenPauseTransaction)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(tokenPauseTransaction, ATOMIC_BATCH, expectedBaseFee, 5)));
    }

    @HapiTest
    final Stream<DynamicTest> baseUnpauseHaveExpectedPrices() {
        final var expectedBaseFee = 0.001;
        final var token = "token";
        final var tokenUnpauseTransaction = "tokenUnpauseTxn";
        final var civilian = "NonExemptPayer";

        return hapiTest(flattened(
                basePauseAndUnpauseSetup(),
                atomicBatch(tokenUnpause(token)
                                .blankMemo()
                                .payingWith(civilian)
                                .via(tokenUnpauseTransaction)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(tokenUnpauseTransaction, ATOMIC_BATCH, expectedBaseFee, 5)));
    }

    @HapiTest
    final Stream<DynamicTest> baseNftWipeOperationIsChargedExpectedFee() {
        return hapiTest(flattened(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                cryptoCreate(CIVILIAN_ACCT).key(WIPE_KEY).payingWith(BATCH_OPERATOR),
                cryptoCreate(TOKEN_TREASURY)
                        .balance(ONE_HUNDRED_HBARS)
                        .key(WIPE_KEY)
                        .payingWith(BATCH_OPERATOR),
                tokenCreate(UNIQUE_TOKEN)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0L)
                        .supplyKey(SUPPLY_KEY)
                        .wipeKey(WIPE_KEY)
                        .treasury(TOKEN_TREASURY)
                        .payingWith(BATCH_OPERATOR),
                tokenAssociate(CIVILIAN_ACCT, UNIQUE_TOKEN).payingWith(BATCH_OPERATOR),
                mintToken(UNIQUE_TOKEN, List.of(copyFromUtf8("token_to_wipe"))).payingWith(BATCH_OPERATOR),
                cryptoTransfer(movingUnique(UNIQUE_TOKEN, 1L).between(TOKEN_TREASURY, CIVILIAN_ACCT))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(wipeTokenAccount(UNIQUE_TOKEN, CIVILIAN_ACCT, List.of(1L))
                                .payingWith(TOKEN_TREASURY)
                                .fee(ONE_HBAR)
                                .blankMemo()
                                .via(BASE_TXN)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(BASE_TXN, ATOMIC_BATCH, EXPECTED_NFT_WIPE_PRICE_USD, 5)));
    }

    @HapiTest
    final Stream<DynamicTest> updateTokenChargedAsExpected() {
        final var expectedUpdatePriceUsd = 0.001;

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                tokenCreate(FUNGIBLE_COMMON_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .entityMemo("")
                        .symbol("a")
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenUpdate(FUNGIBLE_COMMON_TOKEN)
                                .via("uniqueTokenUpdate")
                                .payingWith(TOKEN_TREASURY)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("uniqueTokenUpdate", ATOMIC_BATCH, expectedUpdatePriceUsd, 5));
    }

    @HapiTest
    final Stream<DynamicTest> updateNftChargedAsExpected() {
        final var expectedNftUpdatePriceUsd = 0.001;
        final var nftUpdateTxn = "nftUpdateTxn";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(METADATA_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(12L)
                        .supplyKey(SUPPLY_KEY)
                        .metadataKey(METADATA_KEY)
                        .initialSupply(0L)
                        .payingWith(BATCH_OPERATOR),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        copyFromUtf8("a"),
                                        copyFromUtf8("b"),
                                        copyFromUtf8("c"),
                                        copyFromUtf8("d"),
                                        copyFromUtf8("e"),
                                        copyFromUtf8("f"),
                                        copyFromUtf8("g")))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(7L))
                                .signedBy(TOKEN_TREASURY, METADATA_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via(nftUpdateTxn)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(nftUpdateTxn, ATOMIC_BATCH, expectedNftUpdatePriceUsd, 0.01));
    }

    @HapiTest
    final Stream<DynamicTest> deleteTokenChargedAsExpected() {
        final var expectedDeletePriceUsd = 0.001;

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                newKeyNamed(MULTI_KEY),
                cryptoCreate(MULTI_KEY).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                tokenCreate(FUNGIBLE_COMMON_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .adminKey(MULTI_KEY)
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenDelete(FUNGIBLE_COMMON_TOKEN)
                                .via("uniqueTokenDelete")
                                .payingWith(MULTI_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("uniqueTokenDelete", ATOMIC_BATCH, expectedDeletePriceUsd, 5));
    }

    private HapiSpecOperation[] tokenAssociateDissociateSetup() {
        final var account = "account";
        return new HapiSpecOperation[] {
            cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
            newKeyNamed(MULTI_KEY),
            cryptoCreate(account).payingWith(BATCH_OPERATOR),
            cryptoCreate(MULTI_KEY).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
            tokenCreate(FUNGIBLE_COMMON_TOKEN).tokenType(FUNGIBLE_COMMON).payingWith(BATCH_OPERATOR),
        };
    }

    @HapiTest
    final Stream<DynamicTest> tokenAssociateChargedAsExpected() {
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                flattened(
                        tokenAssociateDissociateSetup(),
                        atomicBatch(tokenAssociate(MULTI_KEY, FUNGIBLE_COMMON_TOKEN)
                                        .via("tokenAssociate")
                                        .payingWith(MULTI_KEY)
                                        .batchKey(BATCH_OPERATOR))
                                .via(ATOMIC_BATCH)
                                .signedByPayerAnd(BATCH_OPERATOR)
                                .payingWith(BATCH_OPERATOR),
                        validateInnerTxnChargedUsd("tokenAssociate", ATOMIC_BATCH, 0.05, 5)));
    }

    @HapiTest
    final Stream<DynamicTest> tokenDissociateChargedAsExpected() {
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                flattened(
                        tokenAssociateDissociateSetup(),
                        atomicBatch(
                                        tokenAssociate(MULTI_KEY, FUNGIBLE_COMMON_TOKEN)
                                                .via("tokenAssociate")
                                                .payingWith(MULTI_KEY)
                                                .batchKey(BATCH_OPERATOR),
                                        tokenDissociate(MULTI_KEY, FUNGIBLE_COMMON_TOKEN)
                                                .via("tokenDissociate")
                                                .payingWith(MULTI_KEY)
                                                .batchKey(BATCH_OPERATOR))
                                .via(ATOMIC_BATCH)
                                .signedByPayerAnd(BATCH_OPERATOR)
                                .payingWith(BATCH_OPERATOR),
                        validateInnerTxnChargedUsd("tokenDissociate", ATOMIC_BATCH, 0.05, 5)));
    }

    @HapiTest
    final Stream<DynamicTest> updateMultipleNftsFeeChargedAsExpected() {
        final var expectedNftUpdatePriceUsd = 0.005;
        final var nftUpdateTxn = "nftUpdateTxn";

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                newKeyNamed(METADATA_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(12L)
                        .wipeKey(WIPE_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .metadataKey(METADATA_KEY)
                        .initialSupply(0L)
                        .payingWith(BATCH_OPERATOR),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        copyFromUtf8("a"),
                                        copyFromUtf8("b"),
                                        copyFromUtf8("c"),
                                        copyFromUtf8("d"),
                                        copyFromUtf8("e"),
                                        copyFromUtf8("f"),
                                        copyFromUtf8("g")))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L, 2L, 3L, 4L, 5L))
                                .signedBy(TOKEN_TREASURY, METADATA_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .fee(10 * ONE_HBAR)
                                .via(nftUpdateTxn)
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(nftUpdateTxn, ATOMIC_BATCH, expectedNftUpdatePriceUsd, 0.01));
    }

    @HapiTest
    final Stream<DynamicTest> tokenUpdateNftsFeeChargedAsExpected() {
        final var expectedTokenUpdateNfts = 0.001;

        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                newKeyNamed(SUPPLY_KEY),
                newKeyNamed(WIPE_KEY),
                newKeyNamed(METADATA_KEY),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).payingWith(BATCH_OPERATOR),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .maxSupply(12L)
                        .wipeKey(WIPE_KEY)
                        .metadataKey(METADATA_KEY)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(0L)
                        .payingWith(BATCH_OPERATOR),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        copyFromUtf8("a"),
                                        copyFromUtf8("b"),
                                        copyFromUtf8("c"),
                                        copyFromUtf8("d"),
                                        copyFromUtf8("e"),
                                        copyFromUtf8("f"),
                                        copyFromUtf8("g")))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(tokenUpdateNfts(NON_FUNGIBLE_TOKEN, NFT_TEST_METADATA, List.of(1L))
                                .signedBy(TOKEN_TREASURY, METADATA_KEY)
                                .payingWith(TOKEN_TREASURY)
                                .via("nftUpdateTxn")
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd("nftUpdateTxn", ATOMIC_BATCH, expectedTokenUpdateNfts, 1));
    }

    // verify bulk operations base fees
    @Nested
    @DisplayName("Token Bulk Operations - without custom fees")
    class BulkTokenOperationsWithoutCustomFeesTest extends BulkOperationsBase {

        @HapiTest
        final Stream<DynamicTest> mintOneNftTokenWithoutCustomFees() {
            return mintBulkNftAndValidateFees(1);
        }

        @HapiTest
        final Stream<DynamicTest> mintFiveBulkNftTokenWithoutCustomFees() {
            return mintBulkNftAndValidateFees(5);
        }

        @HapiTest
        final Stream<DynamicTest> mintTenBulkNftTokensWithoutCustomFees() {
            return mintBulkNftAndValidateFees(10);
        }

        @HapiTest
        final Stream<DynamicTest> associateOneFtTokenWithoutCustomFees() {
            return associateBulkTokensAndValidateFees(List.of(FT_TOKEN));
        }

        @HapiTest
        final Stream<DynamicTest> associateBulkFtTokensWithoutCustomFees() {
            return associateBulkTokensAndValidateFees(List.of(FT_TOKEN, NFT_TOKEN, NFT_BURN_TOKEN, NFT_BURN_ONE_TOKEN));
        }

        @HapiTest
        final Stream<DynamicTest> updateOneNftTokenWithoutCustomFees() {
            return updateBulkNftTokensAndValidateFees(10, Arrays.asList(1L));
        }

        @HapiTest
        final Stream<DynamicTest> updateFiveBulkNftTokensWithoutCustomFees() {
            return updateBulkNftTokensAndValidateFees(10, Arrays.asList(1L, 2L, 3L, 4L, 5L));
        }

        @HapiTest
        final Stream<DynamicTest> updateTenBulkNftTokensWithoutCustomFees() {
            return updateBulkNftTokensAndValidateFees(10, Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));
        }

        // define reusable methods
        private Stream<DynamicTest> mintBulkNftAndValidateFees(final int rangeAmount) {
            final var supplyKey = "supplyKey";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                    newKeyNamed(supplyKey),
                    cryptoCreate(OWNER)
                            .balance(ONE_HUNDRED_HBARS)
                            .key(supplyKey)
                            .payingWith(BATCH_OPERATOR),
                    tokenCreate(NFT_TOKEN)
                            .treasury(OWNER)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .supplyKey(supplyKey)
                            .supplyType(TokenSupplyType.INFINITE)
                            .initialSupply(0)
                            .payingWith(BATCH_OPERATOR),
                    atomicBatch(mintToken(
                                            NFT_TOKEN,
                                            IntStream.range(0, rangeAmount)
                                                    .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                                    .toList())
                                    .payingWith(OWNER)
                                    .signedBy(supplyKey)
                                    .blankMemo()
                                    .via("mintTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .via(ATOMIC_BATCH)
                            .signedByPayerAnd(BATCH_OPERATOR)
                            .payingWith(BATCH_OPERATOR),
                    validateInnerTxnChargedUsd(
                            "mintTxn",
                            ATOMIC_BATCH,
                            EXPECTED_NFT_MINT_PRICE_USD * rangeAmount,
                            ALLOWED_DIFFERENCE_PERCENTAGE));
        }

        private Stream<DynamicTest> associateBulkTokensAndValidateFees(final List<String> tokens) {
            final var supplyKey = "supplyKey";
            return hapiTest(flattened(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                    createTokensAndAccounts(),
                    newKeyNamed(supplyKey),
                    cryptoCreate(ASSOCIATE_ACCOUNT)
                            .balance(ONE_HUNDRED_HBARS)
                            .key(supplyKey)
                            .payingWith(BATCH_OPERATOR),
                    atomicBatch(tokenAssociate(ASSOCIATE_ACCOUNT, tokens)
                                    .payingWith(ASSOCIATE_ACCOUNT)
                                    .via("associateTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .via(ATOMIC_BATCH)
                            .signedByPayerAnd(BATCH_OPERATOR)
                            .payingWith(BATCH_OPERATOR),
                    validateInnerTxnChargedUsd(
                            "associateTxn",
                            ATOMIC_BATCH,
                            EXPECTED_ASSOCIATE_TOKEN_PRICE * tokens.size(),
                            ALLOWED_DIFFERENCE_PERCENTAGE)));
        }

        private Stream<DynamicTest> updateBulkNftTokensAndValidateFees(
                final int mintAmount, final List<Long> updateAmounts) {
            final var supplyKey = "supplyKey";
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR).balance(ONE_BILLION_HBARS),
                    newKeyNamed(supplyKey),
                    cryptoCreate(OWNER)
                            .balance(ONE_HUNDRED_HBARS)
                            .key(supplyKey)
                            .payingWith(BATCH_OPERATOR),
                    tokenCreate(NFT_TOKEN)
                            .treasury(OWNER)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .supplyKey(supplyKey)
                            .supplyType(TokenSupplyType.INFINITE)
                            .initialSupply(0)
                            .payingWith(BATCH_OPERATOR),
                    mintToken(
                                    NFT_TOKEN,
                                    IntStream.range(0, mintAmount)
                                            .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                            .toList())
                            .payingWith(OWNER)
                            .signedBy(supplyKey)
                            .blankMemo()
                            .payingWith(BATCH_OPERATOR),
                    atomicBatch(tokenUpdateNfts(NFT_TOKEN, TOKEN_UPDATE_METADATA, updateAmounts)
                                    .payingWith(OWNER)
                                    .signedBy(supplyKey)
                                    .blankMemo()
                                    .via("updateTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .via(ATOMIC_BATCH)
                            .signedByPayerAnd(BATCH_OPERATOR)
                            .payingWith(BATCH_OPERATOR),
                    validateInnerTxnChargedUsd(
                            "updateTxn",
                            ATOMIC_BATCH,
                            EXPECTED_NFT_UPDATE_PRICE * updateAmounts.size(),
                            ALLOWED_DIFFERENCE_PERCENTAGE));
        }
    }

    private String txnFor(String tokenSubType) {
        return tokenSubType + "Txn";
    }
}
