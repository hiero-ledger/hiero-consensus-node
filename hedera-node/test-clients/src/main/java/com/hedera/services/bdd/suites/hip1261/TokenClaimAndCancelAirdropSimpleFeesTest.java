// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNonfungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingNFTAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenClaimAirdropFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
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
    private static final String RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD =
            "receiverWithoutFreeAutoAssociationsThird";
    private static final String RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH =
            "receiverWithoutFreeAutoAssociationsFourth";
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
                cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_THIRD)
                        .maxAutomaticTokenAssociations(0)
                        .balance(ONE_HBAR),
                cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS_FOURTH)
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
