// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFeeScheduleUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFeeNetOfTransfers;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeNoFallback;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

public class AtomicBatchEndToEndTokensWithCustomFeesTests {
    private static final double BASE_FEE_BATCH_TRANSACTION = 0.001;
    private static final long HBAR_FEE = 1L;
    private static final long HTS_FEE = 1L;
    private static final String FT_WITH_HBAR_FIXED_FEE = "ftWithHbarFixedFee";
    private static final String FT_WITH_HTS_FIXED_FEE = "ftWithHtsFixedFee";
    private static final String FT_WITH_FRACTIONAL_FEE = "ftFractionalFee";
    private static final String FT_FIXED_AND_FRACTIONAL_FEE = "ftFixedAndFractionalFee";
    private static final String NFT_WITH_HTS_FEE = "nftWithHtsFee";
    private static final String NFT_WITH_ROYALTY_FEE_NO_FALLBACK = "nftWithRoyaltyFeeNoFallback";
    private static final String NFT_WITH_ROYALTY_FEE_WITH_FALLBACK = "nftWithRoyaltyFeeWithFallback";
    private static final String DENOM_TOKEN = "denomToken";
    private static final String FEE_COLLECTOR = "feeCollector";
    private static final String NEW_FEE_COLLECTOR = "newFeeCollector";
    private static final String NEW_FEE_COLLECTOR_SECOND = "newFeeCollectorSecond";
    private static final String HTS_COLLECTOR = "htsCollector";
    private static final String DENOM_COLLECTOR = "denomCollector";
    private static final String ROYALTY_FEE_COLLECTOR = "royaltyFeeCollector";
    private static final String OWNER = "owner";
    private static final String NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS =
            "newTreasuryWithUnlimitedAutoAssociations";
    private static final String DENOM_TREASURY = "denomTreasury";
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String RECEIVER_ASSOCIATED_THIRD = "receiverAssociatedThird";

    private static final String adminKey = "adminKey";
    private static final String feeScheduleKey = "feeScheduleKey";
    private static final String supplyKey = "supplyKey";

    @Nested
    @DisplayName("Atomic Batch End-to-End Test Cases for Tokens with Custom Fees - Positive Cases")
    class AtomicBatchEndToEndTestsForTokensWithCustomFeesPositiveCases {

        @HapiTest
        @DisplayName("Fungible Token with Fixed Fees - Token transfer before and after treasury update "
                + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFixedFeeBeforeAndAfterTreasuryUpdateChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed fee before treasury update
            final var tokenTransferBeforeTreasuryUpdate = cryptoTransfer(
                            moving(1, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update treasury
            final var tokenTreasuryUpdate = tokenUpdate(FT_WITH_HBAR_FIXED_FEE)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer from new treasury to old treasury
            final var transferFromNewTreasuryToOldTreasury = cryptoTransfer(moving(1, FT_WITH_HBAR_FIXED_FEE)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("transferFromNewTreasuryToOldTreasury")
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER)
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fixed fee after treasury update
            final var tokenTransferAfterTreasuryUpdate = cryptoTransfer(
                            moving(1, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeTreasuryUpdate,
                                    tokenTreasuryUpdate,
                                    transferFromNewTreasuryToOldTreasury,
                                    tokenTransferAfterTreasuryUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 0L),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(HBAR_FEE),
                    getTokenInfo(FT_WITH_HBAR_FIXED_FEE).hasTreasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 98L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Fixed Fees - Token transfer before and after fee collector update "
                + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFixedFeeBeforeAndAfterFeeCollectorUpdateChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed fee before fee collector update
            final var tokenTransferBeforeCollectorUpdate = cryptoTransfer(
                            moving(1, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update fee collector
            final var tokenFeeCollectorUpdate = tokenFeeScheduleUpdate(FT_WITH_HBAR_FIXED_FEE)
                    .withCustom(fixedHbarFee(HBAR_FEE, NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fixed fee after fee collector update
            final var tokenTransferAfterCollectorUpdate = cryptoTransfer(moving(1, FT_WITH_HBAR_FIXED_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeCollectorUpdate,
                                    tokenFeeCollectorUpdate,
                                    tokenTransferAfterCollectorUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 99L),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(0),
                    getAccountBalance(NEW_FEE_COLLECTOR).hasTinyBars(HBAR_FEE)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Fixed Fees - Token transfer before and after fee collector updated twice "
                + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFixedFeeBeforeAndAfterFeeCollectorUpdatedTwiceChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed fee before fee collector update
            final var tokenTransferBeforeCollectorUpdate = cryptoTransfer(
                            moving(1, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update fee collector
            final var tokenFeeCollectorUpdateFirst = tokenFeeScheduleUpdate(FT_WITH_HBAR_FIXED_FEE)
                    .withCustom(fixedHbarFee(HBAR_FEE, NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeCollectorUpdateFirst")
                    .batchKey(BATCH_OPERATOR);

            final var tokenFeeCollectorUpdateSecond = tokenFeeScheduleUpdate(FT_WITH_HBAR_FIXED_FEE)
                    .withCustom(fixedHbarFee(HBAR_FEE, NEW_FEE_COLLECTOR_SECOND))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR_SECOND)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeCollectorUpdateSecond")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fixed fee after fee collector updates
            final var tokenTransferAfterCollectorUpdate = cryptoTransfer(moving(1, FT_WITH_HBAR_FIXED_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeCollectorUpdate,
                                    tokenFeeCollectorUpdateFirst,
                                    tokenFeeCollectorUpdateSecond,
                                    tokenTransferAfterCollectorUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 99L),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(0),
                    getAccountBalance(NEW_FEE_COLLECTOR).hasTinyBars(0),
                    getAccountBalance(NEW_FEE_COLLECTOR_SECOND).hasTinyBars(HBAR_FEE)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Fixed Fees - Token transfer before and after treasury and fee collector update "
                        + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFixedFeeBeforeAndAfterTreasuryAndFeeCollectorUpdateChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed fee before update
            final var tokenTransferBeforeUpdate = cryptoTransfer(
                            moving(1, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update fee collector
            final var tokenFeeCollectorUpdate = tokenFeeScheduleUpdate(FT_WITH_HBAR_FIXED_FEE)
                    .withCustom(fixedHbarFee(HBAR_FEE, NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update treasury
            final var tokenTreasuryUpdate = tokenUpdate(FT_WITH_HBAR_FIXED_FEE)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer from new treasury to old treasury
            final var transferFromNewTreasuryToOldTreasury = cryptoTransfer(moving(1, FT_WITH_HBAR_FIXED_FEE)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("transferFromNewTreasuryToOldTreasury")
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER)
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fixed fee after fee collector and treasury update
            final var tokenTransferAfterUpdate = cryptoTransfer(
                            moving(1, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeUpdate,
                                    tokenFeeCollectorUpdate,
                                    tokenTreasuryUpdate,
                                    transferFromNewTreasuryToOldTreasury,
                                    tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 0L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 98L),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(0),
                    getAccountBalance(NEW_FEE_COLLECTOR).hasTinyBars(HBAR_FEE)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Fixed Fees - Token transfer before and after fee amount and collector updates "
                        + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFixedFeeBeforeAndAfterFixedFeeAmountUpdateChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed fee before update
            final var tokenTransferToAssociatedReceiverFirst = cryptoTransfer(
                            moving(10, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferFirst")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferWithCustomFeesBeforeUpdate = cryptoTransfer(moving(5, FT_WITH_HBAR_FIXED_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferWithCustomFeesBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update fee amount and collector
            final var tokenFeeAndFeeCollectorUpdate = tokenFeeScheduleUpdate(FT_WITH_HBAR_FIXED_FEE)
                    .withCustom(fixedHbarFee(HBAR_FEE * 2, NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeAndCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fixed fee after fee collector and fee update
            final var tokenTransferAfterUpdate = cryptoTransfer(moving(1, FT_WITH_HBAR_FIXED_FEE)
                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                    .payingWith(RECEIVER_ASSOCIATED_SECOND)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_WITH_HBAR_FIXED_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferToAssociatedReceiverFirst,
                                    tokenTransferWithCustomFeesBeforeUpdate,
                                    tokenFeeAndFeeCollectorUpdate,
                                    tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 5L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 90L),
                    getAccountBalance(FEE_COLLECTOR).hasTinyBars(HBAR_FEE),
                    getAccountBalance(NEW_FEE_COLLECTOR).hasTinyBars(HBAR_FEE * 2)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Fixed Fees - Token transfer before and after fixed fee updated to fractional "
                + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFixedFeeBeforeAndAfterFixedFeeUpdatedToFractionalChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed fee before update
            final var tokenTransferToAssociatedReceiverFirst = cryptoTransfer(
                            moving(10, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferWithCustomFeesBeforeUpdate = cryptoTransfer(moving(5, FT_WITH_HBAR_FIXED_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update fixed fee to fractional fee
            final var tokenFeeUpdate = tokenFeeScheduleUpdate(FT_WITH_HBAR_FIXED_FEE)
                    .withCustom(fractionalFeeNetOfTransfers(1L, 10L, 1L, OptionalLong.empty(), FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fixed fee after fee collector and fee update
            final var tokenTransferAfterUpdate = cryptoTransfer(moving(1, FT_WITH_HBAR_FIXED_FEE)
                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                    .payingWith(RECEIVER_ASSOCIATED_SECOND)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(FEE_COLLECTOR, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_WITH_HBAR_FIXED_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferToAssociatedReceiverFirst,
                                    tokenTransferWithCustomFeesBeforeUpdate,
                                    tokenFeeUpdate,
                                    tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 5L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 3L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 90L),
                    getAccountBalance(FEE_COLLECTOR)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L)
                            .hasTinyBars(HBAR_FEE)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Fixed Fees - Token transfer before and after removed fee schedule "
                + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFixedFeeBeforeAndAfterRemovedFeeScheduleChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed fee before update
            final var tokenTransferToAssociatedReceiverFirst = cryptoTransfer(
                            moving(10, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferWithCustomFeesBeforeUpdate = cryptoTransfer(moving(5, FT_WITH_HBAR_FIXED_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferWithCustomFeesBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // remove all custom fees
            final var tokenFeeUpdate = tokenFeeScheduleUpdate(FT_WITH_HBAR_FIXED_FEE)
                    .signedBy(adminKey, feeScheduleKey)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT after all custom fees are removed
            final var tokenTransferAfterUpdate = cryptoTransfer(moving(1, FT_WITH_HBAR_FIXED_FEE)
                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                    .payingWith(RECEIVER_ASSOCIATED_SECOND)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    tokenAssociate(FEE_COLLECTOR, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_WITH_HBAR_FIXED_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferToAssociatedReceiverFirst,
                                    tokenTransferWithCustomFeesBeforeUpdate,
                                    tokenFeeUpdate,
                                    tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 5L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 90L),
                    getAccountBalance(FEE_COLLECTOR)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 0L)
                            .hasTinyBars(HBAR_FEE)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Fractional Fees - Token transfer before and after treasury update "
                + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFractionalFeeBeforeAndAfterTreasuryUpdateChargesFeesCorrectlyInBatch() {

            // transfer FT with fractional fee before treasury update
            final var tokenTransferBeforeTreasuryUpdate = cryptoTransfer(
                            moving(1, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("tokenTransferBeforeTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update treasury
            final var tokenTreasuryUpdate = tokenUpdate(FT_WITH_FRACTIONAL_FEE)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .via("tokenTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer from new treasury to old treasury
            final var transferFromNewTreasuryToOldTreasury = cryptoTransfer(moving(2, FT_WITH_FRACTIONAL_FEE)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("transferFromNewTreasuryToOldTreasury")
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER)
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fractional fee after treasury update
            final var tokenTransferAfterTreasuryUpdate = cryptoTransfer(
                            moving(1, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("tokenTransferAfterTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeTreasuryUpdate,
                                    tokenTreasuryUpdate,
                                    transferFromNewTreasuryToOldTreasury,
                                    tokenTransferAfterTreasuryUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getTokenInfo(FT_WITH_FRACTIONAL_FEE).hasTreasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 97L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Fractional Fees - Token transfer before and after fee collector update "
                + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFractionalFeeBeforeAndAfterFeeCollectorUpdateChargesFeesCorrectlyInBatch() {

            // transfer FT with fractional fee before fee collector update
            final var tokenTransferBeforeCollectorUpdate = cryptoTransfer(
                            moving(5, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update fee collector
            final var tokenFeeCollectorUpdate = tokenFeeScheduleUpdate(FT_WITH_FRACTIONAL_FEE)
                    .withCustom(fractionalFeeNetOfTransfers(1L, 10L, 1L, OptionalLong.empty(), NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fractional fee after fee collector update
            final var tokenTransferAfterCollectorUpdate = cryptoTransfer(moving(1, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(NEW_FEE_COLLECTOR, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeCollectorUpdate,
                                    tokenFeeCollectorUpdate,
                                    tokenTransferAfterCollectorUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 3L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 95L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(NEW_FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Fractional Fees - Token transfer before and after fee amount, treasury and collector updates "
                        + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFractionalFeeBeforeAndAfterFeeAmountTreasuryAndCollectorUpdateChargesFeesCorrectlyInBatch() {

            // transfer FT with fractional fee before update
            final var tokenTransferToAssociatedReceiverFirst = cryptoTransfer(
                            moving(10, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdates")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferWithCustomFeesBeforeUpdate = cryptoTransfer(moving(5, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferWithCustomFeesBeforeUpdates")
                    .batchKey(BATCH_OPERATOR);

            // update fee amount and collector
            final var tokenFeeAndFeeCollectorUpdate = tokenFeeScheduleUpdate(FT_WITH_FRACTIONAL_FEE)
                    .withCustom(fractionalFeeNetOfTransfers(1L, 5L, 2L, OptionalLong.empty(), NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeAndCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update treasury
            final var tokenTreasuryUpdate = tokenUpdate(FT_WITH_FRACTIONAL_FEE)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fractional fee after fee collector, fee and treasury updates
            final var tokenTransferAfterUpdate = cryptoTransfer(moving(1, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                    .payingWith(RECEIVER_ASSOCIATED_SECOND)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(NEW_FEE_COLLECTOR, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferToAssociatedReceiverFirst,
                                    tokenTransferWithCustomFeesBeforeUpdate,
                                    tokenFeeAndFeeCollectorUpdate,
                                    tokenTreasuryUpdate,
                                    tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 2L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 90L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),
                    getAccountBalance(NEW_FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 2L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Fractional Fees - Token transfer before and after fractional fee updated to fixed "
                        + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFractionalFeeBeforeAndAfterFeeUpdatedToFractionalChargesFeesCorrectlyInBatch() {

            // transfer FT with fractional fee before update
            final var tokenTransferToAssociatedReceiverFirst = cryptoTransfer(
                            moving(10, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdates")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferWithCustomFeesBeforeUpdate = cryptoTransfer(moving(5, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferWithCustomFeesBeforeUpdates")
                    .batchKey(BATCH_OPERATOR);

            // update fractional fee to fixed fee and update collector
            final var tokenFeeAndFeeCollectorUpdate = tokenFeeScheduleUpdate(FT_WITH_FRACTIONAL_FEE)
                    .withCustom(fixedHbarFee(HBAR_FEE, NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeAndCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fractional fee after fee collector and fee updates
            final var tokenTransferAfterUpdate = cryptoTransfer(moving(1, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                    .payingWith(RECEIVER_ASSOCIATED_SECOND)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(NEW_FEE_COLLECTOR, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferToAssociatedReceiverFirst,
                                    tokenTransferWithCustomFeesBeforeUpdate,
                                    tokenFeeAndFeeCollectorUpdate,
                                    tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 90L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),
                    getAccountBalance(NEW_FEE_COLLECTOR).hasTinyBars(HBAR_FEE)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Fractional Fees - Token transfer before and after removed fee schedule "
                + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFractionalFeeBeforeAndAfterRemovedFeeScheduleChargesFeesCorrectlyInBatch() {

            // transfer FT with fractional fee before update
            final var tokenTransferToAssociatedReceiverFirst = cryptoTransfer(
                            moving(10, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdates")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferWithCustomFeesBeforeUpdate = cryptoTransfer(moving(5, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferWithCustomFeesBeforeUpdates")
                    .batchKey(BATCH_OPERATOR);

            // remove all custom fees
            final var tokenFeeUpdate = tokenFeeScheduleUpdate(FT_WITH_FRACTIONAL_FEE)
                    .signedBy(adminKey, feeScheduleKey)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT after all custom fees are removed
            final var tokenTransferAfterUpdate = cryptoTransfer(moving(1, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                    .payingWith(RECEIVER_ASSOCIATED_SECOND)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferToAssociatedReceiverFirst,
                                    tokenTransferWithCustomFeesBeforeUpdate,
                                    tokenFeeUpdate,
                                    tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 90L),
                    getAccountBalance(FEE_COLLECTOR)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L)
                            .hasTinyBars(0L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Fractional Fees - Max fee charged correctly in Batch")
        public Stream<DynamicTest> tokenTransferOfFTWithFractionalFeeMaxFeeChargedCorrectlyInBatch() {

            // transfer FT with fractional fee before max fee update
            final var tokenTransferBeforeUpdate = cryptoTransfer(
                            moving(3000, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update token with max fee
            final var tokenFeeUpdate = tokenFeeScheduleUpdate(FT_WITH_FRACTIONAL_FEE)
                    .withCustom(fractionalFeeNetOfTransfers(1L, 10L, 1L, OptionalLong.of(5L), FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fractional fee after fee update
            final var tokenTransferAfterUpdate = cryptoTransfer(moving(1000, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 5000, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(tokenTransferBeforeUpdate, tokenFeeUpdate, tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1995L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1000L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 5L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 2000L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Fixed and Fractional Fees - Token transfer before and after treasury update "
                + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFixedAndFractionalFeesBeforeAndAfterTreasuryUpdateChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed and fractional fee before treasury update
            final var tokenTransferBeforeTreasuryUpdate = cryptoTransfer(
                            moving(1, FT_FIXED_AND_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("tokenTransferBeforeTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update treasury
            final var tokenTreasuryUpdate = tokenUpdate(FT_FIXED_AND_FRACTIONAL_FEE)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .via("tokenTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer from new treasury to old treasury
            final var transferFromNewTreasuryToOldTreasury = cryptoTransfer(moving(2, FT_FIXED_AND_FRACTIONAL_FEE)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("transferFromNewTreasuryToOldTreasury")
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER)
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fixed and fractional fee after treasury update
            final var tokenTransferAfterTreasuryUpdate = cryptoTransfer(
                            moving(1, FT_FIXED_AND_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("tokenTransferAfterTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFractionalAndFixedFees(
                            FT_FIXED_AND_FRACTIONAL_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FIXED_AND_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FIXED_AND_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeTreasuryUpdate,
                                    tokenTreasuryUpdate,
                                    transferFromNewTreasuryToOldTreasury,
                                    tokenTransferAfterTreasuryUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 1L),
                    getAccountBalance(FEE_COLLECTOR)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 1L)
                            .hasTinyBars(HBAR_FEE),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 0L),
                    getTokenInfo(FT_FIXED_AND_FRACTIONAL_FEE)
                            .hasTreasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 97L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Fixed and Fractional Fees - Token transfer before and after fee and collector updates "
                        + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFixedAndFractionalFeesBeforeAndAfterFeeAndCollectorUpdatesChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed and fractional fees before updates
            final var tokenTransferToAssociatedReceiverFirst = cryptoTransfer(
                            moving(10, FT_FIXED_AND_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferNoCustomFeesBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferWithCustomFeesBeforeUpdate = cryptoTransfer(moving(5, FT_FIXED_AND_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferWithCustomFeesBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update custom fees and fee collector
            final var tokenFeeAndCollectorUpdate = tokenFeeScheduleUpdate(FT_FIXED_AND_FRACTIONAL_FEE)
                    .withCustom(fixedHbarFee(HBAR_FEE * 2, NEW_FEE_COLLECTOR))
                    .withCustom(fractionalFeeNetOfTransfers(1L, 5L, 2L, OptionalLong.empty(), NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fixed and fractional fee after updates
            final var tokenTransferAfterUpdates = cryptoTransfer(moving(1, FT_FIXED_AND_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFractionalAndFixedFees(
                            FT_FIXED_AND_FRACTIONAL_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR, FEE_COLLECTOR),
                    tokenAssociate(NEW_FEE_COLLECTOR, FT_FIXED_AND_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FIXED_AND_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FIXED_AND_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_FIXED_AND_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferToAssociatedReceiverFirst,
                                    tokenTransferWithCustomFeesBeforeUpdate,
                                    tokenFeeAndCollectorUpdate,
                                    tokenTransferAfterUpdates)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 2L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 1L),
                    getAccountBalance(FEE_COLLECTOR)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 1L)
                            .hasTinyBars(HBAR_FEE),
                    getAccountBalance(NEW_FEE_COLLECTOR)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 2L)
                            .hasTinyBars(HBAR_FEE * 2),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 90L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Fixed and Fractional Fees - Token transfer before and after removed fee schedule "
                        + "charges fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfFTWithFixedAndFractionalFeesBeforeAndAfterRemovedFeeScheduleChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed and fractional fees before updates
            final var tokenTransferToAssociatedReceiverFirst = cryptoTransfer(
                            moving(10, FT_FIXED_AND_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferNoCustomFeesBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferWithCustomFeesBeforeUpdate = cryptoTransfer(moving(5, FT_FIXED_AND_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferWithCustomFeesBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // remove all custom fees
            final var tokenFeeAndCollectorUpdate = tokenFeeScheduleUpdate(FT_FIXED_AND_FRACTIONAL_FEE)
                    .signedBy(adminKey, feeScheduleKey)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT after all custom fees are removed
            final var tokenTransferAfterUpdates = cryptoTransfer(moving(1, FT_FIXED_AND_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_SECOND, RECEIVER_ASSOCIATED_THIRD))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFractionalAndFixedFees(
                            FT_FIXED_AND_FRACTIONAL_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FIXED_AND_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FIXED_AND_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FT_FIXED_AND_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferToAssociatedReceiverFirst,
                                    tokenTransferWithCustomFeesBeforeUpdate,
                                    tokenFeeAndCollectorUpdate,
                                    tokenTransferAfterUpdates)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 1L),
                    getAccountBalance(FEE_COLLECTOR)
                            .hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 1L)
                            .hasTinyBars(HBAR_FEE),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FIXED_AND_FRACTIONAL_FEE, 90L)));
        }

        @HapiTest
        @DisplayName("NFT with Two Layers of Fixed Fees - Token transfer before and after treasury update charges "
                + "all fee layers correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfNFTWithFixedFeeBeforeAndAfterTreasuryUpdateChargesFeesCorrectlyInBatch() {

            // transfer NFT with fixed fee before treasury update
            final var tokenTransferBeforeTreasuryUpdate = cryptoTransfer(
                            movingUnique(NFT_WITH_HTS_FEE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("tokenTransferBeforeTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update treasury
            final var tokenTreasuryUpdate = tokenUpdate(NFT_WITH_HTS_FEE)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .via("tokenTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer from new treasury to old treasury
            final var transferFromNewTreasuryToOldTreasury = cryptoTransfer(movingUnique(NFT_WITH_HTS_FEE, 2L)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("transferFromNewTreasuryToOldTreasury")
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER)
                    .batchKey(BATCH_OPERATOR);

            // transfer NFT with fixed fee after treasury update
            final var tokenTransferAfterTreasuryUpdate = cryptoTransfer(
                            movingUnique(NFT_WITH_HTS_FEE, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("tokenTransferAfterTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(DENOM_TOKEN, 100, DENOM_TREASURY, adminKey),
                    tokenAssociate(DENOM_COLLECTOR, DENOM_TOKEN),
                    tokenAssociate(OWNER, DENOM_TOKEN),
                    cryptoTransfer(moving(10, DENOM_TOKEN).between(DENOM_TREASURY, OWNER)),
                    createFungibleTokenWithFixedHtsFee(FT_WITH_HTS_FIXED_FEE, 100, DENOM_TREASURY, adminKey, HTS_FEE),
                    tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(OWNER, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, OWNER)),
                    createNFTWithFixedFee(NFT_WITH_HTS_FEE, OWNER, supplyKey, adminKey, HTS_FEE),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_HTS_FEE),

                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeTreasuryUpdate,
                                    tokenTreasuryUpdate,
                                    transferFromNewTreasuryToOldTreasury,
                                    tokenTransferAfterTreasuryUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_WITH_HTS_FEE, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_WITH_HTS_FEE, 1L),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 0L)
                            .hasTokenBalance(DENOM_TOKEN, 10 - HTS_FEE)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10 - HTS_FEE),
                    getAccountBalance(DENOM_TREASURY).hasTokenBalance(DENOM_TOKEN, 90),
                    getAccountBalance(DENOM_TREASURY).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 90),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, HTS_FEE),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, HTS_FEE),
                    getTokenInfo(NFT_WITH_HTS_FEE).hasTreasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 8L)));
        }

        @HapiTest
        @DisplayName(
                "NFT with Two Layers of Fixed Fees - Token transfer before and after fee and collector update charges "
                        + "all fee layers correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfNFTWithFixedFeeBeforeAndAfterFeeAndCollectorUpdatesChargesFeesCorrectlyInBatch() {

            // transfer NFT with fixed fee before updates
            final var tokenTransferBeforeUpdates = cryptoTransfer(
                            movingUnique(NFT_WITH_HTS_FEE, 1L, 2L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update fee and collector
            final var tokenFeeAndCollectorUpdate = tokenFeeScheduleUpdate(NFT_WITH_HTS_FEE)
                    .withCustom(fixedHtsFee(HTS_FEE * 2, FT_WITH_HTS_FIXED_FEE, NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, OWNER, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeAndCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer NFT with fixed fee after update
            final var tokenTransferAfterUpdates = cryptoTransfer(movingUnique(NFT_WITH_HTS_FEE, 1L)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(DENOM_TOKEN, 100, DENOM_TREASURY, adminKey),
                    tokenAssociate(DENOM_COLLECTOR, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, DENOM_TOKEN),
                    cryptoTransfer(moving(10, DENOM_TOKEN).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST)),
                    createFungibleTokenWithFixedHtsFee(FT_WITH_HTS_FIXED_FEE, 100, DENOM_TREASURY, adminKey, HTS_FEE),
                    tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(NEW_FEE_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(
                            moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST)),
                    createNFTWithFixedFee(NFT_WITH_HTS_FEE, OWNER, supplyKey, adminKey, HTS_FEE),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_HTS_FEE),

                    // perform token transfers in batch
                    atomicBatch(tokenTransferBeforeUpdates, tokenFeeAndCollectorUpdate, tokenTransferAfterUpdates)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 1L)
                            .hasTokenBalance(DENOM_TOKEN, 10 - HTS_FEE)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10 - (HTS_FEE * 2)),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_WITH_HTS_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_HTS_FEE, 8L),
                    getAccountBalance(DENOM_TREASURY).hasTokenBalance(DENOM_TOKEN, 90),
                    getAccountBalance(DENOM_TREASURY).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 90),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 0L),
                    getAccountBalance(NEW_FEE_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, HTS_FEE * 2),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, HTS_FEE)));
        }

        @HapiTest
        @DisplayName("NFT with Two Layers of Fixed Fees - Token transfer before and after removed fee schedule "
                + "all fee layers correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfNFTWithFixedFeeBeforeAndAfterRemovedFeeScheduleChargesFeesCorrectlyInBatch() {

            // transfer NFT with fixed fee before updates
            final var tokenTransferBeforeUpdates = cryptoTransfer(
                            movingUnique(NFT_WITH_HTS_FEE, 1L, 2L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // remove all custom fees
            final var tokenFeeAndCollectorUpdate = tokenFeeScheduleUpdate(NFT_WITH_HTS_FEE)
                    .signedBy(adminKey, feeScheduleKey)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeAndCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer NFT with fixed fee after treasury update
            final var tokenTransferAfterUpdates = cryptoTransfer(movingUnique(NFT_WITH_HTS_FEE, 1L)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(DENOM_TOKEN, 100, DENOM_TREASURY, adminKey),
                    tokenAssociate(DENOM_COLLECTOR, DENOM_TOKEN),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, DENOM_TOKEN),
                    cryptoTransfer(moving(10, DENOM_TOKEN).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST)),
                    createFungibleTokenWithFixedHtsFee(FT_WITH_HTS_FIXED_FEE, 100, DENOM_TREASURY, adminKey, HTS_FEE),
                    tokenAssociate(HTS_COLLECTOR, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(
                            moving(10, FT_WITH_HTS_FIXED_FEE).between(DENOM_TREASURY, RECEIVER_ASSOCIATED_FIRST)),
                    createNFTWithFixedFee(NFT_WITH_HTS_FEE, OWNER, supplyKey, adminKey, HTS_FEE),
                    mintNFT(NFT_WITH_HTS_FEE, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_HTS_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_HTS_FEE),

                    // perform token transfers in batch
                    atomicBatch(tokenTransferBeforeUpdates, tokenFeeAndCollectorUpdate, tokenTransferAfterUpdates)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_HTS_FEE, 1L)
                            .hasTokenBalance(DENOM_TOKEN, 10)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 10),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_WITH_HTS_FEE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_HTS_FEE, 8L),
                    getAccountBalance(DENOM_TREASURY).hasTokenBalance(DENOM_TOKEN, 90),
                    getAccountBalance(DENOM_TREASURY).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 90),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 0L),
                    getAccountBalance(DENOM_COLLECTOR).hasTokenBalance(DENOM_TOKEN, 0)));
        }

        @HapiTest
        @DisplayName("NFT with Royalty Fee No Fallback - Token transfer before and after treasury update charges "
                + "all fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfNFTWithRoyaltyNoFallbackFeeBeforeAndAfterTreasuryUpdateChargesFeesCorrectlyInBatch() {

            // transfer NFT with royalty fee before treasury update
            final var tokenTransferBeforeTreasuryUpdate = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update treasury
            final var tokenTreasuryUpdate = tokenUpdate(NFT_WITH_ROYALTY_FEE_NO_FALLBACK)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer from new treasury to old treasury
            final var transferFromNewTreasuryToOldTreasury = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 2L)
                                    .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("transferFromNewTreasuryToOldTreasury")
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER)
                    .batchKey(BATCH_OPERATOR);

            // transfer NFT with fixed fee after treasury update
            final var tokenTransferAfterTreasuryUpdate = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 2L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                            movingHbar(100).between(RECEIVER_ASSOCIATED_SECOND, OWNER)) // to trigger royalty fee
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithRoyaltyNoFallback(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, OWNER, supplyKey, adminKey),
                    mintNFT(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_ROYALTY_FEE_NO_FALLBACK),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_ROYALTY_FEE_NO_FALLBACK),

                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeTreasuryUpdate,
                                    tokenTreasuryUpdate,
                                    transferFromNewTreasuryToOldTreasury,
                                    tokenTransferAfterTreasuryUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 0L),
                    getAccountBalance(ROYALTY_FEE_COLLECTOR).hasTinyBars(10),
                    getTokenInfo(NFT_WITH_ROYALTY_FEE_NO_FALLBACK)
                            .hasTreasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 8L)));
        }

        @HapiTest
        @DisplayName("NFT with Royalty Fee No Fallback - Token transfer before and after removed fee schedule "
                + "all fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfNFTWithRoyaltyNoFallbackFeeBeforeAndAfterRemovedFeeScheduleChargesFeesCorrectlyInBatch() {

            // transfer NFT with royalty fee before update
            final var tokenTransferBeforeUpdate = cryptoTransfer(movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L)
                            .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // remove all custom fees
            final var tokenFeeUpdate = tokenFeeScheduleUpdate(NFT_WITH_ROYALTY_FEE_NO_FALLBACK)
                    .signedBy(adminKey, feeScheduleKey)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer NFT after all custom fees are removed
            final var tokenTransferAfterUpdate = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L)
                                    .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND),
                            movingHbar(100)
                                    .between(
                                            RECEIVER_ASSOCIATED_SECOND,
                                            RECEIVER_ASSOCIATED_FIRST)) // to check whether royalty fee is triggered
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithRoyaltyNoFallback(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, OWNER, supplyKey, adminKey),
                    mintNFT(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_ROYALTY_FEE_NO_FALLBACK),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_ROYALTY_FEE_NO_FALLBACK),

                    // perform token transfers in batch
                    atomicBatch(tokenTransferBeforeUpdate, tokenFeeUpdate, tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 9L),
                    getAccountBalance(ROYALTY_FEE_COLLECTOR).hasTinyBars(0L)));
        }

        @HapiTest
        @DisplayName("NFT with Royalty Fee With Fallback - Token transfer with fungible value exchanged before "
                + "and after treasury update charges all fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfNFTWithRoyaltyWithFallbackFeeWithFTExchangedBeforeAndAfterTreasuryUpdateChargesFeesCorrectlyInBatch() {

            // transfer NFT with royalty fee before treasury update
            final var tokenTransferBeforeTreasuryUpdate = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update treasury
            final var tokenTreasuryUpdate = tokenUpdate(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer from new treasury to old treasury
            final var transferFromNewTreasuryToOldTreasury = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 2L)
                                    .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("transferFromNewTreasuryToOldTreasury")
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER)
                    .batchKey(BATCH_OPERATOR);

            // transfer NFT with fixed fee after treasury update
            final var tokenTransferAfterTreasuryUpdate = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 2L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                            movingHbar(100).between(RECEIVER_ASSOCIATED_SECOND, OWNER)) // to trigger royalty fee
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithRoyaltyWithFallback(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, OWNER, supplyKey, adminKey),
                    mintNFT(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),

                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeTreasuryUpdate,
                                    tokenTreasuryUpdate,
                                    transferFromNewTreasuryToOldTreasury,
                                    tokenTransferAfterTreasuryUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0L),
                    getAccountBalance(ROYALTY_FEE_COLLECTOR).hasTinyBars(10),
                    getTokenInfo(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK)
                            .hasTreasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 8L)));
        }

        @HapiTest
        @DisplayName("NFT with Royalty Fee With Fallback - Token transfer without fungible value exchanged before and "
                + "after treasury update charges all fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfNFTWithRoyaltyWithFallbackFeeNoFTExchangedBeforeAndAfterTreasuryUpdateChargesFeesCorrectlyInBatch() {

            // transfer NFT with royalty fee before treasury update
            final var tokenTransferBeforeTreasuryUpdate = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update treasury
            final var tokenTreasuryUpdate = tokenUpdate(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer from new treasury to old treasury
            final var transferFromNewTreasuryToOldTreasury = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 2L)
                                    .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("transferFromNewTreasuryToOldTreasury")
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER)
                    .batchKey(BATCH_OPERATOR);

            // transfer NFT with fixed fee after treasury update
            final var tokenTransferAfterTreasuryUpdate = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 2L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .signedBy(OWNER, RECEIVER_ASSOCIATED_SECOND)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithRoyaltyWithFallback(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, OWNER, supplyKey, adminKey),
                    mintNFT(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),

                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeTreasuryUpdate,
                                    tokenTreasuryUpdate,
                                    transferFromNewTreasuryToOldTreasury,
                                    tokenTransferAfterTreasuryUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0L),
                    getAccountBalance(ROYALTY_FEE_COLLECTOR).hasTinyBars(1L),
                    getTokenInfo(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK)
                            .hasTreasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 8L)));
        }

        @HapiTest
        @DisplayName("NFT with Royalty Fee With Fallback - Token transfer without fungible value exchanged before and "
                + "after removed fee schedule charges all fees correctly in Batch")
        public Stream<DynamicTest>
                tokenTransferOfNFTWithRoyaltyWithFallbackFeeNoFTExchangedBeforeAndAfterRemovedFeeScheduleChargesFeesCorrectlyInBatch() {

            // transfer NFT with royalty fee before update
            final var tokenTransferBeforeUpdate = cryptoTransfer(movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L)
                            .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // remove all custom fees
            final var tokenFeeUpdate = tokenFeeScheduleUpdate(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK)
                    .signedBy(adminKey, feeScheduleKey)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer NFT after all custom fees are removed
            final var tokenTransferAfterUpdate = cryptoTransfer(movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithRoyaltyWithFallback(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, OWNER, supplyKey, adminKey),
                    mintNFT(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),

                    // perform token transfers in batch
                    atomicBatch(tokenTransferBeforeUpdate, tokenFeeUpdate, tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 9L),
                    getAccountBalance(ROYALTY_FEE_COLLECTOR).hasTinyBars(0L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Custom Fees - Token transfer before and after token treasury is updated "
                + "and old treasury is deleted successfully in Batch")
        public Stream<DynamicTest>
                transferFTTokenWithCustomFeesBeforeAndAfterFeeTreasuryIsDeletedSuccessfullyInBatch() {

            // transfer FT with fractional fee before fee treasury is deleted
            final var tokenTransferBeforeTreasuryDeleted = cryptoTransfer(
                            moving(10, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeTreasuryDelete")
                    .batchKey(BATCH_OPERATOR);

            // update fee treasury before old treasury is deleted
            final var tokenFeeTreasuryUpdate = tokenUpdate(FT_WITH_FRACTIONAL_FEE)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeTreasuryUpdate")
                    .batchKey(BATCH_OPERATOR);

            // delete old fee treasury
            final var tokenFeeOldTreasuryDelete = cryptoDelete(OWNER)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeOldTreasuryDelete")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fractional fee after old fee treasury is deleted
            final var tokenTransferAfterOldTreasuryDelete = cryptoTransfer(moving(5, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterTreasuryDelete")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeTreasuryDeleted,
                                    tokenFeeTreasuryUpdate,
                                    tokenFeeOldTreasuryDelete,
                                    tokenTransferAfterOldTreasuryDelete)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 5L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 90L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),

                    // confirm old fee treasury is deleted
                    cryptoTransfer(movingHbar(1).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .hasKnownStatus(ACCOUNT_DELETED)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Custom Fees - Multiple Token transfers with tokens with custom fees "
                + "charges fees correctly in Batch")
        public Stream<DynamicTest> multipleTokenTransfersOfTokensWithCustomFeesChargesFeesCorrectlyInBatch() {

            // transfer FT with fixed fee
            final var tokenTransferFTWithFixedFeeNoCustomFees = cryptoTransfer(
                            moving(5, FT_WITH_HBAR_FIXED_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferFTWithFixedFeeNoCustomFees")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferFTWithFixedFeeWithCustomFees = cryptoTransfer(moving(1, FT_WITH_HBAR_FIXED_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferFTWithFixedFeeWithCustomFees")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fractional fee
            final var tokenTransferFTWithFractionalFeeNoCustomFees = cryptoTransfer(
                            moving(5, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferFTWithFractionalFeeNoCustomFees")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferFTWithFractionalFeeWithCustomFees = cryptoTransfer(moving(1, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferFTWithFractionalFeeWithCustomFees")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithFixedHbarFee(
                            FT_WITH_HBAR_FIXED_FEE, 100, OWNER, adminKey, HBAR_FEE, FEE_COLLECTOR),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_HBAR_FIXED_FEE, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_HBAR_FIXED_FEE, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in a batch
                    atomicBatch(
                                    tokenTransferFTWithFixedFeeNoCustomFees,
                                    tokenTransferFTWithFixedFeeWithCustomFees,
                                    tokenTransferFTWithFractionalFeeNoCustomFees,
                                    tokenTransferFTWithFractionalFeeWithCustomFees)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 4L)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 3L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1L)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L),
                    getAccountBalance(OWNER)
                            .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 95L)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 95L),
                    getAccountBalance(FEE_COLLECTOR)
                            .hasTinyBars(HBAR_FEE)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L)));
        }
    }

    @Nested
    @DisplayName("Atomic Batch End-to-End Test Cases for Tokens with Custom Fees - Negative Cases")
    class AtomicBatchEndToEndTestsForTokensWithCustomFeesNegativeCases {

        @HapiTest
        @DisplayName(
                "Fungible Token with Custom Fees - Update fee collector with new not associated collector fails in Batch")
        public Stream<DynamicTest> updateFTTokenWithCustomFeesWithNotAssociatedFeeCollectorFailsInBatch() {

            // transfer FT with fractional fee before fee collector update
            final var tokenTransferBeforeCollectorUpdate = cryptoTransfer(
                            moving(5, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update fee collector
            final var tokenFeeCollectorUpdate = tokenFeeScheduleUpdate(FT_WITH_FRACTIONAL_FEE)
                    .withCustom(fractionalFeeNetOfTransfers(1L, 10L, 1L, OptionalLong.empty(), NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR);

            // transfer FT with fractional fee after fee collector update
            final var tokenTransferAfterCollectorUpdate = cryptoTransfer(moving(1, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeCollectorUpdate,
                                    tokenFeeCollectorUpdate,
                                    tokenTransferAfterCollectorUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 100L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(NEW_FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Custom Fees - Token transfer before and after fee collector update with "
                + "insufficient account balance fails in Batch")
        public Stream<DynamicTest>
                transferFTTokenWithCustomFeesAfterFeeCollectorIsUpdatedWithInsufficientAccountBalanceFailsInBatch() {

            // transfer FT with fractional fee before fee collector update
            final var tokenTransferBeforeCollectorUpdate = cryptoTransfer(
                            moving(1, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // update fee collector
            final var tokenFeeCollectorUpdate = tokenFeeScheduleUpdate(FT_WITH_FRACTIONAL_FEE)
                    .withCustom(fractionalFeeNetOfTransfers(1L, 10L, 1L, OptionalLong.empty(), NEW_FEE_COLLECTOR))
                    .signedBy(adminKey, feeScheduleKey, NEW_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeCollectorUpdate")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fractional fee after fee collector update
            final var tokenTransferAfterCollectorUpdate = cryptoTransfer(moving(1, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterCollectorUpdate")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(NEW_FEE_COLLECTOR, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeCollectorUpdate,
                                    tokenFeeCollectorUpdate,
                                    tokenTransferAfterCollectorUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 100L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(NEW_FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L)));
        }

        @HapiTest
        @DisplayName(
                "Fungible Token with Custom Fees - Token fee schedule remove not signed by fee schedule key fails in Batch "
                        + "and custom fees are still applied")
        public Stream<DynamicTest> tokenWithCustomFeesRemoveFeeScheduleNotSignedByFeeScheduleKeyFailsInBatch() {

            // transfer FT with fractional fee before fee schedule is removed
            final var tokenTransferBeforeUpdate = cryptoTransfer(
                            moving(1, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeUpdate")
                    .batchKey(BATCH_OPERATOR);

            // remove all custom fees
            final var tokenFeeUpdate = tokenFeeScheduleUpdate(FT_WITH_FRACTIONAL_FEE)
                    .signedBy(adminKey) // missing feeScheduleKey
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeUpdate")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            // transfer FT with fractional fee after update
            final var tokenTransferAfterUpdate = cryptoTransfer(moving(1, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterUpdate")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(tokenTransferBeforeUpdate, tokenFeeUpdate, tokenTransferAfterUpdate)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 100L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),

                    // validate custom fees are still applied
                    cryptoTransfer(moving(10, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .fee(ONE_HBAR)
                            .via("postBatchTxnFirst"),
                    cryptoTransfer(moving(5, FT_WITH_FRACTIONAL_FEE)
                                    .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                            .payingWith(RECEIVER_ASSOCIATED_FIRST)
                            .fee(ONE_HBAR)
                            .via("postBatchTxnSecond"),

                    // validate account balances after post batch transactions
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 5L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 90L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Custom Fees - Token transfer after fee collector is deleted fails in Batch")
        public Stream<DynamicTest> transferFTTokenWithCustomFeesAfterFeeCollectorIsDeletedFailsInBatch() {

            // transfer FT with fractional fee before fee collector is deleted
            final var tokenTransferBeforeCollectorDeleted = cryptoTransfer(
                            moving(1, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeCollectorDelete")
                    .batchKey(BATCH_OPERATOR);

            // delete fee collector
            final var tokenFeeCollectorDelete = cryptoDelete(FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeCollectorDelete")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fractional fee after fee collector is deleted
            final var tokenTransferAfterCollectorDelete = cryptoTransfer(moving(1, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterCollectorDelete")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(ACCOUNT_DELETED);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeCollectorDeleted,
                                    tokenFeeCollectorDelete,
                                    tokenTransferAfterCollectorDelete)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 100L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L)));
        }

        @HapiTest
        @DisplayName("NFT with Royalty Fee With Fallback - Token transfer without fungible value exchanged after "
                + "collector is deleted fails in Batch")
        public Stream<DynamicTest>
                tokenTransferOfNFTWithRoyaltyWithFallbackFeeNoFTExchangedAfterCollectorIsDeletedFailsInBatch() {

            // transfer NFT with royalty fee before fee collector is deleted
            final var tokenTransferBeforeCollectorDelete = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L)
                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeCollectorDelete")
                    .batchKey(BATCH_OPERATOR);

            // delete fee collector
            final var tokenFeeCollectorDelete = cryptoDelete(ROYALTY_FEE_COLLECTOR)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeCollectorDelete")
                    .batchKey(BATCH_OPERATOR);

            // transfer NFT with fixed fee after fee collector is deleted
            final var tokenTransferAfterCollectorDelete = cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 1L)
                                    .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .signedBy(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterCollectorDelete")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(ACCOUNT_DELETED);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithRoyaltyWithFallback(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, OWNER, supplyKey, adminKey),
                    mintNFT(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_WITH_ROYALTY_FEE_WITH_FALLBACK),

                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeCollectorDelete,
                                    tokenFeeCollectorDelete,
                                    tokenTransferAfterCollectorDelete)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_WITH_ROYALTY_FEE_WITH_FALLBACK, 10L),
                    getAccountBalance(ROYALTY_FEE_COLLECTOR).hasTinyBars(0L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Custom Fees - Token Treasury can not be deleted in Batch")
        public Stream<DynamicTest> tokenTreasuryForTokenWithCustomFeesDeleteFailsInBatch() {

            // transfer FT with fractional fee before fee treasury is deleted
            final var tokenTransferBeforeTreasuryDeleted = cryptoTransfer(
                            moving(1, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeTreasuryDelete")
                    .batchKey(BATCH_OPERATOR);

            // delete fee treasury
            final var tokenFeeTreasuryDelete = cryptoDelete(OWNER)
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenFeeOldTreasuryDelete")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(ACCOUNT_IS_TREASURY);

            // transfer FT with fractional fee after fee treasury is deleted
            final var tokenTransferAfterTreasuryDelete = cryptoTransfer(moving(1, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterTreasuryDelete")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeTreasuryDeleted,
                                    tokenFeeTreasuryDelete,
                                    tokenTransferAfterTreasuryDelete)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 100L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L)));
        }

        @HapiTest
        @DisplayName("Fungible Token with Custom Fees - Token transfer after token is deleted fails in Batch")
        public Stream<DynamicTest> transferFTTokenWithCustomFeesAfterTokenIsDeletedFailsInBatch() {

            // transfer FT with fractional fee before token is deleted
            final var tokenTransferBeforeTreasuryDeleted = cryptoTransfer(
                            moving(10, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .fee(ONE_HBAR)
                    .via("tokenTransferBeforeDelete")
                    .batchKey(BATCH_OPERATOR);

            // delete token
            final var deleteTokenWithCustomFees = tokenDelete(FT_WITH_FRACTIONAL_FEE)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .fee(ONE_HBAR)
                    .via("tokenDelete")
                    .batchKey(BATCH_OPERATOR);

            // transfer FT with fractional fee after token is deleted
            final var tokenTransferAfterTokenDelete = cryptoTransfer(moving(5, FT_WITH_FRACTIONAL_FEE)
                            .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .fee(ONE_HBAR)
                    .via("tokenTransferAfterDelete")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(TOKEN_WAS_DELETED);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithTenPercentFractionalFee(
                            FT_WITH_FRACTIONAL_FEE, 100, OWNER, adminKey, FEE_COLLECTOR),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_WITH_FRACTIONAL_FEE),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_WITH_FRACTIONAL_FEE),
                    // perform token transfers in batch
                    atomicBatch(
                                    tokenTransferBeforeTreasuryDeleted,
                                    deleteTokenWithCustomFees,
                                    tokenTransferAfterTokenDelete)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 0L),

                    // confirm token is not deleted
                    cryptoTransfer(moving(10, FT_WITH_FRACTIONAL_FEE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .payingWith(OWNER)
                            .fee(ONE_HBAR)
                            .via("tokenTransferPostBatchFirst"),
                    cryptoTransfer(moving(5, FT_WITH_FRACTIONAL_FEE)
                                    .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                            .payingWith(RECEIVER_ASSOCIATED_FIRST)
                            .fee(ONE_HBAR)
                            .via("tokenTransferPostBatchSecond"),
                    // validate account balances after post batch transactions
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 4L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 5L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 90L),
                    getAccountBalance(FEE_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 1L)));
        }
    }

    private HapiTokenCreate createFungibleTokenWithFixedHbarFee(
            String tokenName, long supply, String treasury, String adminKey, long hbarFee, String fixedFeeCollector) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON)
                .withCustom(fixedHbarFee(hbarFee, fixedFeeCollector));
    }

    private HapiTokenCreate createFungibleTokenWithFixedHtsFee(
            String tokenName, long supply, String treasury, String adminKey, long htsFee) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON)
                .withCustom(fixedHtsFee(htsFee, DENOM_TOKEN, DENOM_COLLECTOR));
    }

    private HapiTokenCreate createFungibleTokenWithTenPercentFractionalFee(
            String tokenName, long supply, String treasury, String adminKey, String fractionalFeeCollector) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON)
                .withCustom(fractionalFeeNetOfTransfers(1L, 10L, 1L, OptionalLong.empty(), fractionalFeeCollector))
                .payingWith(treasury)
                .signedBy(treasury, adminKey, fractionalFeeCollector);
    }

    private HapiTokenCreate createFungibleTokenWithFractionalAndFixedFees(
            String tokenName,
            long supply,
            String treasury,
            String adminKey,
            long hbarFee,
            String fixedFeeCollector,
            String fractionalFeeCollector) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON)
                .withCustom(fixedHbarFee(hbarFee, fixedFeeCollector))
                .withCustom(fractionalFeeNetOfTransfers(1L, 10L, 1L, OptionalLong.empty(), fractionalFeeCollector))
                .payingWith(treasury)
                .signedBy(treasury, adminKey, fractionalFeeCollector);
    }

    private HapiTokenCreate createNFTWithFixedFee(
            String tokenName, String treasury, String supplyKey, String adminKey, long htsFee) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .withCustom(fixedHtsFee(htsFee, FT_WITH_HTS_FIXED_FEE, HTS_COLLECTOR));
    }

    private HapiTokenCreate createNFTWithRoyaltyNoFallback(
            String tokenName, String treasury, String supplyKey, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .withCustom(royaltyFeeNoFallback(1L, 10L, ROYALTY_FEE_COLLECTOR));
    }

    private HapiTokenCreate createNFTWithRoyaltyWithFallback(
            String tokenName, String treasury, String supplyKey, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .withCustom(royaltyFeeWithFallback(
                        1L, 10L, fixedHbarFeeInheritingRoyaltyCollector(1), ROYALTY_FEE_COLLECTOR));
    }

    private HapiTokenMint mintNFT(String tokenName, int rangeStart, int rangeEnd) {
        return mintToken(
                tokenName,
                IntStream.range(rangeStart, rangeEnd)
                        .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                        .toList());
    }

    private HapiTokenCreate createFungibleTokenWithAdminKey(
            String tokenName, long supply, String treasury, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .feeScheduleKey(feeScheduleKey)
                .tokenType(FUNGIBLE_COMMON);
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HBAR),
                cryptoCreate(OWNER).balance(ONE_BILLION_HBARS),
                cryptoCreate(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                        .balance(ONE_MILLION_HBARS)
                        .maxAutomaticTokenAssociations(-1),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_THIRD).balance(ONE_HBAR),
                cryptoCreate(FEE_COLLECTOR).balance(0L),
                cryptoCreate(NEW_FEE_COLLECTOR).balance(0L),
                cryptoCreate(NEW_FEE_COLLECTOR_SECOND).balance(0L),
                cryptoCreate(HTS_COLLECTOR).balance(0L),
                cryptoCreate(DENOM_COLLECTOR).balance(0L),
                cryptoCreate(ROYALTY_FEE_COLLECTOR).balance(0L),
                cryptoCreate(DENOM_TREASURY).balance(0L),
                newKeyNamed(adminKey),
                newKeyNamed(feeScheduleKey),
                newKeyNamed(supplyKey));
    }
}
