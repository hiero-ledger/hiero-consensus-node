// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.node.app.service.token.AliasUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNftPendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNonfungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCancelAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingNFTAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenCancelAirdropFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenClaimAirdropFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
public class TokenClaimAndCancelAirdropSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String THRESHOLD_PAYER = "thresholdPayer";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS = "receiverWithoutFreeAutoAssociations";
    private static final String RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND =
            "receiverWithoutFreeAutoAssociationsSecond";
    private static final String RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD =
            "receiverWithoutFreeAutoAssociationsThird";
    private static final String RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH =
            "receiverWithoutFreeAutoAssociationsFourth";
    private static final String OWNER = "owner";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String adminKey = "adminKey";
    private static final String supplyKey = "supplyKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("Token Claim and Cancel Airdrop Simple Fees Tests")
    class TokenClaimAirdropSimpleFeesTests {

        @Nested
        @DisplayName("Token Claim and Cancel Airdrop Simple Fees Positive Tests")
        class TokenClaimAirdropSimpleFeesPositiveTests {

            @HapiTest
            @DisplayName("Token Claim FT Pending Airdrop - base fees full charging")
            final Stream<DynamicTest> tokenClaimAirdropBaseFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxn"),
                        getTxnRecord("tokenClaimAirdropTxn")
                                .hasPriority(recordWith()
                                        .tokenTransfers(includingFungibleMovement(moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Claim Multiple FT Pending Airdrops - full fees charging")
            final Stream<DynamicTest> tokenClaimMultiplePendingFTAirdropsFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(40, FUNGIBLE_TOKEN)
                                        .distributing(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(
                                        OWNER,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)
                                .via("tokenClaimAirdropTxn"),
                        getTxnRecord("tokenClaimAirdropTxn")
                                .hasPriority(recordWith()
                                        .tokenTransfers(includingFungibleMovement(moving(40, FUNGIBLE_TOKEN)
                                                .distributing(
                                                        OWNER,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 5L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Claim NFT Pending Airdrop - full fees charging")
            final Stream<DynamicTest> tokenClaimPendingNFTAirdropFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenClaimAirdrop(pendingNFTAirdrop(
                                        OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxn"),
                        getTxnRecord("tokenClaimAirdropTxn")
                                .hasPriority(recordWith()
                                        .tokenTransfers(
                                                includingNonfungibleMovement(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Claim Multiple NFT Pending Airdrops - full fees charging")
            final Stream<DynamicTest> tokenClaimMultiplePendingNFTAirdropsFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAirdrop(
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 3L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 4L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenClaimAirdrop(
                                        pendingNFTAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                                NON_FUNGIBLE_TOKEN,
                                                2L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                                NON_FUNGIBLE_TOKEN,
                                                3L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH,
                                                NON_FUNGIBLE_TOKEN,
                                                4L))
                                .payingWith(OWNER)
                                .signedBy(
                                        OWNER,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)
                                .via("tokenClaimAirdropTxn"),
                        getTxnRecord("tokenClaimAirdropTxn")
                                .hasPriority(recordWith()
                                        .tokenTransfers(
                                                includingNonfungibleMovement(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))
                                        .tokenTransfers(includingNonfungibleMovement(movingUnique(
                                                        NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)))
                                        .tokenTransfers(
                                                includingNonfungibleMovement(movingUnique(NON_FUNGIBLE_TOKEN, 3L)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD)))
                                        .tokenTransfers(includingNonfungibleMovement(movingUnique(
                                                        NON_FUNGIBLE_TOKEN, 4L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 5L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Claim Multiple FT and NFT Pending Airdrops - full fees charging")
            final Stream<DynamicTest> tokenClaimMultiplePendingFTAndNFTAirdropsFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAirdrop(
                                        moving(40, FUNGIBLE_TOKEN)
                                                .distributing(
                                                        OWNER,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 3L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 4L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH, FUNGIBLE_TOKEN),
                                        pendingNFTAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                                NON_FUNGIBLE_TOKEN,
                                                2L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                                NON_FUNGIBLE_TOKEN,
                                                3L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH,
                                                NON_FUNGIBLE_TOKEN,
                                                4L))
                                .payingWith(OWNER)
                                .signedBy(
                                        OWNER,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)
                                .via("tokenClaimAirdropTxn"),
                        getTxnRecord("tokenClaimAirdropTxn")
                                .hasPriority(recordWith()
                                        .tokenTransfers(includingFungibleMovement(moving(40, FUNGIBLE_TOKEN)
                                                .distributing(
                                                        OWNER,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)))
                                        .tokenTransfers(
                                                includingNonfungibleMovement(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))
                                        .tokenTransfers(includingNonfungibleMovement(movingUnique(
                                                        NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)))
                                        .tokenTransfers(
                                                includingNonfungibleMovement(movingUnique(NON_FUNGIBLE_TOKEN, 3L)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD)))
                                        .tokenTransfers(includingNonfungibleMovement(movingUnique(
                                                        NON_FUNGIBLE_TOKEN, 4L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 5L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Claim FT Pending Airdrop with threshold key - full fees with extra signatures charging")
            final Stream<DynamicTest> tokenClaimAirdropWithExtraSignaturesFullCharging() {
                // Define a threshold submit key
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

                // Create valid signature
                SigControl validSig = keyShape.signedWith(sigs(ON, ON, sigs(ON, ON)));

                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .maxAutomaticTokenAssociations(0)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .balance(ONE_HUNDRED_HBARS),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxn"),
                        getTxnRecord("tokenClaimAirdropTxn")
                                .hasPriority(recordWith()
                                        .tokenTransfers(includingFungibleMovement(moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 5L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Cancel FT Pending Airdrop - base fees full charging")
            final Stream<DynamicTest> tokenCancelFTAirdropFullFeesCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenCancelAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenCancelAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxn",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Cancel Multiple FT Pending Airdrop - full fees charging")
            final Stream<DynamicTest> tokenCancelMultipleFTAirdropBaseFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(40, FUNGIBLE_TOKEN)
                                        .distributing(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenCancelAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenCancelAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxn",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Cancel NFT Pending Airdrop - full fees charging")
            final Stream<DynamicTest> tokenCancelPendingNFTAirdropFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenCancelAirdrop(pendingNFTAirdrop(
                                        OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenCancelAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxn",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Cancel Multiple NFT Pending Airdrops - full fees charging")
            final Stream<DynamicTest> tokenCancelMultiplePendingNFTAirdropsFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAirdrop(
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 3L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 4L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenCancelAirdrop(
                                        pendingNFTAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                                NON_FUNGIBLE_TOKEN,
                                                2L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                                NON_FUNGIBLE_TOKEN,
                                                3L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH,
                                                NON_FUNGIBLE_TOKEN,
                                                4L))
                                .payingWith(OWNER)
                                .signedBy(
                                        OWNER,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)
                                .via("tokenCancelAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxn",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Cancel Multiple FT and NFT Pending Airdrops - full fees charging")
            final Stream<DynamicTest> tokenCancelMultiplePendingFTAndNFTAirdropsFeesFullCharging() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAirdrop(
                                        moving(40, FUNGIBLE_TOKEN)
                                                .distributing(
                                                        OWNER,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 3L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 4L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenCancelAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD, FUNGIBLE_TOKEN),
                                        pendingAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH, FUNGIBLE_TOKEN),
                                        pendingNFTAirdrop(
                                                OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                                NON_FUNGIBLE_TOKEN,
                                                2L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                                NON_FUNGIBLE_TOKEN,
                                                3L),
                                        pendingNFTAirdrop(
                                                OWNER,
                                                RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH,
                                                NON_FUNGIBLE_TOKEN,
                                                4L))
                                .payingWith(OWNER)
                                .signedBy(
                                        OWNER,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD,
                                        RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)
                                .via("tokenCancelAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxn",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName(
                    "Token Cancel FT Pending Airdrop with threshold key - full fees with extra signatures charging")
            final Stream<DynamicTest> tokenCancelAirdropWithExtraSignaturesFullCharging() {
                // Define a threshold submit key
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

                // Create valid signature
                SigControl validSig = keyShape.signedWith(sigs(ON, ON, sigs(ON, ON)));

                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(THRESHOLD_PAYER)
                                .key(PAYER_KEY)
                                .sigControl(forKey(PAYER_KEY, validSig))
                                .balance(ONE_HUNDRED_HBARS),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        tokenCancelAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(THRESHOLD_PAYER)
                                .signedBy(OWNER, THRESHOLD_PAYER)
                                .via("tokenCancelAirdropTxn"),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxn",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 5L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }
        }

        @Nested
        @DisplayName("Token Claim and Cancel Airdrop Simple Fees Negative Tests")
        class TokenClaimAndCancelAirdropSimpleFeesNegativeTests {

            @HapiTest
            @DisplayName("Token Claim FT Pending Airdrop with missing sender signature - fails on ingest")
            final Stream<DynamicTest> tokenClaimAirdropWithMissingSenderSignatureFailsOnIngest() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxn")
                                .hasPrecheck(INVALID_SIGNATURE),
                        // assert no txn record is created
                        getTxnRecord("tokenClaimAirdropTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
            }

            @HapiTest
            @DisplayName("Token Claim FT Pending Airdrop with missing receiver signature - full fees charging")
            final Stream<DynamicTest> tokenClaimAirdropWithMissingReceiverSignatureFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenClaimAirdropTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        getTxnRecord("tokenAirdropTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Cancel FT Pending Airdrop that is already Claimed fails on handle")
            final Stream<DynamicTest> tokenCancelClaimedFTAirdropFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxn"),
                        tokenCancelAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenCancelAirdropTxn")
                                .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                        getTxnRecord("tokenAirdropTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxn",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Claim FT Pending Airdrop that is already Canceled fails on handle")
            final Stream<DynamicTest> tokenClaimFTAirdropThatIsAlreadyCanceledFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        tokenCancelAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenCancelAirdropTxn"),
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxn")
                                .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxn",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Claim FT Pending Airdrop that is already Claimed fails on handle")
            final Stream<DynamicTest> tokenClaimFTAirdropThatIsAlreadyClaimedFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxnFirst"),
                        tokenClaimAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxnSecond")
                                .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxnFirst",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxnSecond",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Cancel FT Pending Airdrop that is already Canceled fails on handle")
            final Stream<DynamicTest> tokenCancelFTAirdropThatIsAlreadyCanceledFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        tokenCancelAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenCancelAirdropTxnFirst"),
                        tokenCancelAirdrop(
                                        pendingAirdrop(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenCancelAirdropTxnSecond")
                                .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxnFirst",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxnSecond",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Claim FT Pending Airdrop that is already Canceled fails on handle")
            final Stream<DynamicTest> tokenClaimNonExistingFTAirdropFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        tokenClaimAirdrop(pendingAirdrop(
                                        OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxn")
                                .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Cancel FT Pending Airdrop that is already Canceled fails on handle")
            final Stream<DynamicTest> tokenCancelNonExistingFTAirdropFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        tokenCancelAirdrop(pendingAirdrop(
                                        OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenCancelAirdropTxn")
                                .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenCancelAirdropTxn",
                                txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Claim NFT Pending Airdrop when the Sender no longer owns the token fails on handle")
            final Stream<DynamicTest> tokenClaimPendingNFTAirdropWhenSenderNoLongerOwnsTheTokenFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN),
                        tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxn"),
                        getTxnRecord("tokenAirdropTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingNftPendingAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST)),
                        tokenClaimAirdrop(pendingAirdrop(
                                        OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxn")
                                .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName(
                    "Token Claim NFT - Multiple Pending Airdrops for the same NFT serial - all claims after first one fail on handle")
            final Stream<DynamicTest>
                    tokenClaimNFTMultiplePendingAirdropsForTheSameSerialAllClaimsAfterFirstOneFailOnHandle() {
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
                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxnSecond"),
                        tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxnThird"),
                        getTxnRecord("tokenAirdropTxnFirst")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingNftPendingAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        getTxnRecord("tokenAirdropTxnSecond")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingNftPendingAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)))),
                        getTxnRecord("tokenAirdropTxnThird")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingNftPendingAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD)))),
                        tokenClaimAirdrop(pendingNFTAirdrop(
                                        OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxnFirst"),
                        tokenClaimAirdrop(pendingNFTAirdrop(
                                        OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND, NON_FUNGIBLE_TOKEN, 1L))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)
                                .via("tokenClaimAirdropTxnSecond")
                                .hasKnownStatus(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxnFirst",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxnSecond",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Token Claim NFT Pending Airdrop with wrong serial - fails on handle")
            final Stream<DynamicTest> tokenClaimNFTPendingAirdropWithWrongSerialFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 1, 5),
                        tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("tokenAirdropTxnFirst"),
                        getTxnRecord("tokenAirdropTxnFirst")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingNftPendingAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        tokenClaimAirdrop(pendingNFTAirdrop(
                                        OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 2L))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxn")
                                .hasKnownStatus(INVALID_PENDING_AIRDROP_ID),
                        validateChargedUsdWithinWithTxnSize(
                                "tokenClaimAirdropTxn",
                                txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
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

    private SpecOperation registerEvmAddressAliasFrom(String secp256k1KeyName, AtomicReference<ByteString> evmAlias) {
        return withOpContext((spec, opLog) -> {
            final var ecdsaKey =
                    spec.registry().getKey(secp256k1KeyName).getECDSASecp256K1().toByteArray();
            final var evmAddressBytes = recoverAddressFromPubKey(Bytes.wrap(ecdsaKey));
            final var evmAddress = ByteString.copyFrom(evmAddressBytes.toByteArray());
            evmAlias.set(evmAddress);
        });
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(HOOK_CONTRACT),
                contractCreate(HOOK_CONTRACT).gas(5_000_000),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                        .maxAutomaticTokenAssociations(0)
                        .balance(ONE_HBAR),
                cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_SECOND)
                        .maxAutomaticTokenAssociations(0)
                        .balance(ONE_HBAR),
                cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD)
                        .maxAutomaticTokenAssociations(0)
                        .balance(ONE_HBAR),
                cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)
                        .maxAutomaticTokenAssociations(0)
                        .balance(ONE_HBAR),
                newKeyNamed(adminKey),
                newKeyNamed(supplyKey));
    }
}
