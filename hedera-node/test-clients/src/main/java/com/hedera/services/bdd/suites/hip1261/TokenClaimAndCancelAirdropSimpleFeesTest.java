// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.node.app.service.token.AliasUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.EmbeddedReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
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
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
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
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

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
    private static final String FUNGIBLE_TOKEN_SECOND = "fungibleTokenSecond";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String adminKey = "adminKey";
    private static final String supplyKey = "supplyKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String VALID_ALIAS_ECDSA = "validAliasECDSA";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "entities.unlimitedAutoAssociationsEnabled", "true"));
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
                                        Map.of(SIGNATURES, 5L, PROCESSING_BYTES, (long) txnSize)),
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
                                        Map.of(SIGNATURES, 5L, PROCESSING_BYTES, (long) txnSize)),
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

            @LeakyEmbeddedHapiTest(
                    reason = NEEDS_STATE_ACCESS,
                    overrides = {"entities.unlimitedAutoAssociationsEnabled"})
            @DisplayName(
                    "Token Airdrop - Claim Pending Airdrop to Hollow Account without auto-associations with FT moving to non-existing evm alias - full fees charging")
            final Stream<DynamicTest> tokenAirdropClaimPendingAirdropToHollowAccountWithFTMovingFullFeesCharging() {

                final AtomicReference<ByteString> evmAlias = new AtomicReference<>();
                final String HOLLOW_RECEIVER = "hollowReceiver";

                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN_SECOND, 100L, OWNER, adminKey),
                        registerEvmAddressAliasFrom(VALID_ALIAS_ECDSA, evmAlias),
                        withOpContext((spec, log) -> {
                            final var alias = evmAlias.get();

                            // Disable unlimited auto-associations so the FT airdrop creates a pending airdrop to a
                            // hollow account
                            allRunFor(spec, overriding("entities.unlimitedAutoAssociationsEnabled", "false"));

                            final var firstTokenAirdropOp = tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN).between(OWNER, alias))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("tokenAirdropTxn");

                            allRunFor(spec, firstTokenAirdropOp);

                            // Save the actual hollow account id in the registry
                            allRunFor(
                                    spec,
                                    getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                                            .savingSnapshot(HOLLOW_RECEIVER)
                                            .isHollow()
                                            .has(accountWith()
                                                    .hasEmptyKey()
                                                    .noAlias()
                                                    .balance(0L)
                                                    .maxAutoAssociations(1))
                                            .exposingIdTo(id -> spec.registry().saveAccountId(HOLLOW_RECEIVER, id)));

                            // Confirm the first FT already associated successfully
                            allRunFor(
                                    spec,
                                    getAccountInfo(HOLLOW_RECEIVER).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                                    getAccountBalance(HOLLOW_RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 10L));

                            // Second FT airdrop should become pending, because the only slot is already consumed
                            final var secondAirdropOp = tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN_SECOND).between(OWNER, alias))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("secondAirdropTxn");

                            allRunFor(spec, secondAirdropOp);

                            // Verify the second FT is pending, not directly associated
                            allRunFor(
                                    spec,
                                    getTxnRecord("secondAirdropTxn")
                                            .hasPriority(recordWith()
                                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                                            moving(10, FUNGIBLE_TOKEN_SECOND)
                                                                    .between(OWNER, HOLLOW_RECEIVER)))),
                                    getAccountInfo(HOLLOW_RECEIVER).hasNoTokenRelationship(FUNGIBLE_TOKEN_SECOND));

                            // Re-enable unlimited auto-associations
                            allRunFor(spec, overriding("entities.unlimitedAutoAssociationsEnabled", "true"));

                            final var tokenClaimAirdropOp = tokenClaimAirdrop(
                                            pendingAirdrop(OWNER, HOLLOW_RECEIVER, FUNGIBLE_TOKEN_SECOND))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER, VALID_ALIAS_ECDSA)
                                    .via("tokenClaimAirdropTxn");

                            allRunFor(spec, tokenClaimAirdropOp);

                            final var checkOpChargedUsd = validateChargedUsdWithinWithTxnSize(
                                    "tokenClaimAirdropTxn",
                                    txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                            Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1);

                            final var checkReceiverInfo = getAccountInfo(HOLLOW_RECEIVER)
                                    .isNotHollow()
                                    .hasToken(relationshipWith(FUNGIBLE_TOKEN))
                                    .hasToken(relationshipWith(FUNGIBLE_TOKEN_SECOND))
                                    .has(accountWith()
                                            .hasNonEmptyKey()
                                            .noAlias()
                                            .balance(0L));

                            final var checkOwnerBalanceFirstFT =
                                    getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 90L);
                            final var checkOwnerBalanceSecondFT =
                                    getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN_SECOND, 90L);
                            final var checkReceiverBalance = getAccountBalance(HOLLOW_RECEIVER)
                                    .hasTokenBalance(FUNGIBLE_TOKEN, 10L)
                                    .hasTokenBalance(FUNGIBLE_TOKEN_SECOND, 10L);

                            allRunFor(
                                    spec,
                                    checkOpChargedUsd,
                                    checkReceiverInfo,
                                    checkOwnerBalanceFirstFT,
                                    checkOwnerBalanceSecondFT,
                                    checkReceiverBalance);
                        })));
            }

            @LeakyEmbeddedHapiTest(
                    reason = NEEDS_STATE_ACCESS,
                    overrides = {"entities.unlimitedAutoAssociationsEnabled"})
            @DisplayName(
                    "Token Airdrop - Cancel Pending Airdrop to Hollow Account without auto-associations with FT moving to non-existing evm alias - full fees charging")
            final Stream<DynamicTest> tokenAirdropCancelPendingAirdropToHollowAccountWithFTMovingFullFeesCharging() {

                final AtomicReference<ByteString> evmAlias = new AtomicReference<>();
                final String HOLLOW_RECEIVER = "hollowReceiver";

                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN_SECOND, 100L, OWNER, adminKey),
                        registerEvmAddressAliasFrom(VALID_ALIAS_ECDSA, evmAlias),
                        withOpContext((spec, log) -> {
                            final var alias = evmAlias.get();

                            allRunFor(spec, overriding("entities.unlimitedAutoAssociationsEnabled", "false"));

                            final var firstTokenAirdropOp = tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN).between(OWNER, alias))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("tokenAirdropTxn");

                            allRunFor(spec, firstTokenAirdropOp);

                            allRunFor(
                                    spec,
                                    getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                                            .savingSnapshot(HOLLOW_RECEIVER)
                                            .isHollow()
                                            .has(accountWith()
                                                    .hasEmptyKey()
                                                    .noAlias()
                                                    .balance(0L)
                                                    .maxAutoAssociations(1))
                                            .exposingIdTo(id -> spec.registry().saveAccountId(HOLLOW_RECEIVER, id)));

                            allRunFor(
                                    spec,
                                    getAccountInfo(HOLLOW_RECEIVER).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                                    getAccountBalance(HOLLOW_RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 10L));

                            final var secondAirdropOp = tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN_SECOND).between(OWNER, alias))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("secondAirdropTxn");

                            allRunFor(spec, secondAirdropOp);

                            allRunFor(
                                    spec,
                                    getTxnRecord("secondAirdropTxn")
                                            .hasPriority(recordWith()
                                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                                            moving(10, FUNGIBLE_TOKEN_SECOND)
                                                                    .between(OWNER, HOLLOW_RECEIVER)))),
                                    getAccountInfo(HOLLOW_RECEIVER).hasNoTokenRelationship(FUNGIBLE_TOKEN_SECOND));

                            allRunFor(spec, overriding("entities.unlimitedAutoAssociationsEnabled", "true"));

                            // Owner cancels — only owner signature required, hollow account stays hollow
                            final var tokenCancelAirdropOp = tokenCancelAirdrop(
                                            pendingAirdrop(OWNER, HOLLOW_RECEIVER, FUNGIBLE_TOKEN_SECOND))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("tokenCancelAirdropTxn");

                            allRunFor(spec, tokenCancelAirdropOp);

                            final var checkOpChargedUsd = validateChargedUsdWithinWithTxnSize(
                                    "tokenCancelAirdropTxn",
                                    txnSize -> expectedTokenCancelAirdropFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1);

                            // Hollow account stays hollow, no FUNGIBLE_TOKEN_SECOND received
                            final var checkReceiverInfo = getAccountInfo(HOLLOW_RECEIVER)
                                    .isHollow()
                                    .hasToken(relationshipWith(FUNGIBLE_TOKEN))
                                    .hasNoTokenRelationship(FUNGIBLE_TOKEN_SECOND)
                                    .has(accountWith().hasEmptyKey().noAlias().balance(0L));

                            // 10 of FUNGIBLE_TOKEN auto-transferred, all 100 of FUNGIBLE_TOKEN_SECOND returned after
                            // cancel
                            final var checkOwnerBalanceFirstFT =
                                    getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN, 90L);
                            final var checkOwnerBalanceSecondFT =
                                    getAccountBalance(OWNER).hasTokenBalance(FUNGIBLE_TOKEN_SECOND, 100L);

                            allRunFor(
                                    spec,
                                    checkOpChargedUsd,
                                    checkReceiverInfo,
                                    checkOwnerBalanceFirstFT,
                                    checkOwnerBalanceSecondFT);
                        })));
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
            @DisplayName("Token Claim Non-existing FT Pending Airdrop fails on handle")
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
            @DisplayName("Token Cancel Non-existing FT Pending Airdrop fails on handle")
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
                        tokenClaimAirdrop(pendingNFTAirdrop(
                                        OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS, NON_FUNGIBLE_TOKEN, 1L))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .via("tokenClaimAirdropTxn")
                                .hasKnownStatus(SENDER_DOES_NOT_OWN_NFT_SERIAL_NO),
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

            @LeakyEmbeddedHapiTest(
                    reason = NEEDS_STATE_ACCESS,
                    overrides = {"entities.unlimitedAutoAssociationsEnabled"})
            @DisplayName(
                    "Token Claim FT Pending Airdrop for hollow account without hollow account signature - fails on handle")
            final Stream<DynamicTest> tokenClaimPendingAirdropForHollowAccountWithoutSignatureFailsOnHandle() {

                final AtomicReference<ByteString> evmAlias = new AtomicReference<>();
                final String HOLLOW_RECEIVER = "hollowReceiver";

                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN_SECOND, 100L, OWNER, adminKey),
                        registerEvmAddressAliasFrom(VALID_ALIAS_ECDSA, evmAlias),
                        withOpContext((spec, log) -> {
                            final var alias = evmAlias.get();

                            allRunFor(spec, overriding("entities.unlimitedAutoAssociationsEnabled", "false"));

                            final var firstTokenAirdropOp = tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN).between(OWNER, alias))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("tokenAirdropTxn");

                            allRunFor(spec, firstTokenAirdropOp);

                            allRunFor(
                                    spec,
                                    getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                                            .savingSnapshot(HOLLOW_RECEIVER)
                                            .isHollow()
                                            .has(accountWith()
                                                    .hasEmptyKey()
                                                    .noAlias()
                                                    .balance(0L)
                                                    .maxAutoAssociations(1))
                                            .exposingIdTo(id -> spec.registry().saveAccountId(HOLLOW_RECEIVER, id)));

                            allRunFor(
                                    spec,
                                    getAccountInfo(HOLLOW_RECEIVER).hasToken(relationshipWith(FUNGIBLE_TOKEN)),
                                    getAccountBalance(HOLLOW_RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 10L));

                            final var secondAirdropOp = tokenAirdrop(
                                            moving(10, FUNGIBLE_TOKEN_SECOND).between(OWNER, alias))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("secondAirdropTxn");

                            allRunFor(spec, secondAirdropOp);

                            allRunFor(
                                    spec,
                                    getTxnRecord("secondAirdropTxn")
                                            .hasPriority(recordWith()
                                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                                            moving(10, FUNGIBLE_TOKEN_SECOND)
                                                                    .between(OWNER, HOLLOW_RECEIVER)))),
                                    getAccountInfo(HOLLOW_RECEIVER).hasNoTokenRelationship(FUNGIBLE_TOKEN_SECOND));

                            allRunFor(spec, overriding("entities.unlimitedAutoAssociationsEnabled", "true"));

                            // Attempt to claim without hollow account's SECP256K1 signature → INVALID_SIGNATURE
                            final var tokenClaimAirdropOp = tokenClaimAirdrop(
                                            pendingAirdrop(OWNER, HOLLOW_RECEIVER, FUNGIBLE_TOKEN_SECOND))
                                    .payingWith(OWNER)
                                    .signedBy(OWNER)
                                    .via("tokenClaimAirdropTxn")
                                    .hasKnownStatus(INVALID_SIGNATURE);

                            allRunFor(spec, tokenClaimAirdropOp);

                            final var checkOpChargedUsd = validateChargedUsdWithinWithTxnSize(
                                    "tokenClaimAirdropTxn",
                                    txnSize -> expectedTokenClaimAirdropFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1);

                            // Hollow account stays hollow, pending airdrop unchanged
                            final var checkReceiverInfo = getAccountInfo(HOLLOW_RECEIVER)
                                    .isHollow()
                                    .hasToken(relationshipWith(FUNGIBLE_TOKEN))
                                    .hasNoTokenRelationship(FUNGIBLE_TOKEN_SECOND)
                                    .has(accountWith().hasEmptyKey().noAlias().balance(0L));

                            allRunFor(spec, checkOpChargedUsd, checkReceiverInfo);
                        })));
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
                newKeyNamed(supplyKey),
                newKeyNamed(VALID_ALIAS_ECDSA).shape(SECP_256K1_SHAPE));
    }
}
