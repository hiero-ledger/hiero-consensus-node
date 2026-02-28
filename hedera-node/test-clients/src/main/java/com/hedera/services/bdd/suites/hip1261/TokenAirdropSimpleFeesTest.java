// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNftPendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithAllowance;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithDecimals;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTAndNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenAirdropSurchargeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsdWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_BASE_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AIRDROP_CONTAINS_MULTIPLE_SENDERS_FOR_A_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_TOKEN_TRANSFER_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PENDING_NFT_AIRDROP_ALREADY_EXISTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNEXPECTED_TOKEN_DECIMALS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.AIRDROPS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hiero.hapi.support.fees.Extra.TOKEN_TYPES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenAirdropSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String THRESHOLD_PAYER = "thresholdPayer";
    private static final String PAYER_INSUFFICIENT_BALANCE = "payerInsufficientBalance";
    private static final String PAYER_WITH_HOOK = "payerWithHook";
    private static final String PAYER_WITH_TWO_HOOKS = "payerWithTwoHooks";
    private static final String PAYER_WITH_THREE_HOOKS = "payerWithThreeHooks";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String RECEIVER_ASSOCIATED_THIRD = "receiverAssociatedThird";
    private static final String RECEIVER_UNLIMITED_AUTO_ASSOCIATIONS = "receiverUnlimitedAutoAssociations";
    private static final String RECEIVER_FREE_AUTO_ASSOCIATIONS = "receiverFreeAutoAssociations";
    private static final String RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS = "receiverWithoutFreeAutoAssociations";
    private static final String RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND =
            "receiverWithoutFreeAutoAssociationsSecond";
    private static final String RECEIVER_NOT_ASSOCIATED = "receiverNotAssociated";
    private static final String RECEIVER_WITH_SIG_REQUIRED = "receiver_sig_required";
    private static final String RECEIVER_ZERO_BALANCE = "receiverZeroBalance";
    private static final String VALID_ALIAS_ED25519 = "validAliasED25519";
    private static final String VALID_ALIAS_ED25519_SECOND = "validAliasED25519Second";
    private static final String VALID_ALIAS_ECDSA = "validAliasECDSA";
    private static final String VALID_ALIAS_ECDSA_SECOND = "validAliasECDSASecond";
    private static final String VALID_ALIAS_HOLLOW = "validAliasHollow";
    private static final String VALID_ALIAS_HOLLOW_SECOND = "validAliasHollowSecond";
    private static final String OWNER = "owner";
    private static final String HBAR_OWNER_INSUFFICIENT_BALANCE = "hbarOwnerInsufficientBalance";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String FUNGIBLE_TOKEN_2 = "fungibleToken2";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String adminKey = "adminKey";
    private static final String supplyKey = "supplyKey";
    private static final String freezeKey = "freezeKey";
    private static final String pauseKey = "pauseKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("Token Airdrop Simple Fees Tests")
    class TokenAirdropSimpleFeesTests {

        @Nested
        @DisplayName("Token Airdrop Simple Fees Positive Tests")
        class TokenAirdropSimpleFeesPositiveTests {
            @HapiTest
            @DisplayName("Token Airdrop FT to Associated Receiver - base fees full charging")
            final Stream<DynamicTest> tokenAirdropFTToAssociatedReceiverBaseFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ACCOUNTS, 2L,
                                        TOKEN_TYPES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Token Airdrop NFT to Associated Receiver - base fees full charging")
            final Stream<DynamicTest> tokenAirdropNFTToAssociatedReceiverBaseFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferNFTFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ACCOUNTS, 2L,
                                        TOKEN_TYPES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
            }

            @HapiTest
            @DisplayName("Token Airdrop FT and NFT to Associated Receiver - base fees full charging")
            final Stream<DynamicTest> tokenAirdropFTAndNFTToAssociatedReceiverBaseFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                        tokenAirdrop(
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ACCOUNTS, 2L,
                                        TOKEN_TYPES, 2L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Token Airdrop FT to Multiple Associated Receivers - full fees with extras charging")
            final Stream<DynamicTest> tokenAirdropFTToMultipleAssociatedReceiversExtrasFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FUNGIBLE_TOKEN),
                        tokenAirdrop(
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_THIRD))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ACCOUNTS, 4L,
                                        TOKEN_TYPES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 70L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FUNGIBLE_TOKEN, 10L),
                        getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName("Token Airdrop NFT to Multiple Associated Receivers - full fees with extras charging")
            final Stream<DynamicTest> tokenAirdropNFTToMultipleAssociatedReceiversExtrasFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 4),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NON_FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NON_FUNGIBLE_TOKEN),
                        tokenAirdrop(
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 3L).between(OWNER, RECEIVER_ASSOCIATED_THIRD))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ACCOUNTS, 4L,
                                        TOKEN_TYPES, 3L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                        getAccountBalance(RECEIVER_ASSOCIATED_THIRD).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
            }

            @HapiTest
            @DisplayName("Token Airdrop FT and NFT to Multiple Associated Receivers - full fees with extras charging")
            final Stream<DynamicTest> tokenAirdropFTAndNFTToMultipleAssociatedReceiversExtrasFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_THIRD, FUNGIBLE_TOKEN),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 4),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NON_FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_THIRD, NON_FUNGIBLE_TOKEN),
                        tokenAirdrop(
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_THIRD),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 3L).between(OWNER, RECEIVER_ASSOCIATED_THIRD))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ACCOUNTS, 4L,
                                        TOKEN_TYPES, 4L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 70L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                        getAccountBalance(RECEIVER_ASSOCIATED_THIRD)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L)));
            }

            @HapiTest
            @DisplayName("Token Airdrop FT to Receiver with Free Auto-Associations - base fees full charging")
            final Stream<DynamicTest> tokenAirdropReceiverFreeAutoAssociationsBaseFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                SIGNATURES, 1L,
                                                ACCOUNTS, 2L,
                                                TOKEN_TYPES, 1L,
                                                PROCESSING_BYTES, (long) txnSize))
                                        + TOKEN_ASSOCIATE_BASE_FEE_USD
                                        + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 1L)),
                                0.1),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN, 10L)));
            }

            @HapiTest
            @DisplayName(
                    "Token Airdrop FT to Receiver without Free Auto-Associations - One Pending Airdrop - base fees full charging")
            final Stream<DynamicTest> tokenAirdropReceiverNoFreeAutoAssociationsResultsInPendingBaseFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        getTxnRecord("tokenAirdropTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                SIGNATURES, 1L,
                                                ACCOUNTS, 2L,
                                                TOKEN_TYPES, 1L,
                                                PROCESSING_BYTES, (long) txnSize))
                                        + TOKEN_ASSOCIATE_BASE_FEE_USD
                                        + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 1L)),
                                0.1),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 100L),
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 0L)));
            }

            @HapiTest
            @DisplayName(
                    "Token Airdrop FT to Receivers without Free Auto-Associations - Two Pending Airdrops - fees full charging")
            final Stream<DynamicTest> tokenAirdropReceiverNoFreeAutoAssociationsExtraAirdropFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(
                                        moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        getTxnRecord("tokenAirdropTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                moving(10, FUNGIBLE_TOKEN)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                                moving(10, FUNGIBLE_TOKEN)
                                                        .between(
                                                                OWNER,
                                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                SIGNATURES, 1L,
                                                ACCOUNTS, 3L,
                                                TOKEN_TYPES, 1L,
                                                PROCESSING_BYTES, (long) txnSize))
                                        + TOKEN_ASSOCIATE_BASE_FEE_USD * 2
                                        + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 2L)),
                                0.1),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 100L),
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 0L),
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 0L)));
            }

            @HapiTest
            @DisplayName(
                    "Token Airdrop FT and NFT to Receivers without Free Auto-Associations - Multiple Pending Airdrops - fees full charging")
            final Stream<DynamicTest> tokenAirdropReceiverNoFreeAutoAssociationsMultiplePendingAirdropsFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAirdrop(
                                        moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        getTxnRecord("tokenAirdropTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                moving(10, FUNGIBLE_TOKEN)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                                moving(10, FUNGIBLE_TOKEN)
                                                        .between(
                                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)))
                                        .pendingAirdrops(includingNftPendingAirdrop(
                                                movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                                movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                        .between(
                                                                OWNER,
                                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                                SIGNATURES, 1L,
                                                ACCOUNTS, 3L,
                                                TOKEN_TYPES, 3L,
                                                PROCESSING_BYTES, (long) txnSize))
                                        + TOKEN_ASSOCIATE_BASE_FEE_USD * 4
                                        + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 4L)),
                                0.1),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 100L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L),
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
            }

            @HapiTest
            @DisplayName(
                    "Token Airdrop FT and NFT to Associated Receiver and Receiver without Free Auto-Associations - Results in Pending and Successful Airdrops - fees full charging")
            final Stream<DynamicTest>
                    tokenAirdropReceiverWithAndWithoutFreeAutoAssociationsResultsInPendingAndSuccessfulAirdropsFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAirdrop(
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                        moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        getTxnRecord("tokenAirdropTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))
                                        .pendingAirdrops(includingNftPendingAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                                SIGNATURES, 1L,
                                                ACCOUNTS, 3L,
                                                TOKEN_TYPES, 3L,
                                                PROCESSING_BYTES, (long) txnSize))
                                        + TOKEN_ASSOCIATE_BASE_FEE_USD * 2
                                        + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 2L)),
                                0.1),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 90L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 3L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
            }

            @HapiTest
            @DisplayName(
                    "Token Airdrop FT and NFT to Associated Receiver, Receiver with and Receiver without Free Auto-Associations - Results in Pending and Successful Airdrops - fees full charging")
            final Stream<DynamicTest>
                    tokenAirdropReceiverAssociatedWithAndWithoutFreeAutoAssociationsResultsInPendingAndSuccessfulAirdropsFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAirdrop(
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                        moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 3L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        getTxnRecord("tokenAirdropTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))
                                        .pendingAirdrops(includingNftPendingAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 3L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                                SIGNATURES, 1L,
                                                ACCOUNTS, 4L,
                                                TOKEN_TYPES, 4L,
                                                PROCESSING_BYTES, (long) txnSize))
                                        + TOKEN_ASSOCIATE_BASE_FEE_USD * 4
                                        + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 4L)),
                                0.1),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 80L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                        getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1L),
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
            }

            @HapiTest
            @DisplayName(
                    "Token Airdrop FT to Associated Receiver from Sender with threshold key - full fees and extras charging")
            final Stream<DynamicTest> tokenAirdropFTToAssociatedReceiverFromSenderWithThresholdKeyFullCharging() {
                // Define a threshold submit key that requires two simple keys signatures
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                // Create valid signature with both simple keys signing
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));

                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(THRESHOLD_PAYER)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .balance(ONE_HUNDRED_HBARS),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(THRESHOLD_PAYER, FUNGIBLE_TOKEN),
                        cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, THRESHOLD_PAYER)),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        tokenAirdrop(moving(5, FUNGIBLE_TOKEN).between(THRESHOLD_PAYER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(THRESHOLD_PAYER)
                                .signedBy(THRESHOLD_PAYER)
                                .via("tokenAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        ACCOUNTS, 2L,
                                        TOKEN_TYPES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 90L),
                        getAccountBalance(THRESHOLD_PAYER).hasTokenBalance(FUNGIBLE_TOKEN, 5L),
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FUNGIBLE_TOKEN, 5L)));
            }

            @HapiTest
            @DisplayName("Token Airdrop FT to Receiver with receiverSigRequired(true) - full fees and extras charging")
            final Stream<DynamicTest> tokenAirdropFTToAssociatedReceiverSigRequiredFullCharging() {

                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        cryptoCreate(RECEIVER_WITH_SIG_REQUIRED)
                                .receiverSigRequired(true)
                                .maxAutomaticTokenAssociations(0),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITH_SIG_REQUIRED))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITH_SIG_REQUIRED)
                                .via("tokenAirdropTxn"),
                        getTxnRecord("tokenAirdropTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITH_SIG_REQUIRED)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                SIGNATURES, 2L,
                                                ACCOUNTS, 2L,
                                                TOKEN_TYPES, 1L,
                                                PROCESSING_BYTES, (long) txnSize))
                                        + TOKEN_ASSOCIATE_BASE_FEE_USD
                                        + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 1L)),
                                0.1),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 100L),
                        getAccountBalance(RECEIVER_WITH_SIG_REQUIRED).hasTokenBalance(FUNGIBLE_TOKEN, 0L)));
            }

            @HapiTest
            @DisplayName(
                    "Multiple Token Airdrop FT to Receiver without Free Auto-Associations - aggregate pending airdrops full charging")
            final Stream<DynamicTest>
                    multipleTokenAirdropFTToReceiverWithoutFreeAutoAssociationsAggregateAirdropFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(
                                        moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        moving(15, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        moving(25, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        getTxnRecord("tokenAirdropTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(moving(50, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenAirdropTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                SIGNATURES, 1L,
                                                ACCOUNTS, 2L,
                                                TOKEN_TYPES, 1L,
                                                PROCESSING_BYTES, (long) txnSize))
                                        + TOKEN_ASSOCIATE_BASE_FEE_USD
                                        + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 1L)),
                                0.1),
                        getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 100L),
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 0L)));
            }
        }

        @Nested
        @DisplayName("Token Airdrop Simple Fees Negative Tests")
        class TokenAirdropSimpleFeesNegativeTests {
            @Nested
            @DisplayName("Token Airdrop Simple Fees Failures on Ingest")
            class TokenAirdropSimpleFeesFailuresOnIngest {
                @HapiTest
                @DisplayName(
                        "Token Airdrop FT to Associated Receiver from Sender with threshold key - Invalid signature fails on ingest")
                final Stream<DynamicTest>
                        tokenAirdropFTToAssociatedReceiverFromSenderWithInvalidSignatureFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();

                    // Define a threshold submit key that requires two simple keys signatures
                    KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                    // Create valid signature with both simple keys signing
                    SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            newKeyNamed(PAYER_KEY).shape(keyShape),
                            cryptoCreate(THRESHOLD_PAYER)
                                    .key(PAYER_KEY)
                                    .sigControl(forKey(PAYER_KEY, invalidSig))
                                    .balance(ONE_HUNDRED_HBARS),
                            getAccountBalance(THRESHOLD_PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(THRESHOLD_PAYER, FUNGIBLE_TOKEN),
                            cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, THRESHOLD_PAYER)),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            tokenAirdrop(moving(5, FUNGIBLE_TOKEN).between(THRESHOLD_PAYER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(THRESHOLD_PAYER)
                                    .via("tokenAirdropTxn")
                                    .hasPrecheck(INVALID_SIGNATURE),

                            // assert no txn record is created
                            getTxnRecord("tokenAirdropTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(THRESHOLD_PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName(
                        "Token Airdrop FT and NFT to Associated Receiver - with insufficient txn fee - fails on ingest")
                final Stream<DynamicTest>
                        tokenAirdropFTAndNFTToAssociatedReceiverWithInsufficientTxnFeeFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                            tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .fee(ONE_HBAR / 100000)
                                    .via("tokenAirdropTxn")
                                    .hasPrecheck(INSUFFICIENT_TX_FEE),
                            // assert no txn record is created
                            getTxnRecord("tokenAirdropTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName(
                        "Token Airdrop FT and NFT to Associated Receiver - with insufficient payer balance - fails on ingest")
                final Stream<DynamicTest>
                        tokenAirdropFTAndNFTToAssociatedReceiverWithInsufficientPayerBalanceFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER_INSUFFICIENT_BALANCE).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                            tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER_INSUFFICIENT_BALANCE)
                                    .signedBy(OWNER, PAYER_INSUFFICIENT_BALANCE)
                                    .via("tokenAirdropTxn")
                                    .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                            // assert no txn record is created
                            getTxnRecord("tokenAirdropTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER_INSUFFICIENT_BALANCE).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Token Airdrop - with empty token transfer body  - fails on ingest")
                final Stream<DynamicTest> tokenAirdropWithEmptyTokenTransferBodyFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            tokenAirdrop()
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .via("tokenAirdropTxn")
                                    .hasPrecheckFrom(EMPTY_TOKEN_TRANSFER_BODY),
                            // assert no txn record is created
                            getTxnRecord("tokenAirdropTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Token Airdrop - with multiple senders for a token - fails on ingest")
                final Stream<DynamicTest> tokenAirdropWithMultipleSendersForATokenFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            cryptoCreate("newSender"),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                            tokenAssociate("newSender", FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                            tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                            moving(10, FUNGIBLE_TOKEN).between("newSender", RECEIVER_ASSOCIATED_FIRST),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between("newSender", RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .via("tokenAirdropTxn")
                                    .hasPrecheck(AIRDROP_CONTAINS_MULTIPLE_SENDERS_FOR_A_TOKEN),
                            // assert no txn record is created
                            getTxnRecord("tokenAirdropTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Token Airdrop - with duplicate NFT Serial - fails on ingest")
                final Stream<DynamicTest> tokenAirdropWithDuplicateNFTSerialFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),
                            tokenAirdrop(
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(OWNER, RECEIVER_NOT_ASSOCIATED),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .via("tokenAirdropTxn")
                                    .hasPrecheck(INVALID_ACCOUNT_AMOUNTS),
                            // assert no txn record is created
                            getTxnRecord("tokenAirdropTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }

                @HapiTest
                @DisplayName("Token Airdrop - with allowance is not supported - fails on ingest")
                final Stream<DynamicTest> tokenAirdropWithAllowanceIsNotSupportedFailsOnIngest() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            cryptoCreate("spender").balance(ONE_HBAR),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            cryptoApproveAllowance()
                                    .payingWith(OWNER)
                                    .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, "spender", 10L),
                            tokenAirdrop(movingWithAllowance(5L, FUNGIBLE_TOKEN)
                                            .between("spender", RECEIVER_NOT_ASSOCIATED))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER, "spender")
                                    .via("tokenAirdropTxn")
                                    .hasPrecheck(NOT_SUPPORTED),
                            // assert no txn record is created
                            getTxnRecord("tokenAirdropTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                            // Save balances and assert changes
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            withOpContext((spec, log) -> {
                                assertEquals(initialBalance.get(), afterBalance.get());
                            })));
                }
            }

            @Nested
            @DisplayName("Token Airdrop Simple Fees Failures on Pre-Handle")
            class TokenAirdropSimpleFeesFailuresOnPreHandle {
                @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
                @DisplayName(
                        "Token Airdrop FT to Associated Receiver from Sender with threshold key - Invalid signature fails on pre-handle")
                final Stream<DynamicTest>
                        tokenAirdropFTToAssociatedReceiverFromSenderWithInvalidSignatureFailsOnPreHandle() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    final AtomicLong initialNodeBalance = new AtomicLong();
                    final AtomicLong afterNodeBalance = new AtomicLong();

                    final String INNER_ID = "txn-inner-id";

                    // Define a threshold submit key that requires two simple keys signatures
                    KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);

                    // Create valid signature with both simple keys signing
                    SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            newKeyNamed(PAYER_KEY).shape(keyShape),
                            cryptoCreate(THRESHOLD_PAYER)
                                    .key(PAYER_KEY)
                                    .sigControl(forKey(PAYER_KEY, invalidSig))
                                    .balance(ONE_HUNDRED_HBARS),
                            getAccountBalance(THRESHOLD_PAYER).exposingBalanceTo(initialBalance::set),
                            cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                            getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(THRESHOLD_PAYER, FUNGIBLE_TOKEN),
                            cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, THRESHOLD_PAYER)),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            tokenAirdrop(moving(5, FUNGIBLE_TOKEN).between(THRESHOLD_PAYER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(THRESHOLD_PAYER)
                                    .setNode(4)
                                    .via(INNER_ID)
                                    .hasKnownStatus(INVALID_PAYER_SIGNATURE),

                            // Save balances and assert changes
                            getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                            getAccountBalance(THRESHOLD_PAYER).exposingBalanceTo(afterBalance::set),
                            getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                            withOpContext((spec, log) -> {
                                long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                                log.info("Node balance change: {}", nodeDelta);
                                log.info(
                                        "Recorded fee: {}",
                                        expectedCryptoTransferNetworkFeeOnlyUsd(Map.of(SIGNATURES, 2L)));
                                assertEquals(initialBalance.get(), afterBalance.get());
                                assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                            }),
                            validateChargedFeeToUsdWithTxnSize(
                                    INNER_ID,
                                    initialNodeBalance,
                                    afterNodeBalance,
                                    txnSize -> expectedCryptoTransferNetworkFeeOnlyUsd(
                                            Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                    0.01)));
                }

                @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
                @DisplayName(
                        "Token Airdrop FT and NFT to Associated Receiver - with insufficient txn fee - fails on pre-handle")
                final Stream<DynamicTest>
                        tokenAirdropFTAndNFTToAssociatedReceiverWithInsufficientTxnFeeFailsOnPreHandle() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    final AtomicLong initialNodeBalance = new AtomicLong();
                    final AtomicLong afterNodeBalance = new AtomicLong();

                    final String INNER_ID = "txn-inner-id";

                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                            cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                            getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                            tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .fee(ONE_HBAR / 100000)
                                    .setNode(4)
                                    .via(INNER_ID)
                                    .hasPrecheck(INSUFFICIENT_TX_FEE),
                            // Save balances and assert changes
                            getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                            getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                            getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                            withOpContext((spec, log) -> {
                                long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                                log.info("Node balance change: {}", nodeDelta);
                                log.info(
                                        "Recorded fee: {}",
                                        expectedCryptoTransferNetworkFeeOnlyUsd(Map.of(SIGNATURES, 1L)));
                                assertEquals(initialBalance.get(), afterBalance.get());
                                assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                            }),
                            validateChargedFeeToUsdWithTxnSize(
                                    INNER_ID,
                                    initialNodeBalance,
                                    afterNodeBalance,
                                    txnSize -> expectedCryptoTransferNetworkFeeOnlyUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.01)));
                }

                @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
                @DisplayName(
                        "Token Airdrop FT and NFT to Associated Receiver - with insufficient payer balance - fails on pre-handle")
                final Stream<DynamicTest>
                        tokenAirdropFTAndNFTToAssociatedReceiverWithInsufficientPayerBalanceFailsOnPreHandle() {
                    final AtomicLong initialBalance = new AtomicLong();
                    final AtomicLong afterBalance = new AtomicLong();
                    final AtomicLong initialNodeBalance = new AtomicLong();
                    final AtomicLong afterNodeBalance = new AtomicLong();

                    final String INNER_ID = "txn-inner-id";

                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            getAccountBalance(PAYER_INSUFFICIENT_BALANCE).exposingBalanceTo(initialBalance::set),
                            cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                            getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                            tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER_INSUFFICIENT_BALANCE)
                                    .signedBy(OWNER, PAYER_INSUFFICIENT_BALANCE)
                                    .setNode(4)
                                    .via(INNER_ID)
                                    .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                            // Save balances and assert changes
                            getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                            getAccountBalance(PAYER_INSUFFICIENT_BALANCE).exposingBalanceTo(afterBalance::set),
                            getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                            withOpContext((spec, log) -> {
                                long nodeDelta = initialNodeBalance.get() - afterNodeBalance.get();
                                log.info("Node balance change: {}", nodeDelta);
                                log.info(
                                        "Recorded fee: {}",
                                        expectedCryptoTransferNetworkFeeOnlyUsd(Map.of(SIGNATURES, 2L)));
                                assertEquals(initialBalance.get(), afterBalance.get());
                                assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                            }),
                            validateChargedFeeToUsdWithTxnSize(
                                    INNER_ID,
                                    initialNodeBalance,
                                    afterNodeBalance,
                                    txnSize -> expectedCryptoTransferNetworkFeeOnlyUsd(
                                            Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                    0.01)));
                }
            }

            @Nested
            @DisplayName("Token Airdrop Simple Fees Failures on Handle")
            class TokenAirdropSimpleFeesFailuresOnHandle {
                @HapiTest
                @DisplayName(
                        "Token Airdrop FT and NFT to Receiver with exhausted Free Auto-Associations - Results in Failed txn and fees full charging")
                final Stream<DynamicTest>
                        tokenAirdropReceiverWithExhaustedFreeAutoAssociationsResultsInFaildTxnAndFullCharging() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN_2, 100L, OWNER, adminKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                            tokenAirdrop(
                                            moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                            moving(10L, FUNGIBLE_TOKEN_2)
                                                    .between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("tokenAirdropTxn")
                                    .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS),
                            validateChargedUsdWithinWithTxnSize(
                                    "tokenAirdropTxn",
                                    txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            ACCOUNTS, 2L,
                                            TOKEN_TYPES, 3L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            getAccountBalance(OWNER)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 100L)
                                    .hasTokenBalance(FUNGIBLE_TOKEN_2, 100L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L),
                            getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                    .hasTokenBalance(FUNGIBLE_TOKEN_2, 0L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Token Airdrop - with invalid NFT Serial - fails on handle and fees full charging")
                final Stream<DynamicTest> tokenAirdropWithInvalidNFTSerialFailsOnHandleAndFeesFullCharging() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 3),
                            tokenAirdrop(
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(OWNER, RECEIVER_NOT_ASSOCIATED),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 10L)
                                                    .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                    .payingWith(PAYER)
                                    .signedBy(OWNER, PAYER)
                                    .via("tokenAirdropTxn")
                                    .hasKnownStatus(INVALID_NFT_ID),
                            validateChargedUsdWithinWithTxnSize(
                                    "tokenAirdropTxn",
                                    txnSize -> expectedCryptoTransferNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            ACCOUNTS, 3L,
                                            TOKEN_TYPES, 2L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 2L),
                            getAccountBalance(RECEIVER_NOT_ASSOCIATED).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L),
                            getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Token Airdrop - with invalid transfer decimals - fails on handle and fees full charging")
                final Stream<DynamicTest> tokenAirdropWithInvalidTokenDecimalsFailsOnHandleAndFeesFullCharging() {
                    return hapiTest(
                            cryptoCreate("receiver").maxAutomaticTokenAssociations(5),
                            cryptoCreate(OWNER),
                            cryptoCreate(PAYER).balance(ONE_HBAR),
                            tokenCreate("decimalFT")
                                    .tokenType(FUNGIBLE_COMMON)
                                    .treasury(OWNER)
                                    .decimals(2)
                                    .initialSupply(1000),
                            tokenAirdrop(movingWithDecimals(10, "decimalFT", 4).betweenWithDecimals(OWNER, "receiver"))
                                    .signedBy(PAYER, OWNER, "receiver")
                                    .payingWith(PAYER)
                                    .via("tokenAirdropTxn")
                                    .hasKnownStatus(UNEXPECTED_TOKEN_DECIMALS),
                            validateChargedUsdWithinWithTxnSize(
                                    "tokenAirdropTxn",
                                    txnSize -> expectedCryptoTransferNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            ACCOUNTS, 2L,
                                            TOKEN_TYPES, 1L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            getAccountBalance(OWNER).hasTokenBalance("decimalFT", 1000L),
                            getAccountBalance("receiver").hasTokenBalance("decimalFT", 0L));
                }

                @HapiTest
                @DisplayName(
                        "Token Airdrop FT and NFT - with insufficient token balance - fails on handle and fees full charging")
                final Stream<DynamicTest> tokenAirdropWithInsufficientTokenBalanceFailsOnHandleAndFeesFullCharging() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                            tokenAirdrop(
                                            moving(500L, FUNGIBLE_TOKEN)
                                                    .between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("tokenAirdropTxn")
                                    .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                            validateChargedUsdWithinWithTxnSize(
                                    "tokenAirdropTxn",
                                    txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            ACCOUNTS, 2L,
                                            TOKEN_TYPES, 2L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            getAccountBalance(OWNER)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 100L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L),
                            getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName(
                        "Token Airdrop FT and NFT - sender not associated to token - fails on handle and fees full charging")
                final Stream<DynamicTest> tokenAirdropWithSenderNotAssociatedToTokenFailsOnHandleAndFeesFullCharging() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                            tokenAirdrop(
                                            moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(PAYER, RECEIVER_FREE_AUTO_ASSOCIATIONS))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER, PAYER)
                                    .via("tokenAirdropTxn")
                                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                            validateChargedUsdWithinWithTxnSize(
                                    "tokenAirdropTxn",
                                    txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            ACCOUNTS, 3L,
                                            TOKEN_TYPES, 2L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            getAccountBalance(OWNER)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 100L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L),
                            getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName(
                        "Token Airdrop FT and NFT - account frozen for token - fails on handle and fees full charging")
                final Stream<DynamicTest> tokenAirdropWithAccountFrozenForTokenFailsOnHandleAndFeesFullCharging() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFeesWithFreezeKey(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey, freezeKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                            tokenFreeze(FUNGIBLE_TOKEN, OWNER),
                            tokenAirdrop(
                                            moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(PAYER, RECEIVER_FREE_AUTO_ASSOCIATIONS))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER, PAYER)
                                    .via("tokenAirdropTxn")
                                    .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN),
                            validateChargedUsdWithinWithTxnSize(
                                    "tokenAirdropTxn",
                                    txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            ACCOUNTS, 3L,
                                            TOKEN_TYPES, 2L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            getAccountBalance(OWNER)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 100L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L),
                            getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Token Airdrop FT and NFT - with paused token - fails on handle and fees full charging")
                final Stream<DynamicTest> tokenAirdropWithPausedTokenFailsOnHandleAndFeesFullCharging() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFeesWithPauseKey(
                                    FUNGIBLE_TOKEN, 100L, OWNER, adminKey, pauseKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                            tokenPause(FUNGIBLE_TOKEN),
                            tokenAirdrop(
                                            moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_FREE_AUTO_ASSOCIATIONS),
                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                    .between(PAYER, RECEIVER_FREE_AUTO_ASSOCIATIONS))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER, PAYER)
                                    .via("tokenAirdropTxn")
                                    .hasKnownStatus(TOKEN_IS_PAUSED),
                            validateChargedUsdWithinWithTxnSize(
                                    "tokenAirdropTxn",
                                    txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            ACCOUNTS, 3L,
                                            TOKEN_TYPES, 2L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            getAccountBalance(OWNER)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 100L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L),
                            getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 0L)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }

                @HapiTest
                @DisplayName("Token Airdrop NFT - duplicate pending airdrop - fails on handle and fees full charging")
                final Stream<DynamicTest> tokenAirdropWithDuplicatePendingAirdropFailsOnHandleAndFeesFullCharging() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                            tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                            .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("tokenAirdropTxnFirst"),
                            tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                            .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("tokenAirdropTxn")
                                    .hasKnownStatus(PENDING_NFT_AIRDROP_ALREADY_EXISTS),
                            validateChargedUsdWithinWithTxnSize(
                                    "tokenAirdropTxn",
                                    txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            ACCOUNTS, 2L,
                                            TOKEN_TYPES, 1L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            getAccountBalance(OWNER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 4L),
                            getAccountBalance(RECEIVER_FREE_AUTO_ASSOCIATIONS)
                                    .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0L)));
                }
            }
        }
    }

    private HapiTokenCreate createFungibleTokenWithoutCustomFees(
            String tokenName, long supply, String treasury, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .tokenType(FUNGIBLE_COMMON);
    }

    private HapiTokenCreate createFungibleTokenWithoutCustomFeesWithFreezeKey(
            String tokenName, long supply, String treasury, String adminKey, String freezeKey) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .freezeKey(freezeKey)
                .tokenType(FUNGIBLE_COMMON);
    }

    private HapiTokenCreate createFungibleTokenWithoutCustomFeesWithPauseKey(
            String tokenName, long supply, String treasury, String adminKey, String pauseKey) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .pauseKey(pauseKey)
                .tokenType(FUNGIBLE_COMMON);
    }

    private HapiTokenCreate createNonFungibleTokenWithoutCustomFees(
            String tokenName, String treasury, String supplyKey, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .adminKey(adminKey);
    }

    private HapiTokenMint mintNFT(String tokenName, int rangeStart, int rangeEnd) {
        return mintToken(
                tokenName,
                IntStream.range(rangeStart, rangeEnd)
                        .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                        .toList());
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PAYER_INSUFFICIENT_BALANCE).balance(ONE_HBAR / 100000),
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(PAYER_WITH_HOOK)
                        .balance(ONE_MILLION_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT)),
                cryptoCreate(PAYER_WITH_TWO_HOOKS)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT))
                        .withHook(accountAllowanceHook(2L, HOOK_CONTRACT)),
                cryptoCreate(PAYER_WITH_THREE_HOOKS)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT))
                        .withHook(accountAllowanceHook(2L, HOOK_CONTRACT))
                        .withHook(accountAllowanceHook(3L, HOOK_CONTRACT)),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(HBAR_OWNER_INSUFFICIENT_BALANCE).balance(ONE_HBAR / 100000),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_THIRD).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_UNLIMITED_AUTO_ASSOCIATIONS)
                        .maxAutomaticTokenAssociations(-1)
                        .balance(ONE_HBAR),
                cryptoCreate(RECEIVER_FREE_AUTO_ASSOCIATIONS)
                        .maxAutomaticTokenAssociations(2)
                        .balance(ONE_HBAR),
                cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                        .maxAutomaticTokenAssociations(0)
                        .balance(ONE_HBAR),
                cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)
                        .maxAutomaticTokenAssociations(0)
                        .balance(ONE_HBAR),
                cryptoCreate(RECEIVER_NOT_ASSOCIATED).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ZERO_BALANCE).balance(0L),
                newKeyNamed(VALID_ALIAS_ED25519).shape(KeyShape.ED25519),
                newKeyNamed(VALID_ALIAS_ED25519_SECOND).shape(KeyShape.ED25519),
                newKeyNamed(VALID_ALIAS_ECDSA).shape(SECP_256K1_SHAPE),
                newKeyNamed(VALID_ALIAS_ECDSA_SECOND).shape(SECP_256K1_SHAPE),
                newKeyNamed(VALID_ALIAS_HOLLOW).shape(SECP_256K1_SHAPE),
                newKeyNamed(VALID_ALIAS_HOLLOW_SECOND).shape(SECP_256K1_SHAPE),
                newKeyNamed(adminKey),
                newKeyNamed(supplyKey),
                newKeyNamed(freezeKey),
                newKeyNamed(pauseKey));
    }
}
