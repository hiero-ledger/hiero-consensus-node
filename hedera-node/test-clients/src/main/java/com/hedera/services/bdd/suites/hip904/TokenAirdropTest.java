// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip904;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiPropertySource.asHexedSolidityAddress;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNftPendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNonfungibleMovement;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdateAliased;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenClaimAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenReject;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenClaimAirdrop.pendingAirdrop;
import static com.hedera.services.bdd.spec.transactions.token.HapiTokenReject.rejectingToken;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUniqueWithAllowance;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithAllowance;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithDecimals;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FREEZE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.DEPLOY;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.GET_BYTECODE;
import static com.hedera.services.bdd.suites.contract.opcodes.Create2OperationSuite.setExpectedCreate2Address;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.crypto.TransferWithCustomFixedFees.htsFee;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_HAS_PENDING_AIRDROPS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AIRDROP_CONTAINS_MULTIPLE_SENDERS_FOR_A_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_TOKEN_TRANSFER_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PENDING_NFT_AIRDROP_ALREADY_EXISTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_AIRDROP_WITH_FALLBACK_ROYALTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNEXPECTED_TOKEN_DECIMALS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.junit.EmbeddedHapiTest;
import com.hedera.services.bdd.junit.EmbeddedReason;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.EmbeddedVerbs;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

@Tag(CRYPTO)
@HapiTestLifecycle
@DisplayName("Token airdrop")
public class TokenAirdropTest extends TokenAirdropBase {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of(
                "tokens.airdrops.enabled", "false",
                "tokens.airdrops.claim.enabled", "false",
                "entities.unlimitedAutoAssociationsEnabled", "false"));
        // create some entities with disabled airdrops
        lifecycle.doAdhoc(setUpEntitiesPreHIP904());
        // enable airdrops
        lifecycle.doAdhoc(
                overriding("tokens.airdrops.enabled", "true"),
                overriding("tokens.airdrops.claim.enabled", "true"),
                overriding("entities.unlimitedAutoAssociationsEnabled", "true"));
        lifecycle.doAdhoc(setUpTokensAndAllReceivers());
    }

    @Nested
    @DisplayName("to existing accounts")
    class AirdropToExistingAccounts {

        @Nested
        @DisplayName("with free auto associations slots")
        class AirdropToExistingAccountsWhitFreeAutoAssociations {

            @HapiTest
            final Stream<DynamicTest> tokenAirdropToExistingAccountsTransfers() {
                return hapiTest(
                        // associated receiver and receivers with free auto association slots
                        tokenAirdrop(
                                        moveFungibleTokensTo(ASSOCIATED_RECEIVER),
                                        moveFungibleTokensTo(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                                        moveFungibleTokensTo(RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .via("fungible airdrop"),
                        // assert txn record
                        getTxnRecord("fungible airdrop")
                                .hasPriority(recordWith()
                                        .tokenTransfers(includingFungibleMovement(moving(30, FUNGIBLE_TOKEN)
                                                .distributing(
                                                        OWNER,
                                                        RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS,
                                                        RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS,
                                                        ASSOCIATED_RECEIVER)))),
                        // assert balance
                        getAccountBalance(ASSOCIATED_RECEIVER).hasTokenBalance(FUNGIBLE_TOKEN, 10),
                        getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 10),
                        getAccountBalance(RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN, 10),
                        // associate receiver - will be simple transfer
                        // unlimited associations receiver - 0.1 (because not associated yet)
                        // free auto associations receiver - 0.1 (because not associated yet)
                        validateChargedUsd("fungible airdrop", 0.2, 1));
            }

            @HapiTest
            final Stream<DynamicTest> tokenMultipleAirdropsToSameAccount() {
                String receiver = "OneReceiver";
                return hapiTest(
                        cryptoCreate("Sender1"),
                        cryptoCreate("Sender2"),
                        cryptoCreate("Sender3"),
                        cryptoCreate(receiver).maxAutomaticTokenAssociations(1),
                        tokenAssociate("Sender1", FUNGIBLE_TOKEN),
                        tokenAssociate("Sender2", FUNGIBLE_TOKEN),
                        tokenAssociate("Sender3", FUNGIBLE_TOKEN),
                        cryptoTransfer(
                                moving(10, FUNGIBLE_TOKEN).between(OWNER, "Sender1"),
                                moving(10, FUNGIBLE_TOKEN).between(OWNER, "Sender2"),
                                moving(10, FUNGIBLE_TOKEN).between(OWNER, "Sender3")),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between("Sender1", receiver))
                                .payingWith("Sender1"),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between("Sender2", receiver))
                                .payingWith("Sender2"),
                        tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between("Sender3", receiver))
                                .payingWith("Sender3")
                                .via("multiple fungible airdrop"),
                        // assert balance
                        getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, 30),
                        getAccountBalance("Sender1").hasTokenBalance(FUNGIBLE_TOKEN, 0),
                        getAccountBalance("Sender2").hasTokenBalance(FUNGIBLE_TOKEN, 0),
                        getAccountBalance("Sender3").hasTokenBalance(FUNGIBLE_TOKEN, 0));
            }

            @HapiTest
            final Stream<DynamicTest> nftAirdropToExistingAccountsTransfers() {
                return hapiTest(
                        // receivers with free auto association slots
                        tokenAirdrop(
                                        movingUnique(NON_FUNGIBLE_TOKEN, 3L)
                                                .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 4L)
                                                .between(OWNER, RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 5L).between(OWNER, ASSOCIATED_RECEIVER))
                                .payingWith(OWNER)
                                .via("non fungible airdrop"),
                        // assert txn record
                        getTxnRecord("non fungible airdrop")
                                .hasPriority(recordWith()
                                        .tokenTransfers(includingNonfungibleMovement(
                                                movingUnique(NON_FUNGIBLE_TOKEN, 3L, 4L, 5L)
                                                        .distributing(
                                                                RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS,
                                                                RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS,
                                                                ASSOCIATED_RECEIVER)))),
                        // assert account balances
                        getAccountBalance(ASSOCIATED_RECEIVER).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),
                        getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),
                        getAccountBalance(RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),
                        // associate receiver - will be simple transfer
                        // unlimited associations receiver - 0.1 (because not associated yet)
                        // free auto associations receiver - 0.1 (because not associated yet)
                        validateChargedUsd("non fungible airdrop", 0.2, 1));
            }
        }

        @Nested
        @DisplayName("without free auto associations slots")
        class AirdropToExistingAccountsWithoutFreeAutoAssociations {
            @HapiTest
            final Stream<DynamicTest> tokenAirdropToExistingAccountsPending() {
                return hapiTest(
                        tokenAirdrop(
                                        moveFungibleTokensTo(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        moveFungibleTokensTo(RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .via("fungible airdrop"),
                        // assert txn record
                        getTxnRecord("fungible airdrop")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                moveFungibleTokensTo(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                                moveFungibleTokensTo(RECEIVER_WITH_0_AUTO_ASSOCIATIONS)))),
                        // assert balances
                        getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(FUNGIBLE_TOKEN, 0),
                        // zero auto associations receiver - 0.1 (creates pending airdrop)
                        // without free auto associations receiver - 0.1 (creates pending airdrop)
                        validateChargedUsd("fungible airdrop", 0.2, 1));
            }

            @HapiTest
            final Stream<DynamicTest> nftAirdropToExistingAccountsPending() {
                return hapiTest(
                        // without free auto association slots
                        tokenAirdrop(
                                        movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                .between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                                        movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .via("non fungible airdrop"),
                        // assert the pending list
                        getTxnRecord("non fungible airdrop")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingNftPendingAirdrop(
                                                movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                        .between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                                                movingUnique(NON_FUNGIBLE_TOKEN, 2L)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),

                        // assert account balances
                        getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(NON_FUNGIBLE_TOKEN, 0),
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(NON_FUNGIBLE_TOKEN, 0),
                        // zero auto associations receiver - 0.1 (creates pending airdrop)
                        // without free auto associations receiver - 0.1 (creates pending airdrop)
                        validateChargedUsd("non fungible airdrop", 0.2, 1));
            }

            @HapiTest
            @DisplayName("charge association fee for FT correctly")
            final Stream<DynamicTest> chargeAssociationFeeForFT() {
                var receiver = "receiver";
                return hapiTest(
                        cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                        tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, receiver))
                                .payingWith(OWNER)
                                .via("airdrop"),
                        tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, receiver))
                                .payingWith(OWNER)
                                .via("second airdrop"),
                        validateChargedUsd("airdrop", 0.1, 1),
                        validateChargedUsd("second airdrop", 0.05, 1));
            }

            @HapiTest
            @DisplayName("charge association fee for NFT correctly")
            final Stream<DynamicTest> chargeAssociationFeeForNFT() {
                var receiver = "receiver";
                return hapiTest(
                        cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                        tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1).between(OWNER, receiver))
                                .payingWith(OWNER)
                                .via("airdrop"),
                        tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 2).between(OWNER, receiver))
                                .payingWith(OWNER)
                                .via("second airdrop"),
                        validateChargedUsd("airdrop", 0.1, 1),
                        validateChargedUsd("second airdrop", 0.1, 1));
            }

            // AIRDROP_17
            @HapiTest
            final Stream<DynamicTest> transferMultipleFtAndNftToEOAWithNoFreeAutoAssociationsAccountResultsInPending() {
                final String NFT_FOR_MULTIPLE_PENDING_TRANSFER = "nftForMultiplePendingTransfer";
                final String FT_FOR_MULTIPLE_PENDING_TRANSFER = "ftForMultiplePendingTransfer";
                var nftSupplyKeyForMultipleTransfers = "nftSupplyKeyForMultipleTransfer";
                return hapiTest(
                        tokenCreate(FT_FOR_MULTIPLE_PENDING_TRANSFER)
                                .treasury(OWNER)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L),
                        newKeyNamed(nftSupplyKeyForMultipleTransfers),
                        tokenCreate(NFT_FOR_MULTIPLE_PENDING_TRANSFER)
                                .treasury(OWNER)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0L)
                                .name(NFT_FOR_MULTIPLE_PENDING_TRANSFER)
                                .supplyKey(nftSupplyKeyForMultipleTransfers),
                        mintToken(
                                NFT_FOR_MULTIPLE_PENDING_TRANSFER,
                                IntStream.range(0, 10)
                                        .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                        .toList()),
                        tokenAirdrop(
                                        moving(10, FT_FOR_MULTIPLE_PENDING_TRANSFER)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NFT_FOR_MULTIPLE_PENDING_TRANSFER, 1L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("first airdrop"),
                        tokenAirdrop(
                                        moving(10, FT_FOR_MULTIPLE_PENDING_TRANSFER)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                        movingUnique(NFT_FOR_MULTIPLE_PENDING_TRANSFER, 2L)
                                                .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("second airdrop"),
                        getTxnRecord("first airdrop")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                moving(10, FT_FOR_MULTIPLE_PENDING_TRANSFER)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))
                                        .pendingAirdrops(includingNftPendingAirdrop(
                                                movingUnique(NFT_FOR_MULTIPLE_PENDING_TRANSFER, 1L)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        getTxnRecord("second airdrop")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                moving(20, FT_FOR_MULTIPLE_PENDING_TRANSFER)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))
                                        .pendingAirdrops(includingNftPendingAirdrop(
                                                movingUnique(NFT_FOR_MULTIPLE_PENDING_TRANSFER, 2L)
                                                        .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                        // assert account balances
                        getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                .hasTokenBalance(FT_FOR_MULTIPLE_PENDING_TRANSFER, 0)
                                .hasTokenBalance(NFT_FOR_MULTIPLE_PENDING_TRANSFER, 0),
                        getAccountBalance(OWNER)
                                .hasTokenBalance(FT_FOR_MULTIPLE_PENDING_TRANSFER, 1000)
                                .hasTokenBalance(NFT_FOR_MULTIPLE_PENDING_TRANSFER, 10L),
                        validateChargedUsd("first airdrop", 0.2, 10),
                        validateChargedUsd("second airdrop", 0.15, 10));
            }

            // AIRDROP_21
            @HapiTest
            final Stream<DynamicTest>
                    transferOneFTTwiceFromEOAWithOneFTInBalanceToAccountWithNoFreeAutoAssociationsResultsInPendingAggregated() {
                var sender = "sender";
                return defaultHapiSpec(
                                "Send one FT from EOA with only One FT in balance twice to Account without free Auto-Associations")
                        .given(
                                cryptoCreate(sender).maxAutomaticTokenAssociations(-1),
                                cryptoTransfer(moving(1, FUNGIBLE_TOKEN).between(OWNER, sender))
                                        .payingWith(OWNER))
                        .when(
                                tokenAirdrop(moving(1, FUNGIBLE_TOKEN)
                                                .between(sender, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                        .payingWith(sender)
                                        .signedBy(sender)
                                        .via("first airdrop"),
                                tokenAirdrop(moving(1, FUNGIBLE_TOKEN)
                                                .between(sender, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS))
                                        .payingWith(sender)
                                        .signedBy(sender)
                                        .via("second airdrop"))
                        .then(
                                getTxnRecord("first airdrop")
                                        .hasPriority(recordWith()
                                                .pendingAirdrops(includingFungiblePendingAirdrop(moving(
                                                                1, FUNGIBLE_TOKEN)
                                                        .between(sender, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                                getTxnRecord("second airdrop")
                                        .hasPriority(recordWith()
                                                .pendingAirdrops(includingFungiblePendingAirdrop(moving(
                                                                2, FUNGIBLE_TOKEN)
                                                        .between(sender, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)))),
                                // assert account balances
                                getAccountBalance(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                                        .hasTokenBalance(FUNGIBLE_TOKEN, 0),
                                getAccountBalance(sender).hasTokenBalance(FUNGIBLE_TOKEN, 1),
                                validateChargedUsd("first airdrop", 0.1, 10),
                                validateChargedUsd("second airdrop", 0.05, 10));
            }

            @HapiTest
            @DisplayName("with multiple tokens")
            final Stream<DynamicTest> tokenAirdropMultipleTokens() {
                return hapiTest(
                        createTokenWithName("FT1"),
                        createTokenWithName("FT2"),
                        createTokenWithName("FT3"),
                        createTokenWithName("FT4"),
                        createTokenWithName("FT5"),
                        tokenAirdrop(
                                        defaultMovementOfToken("FT1"),
                                        defaultMovementOfToken("FT2"),
                                        defaultMovementOfToken("FT3"),
                                        defaultMovementOfToken("FT4"),
                                        defaultMovementOfToken("FT5"))
                                .payingWith(OWNER)
                                .via("fungible airdrop"),
                        // assert balances
                        getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                                .hasTokenBalance("FT1", 10),
                        getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                                .hasTokenBalance("FT2", 10),
                        getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                                .hasTokenBalance("FT3", 10),
                        getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                                .hasTokenBalance("FT4", 10),
                        getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                                .hasTokenBalance("FT5", 10));
            }
        }

        @HapiTest
        @DisplayName("in pending state")
        final Stream<DynamicTest> consequentAirdrops() {
            // Verify that when sending 2 consequent airdrops to a recipient,
            // which associated themselves to the token after the first airdrop,
            // the second airdrop is directly transferred to the recipient and the first airdrop remains in pending
            // state
            var receiver = "receiver";
            return hapiTest(
                    cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                    // send first airdrop
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver))
                            .payingWith(OWNER)
                            .via("first"),
                    getTxnRecord("first")
                            // assert pending airdrops
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver)))),
                    // creates pending airdrop
                    validateChargedUsd("first", 0.1, 10),
                    tokenAssociate(receiver, FUNGIBLE_TOKEN),
                    // this time tokens should be transferred
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver))
                            .payingWith(OWNER)
                            .via("second"),
                    // assert OWNER and receiver accounts to ensure first airdrop is still in pending state
                    getTxnRecord("second")
                            // assert transfers
                            .hasPriority(recordWith()
                                    .tokenTransfers(includingFungibleMovement(
                                            moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver)))),
                    // just a crypto transfer
                    validateChargedUsd("second", 0.001, 10),
                    // assert the account balance
                    getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, 10));
        }

        @HapiTest
        @DisplayName("that is alias with 0 free maxAutoAssociations")
        final Stream<DynamicTest> airdropToAliasWithNoFreeSlots() {
            final var validAliasWithNoFreeSlots = "validAliasWithNoFreeSlots";
            return hapiTest(
                    newKeyNamed(validAliasWithNoFreeSlots),
                    cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 10L).between(OWNER, validAliasWithNoFreeSlots))
                            .payingWith(OWNER)
                            .signedBy(OWNER, validAliasWithNoFreeSlots),
                    withOpContext((spec, opLog) -> updateSpecFor(spec, validAliasWithNoFreeSlots)),
                    cryptoUpdateAliased(validAliasWithNoFreeSlots)
                            .maxAutomaticAssociations(1)
                            .signedBy(validAliasWithNoFreeSlots, DEFAULT_PAYER),
                    tokenAirdrop(moveFungibleTokensTo(validAliasWithNoFreeSlots))
                            .payingWith(OWNER)
                            .via("aliasAirdrop"),
                    getTxnRecord("aliasAirdrop")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moveFungibleTokensTo(validAliasWithNoFreeSlots)))),
                    getAccountBalance(validAliasWithNoFreeSlots).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),
                    getAccountBalance(validAliasWithNoFreeSlots).hasTokenBalance(FUNGIBLE_TOKEN, 0));
        }

        @HapiTest
        @DisplayName("airdrop to contract with admin key")
        final Stream<DynamicTest> airdropToContractWithAdminKey() {
            final var testContract = "ToyMaker";
            final var key = "key";
            return hapiTest(
                    newKeyNamed(key),
                    uploadInitCode(testContract),
                    contractCreate(testContract).adminKey(key),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, testContract))
                            .signedBy(OWNER)
                            .payingWith(OWNER));
        }

        @HapiTest
        @DisplayName("after reject should keep the association")
        final Stream<DynamicTest> afterRejectShouldKeepTheAssociation() {
            final var receiver = "receiver";
            return hapiTest(
                    cryptoCreate(receiver).maxAutomaticTokenAssociations(0),

                    // Token airdrop and verify that the receiver balance is 0
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver))
                            .signedBy(OWNER)
                            .payingWith(OWNER),
                    getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, 0),

                    // Claim and verify that receiver balance is 10
                    tokenClaimAirdrop(pendingAirdrop(OWNER, receiver, FUNGIBLE_TOKEN))
                            .payingWith(receiver),
                    getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, 10),

                    // Reject and verify that receiver balance is 0
                    tokenReject(rejectingToken(FUNGIBLE_TOKEN)).payingWith(receiver),
                    getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, 0),

                    // Airdrop without claim and verify that the receiver balance is 10
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver))
                            .signedBy(OWNER)
                            .payingWith(OWNER),
                    getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, 10));
        }

        @HapiTest
        @DisplayName("airdrop after claim should result in CryptoTransfer")
        final Stream<DynamicTest> airdropAfterClaimShouldResultInCryptoTransfer() {
            final var receiver = "receiver";
            return hapiTest(
                    cryptoCreate(receiver).maxAutomaticTokenAssociations(0),

                    // Token airdrop and verify that the receiver balance is 0
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver))
                            .signedBy(OWNER)
                            .payingWith(OWNER),
                    getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, 0),

                    // Claim and verify that receiver balance is 10
                    tokenClaimAirdrop(pendingAirdrop(OWNER, receiver, FUNGIBLE_TOKEN))
                            .payingWith(receiver),
                    getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, 10),

                    // Airdrop without claim and verify that the receiver balance is 20
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver))
                            .signedBy(OWNER)
                            .payingWith(OWNER),
                    getAccountBalance(receiver).hasTokenBalance(FUNGIBLE_TOKEN, 20));
        }

        @Nested
        @DisplayName("and with receiverSigRequired=true")
        class ReceiverSigRequiredTests {
            private static final String RECEIVER_WITH_SIG_REQUIRED = "receiver_sig_required";

            @HapiTest
            @DisplayName("signed and no free slots")
            final Stream<DynamicTest> receiverSigInPending() {

                return hapiTest(
                        cryptoCreate(RECEIVER_WITH_SIG_REQUIRED)
                                .receiverSigRequired(true)
                                .maxAutomaticTokenAssociations(0),
                        tokenAirdrop(moveFungibleTokensTo(RECEIVER_WITH_SIG_REQUIRED))
                                .payingWith(OWNER)
                                .signedBy(RECEIVER_WITH_SIG_REQUIRED, OWNER)
                                .via("sigTxn"),
                        getTxnRecord("sigTxn")
                                // assert transfers
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITH_SIG_REQUIRED)))),
                        // assert balances
                        getAccountBalance(RECEIVER_WITH_SIG_REQUIRED).hasTokenBalance(FUNGIBLE_TOKEN, 0));
            }

            @HapiTest
            @DisplayName("signed and with free slots")
            final Stream<DynamicTest> receiverSigInPendingFreeSlots() {

                return hapiTest(
                        cryptoCreate(RECEIVER_WITH_SIG_REQUIRED)
                                .receiverSigRequired(true)
                                .maxAutomaticTokenAssociations(5),
                        tokenAirdrop(moveFungibleTokensTo(RECEIVER_WITH_SIG_REQUIRED))
                                .payingWith(OWNER)
                                .signedBy(RECEIVER_WITH_SIG_REQUIRED, OWNER)
                                .via("sigTxn"),
                        getTxnRecord("sigTxn")
                                // assert transfers
                                .hasPriority(recordWith()
                                        .tokenTransfers(includingFungibleMovement(moving(10, FUNGIBLE_TOKEN)
                                                .between(OWNER, RECEIVER_WITH_SIG_REQUIRED)))),
                        // assert balances
                        getAccountBalance(RECEIVER_WITH_SIG_REQUIRED).hasTokenBalance(FUNGIBLE_TOKEN, 10));
            }

            @HapiTest
            @DisplayName("and is associated and signed by receiver")
            final Stream<DynamicTest> receiverSigIsAssociated() {

                return hapiTest(
                        cryptoCreate(RECEIVER_WITH_SIG_REQUIRED)
                                .receiverSigRequired(true)
                                .maxAutomaticTokenAssociations(5),
                        tokenAssociate(RECEIVER_WITH_SIG_REQUIRED, FUNGIBLE_TOKEN),
                        tokenAirdrop(moveFungibleTokensTo(RECEIVER_WITH_SIG_REQUIRED))
                                .payingWith(OWNER)
                                .signedBy(RECEIVER_WITH_SIG_REQUIRED, OWNER)
                                .via("sigTxn"),
                        getTxnRecord("sigTxn")
                                .hasPriority(recordWith()
                                        .tokenTransfers(includingFungibleMovement(
                                                moveFungibleTokensTo(RECEIVER_WITH_SIG_REQUIRED)))),
                        getAccountBalance(RECEIVER_WITH_SIG_REQUIRED).hasTokenBalance(FUNGIBLE_TOKEN, 10));
            }

            @HapiTest
            @DisplayName("and is associated but not signed by receiver")
            final Stream<DynamicTest> receiverSigIsAssociatedButNotSigned() {

                return hapiTest(
                        cryptoCreate(RECEIVER_WITH_SIG_REQUIRED)
                                .receiverSigRequired(true)
                                .maxAutomaticTokenAssociations(5),
                        tokenAssociate(RECEIVER_WITH_SIG_REQUIRED, FUNGIBLE_TOKEN),
                        tokenAirdrop(moveFungibleTokensTo(RECEIVER_WITH_SIG_REQUIRED))
                                .payingWith(OWNER)
                                .signedBy(OWNER)
                                .via("sigTxn"),
                        getTxnRecord("sigTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                moveFungibleTokensTo(RECEIVER_WITH_SIG_REQUIRED)))),
                        getAccountBalance(RECEIVER_WITH_SIG_REQUIRED).hasTokenBalance(FUNGIBLE_TOKEN, 0));
            }

            @HapiTest
            @DisplayName("multiple tokens with one associated")
            final Stream<DynamicTest> multipleTokensOneAssociated() {

                return hapiTest(
                        tokenCreate("FT_B").treasury(OWNER).initialSupply(500),
                        cryptoCreate(RECEIVER_WITH_SIG_REQUIRED)
                                .receiverSigRequired(true)
                                .maxAutomaticTokenAssociations(0),
                        tokenAssociate(RECEIVER_WITH_SIG_REQUIRED, "FT_B"),
                        tokenAirdrop(
                                        moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITH_SIG_REQUIRED),
                                        moving(10, "FT_B").between(OWNER, RECEIVER_WITH_SIG_REQUIRED))
                                .payingWith(OWNER)
                                .signedBy(OWNER, RECEIVER_WITH_SIG_REQUIRED)
                                .via("sigTxn"),
                        getTxnRecord("sigTxn")
                                .hasPriority(recordWith()
                                        .pendingAirdrops(includingFungiblePendingAirdrop(
                                                moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITH_SIG_REQUIRED)))
                                        .tokenTransfers(includingFungibleMovement(
                                                moving(10, "FT_B").between(OWNER, RECEIVER_WITH_SIG_REQUIRED)))),
                        getAccountBalance(RECEIVER_WITH_SIG_REQUIRED).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                        getAccountBalance(RECEIVER_WITH_SIG_REQUIRED).hasTokenBalance("FT_B", 10));
            }
        }

        @HapiTest
        @DisplayName("token not associated after pending airdrop")
        final Stream<DynamicTest> tokenNotAssociatedAfterPendingAirdrop() {
            final var notAssociatedReceiver = "notAssociatedReceiver";
            return hapiTest(
                    cryptoCreate(notAssociatedReceiver).maxAutomaticTokenAssociations(0),
                    tokenAirdrop(moveFungibleTokensTo(notAssociatedReceiver)).payingWith(OWNER),
                    cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, notAssociatedReceiver))
                            .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
        }
    }

    // custom fees
    @Nested
    @DisplayName("with custom fees for")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AirdropTokensWithCustomFees {
        private static final long HBAR_FEE = 1000L;
        private static final long HTS_FEE = 100L;
        private static final long TOKEN_TOTAL = 1_000_000L;
        private static final long initialBalance = 100 * ONE_HUNDRED_HBARS;

        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle lifecycle) {
            lifecycle.doAdhoc(setUpTokensWithCustomFees(TOKEN_TOTAL, HBAR_FEE, HTS_FEE));
        }

        @HapiTest
        @DisplayName("fungible token with fixed Hbar fee")
        @Order(1)
        final Stream<DynamicTest> airdropFungibleWithFixedHbarCustomFee() {
            final var initialBalance = 100 * ONE_HUNDRED_HBARS;
            return hapiTest(
                    cryptoCreate(OWNER_OF_TOKENS_WITH_CUSTOM_FEES).balance(initialBalance),
                    tokenAssociate(OWNER_OF_TOKENS_WITH_CUSTOM_FEES, FT_WITH_HBAR_FIXED_FEE),
                    cryptoTransfer(moving(1000, FT_WITH_HBAR_FIXED_FEE)
                            .between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_CUSTOM_FEES)),
                    tokenAirdrop(moving(1, FT_WITH_HBAR_FIXED_FEE)
                                    .between(OWNER_OF_TOKENS_WITH_CUSTOM_FEES, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .fee(ONE_HUNDRED_HBARS)
                            .payingWith(OWNER_OF_TOKENS_WITH_CUSTOM_FEES)
                            .via("transferTx"),
                    // assert balances
                    getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 0),
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(HBAR_FEE),
                    withOpContext((spec, log) -> {
                        final var record = getTxnRecord("transferTx");
                        allRunFor(spec, record);
                        final var txFee = record.getResponseRecord().getTransactionFee();
                        // the token should not be transferred but the custom fee should be charged
                        final var ownerBalance = getAccountBalance(OWNER_OF_TOKENS_WITH_CUSTOM_FEES)
                                .hasTinyBars(initialBalance - (txFee + HBAR_FEE))
                                .hasTokenBalance(FT_WITH_HBAR_FIXED_FEE, 1000);
                        allRunFor(spec, ownerBalance);
                    }),
                    // pending airdrop should be created
                    validateChargedUsd("transferTx", 0.1, 10));
        }

        @HapiTest
        @DisplayName("fungible token with fixed Hbar fee payed by treasury")
        final Stream<DynamicTest> airdropFungibleWithFixedHbarCustomFeePayedByTreasury() {
            return hapiTest(
                    tokenAirdrop(moving(1, TREASURY_AS_SENDER_TOKEN)
                                    .between(TREASURY_AS_SENDER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .payingWith(TREASURY_AS_SENDER)
                            .signedBy(TREASURY_AS_SENDER)
                            .via("transferTx"),
                    // custom fee should not be charged
                    getAccountBalance(TREASURY_AS_SENDER).hasTokenBalance(DENOM_TOKEN, 0),
                    validateChargedUsd("transferTx", 0.1, 10));
        }

        @HapiTest
        @DisplayName("NFT with 2 layers fixed Hts fee")
        @Order(2)
        final Stream<DynamicTest> transferNonFungibleWithFixedHtsCustomFees2Layers() {
            return hapiTest(
                    cryptoCreate(OWNER_OF_TOKENS_WITH_CUSTOM_FEES).balance(100 * ONE_HUNDRED_HBARS),
                    tokenAssociate(OWNER_OF_TOKENS_WITH_CUSTOM_FEES, DENOM_TOKEN),
                    tokenAssociate(OWNER_OF_TOKENS_WITH_CUSTOM_FEES, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(OWNER_OF_TOKENS_WITH_CUSTOM_FEES, NFT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(RECEIVER_WITH_0_AUTO_ASSOCIATIONS, FT_WITH_HTS_FIXED_FEE),
                    cryptoTransfer(
                            movingUnique(NFT_WITH_HTS_FIXED_FEE, 1L)
                                    .between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_CUSTOM_FEES),
                            moving(HTS_FEE, FT_WITH_HTS_FIXED_FEE)
                                    .between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_CUSTOM_FEES),
                            moving(HTS_FEE, DENOM_TOKEN)
                                    .between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER_OF_TOKENS_WITH_CUSTOM_FEES)),
                    tokenAirdrop(movingUnique(NFT_WITH_HTS_FIXED_FEE, 1L)
                                    .between(OWNER_OF_TOKENS_WITH_CUSTOM_FEES, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .payingWith(OWNER_OF_TOKENS_WITH_CUSTOM_FEES)
                            .via("transferTx"),
                    // pending airdrop should be created
                    validateChargedUsd("transferTx", 0.1, 10),
                    getAccountBalance(OWNER_OF_TOKENS_WITH_CUSTOM_FEES)
                            .hasTokenBalance(NFT_WITH_HTS_FIXED_FEE, 1) // token was transferred
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, 0) // hts was charged
                            .hasTokenBalance(DENOM_TOKEN, 0), // hts was charged
                    getAccountBalance(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).hasTokenBalance(NFT_WITH_HTS_FIXED_FEE, 0),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(DENOM_TOKEN, htsFee),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FIXED_FEE, htsFee));
        }

        @HapiTest
        @DisplayName("FT with fractional fee and net of transfers true")
        @Order(3)
        final Stream<DynamicTest> ftWithFractionalFeeNetOfTransfersTre() {
            return hapiTest(
                    tokenAssociate(OWNER, FT_WITH_FRACTIONAL_FEE_NET_OF_TRANSFERS),
                    cryptoTransfer(moving(100, FT_WITH_FRACTIONAL_FEE_NET_OF_TRANSFERS)
                            .between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER)),
                    tokenAirdrop(moving(10, FT_WITH_FRACTIONAL_FEE_NET_OF_TRANSFERS)
                                    .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS))
                            .payingWith(OWNER)
                            .via("fractionalTxn"),
                    validateChargedUsd("fractionalTxn", 0.1, 10),
                    // sender should pay 1 token for fractional fee
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE_NET_OF_TRANSFERS, 89),
                    getAccountBalance(HTS_COLLECTOR).hasTokenBalance(FT_WITH_FRACTIONAL_FEE_NET_OF_TRANSFERS, 1),
                    getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE_NET_OF_TRANSFERS, 10));
        }

        @HapiTest
        @DisplayName("FT with fractional fee with netOfTransfers=false")
        @Order(4)
        final Stream<DynamicTest> ftWithFractionalFeeNetOfTransfersFalse() {
            return hapiTest(
                    tokenAssociate(OWNER, FT_WITH_FRACTIONAL_FEE),
                    cryptoTransfer(moving(100, FT_WITH_FRACTIONAL_FEE).between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER)),
                    tokenAirdrop(moving(10, FT_WITH_FRACTIONAL_FEE)
                                    .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS))
                            .payingWith(OWNER)
                            .via("fractionalTxn"),
                    validateChargedUsd("fractionalTxn", 0.1, 10),
                    getAccountBalance(OWNER).hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 90),
                    // the fee is charged from the transfer value
                    getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE, 9));
        }

        @HapiTest
        @DisplayName("FT with fractional fee with netOfTransfers=false, in pending state")
        @Order(5)
        final Stream<DynamicTest> ftWithFractionalFeeNetOfTransfersFalseInPendingState() {
            var sender = "sender";
            return hapiTest(
                    cryptoCreate(sender).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(-1),
                    tokenAssociate(sender, FT_WITH_FRACTIONAL_FEE),
                    cryptoTransfer(moving(100, FT_WITH_FRACTIONAL_FEE).between(TREASURY_FOR_CUSTOM_FEE_TOKENS, sender)),
                    tokenAirdrop(moving(100, FT_WITH_FRACTIONAL_FEE).between(sender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .payingWith(sender)
                            .via("fractionalTxn"),
                    validateChargedUsd("fractionalTxn", 0.1, 10),
                    // the fee is charged from the transfer value,
                    // so we expect 90% of the value to be in the pending state
                    getTxnRecord("fractionalTxn")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(moving(90, FT_WITH_FRACTIONAL_FEE)
                                            .between(sender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS)))));
        }

        @HapiTest
        @DisplayName("FT with fractional fee with netOfTransfers=false and dissociated collector")
        @Order(6)
        final Stream<DynamicTest> ftWithFractionalFeeNetOfTransfersFalseNotAssociatedCollector() {
            var sender = "sender";
            return hapiTest(
                    cryptoCreate(sender).balance(ONE_HUNDRED_HBARS),
                    tokenAssociate(sender, FT_WITH_FRACTIONAL_FEE_2),
                    cryptoTransfer(
                            moving(100, FT_WITH_FRACTIONAL_FEE_2).between(TREASURY_FOR_CUSTOM_FEE_TOKENS, sender)),
                    tokenDissociate(HTS_COLLECTOR, FT_WITH_FRACTIONAL_FEE_2),
                    tokenAirdrop(moving(100, FT_WITH_FRACTIONAL_FEE_2)
                                    .between(sender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .payingWith(sender)
                            .via("fractionalTxn"),
                    validateChargedUsd("fractionalTxn", 0.2, 10),
                    getTxnRecord("fractionalTxn")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moving(90, FT_WITH_FRACTIONAL_FEE_2)
                                                    .between(sender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                                            moving(10, FT_WITH_FRACTIONAL_FEE_2).between(sender, HTS_COLLECTOR)))));
        }

        @HapiTest
        @DisplayName("NFT with royalty fee with fallback")
        @Order(7)
        final Stream<DynamicTest> nftWithRoyaltyFeesPaidByReceiverFails() {
            return hapiTest(
                    tokenAssociate(OWNER, NFT_WITH_ROYALTY_FEE),
                    cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE, 1L).between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER)),
                    tokenAirdrop(movingUnique(NFT_WITH_ROYALTY_FEE, 1L)
                                    .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS))
                            .signedByPayerAnd(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS, OWNER)
                            .hasKnownStatus(TOKEN_AIRDROP_WITH_FALLBACK_ROYALTY));
        }

        @HapiTest
        @DisplayName("with custom fee and not associated collector")
        final Stream<DynamicTest> withFtCustomFeeAndNotAssociatedCollector() {
            var sender = "sender";
            var collector = "collect";
            return hapiTest(
                    cryptoCreate(collector),
                    cryptoCreate(TOKEN_TREASURY),
                    cryptoCreate(sender).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate("receiver"),
                    tokenCreate("denomToken").treasury(TOKEN_TREASURY).initialSupply(1000),
                    tokenAssociate(collector, "denomToken"),
                    tokenCreate("FT").treasury(TOKEN_TREASURY).withCustom(fixedHtsFee(1, "denomToken", collector)),
                    tokenAssociate(sender, "FT", "denomToken"),
                    cryptoTransfer(moving(100, "FT").between(TOKEN_TREASURY, sender)),
                    cryptoTransfer(moving(100, "denomToken").between(TOKEN_TREASURY, sender)),
                    tokenDissociate(collector, "denomToken"),
                    tokenAirdrop(moving(5, "FT").between(sender, "receiver"))
                            .payingWith(sender)
                            .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR));
        }

        @HapiTest
        @DisplayName("NFT with royalty fee with fee collector as receiver")
        final Stream<DynamicTest> nftWithRoyaltyFeesPaidByReceiverWithFeeCollectorReceiver() {
            // declare collector account balance variables
            final AtomicLong currentCollectorBalance = new AtomicLong();
            final AtomicLong newCollectorBalance = new AtomicLong();
            return hapiTest(
                    // set initial collector balance variable
                    getAccountBalance(HTS_COLLECTOR).exposingBalanceTo(currentCollectorBalance::set),
                    cryptoCreate(OWNER).balance(initialBalance),
                    tokenAssociate(OWNER, NFT_WITH_ROYALTY_FEE),
                    cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE, 2L).between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER)),
                    tokenAirdrop(movingUnique(NFT_WITH_ROYALTY_FEE, 2L).between(OWNER, HTS_COLLECTOR))
                            .signedByPayerAnd(HTS_COLLECTOR, OWNER)
                            .payingWith(OWNER)
                            .via("NFT with royalty fee airdrop to collector"),
                    // assert owner balance
                    withOpContext((spec, log) -> {
                        final var record = getTxnRecord("NFT with royalty fee airdrop to collector");
                        allRunFor(spec, record);
                        final var txFee = record.getResponseRecord().getTransactionFee();
                        // the transaction fee should be charged
                        final var ownerBalance = getAccountBalance(OWNER)
                                .hasTinyBars(initialBalance - txFee)
                                .hasTokenBalance(NFT_WITH_ROYALTY_FEE, 0);
                        allRunFor(spec, ownerBalance);
                    }),
                    // set new collector balance variable
                    getAccountBalance(HTS_COLLECTOR)
                            .exposingBalanceTo(newCollectorBalance::set)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE, 1),
                    // assert collector balance is not changed
                    withOpContext((spec, log) ->
                            Assertions.assertEquals(currentCollectorBalance.get(), newCollectorBalance.get())),
                    validateChargedUsd("NFT with royalty fee airdrop to collector", 0.001, 20));
        }

        @HapiTest
        @DisplayName("FT with HTS fee with fee collector as receiver")
        final Stream<DynamicTest> ftWithRoyaltyFeesPaidByReceiverWithFeeCollectorReceiver() {
            // declare collector account balance variables
            final AtomicLong currentCollectorBalance = new AtomicLong();
            final AtomicLong newCollectorBalance = new AtomicLong();
            return hapiTest(
                    // set initial collector balance variable
                    getAccountBalance(HTS_COLLECTOR).exposingBalanceTo(currentCollectorBalance::set),
                    cryptoCreate(OWNER).balance(initialBalance),
                    tokenAssociate(OWNER, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(OWNER, DENOM_TOKEN),
                    cryptoTransfer(
                            moving(HTS_FEE, DENOM_TOKEN).between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER),
                            moving(HTS_FEE, FT_WITH_HTS_FIXED_FEE).between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER)),
                    tokenAirdrop(moving(50, FT_WITH_HTS_FIXED_FEE).between(OWNER, HTS_COLLECTOR))
                            .signedByPayerAnd(HTS_COLLECTOR, OWNER)
                            .payingWith(OWNER)
                            .via("FT with HTS fee airdrop to collector"),
                    // assert owner balance
                    withOpContext((spec, log) -> {
                        final var record = getTxnRecord("FT with HTS fee airdrop to collector");
                        allRunFor(spec, record);
                        final var txFee = record.getResponseRecord().getTransactionFee();
                        // the transaction fee should be charged
                        final var ownerBalance = getAccountBalance(OWNER)
                                .hasTinyBars(initialBalance - txFee)
                                .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, HTS_FEE - 50);
                        allRunFor(spec, ownerBalance);
                    }),
                    // set new collector balance variable
                    getAccountBalance(HTS_COLLECTOR)
                            .exposingBalanceTo(newCollectorBalance::set)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, HTS_FEE + 50)
                            .hasTokenBalance(DENOM_TOKEN, 3 * HTS_FEE),
                    withOpContext((spec, log) ->
                            Assertions.assertEquals(currentCollectorBalance.get(), newCollectorBalance.get())),
                    validateChargedUsd("FT with HTS fee airdrop to collector", 0.002, 20));
        }

        @HapiTest
        @DisplayName("NFT with royalty fee with treasury as receiver")
        final Stream<DynamicTest> nftWithRoyaltyFeesPaidByReceiverWithTreasuryReceiver() {
            // declare treasury account balance variables
            final AtomicLong currentTreasuryBalance = new AtomicLong();
            final AtomicLong newTreasuryBalance = new AtomicLong();
            return hapiTest(
                    // set initial treasury balance variable
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKENS).exposingBalanceTo(currentTreasuryBalance::set),
                    cryptoCreate(OWNER).balance(initialBalance),
                    tokenAssociate(OWNER, NFT_WITH_ROYALTY_FEE),
                    cryptoTransfer(
                            movingUnique(NFT_WITH_ROYALTY_FEE, 3L).between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER)),
                    tokenAirdrop(movingUnique(NFT_WITH_ROYALTY_FEE, 3L).between(OWNER, TREASURY_FOR_CUSTOM_FEE_TOKENS))
                            .signedByPayerAnd(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER)
                            .payingWith(OWNER)
                            .via("NFT with royalty fee airdrop to treasury"),
                    // set new treasury balance variable
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                            .exposingBalanceTo(newTreasuryBalance::set)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE, 99),
                    // assert owner balance
                    withOpContext((spec, log) -> {
                        final var record = getTxnRecord("NFT with royalty fee airdrop to treasury");
                        allRunFor(spec, record);
                        final var txFee = record.getResponseRecord().getTransactionFee();
                        // the transaction fee should be charged
                        final var ownerBalance = getAccountBalance(OWNER)
                                .hasTinyBars(initialBalance - txFee)
                                .hasTokenBalance(NFT_WITH_ROYALTY_FEE, 0);
                        allRunFor(spec, ownerBalance);
                        // assert treasury balance is not changed
                        Assertions.assertEquals(currentTreasuryBalance.get(), newTreasuryBalance.get());
                    }),
                    validateChargedUsd("NFT with royalty fee airdrop to treasury", 0.001, 20));
        }

        @HapiTest
        @DisplayName("FT with HTS fee with treasury as receiver")
        final Stream<DynamicTest> ftWithRoyaltyFeesPaidByReceiverWithTreasuryReceiver() {
            // declare treasury account balance variables
            final AtomicLong currentTreasuryBalance = new AtomicLong();
            final AtomicLong newTreasuryBalance = new AtomicLong();
            return hapiTest(
                    // set initial treasury balance variable
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKENS).exposingBalanceTo(currentTreasuryBalance::set),
                    cryptoCreate(OWNER).balance(initialBalance),
                    tokenAssociate(OWNER, FT_WITH_HTS_FIXED_FEE),
                    tokenAssociate(OWNER, DENOM_TOKEN),
                    cryptoTransfer(
                            moving(HTS_FEE, DENOM_TOKEN).between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER),
                            moving(HTS_FEE, FT_WITH_HTS_FIXED_FEE).between(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER)),
                    tokenAirdrop(moving(50, FT_WITH_HTS_FIXED_FEE).between(OWNER, TREASURY_FOR_CUSTOM_FEE_TOKENS))
                            .signedByPayerAnd(TREASURY_FOR_CUSTOM_FEE_TOKENS, OWNER)
                            .payingWith(OWNER)
                            .via("FT with HTS fee airdrop to treasury"),
                    // set new treasury balance variable
                    getAccountBalance(TREASURY_FOR_CUSTOM_FEE_TOKENS)
                            .exposingBalanceTo(newTreasuryBalance::set)
                            .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, TOKEN_TOTAL - 2 * HTS_FEE + 50),
                    // assert owner balance
                    withOpContext((spec, log) -> {
                        final var record = getTxnRecord("FT with HTS fee airdrop to treasury");
                        allRunFor(spec, record);
                        final var txFee = record.getResponseRecord().getTransactionFee();
                        // the transaction fee should be charged
                        final var ownerBalance = getAccountBalance(OWNER)
                                .hasTinyBars(initialBalance - txFee)
                                .hasTokenBalance(FT_WITH_HTS_FIXED_FEE, HTS_FEE - 50);
                        allRunFor(spec, ownerBalance);
                        // assert treasury balance is not changed
                        Assertions.assertEquals(currentTreasuryBalance.get(), newTreasuryBalance.get());
                    }),
                    validateChargedUsd("FT with HTS fee airdrop to treasury", 0.002, 20));
        }

        // When a receiver is a custom fee collector it should be exempt from the custom fee
        @HapiTest
        @DisplayName("NFT with royalty fee and allCollectorsExempt=true airdrop to NFT collector")
        final Stream<DynamicTest> nftWithARoyaltyFeeAndAllCollectorsExemptTrueAirdropToCollector() {
            return hapiTest(
                    mintToken(
                            NFT_ALL_COLLECTORS_EXEMPT_TOKEN, List.of(ByteStringUtils.wrapUnsafely("meta".getBytes()))),
                    cryptoTransfer(movingUnique(NFT_ALL_COLLECTORS_EXEMPT_TOKEN, 1L)
                            .between(TREASURY_FOR_CUSTOM_FEE_TOKENS, NFT_ALL_COLLECTORS_EXEMPT_OWNER)),
                    tokenAirdrop(movingUnique(NFT_ALL_COLLECTORS_EXEMPT_TOKEN, 1L)
                                    .between(NFT_ALL_COLLECTORS_EXEMPT_OWNER, NFT_ALL_COLLECTORS_EXEMPT_RECEIVER))
                            .signedByPayerAnd(NFT_ALL_COLLECTORS_EXEMPT_RECEIVER, NFT_ALL_COLLECTORS_EXEMPT_OWNER)
                            .payingWith(NFT_ALL_COLLECTORS_EXEMPT_OWNER)
                            .via("NFT allCollectorsExempt airdrop"),
                    getAccountBalance(NFT_ALL_COLLECTORS_EXEMPT_OWNER)
                            .hasTokenBalance(NFT_ALL_COLLECTORS_EXEMPT_TOKEN, 0),
                    getAccountBalance(NFT_ALL_COLLECTORS_EXEMPT_RECEIVER)
                            .hasTokenBalance(NFT_ALL_COLLECTORS_EXEMPT_TOKEN, 1),
                    getAccountBalance(NFT_ALL_COLLECTORS_EXEMPT_COLLECTOR).hasTinyBars(ONE_HUNDRED_HBARS),
                    getAccountBalance(NFT_ALL_COLLECTORS_EXEMPT_RECEIVER).hasTinyBars(ONE_HUNDRED_HBARS));
        }

        // When a receiver is a custom fee collector it should be exempt from the custom fee
        @HapiTest
        @DisplayName("FT with fixed hBar fee and allCollectorsExempt=true airdrop to FT collector")
        final Stream<DynamicTest> ftWithARoyaltyFeeAndAllCollectorsExemptTrueAirdropToCollector() {
            return hapiTest(
                    cryptoTransfer(moving(50, FT_ALL_COLLECTORS_EXEMPT_TOKEN)
                            .between(TREASURY_FOR_CUSTOM_FEE_TOKENS, FT_ALL_COLLECTORS_EXEMPT_OWNER)),
                    tokenAirdrop(moving(50, FT_ALL_COLLECTORS_EXEMPT_TOKEN)
                                    .between(FT_ALL_COLLECTORS_EXEMPT_OWNER, FT_ALL_COLLECTORS_EXEMPT_RECEIVER))
                            .signedByPayerAnd(FT_ALL_COLLECTORS_EXEMPT_RECEIVER, FT_ALL_COLLECTORS_EXEMPT_OWNER)
                            .payingWith(FT_ALL_COLLECTORS_EXEMPT_OWNER)
                            .via("FT allCollectorsExempt airdrop"),
                    getAccountBalance(FT_ALL_COLLECTORS_EXEMPT_OWNER)
                            .hasTokenBalance(FT_ALL_COLLECTORS_EXEMPT_TOKEN, 0),
                    getAccountBalance(FT_ALL_COLLECTORS_EXEMPT_RECEIVER)
                            .hasTokenBalance(FT_ALL_COLLECTORS_EXEMPT_TOKEN, 50),
                    getAccountBalance(FT_ALL_COLLECTORS_EXEMPT_COLLECTOR).hasTinyBars(100),
                    getAccountBalance(FT_ALL_COLLECTORS_EXEMPT_RECEIVER).hasTinyBars(ONE_HUNDRED_HBARS + 100));
        }

        // AIRDROP_27
        @HapiTest
        @DisplayName(
                "max 10 tokens to not associated account and different fee collectors does not hit the transaction limit")
        final Stream<DynamicTest> maxTokensNumberWithAllCustomFeesToNotAssociatedAccountWithDifferentFeeCollectors() {
            final var initialBalance = 100 * ONE_HUNDRED_HBARS;
            return hapiTest(flattened(
                    // test setup
                    createAccountsAndTokensWithAllCustomFees(TOKEN_TOTAL, HBAR_FEE, HTS_FEE),
                    // create Airdrop
                    tokenAirdrop(
                                    moving(1, FT_WITH_HBAR_FEE)
                                            .between(
                                                    OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES,
                                                    RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                                    moving(1, FT_WITH_HBAR_FEE)
                                            .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_HBAR_FEE),
                                    moving(10, FT_WITH_FRACTIONAL_FEE_WITH_NET_OF_TRANSFERS)
                                            .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_FRACTIONAL_FEE),
                                    moving(10, FT_WITH_FRACTIONAL_FEE_WITH_NET_OF_TRANSFERS)
                                            .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_FRACTIONAL_FEE),
                                    moving(1, FT_WITH_HTS_FEE)
                                            .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_HTS_FEE),
                                    moving(1, FT_WITH_HTS_FEE)
                                            .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_HTS_FEE),
                                    moving(1, FT_WITH_HTS_FEE)
                                            .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_HTS_FEE_SECOND),
                                    movingUnique(NFT_WITH_HBAR_FEE, 1)
                                            .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_NFT_HBAR_FEE),
                                    movingUnique(NFT_WITH_HTS_FEE, 1L)
                                            .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_NFT_HTS_FEE),
                                    movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L)
                                            .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_NFT_ROYALTY_FEE))
                            .payingWith(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES)
                            .via("multiple tokens transactions"),

                    // assert outcomes
                    getTxnRecord("multiple tokens transactions")
                            .hasPriority(recordWith()
                                    // assert FT pending airdrops
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moving(1, FT_WITH_HBAR_FEE)
                                                    .between(
                                                            OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES,
                                                            RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                                            moving(1, FT_WITH_HBAR_FEE)
                                                    .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_HBAR_FEE),
                                            moving(20, FT_WITH_FRACTIONAL_FEE_WITH_NET_OF_TRANSFERS)
                                                    .between(
                                                            OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES,
                                                            RECEIVER_FRACTIONAL_FEE),
                                            moving(2, FT_WITH_HTS_FEE)
                                                    .between(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_HTS_FEE),
                                            moving(1, FT_WITH_HTS_FEE)
                                                    .between(
                                                            OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES,
                                                            RECEIVER_HTS_FEE_SECOND)))
                                    // assert NFT pending airdrops
                                    .pendingAirdrops(includingNftPendingAirdrop(
                                            movingUnique(NFT_WITH_HBAR_FEE, 1L)
                                                    .between(
                                                            OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES,
                                                            RECEIVER_NFT_HBAR_FEE),
                                            movingUnique(NFT_WITH_HTS_FEE, 1L)
                                                    .between(
                                                            OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES, RECEIVER_NFT_HTS_FEE),
                                            movingUnique(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1L)
                                                    .between(
                                                            OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES,
                                                            RECEIVER_NFT_ROYALTY_FEE)))),
                    // assert collectors balances
                    getAccountBalance(FT_HBAR_COLLECTOR).hasTinyBars(HBAR_FEE),
                    getAccountBalance(FT_FRACTIONAL_COLLECTOR)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE_WITH_NET_OF_TRANSFERS, 2),
                    getAccountBalance(FT_WITH_HTS_FEE_COLLECTOR)
                            .hasTokenBalance(DENOM_TOKEN_HTS, 2 * HTS_FEE)
                            .hasTokenBalance(FT_WITH_HTS_FEE, 0),
                    //                    getAccountBalance(FT_WITH_HTS_FEE_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FEE,
                    // 0),
                    getAccountBalance(NFT_HBAR_COLLECTOR).hasTinyBars(HBAR_FEE),
                    getAccountBalance(NFT_HTS_COLLECTOR).hasTokenBalance(FT_WITH_HTS_FEE, HTS_FEE),
                    getAccountBalance(NFT_ROYALTY_FEE_COLLECTOR).hasTinyBars(0),
                    // assert owner balance
                    withOpContext((spec, log) -> {
                        final var record = getTxnRecord("multiple tokens transactions");
                        allRunFor(spec, record);
                        final var txFee = record.getResponseRecord().getTransactionFee();
                        // the token should not be transferred but the custom fee should be charged
                        final var ownerBalance = getAccountBalance(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES)
                                .hasTinyBars(initialBalance - (txFee + 2 * HBAR_FEE))
                                .hasTokenBalance(FT_WITH_HBAR_FEE, 1000)
                                .hasTokenBalance(NFT_WITH_HBAR_FEE, 1L);
                        allRunFor(spec, ownerBalance);
                    }),
                    getAccountBalance(OWNER_OF_TOKENS_WITH_ALL_CUSTOM_FEES)
                            .hasTokenBalance(FT_WITH_FRACTIONAL_FEE_WITH_NET_OF_TRANSFERS, 998)
                            .hasTokenBalance(FT_WITH_HTS_FEE, 1000 - HTS_FEE)
                            .hasTokenBalance(FT_WITH_HTS_FEE, 1000 - HTS_FEE)
                            .hasTokenBalance(DENOM_TOKEN_HTS, TOKEN_TOTAL - 2 * HTS_FEE)
                            .hasTokenBalance(NFT_WITH_ROYALTY_FEE_NO_FALLBACK, 1),
                    validateChargedUsd("multiple tokens transactions", 0.8, 10)));
        }
    }

    @Nested
    @DisplayName("to non existing account")
    class AirdropToNonExistingAccounts {
        @HapiTest
        @DisplayName("ED25519 key")
        final Stream<DynamicTest> airdropToNonExistingED25519Account() {
            var ed25519key = "ed25519key";
            return hapiTest(
                    newKeyNamed(ed25519key).shape(SigControl.ED25519_ON),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, ed25519key))
                            .payingWith(OWNER)
                            .via("ed25519Receiver"),
                    getAutoCreatedAccountBalance(ed25519key).hasTokenBalance(FUNGIBLE_TOKEN, 10),
                    // Any new auto-creation needs to explicitly associate token. So it will be $0.1
                    validateChargedUsd("ed25519Receiver", 0.1, 1));
        }

        @HapiTest
        @DisplayName("SECP256K1 key account")
        final Stream<DynamicTest> airdropToNonExistingSECP256K1Account() {
            var secp256K1 = "secp256K1";
            return hapiTest(
                    newKeyNamed(secp256K1).shape(SigControl.SECP256K1_ON),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, secp256K1))
                            .payingWith(OWNER)
                            .via("secp256k1Receiver"),
                    getAutoCreatedAccountBalance(secp256K1).hasTokenBalance(FUNGIBLE_TOKEN, 10),
                    // Any new auto-creation needs to explicitly associate token. So it will be $0.1
                    validateChargedUsd("secp256k1Receiver", 0.1, 1));
        }

        @HapiTest
        @DisplayName("EVM address account")
        final Stream<DynamicTest> airdropToNonExistingEvmAddressAccount() {
            // calculate evmAddress;
            final byte[] publicKey =
                    CommonUtils.unhex("02641dc27aa851ddc5a238dc569718f82b4e5eb3b61030942432fe7ac9088459c5");
            final ByteString evmAddress = ByteStringUtils.wrapUnsafely(recoverAddressFromPubKey(publicKey));

            return hapiTest(
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, evmAddress))
                            .payingWith(OWNER)
                            .via("evmAddressReceiver"),
                    getAliasedAccountBalance(evmAddress).hasTokenBalance(FUNGIBLE_TOKEN, 10),
                    // Any new auto-creation needs to explicitly associate token. So it will be $0.1
                    validateChargedUsd("evmAddressReceiver", 0.1, 1));
        }

        // AIRDROP_19
        @LeakyHapiTest(overrides = {"entities.unlimitedAutoAssociationsEnabled"})
        final Stream<DynamicTest>
                airdropNFTToNonExistingEvmAddressWithoutAutoAssociationsResultingInPendingAirdropToHollowAccount() {
            final var validAliasForAirdrop = "validAliasForAirdrop";
            return defaultHapiSpec(
                            "Send one NFT from EOA to EVM address without auto-associations resulting in the creation of Hollow account and pending airdrop")
                    .given()
                    .when(tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 7L).between(OWNER, validAliasForAirdrop))
                            .payingWith(OWNER)
                            .signedBy(OWNER)
                            .via("EVM address NFT airdrop"))
                    .then(
                            getTxnRecord("EVM address NFT airdrop")
                                    .hasPriority(recordWith()
                                            .pendingAirdrops(
                                                    includingNftPendingAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 7L)
                                                            .between(OWNER, validAliasForAirdrop)))),
                            // assert hollow account
                            getAliasedAccountInfo(validAliasForAirdrop)
                                    .isHollow()
                                    .hasAlreadyUsedAutomaticAssociations(0)
                                    .hasMaxAutomaticAssociations(0)
                                    .hasNoTokenRelationship(NON_FUNGIBLE_TOKEN),
                            validateChargedUsd("EVM address NFT airdrop", 0.1, 10));
        }

        @HapiTest
        @DisplayName("a NFT to an EVM address account")
        final Stream<DynamicTest> airdropNftToNonExistingAccount() {
            // calculate evmAddress;
            final byte[] publicKey =
                    CommonUtils.unhex("02641dc27aa851ddc5a238dc569718f82b4e5eb3b61030942432fe7ac9088459c5");
            final ByteString evmAddress = ByteStringUtils.wrapUnsafely(recoverAddressFromPubKey(publicKey));

            return hapiTest(
                    tokenAirdrop(TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 15L)
                                    .between(OWNER, evmAddress))
                            .payingWith(OWNER)
                            .via("evmAddressReceiver"),
                    getAliasedAccountBalance(evmAddress).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),
                    // Any new auto-creation needs to explicitly associate token. So it will be $0.1
                    validateChargedUsd("evmAddressReceiver", 0.1, 1));
        }
    }

    @Nested
    @DisplayName("negative scenarios")
    class InvalidAirdrops {
        @HapiTest
        @DisplayName("containing invalid token id")
        final Stream<DynamicTest> airdropInvalidTokenIdFails() {
            return hapiTest(withOpContext((spec, opLog) -> {
                final var bogusTokenId = TokenID.newBuilder().setTokenNum(9999L);
                spec.registry().saveTokenId("nonexistent", bogusTokenId.build());
                allRunFor(
                        spec,
                        tokenAirdrop(movingWithDecimals(1L, "nonexistent", 2)
                                        .betweenWithDecimals(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS))
                                .payingWith(OWNER)
                                .via("transferTx")
                                .hasKnownStatus(INVALID_TOKEN_ID),
                        validateChargedUsd("transferTx", 0.001, 10));
            }));
        }

        @HapiTest
        @DisplayName("containing multiple senders")
        final Stream<DynamicTest> airdropWithMultipleSenders() {
            return hapiTest(
                    cryptoCreate("sender1"),
                    cryptoCreate("sender2"),
                    cryptoCreate("receiver"),
                    tokenAssociate("sender1", FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                    tokenAssociate("sender2", FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                    tokenAirdrop(
                                    moving(5, FUNGIBLE_TOKEN).between("sender1", "receiver"),
                                    moving(5, FUNGIBLE_TOKEN).between("sender2", "receiver"))
                            .hasPrecheck(AIRDROP_CONTAINS_MULTIPLE_SENDERS_FOR_A_TOKEN),
                    tokenAirdrop(
                                    movingUnique(NON_FUNGIBLE_TOKEN, 1).between("sender1", "receiver"),
                                    movingUnique(NON_FUNGIBLE_TOKEN, 2).between("sender2", "receiver"))
                            .hasPrecheck(AIRDROP_CONTAINS_MULTIPLE_SENDERS_FOR_A_TOKEN));
        }

        @HapiTest
        @DisplayName("containing invalid token transfer decimals")
        final Stream<DynamicTest> airdropInvalidDecimals() {
            return hapiTest(
                    cryptoCreate("receiver").maxAutomaticTokenAssociations(5),
                    cryptoCreate(TOKEN_TREASURY),
                    tokenCreate("decimalFT")
                            .tokenType(FUNGIBLE_COMMON)
                            .treasury(TOKEN_TREASURY)
                            .decimals(2)
                            .initialSupply(1234),
                    tokenAirdrop(movingWithDecimals(10, "decimalFT", 4).betweenWithDecimals(TOKEN_TREASURY, "receiver"))
                            .signedBy(DEFAULT_PAYER, TOKEN_TREASURY, "receiver")
                            .hasKnownStatus(UNEXPECTED_TOKEN_DECIMALS));
        }

        @HapiTest
        @DisplayName("containing NFT as fungible amount")
        final Stream<DynamicTest> airdropNFTasFungibleAmount() {
            return hapiTest(
                    newKeyNamed("supply"),
                    cryptoCreate("receiver").maxAutomaticTokenAssociations(5),
                    cryptoCreate(TOKEN_TREASURY),
                    tokenCreate("NFT")
                            .treasury(TOKEN_TREASURY)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .supplyKey("supply")
                            .initialSupply(0L),
                    tokenAirdrop(moving(5, "NFT").between(TOKEN_TREASURY, "receiver"))
                            .payingWith(TOKEN_TREASURY)
                            .hasKnownStatus(ACCOUNT_AMOUNT_TRANSFERS_ONLY_ALLOWED_FOR_FUNGIBLE_COMMON));
        }

        @HapiTest
        @DisplayName("containing negative NFT serial number")
        final Stream<DynamicTest> airdropNFTNegativeSerial() {
            return hapiTest(tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, -5)
                            .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS))
                    .hasPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER));
        }

        @HapiTest
        @DisplayName("in pending state")
        final Stream<DynamicTest> freezeAndAirdrop() {
            var sender = "Sender";
            return hapiTest(
                    cryptoCreate(sender),
                    tokenAssociate(sender, FUNGIBLE_TOKEN),
                    cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, sender)),
                    // send first airdrop
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(sender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .payingWith(sender)
                            .via("first"),
                    getTxnRecord("first")
                            // assert pending airdrops
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(moving(10, FUNGIBLE_TOKEN)
                                            .between(sender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS)))),
                    tokenFreeze(FUNGIBLE_TOKEN, sender),
                    // the airdrop should fail
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(sender, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .payingWith(sender)
                            .via("second")
                            .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN));
        }

        /**
         *  When we set the token value as negative value, the transfer list that we aggregate just switch
         *  the roles of sender and receiver, so the sender checks will fail.
         */
        @HapiTest
        @DisplayName("containing negative amount")
        final Stream<DynamicTest> airdropNegativeAmountFails3() {
            var receiver = "receiver";
            return hapiTest(
                    cryptoCreate(receiver),
                    tokenAssociate(receiver, FUNGIBLE_TOKEN),
                    cryptoTransfer(moving(15, FUNGIBLE_TOKEN).between(OWNER, receiver)),
                    tokenAirdrop(moving(-15, FUNGIBLE_TOKEN).between(OWNER, receiver))
                            .hasKnownStatus(INVALID_SIGNATURE));
        }

        @HapiTest
        @DisplayName("with missing sender's signature")
        final Stream<DynamicTest> missingSenderSigFails() {
            return hapiTest(
                    tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS))
                            .hasPrecheck(INVALID_SIGNATURE));
        }

        @HapiTest
        @DisplayName("fungible token with allowance")
        final Stream<DynamicTest> airdropFtWithAllowance() {
            var spender = "spender";
            return hapiTest(
                    cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                    cryptoApproveAllowance().payingWith(OWNER).addTokenAllowance(OWNER, FUNGIBLE_TOKEN, spender, 100),
                    tokenAirdrop(movingWithAllowance(50, FUNGIBLE_TOKEN)
                                    .between(spender, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS))
                            .signedBy(OWNER, spender)
                            .hasPrecheck(NOT_SUPPORTED));
        }

        @HapiTest
        @DisplayName("NFT with allowance")
        final Stream<DynamicTest> airdropNftWithAllowance() {
            var spender = "spender";
            return hapiTest(
                    cryptoCreate(spender).balance(ONE_HUNDRED_HBARS),
                    cryptoApproveAllowance()
                            .payingWith(OWNER)
                            .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, spender, true, List.of()),
                    tokenAirdrop(movingUniqueWithAllowance(NON_FUNGIBLE_TOKEN, 1L)
                                    .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS))
                            .signedBy(OWNER, spender)
                            .hasPrecheck(NOT_SUPPORTED));
        }

        @HapiTest
        @DisplayName("owner does not have enough balance")
        final Stream<DynamicTest> ownerNotEnoughBalanceFails() {
            var lowBalanceOwner = "lowBalanceOwner";
            return hapiTest(
                    cryptoCreate(lowBalanceOwner),
                    tokenAssociate(lowBalanceOwner, FUNGIBLE_TOKEN),
                    cryptoTransfer(moving(1, FUNGIBLE_TOKEN).between(OWNER, lowBalanceOwner)),
                    tokenAirdrop(moving(99, FUNGIBLE_TOKEN)
                                    .between(lowBalanceOwner, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS))
                            .payingWith(lowBalanceOwner)
                            .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE));
        }

        @HapiTest
        @DisplayName("containing duplicate entries in the transfer list")
        final Stream<DynamicTest> duplicateEntryInTokenTransferFails() {
            return hapiTest(tokenAirdrop(
                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                    .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                    .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS))
                    .payingWith(OWNER)
                    .hasPrecheck(INVALID_ACCOUNT_AMOUNTS));
        }

        @HapiTest
        @DisplayName("already exists in pending airdrop state")
        final Stream<DynamicTest> duplicateEntryInPendingStateFails() {
            var receiver = "receiver";
            return hapiTest(
                    cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                    tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, receiver))
                            .payingWith(OWNER),
                    tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, receiver))
                            .payingWith(OWNER)
                            .hasKnownStatus(PENDING_NFT_AIRDROP_ALREADY_EXISTS));
        }

        @HapiTest
        @DisplayName("has transfer list size above the max to one account")
        final Stream<DynamicTest> aboveMaxTransfersFails() {
            return hapiTest(
                    createTokenWithName("FUNGIBLE1"),
                    createTokenWithName("FUNGIBLE2"),
                    createTokenWithName("FUNGIBLE3"),
                    createTokenWithName("FUNGIBLE4"),
                    createTokenWithName("FUNGIBLE5"),
                    createTokenWithName("FUNGIBLE6"),
                    createTokenWithName("FUNGIBLE7"),
                    createTokenWithName("FUNGIBLE8"),
                    createTokenWithName("FUNGIBLE9"),
                    createTokenWithName("FUNGIBLE10"),
                    createTokenWithName("FUNGIBLE11"),
                    tokenAirdrop(
                                    defaultMovementOfToken("FUNGIBLE1"),
                                    defaultMovementOfToken("FUNGIBLE2"),
                                    defaultMovementOfToken("FUNGIBLE3"),
                                    defaultMovementOfToken("FUNGIBLE4"),
                                    defaultMovementOfToken("FUNGIBLE5"),
                                    defaultMovementOfToken("FUNGIBLE6"),
                                    defaultMovementOfToken("FUNGIBLE7"),
                                    defaultMovementOfToken("FUNGIBLE8"),
                                    defaultMovementOfToken("FUNGIBLE9"),
                                    defaultMovementOfToken("FUNGIBLE10"),
                                    defaultMovementOfToken("FUNGIBLE11"))
                            .payingWith(OWNER)
                            .hasKnownStatus(TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED));
        }

        @HapiTest
        @DisplayName("airdrop from sender that is not associated with the fungible token")
        final Stream<DynamicTest> airdropFungibleTokenNotAssociatedWithSender() {
            final String OWNER_TWO = "owner2";
            return hapiTest(
                    cryptoCreate(OWNER_TWO).balance(ONE_HUNDRED_HBARS),
                    tokenAirdrop(moving(50, FUNGIBLE_TOKEN).between(OWNER_TWO, ASSOCIATED_RECEIVER))
                            .signedByPayerAnd(OWNER_TWO)
                            .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
        }

        @HapiTest
        @DisplayName("airdrop from sender that is not associated with the NFT")
        final Stream<DynamicTest> airdropNFTNotAssociatedWithSender() {
            final String OWNER_TWO = "owner2";
            return hapiTest(
                    cryptoCreate(OWNER_TWO).balance(ONE_HUNDRED_HBARS),
                    tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER_TWO, ASSOCIATED_RECEIVER))
                            .signedByPayerAnd(OWNER_TWO)
                            .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
        }

        @HapiTest
        @DisplayName("with different payer signature")
        final Stream<DynamicTest> missingTheRightPayerSigFails() {
            final String OWNER_TWO = "owner2";
            return hapiTest(
                    cryptoCreate(OWNER_TWO).balance(ONE_HUNDRED_HBARS),
                    tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, ASSOCIATED_RECEIVER))
                            .signedByPayerAnd(OWNER_TWO)
                            .hasKnownStatus(INVALID_SIGNATURE));
        }

        @HapiTest
        @DisplayName("when sending fungible token to system address")
        final Stream<DynamicTest> fungibleTokenReceiverSystemAddress() {
            final String ALICE = "alice";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(100L),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN_A).between(ALICE, FREEZE_ADMIN))
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(INVALID_RECEIVING_NODE_ACCOUNT));
        }

        @HapiTest
        @DisplayName("when sending nft to system address")
        final Stream<DynamicTest> nftTokenReceiverSystemAddress() {
            return hapiTest(tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 1L).between(OWNER, FREEZE_ADMIN))
                    .signedByPayerAnd(OWNER)
                    .hasKnownStatus(INVALID_RECEIVING_NODE_ACCOUNT));
        }

        @HapiTest
        @DisplayName("FT to deleted ECDSA account")
        final Stream<DynamicTest> ftOnDeletedECDSAAccount() {
            final var ecdsaKey = "ecdsaKey";
            final var deletedAccount = "deletedAccount";
            return hapiTest(
                    newKeyNamed(ecdsaKey).shape(SigControl.SECP256K1_ON),
                    cryptoCreate(deletedAccount).key(ecdsaKey),
                    cryptoDelete(deletedAccount),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, deletedAccount))
                            .signedBy(OWNER)
                            .payingWith(OWNER)
                            .hasKnownStatus(ACCOUNT_DELETED));
        }

        @HapiTest
        @DisplayName("NFT to deleted ECDSA account")
        final Stream<DynamicTest> nftToDeletedECDSAAccount() {
            final var ecdsaKey = "ecdsaKey";
            final var deletedAccount = "deletedAccount";
            return hapiTest(
                    newKeyNamed(ecdsaKey).shape(SigControl.SECP256K1_ON),
                    cryptoCreate(deletedAccount).key(ecdsaKey),
                    cryptoDelete(deletedAccount),
                    tokenAirdrop(TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 6)
                                    .between(OWNER, deletedAccount))
                            .signedBy(OWNER)
                            .payingWith(OWNER)
                            .hasKnownStatus(ACCOUNT_DELETED));
        }

        @HapiTest
        @DisplayName("FT on deleted ED25519 account")
        final Stream<DynamicTest> ftOnDeletedED25519Account() {
            final var ed25519 = "ED25519";
            final var deletedAccount = "deletedAccount";
            return hapiTest(
                    newKeyNamed(ed25519).shape(SigControl.ED25519_ON),
                    cryptoCreate(deletedAccount).key(ed25519),
                    cryptoDelete(deletedAccount),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, deletedAccount))
                            .signedBy(OWNER)
                            .payingWith(OWNER)
                            .hasKnownStatus(ACCOUNT_DELETED));
        }

        @HapiTest
        @DisplayName("NFT on deleted ED25519 account")
        final Stream<DynamicTest> nftOnDeletedED25519Account() {
            final var ed25519 = "ED25519";
            final var deletedAccount = "deletedAccount";
            return hapiTest(
                    newKeyNamed(ed25519).shape(SigControl.SECP256K1_ON),
                    cryptoCreate(deletedAccount).key(ed25519),
                    cryptoDelete(deletedAccount),
                    tokenAirdrop(TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 7)
                                    .between(OWNER, deletedAccount))
                            .signedBy(OWNER)
                            .payingWith(OWNER)
                            .hasKnownStatus(ACCOUNT_DELETED));
        }

        @HapiTest
        @DisplayName("transfer fungible token to incorrect account")
        final Stream<DynamicTest> transferFungibleTokenToIncorrectAccount() {
            final String ALICE = "alice";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenAirdrop(moving(10L, FUNGIBLE_TOKEN_A).between(ALICE, "0.0.999999999999999"))
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(INVALID_ACCOUNT_ID));
        }

        @HapiTest
        @DisplayName("transfer fungible token from invalid account")
        final Stream<DynamicTest> transferFungibleTokenFromIncorrectAccount() {
            final String ALICE = "alice";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenAirdrop(moving(10L, FUNGIBLE_TOKEN_A).between("0.0.999999999999999", ALICE))
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(INVALID_ACCOUNT_ID));
        }

        @HapiTest
        @DisplayName("transfer NFT to incorrect account")
        final Stream<DynamicTest> transferNFTTokenToIncorrectAccount() {
            final String ALICE = "alice";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    tokenAssociate(ALICE, NON_FUNGIBLE_TOKEN),
                    tokenAirdrop(TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                    .between(ALICE, "0.0.999999999999999"))
                            .signedByPayerAnd(ALICE, OWNER)
                            .hasKnownStatus(INVALID_ACCOUNT_ID));
        }

        @HapiTest
        @DisplayName("transfer NFT to from invalid account")
        final Stream<DynamicTest> transferNFTTokenFromIncorrectAccount() {
            final String ALICE = "alice";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    tokenAssociate(ALICE, NON_FUNGIBLE_TOKEN),
                    tokenAirdrop(TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                    .between("0.0.999999999999999", ALICE))
                            .signedByPayerAnd(ALICE, OWNER)
                            .hasKnownStatus(INVALID_ACCOUNT_ID));
        }

        @HapiTest
        @DisplayName("transfer fungible token to incorrect alias")
        final Stream<DynamicTest> transferFungibleTokenToIncorrectAliasAccount() {
            final String ALICE = "alice";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenAirdrop(moving(10L, FUNGIBLE_TOKEN_A)
                                    .between(ALICE, "0x000000000000000000000069175290276410818578"))
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(INVALID_ALIAS_KEY));
        }

        @HapiTest
        @DisplayName("transfer fungible token from incorrect alias")
        final Stream<DynamicTest> transferFungibleTokenFromIncorrectAliasAccount() {
            final String ALICE = "alice";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenAirdrop(moving(10L, FUNGIBLE_TOKEN_A)
                                    .between("0x000000000000000000000069175290276410818578", ALICE))
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(INVALID_ACCOUNT_ID));
        }

        @HapiTest
        @DisplayName("transfer invalid fungible token")
        final Stream<DynamicTest> transferInvalidFungibleToken() {
            final String ALICE = "alice";
            final String BOB = "bob";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                    withOpContext((spec, opLog) -> spec.registry()
                            .saveTokenId(
                                    FUNGIBLE_TOKEN_A,
                                    TokenID.newBuilder().setTokenNum(5555555L).build())),
                    tokenAirdrop(moving(50L, FUNGIBLE_TOKEN_A).between(ALICE, BOB))
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(INVALID_TOKEN_ID));
        }

        @HapiTest
        @DisplayName("transfer invalid NFT token")
        final Stream<DynamicTest> transferInvalidNFT() {
            final String ALICE = "alice";
            final String BOB = "bob";
            final String nftKey = "nftKey";

            final String NON_FUNGIBLE_TOKEN_A = "onnFungibleTokenA";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(nftKey),
                    tokenCreate(NON_FUNGIBLE_TOKEN_A)
                            .treasury(OWNER)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .name(NON_FUNGIBLE_TOKEN_A)
                            .supplyKey(nftKey),
                    tokenAssociate(ALICE, NON_FUNGIBLE_TOKEN_A),
                    withOpContext((spec, opLog) -> spec.registry()
                            .saveTokenId(
                                    NON_FUNGIBLE_TOKEN_A,
                                    TokenID.newBuilder().setTokenNum(5555555L).build())),
                    tokenAirdrop(TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN_A, 1L)
                                    .between(ALICE, BOB))
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(INVALID_TOKEN_ID));
        }

        @HapiTest
        @DisplayName("duplicate nft airdrop during handle")
        final Stream<DynamicTest> duplicateNFTHandleTokenAirdrop() {
            return hapiTest(
                    tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 9L).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .payingWith(OWNER),
                    tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 9L).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .payingWith(OWNER)
                            .hasKnownStatus(PENDING_NFT_AIRDROP_ALREADY_EXISTS));
        }

        @HapiTest
        @DisplayName("duplicate nft airdrop during pure checks")
        final Stream<DynamicTest> duplicateNFTPreHAndleTokenAirdrop() {
            return hapiTest(tokenAirdrop(
                            movingUnique(NON_FUNGIBLE_TOKEN, 9L).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                            movingUnique(NON_FUNGIBLE_TOKEN, 9L).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                    .payingWith(OWNER)
                    .hasPrecheck(INVALID_ACCOUNT_AMOUNTS));
        }

        @HapiTest
        @DisplayName("not enough hbar to pay for the trx fee")
        final Stream<DynamicTest> notEnoughHbarToPayForTheTrx() {
            final String ALICE = "alice";
            final String BOB = "bob";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    cryptoCreate(ALICE).balance(0L),
                    cryptoCreate(BOB).balance(0L),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenAssociate(BOB, FUNGIBLE_TOKEN_A),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN_A).between(ALICE, BOB))
                            .payingWith(ALICE)
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE));
        }

        @HapiTest
        @DisplayName("more than 10 tokens to multiple accounts")
        final Stream<DynamicTest> moreThanTenTokensToMultipleAccounts() {
            final String ALICE = "alice";
            final String BOB = "bob";
            final String CAROL = "carol";
            final String STEVE = "steve";
            final String TOM = "tom";
            final String YULIA = "yulia";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            final String FUNGIBLE_TOKEN_B = "fungibleTokenB";
            final String FUNGIBLE_TOKEN_C = "fungibleTokenC";
            final String FUNGIBLE_TOKEN_D = "fungibleTokenD";
            final String FUNGIBLE_TOKEN_E = "fungibleTokenE";
            final String FUNGIBLE_TOKEN_F = "fungibleTokenF";
            final String FUNGIBLE_TOKEN_G = "fungibleTokenG";
            final String FUNGIBLE_TOKEN_H = "fungibleTokenH";
            final String FUNGIBLE_TOKEN_I = "fungibleTokenI";
            final String FUNGIBLE_TOKEN_J = "fungibleTokenJ";
            final String FUNGIBLE_TOKEN_K = "fungibleTokenK";

            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(CAROL).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(STEVE).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TOM).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(YULIA).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenCreate(FUNGIBLE_TOKEN_B)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenCreate(FUNGIBLE_TOKEN_C)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenCreate(FUNGIBLE_TOKEN_D)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenCreate(FUNGIBLE_TOKEN_E)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenCreate(FUNGIBLE_TOKEN_F)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenCreate(FUNGIBLE_TOKEN_G)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenCreate(FUNGIBLE_TOKEN_H)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenCreate(FUNGIBLE_TOKEN_I)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenCreate(FUNGIBLE_TOKEN_J)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenCreate(FUNGIBLE_TOKEN_K)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenAssociate(BOB, FUNGIBLE_TOKEN_A),
                    tokenAssociate(CAROL, FUNGIBLE_TOKEN_B),
                    tokenAssociate(CAROL, FUNGIBLE_TOKEN_C),
                    tokenAssociate(CAROL, FUNGIBLE_TOKEN_D),
                    tokenAssociate(TOM, FUNGIBLE_TOKEN_E),
                    tokenAssociate(TOM, FUNGIBLE_TOKEN_F),
                    tokenAssociate(YULIA, FUNGIBLE_TOKEN_G),
                    tokenAssociate(YULIA, FUNGIBLE_TOKEN_H),
                    tokenAssociate(STEVE, FUNGIBLE_TOKEN_I),
                    tokenAssociate(STEVE, FUNGIBLE_TOKEN_J),
                    tokenAssociate(STEVE, FUNGIBLE_TOKEN_K),
                    tokenAirdrop(
                                    moving(10L, FUNGIBLE_TOKEN_A).between(ALICE, BOB),
                                    moving(10L, FUNGIBLE_TOKEN_B).between(ALICE, CAROL),
                                    moving(10L, FUNGIBLE_TOKEN_C).between(ALICE, CAROL),
                                    moving(10L, FUNGIBLE_TOKEN_D).between(ALICE, CAROL),
                                    moving(10L, FUNGIBLE_TOKEN_E).between(ALICE, TOM),
                                    moving(10L, FUNGIBLE_TOKEN_F).between(ALICE, TOM),
                                    moving(10L, FUNGIBLE_TOKEN_G).between(ALICE, YULIA),
                                    moving(10L, FUNGIBLE_TOKEN_H).between(ALICE, YULIA),
                                    moving(10L, FUNGIBLE_TOKEN_I).between(ALICE, STEVE),
                                    moving(10L, FUNGIBLE_TOKEN_J).between(ALICE, STEVE),
                                    moving(10L, FUNGIBLE_TOKEN_K).between(ALICE, STEVE))
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED));
        }

        @HapiTest
        @DisplayName("account that supposed to pay has no enough tokens to pay custom fees")
        final Stream<DynamicTest> accountThatSupposedToPayHasNoEnoughTokensForCustomFees() {
            final String ALICE = "alice";
            final String BOB = "bob";
            final String TOM = "tom";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HBAR),
                    cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TOM).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(HBAR_COLLECTOR).balance(0L),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(TOM)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L)
                            .withCustom(fixedHbarFee(ONE_HUNDRED_HBARS, HBAR_COLLECTOR)),
                    tokenAssociate(BOB, FUNGIBLE_TOKEN_A),
                    tokenAssociate(ALICE, FUNGIBLE_TOKEN_A),
                    cryptoTransfer(moving(10, FUNGIBLE_TOKEN_A).between(TOM, ALICE)),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN_A).between(ALICE, BOB))
                            .payingWith(ALICE)
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE));
        }

        @HapiTest
        @DisplayName("account that supposed to signed has been deleted")
        final Stream<DynamicTest> accountThatSupposedToSignedHasBeenDeleted() {
            final String ALICE = "alice";
            final String BOB = "bob";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(BOB)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(15L),
                    tokenAssociate(ALICE, FUNGIBLE_TOKEN_A),
                    cryptoDelete(ALICE),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN_A).between(ALICE, BOB))
                            // pay by default payer, but sign with ALICE too
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(ACCOUNT_DELETED));
        }

        @HapiTest
        @DisplayName("account that has a token is frozen supposed to fail")
        final Stream<DynamicTest> accountThatHasTokenIsFrozenSupposedToFail() {
            final String ALICE = "alice";
            final String BOB = "bob";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    newKeyNamed("freezeKey"),
                    cryptoCreate(ALICE).balance(ONE_HBAR),
                    cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey("freezeKey")
                            .initialSupply(15L),
                    tokenFreeze(FUNGIBLE_TOKEN_A, ALICE),
                    tokenAssociate(BOB, FUNGIBLE_TOKEN_A),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN_A).between(ALICE, BOB))
                            .payingWith(ALICE)
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN));
        }

        @HapiTest
        @DisplayName("account that has a token is paused supposed to fail")
        final Stream<DynamicTest> accountThatHasTokenIsPausedSupposedToFail() {
            final String ALICE = "alice";
            final String BOB = "bob";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(
                    newKeyNamed("pauseKey"),
                    cryptoCreate(ALICE).balance(ONE_HBAR),
                    cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey("pauseKey")
                            .initialSupply(15L),
                    tokenPause(FUNGIBLE_TOKEN_A),
                    tokenAssociate(BOB, FUNGIBLE_TOKEN_A).hasKnownStatus(TOKEN_IS_PAUSED),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN_A).between(ALICE, BOB))
                            .payingWith(ALICE)
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(TOKEN_IS_PAUSED));
        }

        @HapiTest
        @DisplayName("token with three layers of custom fees")
        final Stream<DynamicTest> tokenWithThreeLayersOfCustomFees() {
            final String ALICE = "alice";
            final String BOB = "bob";
            final String TOM = "tom";
            final String COLLECTOR = "collector";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            final String FUNGIBLE_TOKEN_B = "fungibleTokenB";
            final String FUNGIBLE_TOKEN_C = "fungibleTokenC";
            final String FUNGIBLE_TOKEN_D = "fungibleTokenD";

            return hapiTest(
                    cryptoCreate(ALICE).balance(ONE_MILLION_HBARS),
                    cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TOM).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(COLLECTOR).balance(0L),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(TOM)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(5000L)
                            .withCustom(fixedHbarFee(100, COLLECTOR)),
                    tokenAssociate(COLLECTOR, FUNGIBLE_TOKEN_A),
                    tokenCreate(FUNGIBLE_TOKEN_B)
                            .treasury(TOM)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(5000L)
                            .withCustom(fixedHtsFee(100, FUNGIBLE_TOKEN_A, COLLECTOR)),
                    tokenAssociate(COLLECTOR, FUNGIBLE_TOKEN_B),
                    tokenCreate(FUNGIBLE_TOKEN_C)
                            .treasury(TOM)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(5000L)
                            .withCustom(fixedHtsFee(100, FUNGIBLE_TOKEN_B, COLLECTOR)),
                    tokenAssociate(COLLECTOR, FUNGIBLE_TOKEN_C),
                    tokenCreate(FUNGIBLE_TOKEN_D)
                            .treasury(TOM)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(5000L)
                            .withCustom(fixedHtsFee(100, FUNGIBLE_TOKEN_C, COLLECTOR)),
                    tokenAssociate(COLLECTOR, FUNGIBLE_TOKEN_D),
                    tokenAssociate(BOB, FUNGIBLE_TOKEN_D),
                    tokenAssociate(ALICE, FUNGIBLE_TOKEN_D),
                    tokenAssociate(ALICE, FUNGIBLE_TOKEN_C),
                    tokenAssociate(ALICE, FUNGIBLE_TOKEN_B),
                    tokenAssociate(ALICE, FUNGIBLE_TOKEN_A),
                    cryptoTransfer(moving(1000, FUNGIBLE_TOKEN_D).between(TOM, ALICE)),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN_D).between(ALICE, BOB))
                            .payingWith(ALICE)
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(CUSTOM_FEE_CHARGING_EXCEEDED_MAX_RECURSION_DEPTH));
        }

        @HapiTest
        @DisplayName("self airdrop fails")
        final Stream<DynamicTest> selfAirdropFails() {
            return hapiTest(tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, OWNER))
                    .signedBy(OWNER)
                    .payingWith(OWNER)
                    .hasPrecheck(AIRDROP_CONTAINS_MULTIPLE_SENDERS_FOR_A_TOKEN));
        }

        @HapiTest
        @DisplayName("airdrop to 0x0 address")
        final Stream<DynamicTest> airdropTo0x0Address() {
            final byte[] publicKey =
                    CommonUtils.unhex("0000000000000000000000000000000000000000000000000000000000000000");
            final ByteString evmAddress = ByteStringUtils.wrapUnsafely(recoverAddressFromPubKey(publicKey));

            return hapiTest(tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, evmAddress))
                    .payingWith(OWNER)
                    .hasKnownStatus(INVALID_ACCOUNT_ID));
        }

        @HapiTest
        @DisplayName("airdrop 1 fungible token to 10 accounts")
        final Stream<DynamicTest> pendingAirdropOneTokenToMoreThan10Accounts() {
            final var accountNames = generateAccountNames(10);
            return hapiTest(flattened(
                    // create 10 accounts with 0 auto associations
                    createAccounts(accountNames, 0),
                    tokenAirdrop(distributeTokens(FUNGIBLE_TOKEN, OWNER, accountNames))
                            .payingWith(OWNER)
                            .hasKnownStatus(TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED)));
        }

        @HapiTest
        @DisplayName("airdrop more than 10 nft")
        final Stream<DynamicTest> airdropMoreThan10Nft() {
            final var nft = "nft";
            var nftSupplyKey = "nftSupplyKey";
            return hapiTest(flattened(
                    newKeyNamed(nftSupplyKey),
                    tokenCreate(nft)
                            .supplyKey(nftSupplyKey)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0)
                            .treasury(OWNER),
                    // mint from 1 to 10 serials
                    mintToken(
                            nft,
                            IntStream.range(0, 10)
                                    .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                                    .toList()),
                    // mint 11th serial
                    mintToken(nft, List.of(ByteString.copyFromUtf8(String.valueOf(11)))),
                    // try to airdrop 11 NFT
                    tokenAirdrop(distributeNFT(nft, OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .payingWith(OWNER)
                            .hasKnownStatus(BATCH_SIZE_LIMIT_EXCEEDED)));
        }

        private static ArrayList<String> generateAccountNames(int count) {
            final var accountNames = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) {
                accountNames.add(String.format("account%d", i));
            }
            return accountNames;
        }

        private static ArrayList<SpecOperation> createAccounts(
                ArrayList<String> accountNames, int numberOfAutoAssociations) {
            final var specOps = new ArrayList<SpecOperation>(accountNames.size());
            for (String accountName : accountNames) {
                specOps.add(cryptoCreate(accountName).maxAutomaticTokenAssociations(numberOfAutoAssociations));
            }
            return specOps;
        }

        private static TokenMovement distributeTokens(String token, String sender, ArrayList<String> accountNames) {
            return moving(accountNames.size(), token).distributing(sender, accountNames.toArray(new String[0]));
        }

        private static TokenMovement distributeNFT(String token, String sender, String receiver) {
            final long[] serials = LongStream.rangeClosed(1, 11).toArray();
            return TokenMovement.movingUnique(token, serials).between(sender, receiver);
        }
    }

    @EmbeddedHapiTest(EmbeddedReason.NEEDS_STATE_ACCESS)
    @DisplayName("verify that two fungible tokens airdrops combined into one pending airdrop")
    final Stream<DynamicTest> twoFungibleTokenCombinedIntoOneAirdrop() {
        final String ALICE = "alice";
        final String BOB = "bob";
        final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                tokenCreate(FUNGIBLE_TOKEN_A)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(100L),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_A).between(ALICE, BOB)).signedByPayerAnd(ALICE),
                tokenAirdrop(moving(1, FUNGIBLE_TOKEN_A).between(ALICE, BOB)).signedByPayerAnd(ALICE),
                EmbeddedVerbs.viewAccountPendingAirdrop(
                        FUNGIBLE_TOKEN_A,
                        ALICE,
                        BOB,
                        pendingAirdrop -> Assertions.assertEquals(
                                2, pendingAirdrop.pendingAirdropValueOrThrow().amount())));
    }

    @HapiTest
    @DisplayName("max supply hit - max long value")
    final Stream<DynamicTest> fungibleTokenMaxSupplyHit() {
        final String ALICE = "alice";
        final String BOB = "bob";
        final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
        return hapiTest(
                cryptoCreate(ALICE).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                tokenCreate(FUNGIBLE_TOKEN_A)
                        .treasury(ALICE)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(Long.MAX_VALUE),
                tokenAirdrop(moving(Long.MAX_VALUE, FUNGIBLE_TOKEN_A).between(ALICE, BOB))
                        .signedByPayerAnd(ALICE),
                tokenAirdrop(moving(Long.MAX_VALUE, FUNGIBLE_TOKEN_A).between(ALICE, BOB))
                        .signedByPayerAnd(ALICE)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE));
    }

    @Nested
    @DisplayName("delete account with relation ")
    class DeleteAccount {
        @HapiTest
        @DisplayName("to fungible token pending airdrop")
        final Stream<DynamicTest> canNotDeleteAccountRelatedToAirdrop() {
            var receiver = "receiverToDelete";
            return hapiTest(
                    cryptoCreate(receiver).maxAutomaticTokenAssociations(0),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN).between(OWNER, receiver))
                            .payingWith(OWNER),
                    cryptoDelete(OWNER).hasKnownStatus(ACCOUNT_HAS_PENDING_AIRDROPS));
        }

        @HapiTest
        @DisplayName("to non-fungible token pending airdrop")
        final Stream<DynamicTest> canNotDeleteAccountRelatedToNFTAirdrop() {
            return hapiTest(
                    tokenAirdrop(TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN, 10L)
                                    .between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                            .payingWith(OWNER),
                    cryptoDelete(OWNER).hasKnownStatus(ACCOUNT_HAS_PENDING_AIRDROPS));
        }
    }

    @Nested
    @DisplayName("to contracts")
    class ToContracts {
        // 1 EOA Airdrops a token to a Contract who is associated to the token
        @HapiTest
        @DisplayName("single token to associated contract should transfer")
        final Stream<DynamicTest> singleTokenToAssociatedContract() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 0),
                    tokenAssociate(mutableContract, FUNGIBLE_TOKEN),
                    tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN, 1)));
        }

        // 2 EOA airdrops multiple tokens to a contract that is associated to all of them
        @HapiTest
        @DisplayName("multiple tokens to associated contract should transfer")
        final Stream<DynamicTest> multipleTokensToAssociatedContract() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 0),
                    tokenAssociate(mutableContract, FUNGIBLE_TOKEN),
                    tokenAssociate(mutableContract, NFT_FOR_CONTRACT_TESTS),
                    tokenAirdrop(
                                    moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract),
                                    movingUnique(NFT_FOR_CONTRACT_TESTS, 1).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN, 1),
                    getAccountBalance(mutableContract).hasTokenBalance(NFT_FOR_CONTRACT_TESTS, 1)));
        }

        // 3 Airdrop multiple tokens to a contract that is associated to SOME of them when the contract has free auto
        // association slots.
        // Case 1:
        // associated only to FT
        @HapiTest
        @DisplayName("multiple tokens, but only FT is associated to the contract")
        final Stream<DynamicTest> multipleTokensOnlyFTIsAssociated() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 0),
                    tokenAssociate(mutableContract, FUNGIBLE_TOKEN),
                    tokenAirdrop(
                                    moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract),
                                    movingUnique(NFT_FOR_CONTRACT_TESTS, 2).between(OWNER, mutableContract))
                            .payingWith(OWNER)
                            .via("airdropToContract"),
                    getTxnRecord("airdropToContract")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingNftPendingAirdrop(movingUnique(NFT_FOR_CONTRACT_TESTS, 2)
                                            .between(OWNER, mutableContract)))),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN, 1)));
        }

        // 3 Airdrop multiple tokens to a contract that is associated to SOME of them when the contract has free auto
        // association slots.
        // Case 2:
        // associated only to NFT
        @HapiTest
        @DisplayName("multiple tokens, but only NFT is associated to the contract")
        final Stream<DynamicTest> multipleTokensOnlyNFTIsAssociated() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 0),
                    tokenAssociate(mutableContract, NFT_FOR_CONTRACT_TESTS),
                    tokenAirdrop(
                                    moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract),
                                    movingUnique(NFT_FOR_CONTRACT_TESTS, 3).between(OWNER, mutableContract))
                            .payingWith(OWNER)
                            .via("airdropToContract"),
                    getTxnRecord("airdropToContract")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract)))),
                    getAccountBalance(mutableContract).hasTokenBalance(NFT_FOR_CONTRACT_TESTS, 1)));
        }

        @HapiTest
        @DisplayName("two tokens, one associated should transfer and go to pending")
        final Stream<DynamicTest> multipleTokensOneAssociated() {
            var mutableContract = "PayReceivable";
            var secondToken = "secondFT";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 0),
                    tokenAssociate(mutableContract, FUNGIBLE_TOKEN),
                    tokenCreate(secondToken).treasury(OWNER),
                    tokenAirdrop(
                                    moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract),
                                    moving(1, secondToken).between(OWNER, mutableContract))
                            .via("airdropToContractTxn")
                            .payingWith(OWNER),
                    getTxnRecord("airdropToContractTxn")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moving(1, secondToken).between(OWNER, mutableContract)))),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN, 1),
                    getAccountBalance(mutableContract).hasTokenBalance(secondToken, 0)));
        }

        @HapiTest
        @DisplayName("with custom royalty fee with fallback to collector succeeds when exempt")
        final Stream<DynamicTest> customFeeToCollector() {
            var collectorContract = "PayReceivable";
            var nftWithCustomFee = "nfTokenWithCustomFee";
            var supplyKey = "supplyKey";
            return hapiTest(flattened(
                    deployMutableContract(collectorContract, 0),
                    newKeyNamed(supplyKey),
                    createNftWithRoyaltyWithFallbackFee(nftWithCustomFee, OWNER, collectorContract, supplyKey),
                    mintToken(nftWithCustomFee, List.of(ByteStringUtils.wrapUnsafely(("customToken1").getBytes()))),
                    tokenAirdrop(TokenMovement.movingUnique(nftWithCustomFee, 1L)
                                    .between(OWNER, collectorContract))
                            .payingWith(OWNER)
                            .via("airdropToContractTxn"),
                    getTxnRecord("airdropToContractTxn")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingNftPendingAirdrop(
                                            movingUnique(nftWithCustomFee, 1L).between(OWNER, collectorContract)))),
                    getAccountBalance(collectorContract).hasTokenBalance(nftWithCustomFee, 0)));
        }

        @HapiTest
        @DisplayName("with multiple custom royalty fee with fallback succeeds when exempt")
        final Stream<DynamicTest> customFeeToDifferentCollectorWhenExempt() {
            var collectorContract = "PayReceivable";
            var nftCollector = "nftCollector";
            var nftWithCustomFee = "nfTokenWithCustomFee";
            var supplyKey = "supplyKey";
            return hapiTest(flattened(
                    deployMutableContract(collectorContract, 1),
                    newKeyNamed(supplyKey),
                    cryptoCreate(nftCollector),
                    createNft(nftWithCustomFee, supplyKey)
                            .withCustom(royaltyFeeWithFallback(
                                    1, 2, fixedHbarFeeInheritingRoyaltyCollector(1), nftCollector, true))
                            .withCustom(royaltyFeeWithFallback(
                                    1, 2, fixedHbarFeeInheritingRoyaltyCollector(1), collectorContract, true)),
                    mintToken(nftWithCustomFee, List.of(ByteStringUtils.wrapUnsafely(("customToken1").getBytes()))),
                    tokenAirdrop(TokenMovement.movingUnique(nftWithCustomFee, 1L)
                                    .between(OWNER, collectorContract))
                            .payingWith(OWNER)
                            .via("airdropToContractTxn"),
                    getTxnRecord("airdropToContractTxn")
                            .hasPriority(recordWith()
                                    .tokenTransfers(includingNonfungibleMovement(
                                            movingUnique(nftWithCustomFee, 1L).between(OWNER, collectorContract)))),
                    getAccountBalance(collectorContract).hasTokenBalance(nftWithCustomFee, 1)));
        }

        @HapiTest
        @DisplayName("with custom fee to treasury")
        final Stream<DynamicTest> customFeeToTreasury() {
            var treasuryContract = "PayReceivable";
            var nftCollector = "nftCollector";
            var nftWithCustomFee = "nfTokenWithCustomFee";
            var supplyKey = "supplyKey";
            return hapiTest(flattened(
                    deployMutableContract(treasuryContract, 0),
                    newKeyNamed(supplyKey),
                    cryptoCreate(nftCollector),
                    createNftWithRoyaltyWithFallbackFee(nftWithCustomFee, treasuryContract, nftCollector, supplyKey),
                    mintToken(nftWithCustomFee, List.of(ByteStringUtils.wrapUnsafely(("customToken1").getBytes()))),
                    tokenAssociate(OWNER, nftWithCustomFee),
                    cryptoTransfer(movingUnique(nftWithCustomFee, 1L).between(treasuryContract, OWNER)),
                    tokenAirdrop(movingUnique(nftWithCustomFee, 1L).between(OWNER, treasuryContract))
                            .payingWith(OWNER)
                            .via("airdropToTreasuryTxn"),
                    getTxnRecord("airdropToTreasuryTxn")
                            .hasPriority(recordWith()
                                    .tokenTransfers(includingNonfungibleMovement(
                                            TokenMovement.movingUnique(nftWithCustomFee, 1L)
                                                    .between(OWNER, treasuryContract)))),
                    getAccountBalance(treasuryContract).hasTokenBalance(nftWithCustomFee, 1)));
        }

        private HapiTokenCreate createNftWithRoyaltyWithFallbackFee(
                String tokenName, String treasury, String collector, String supplyKey) {
            return tokenCreate(tokenName)
                    .treasury(treasury)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .maxSupply(10L)
                    .initialSupply(0)
                    .supplyType(TokenSupplyType.FINITE)
                    .supplyKey(supplyKey)
                    .withCustom(
                            royaltyFeeWithFallback(1, 2, fixedHbarFeeInheritingRoyaltyCollector(1), collector, false));
        }

        private HapiTokenCreate createNft(String tokenName, String supplyKey) {
            return tokenCreate(tokenName)
                    .treasury(OWNER)
                    .tokenType(NON_FUNGIBLE_UNIQUE)
                    .maxSupply(10L)
                    .initialSupply(0)
                    .supplyType(TokenSupplyType.FINITE)
                    .supplyKey(supplyKey);
        }

        @HapiTest
        @DisplayName("empty transfer list should fail")
        final Stream<DynamicTest> emptyTransferListFails() {
            return hapiTest(tokenAirdrop().payingWith(OWNER).hasPrecheckFrom(EMPTY_TOKEN_TRANSFER_BODY));
        }

        @HapiTest
        @DisplayName("FT with free associations")
        final Stream<DynamicTest> ftWithFreeAssociations() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 1),
                    tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN, 1)));
        }

        @HapiTest
        @DisplayName("NFT with free associations")
        final Stream<DynamicTest> nftWithFreeAssociations() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 1),
                    tokenAirdrop(movingUnique(NFT_FOR_CONTRACT_TESTS, 4).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(NFT_FOR_CONTRACT_TESTS, 1)));
        }

        @HapiTest
        @DisplayName("FT with zero free associations")
        final Stream<DynamicTest> ftWithZeroFreeAssociations() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 0),
                    tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN, 0)));
        }

        @HapiTest
        @DisplayName("NFT with zero free associations")
        final Stream<DynamicTest> nftWithZeroFreeAssociations() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 0),
                    tokenAirdrop(movingUnique(NFT_FOR_CONTRACT_TESTS, 5).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(NFT_FOR_CONTRACT_TESTS, 0)));
        }

        @HapiTest
        @DisplayName("FT with no free associations")
        final Stream<DynamicTest> ftWithNoFreeAssociations() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    // Create a contract with a free associations
                    deployMutableContract(mutableContract, 1),
                    // Take the free association and verify that the user received them
                    tokenAirdrop(moving(1, FUNGIBLE_TOKEN2).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN2, 1),
                    // Try airdropping the two tokens again and verify that when there are not more free associations
                    // we create an airdrop instead of crypto transfer
                    tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN, 0)));
        }

        @HapiTest
        @DisplayName("NFT with no free associations")
        final Stream<DynamicTest> nftWithNoFreeAssociations() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    // Create a contract with a free associations
                    deployMutableContract(mutableContract, 1),
                    // Take the free association and verify that the user received them
                    tokenAirdrop(movingUnique(NON_FUNGIBLE_TOKEN, 11).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(NON_FUNGIBLE_TOKEN, 1),
                    // Try airdropping the two tokens again and verify that when there are not more free associations
                    // we create an airdrop instead of crypto transfer
                    tokenAirdrop(movingUnique(NFT_FOR_CONTRACT_TESTS, 6).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(NFT_FOR_CONTRACT_TESTS, 0)));
        }

        @HapiTest
        @DisplayName("FT and NFT with free associations")
        final Stream<DynamicTest> ftAndNftWithFreeAssociations() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 2),
                    tokenAirdrop(
                                    moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract),
                                    movingUnique(NFT_FOR_CONTRACT_TESTS, 7).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN, 1),
                    getAccountBalance(mutableContract).hasTokenBalance(NFT_FOR_CONTRACT_TESTS, 1)));
        }

        @HapiTest
        @DisplayName("FT and NFT with no free associations")
        final Stream<DynamicTest> ftAndNftWithNoFreeAssociations() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 0),
                    tokenAirdrop(
                                    moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract),
                                    movingUnique(NFT_FOR_CONTRACT_TESTS, 8).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN, 0),
                    getAccountBalance(mutableContract).hasTokenBalance(NFT_FOR_CONTRACT_TESTS, 0)));
        }

        @HapiTest
        @DisplayName("FT and NFT with free associations")
        final Stream<DynamicTest> ftAndNftWithFreeAssociationsForMultipleContracts() {
            var mutableContract = "PayReceivable";
            var mutableContract2 = "PayReceivable2";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 2),
                    deployMutableContract(mutableContract2, 2),
                    tokenAirdrop(
                                    moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract),
                                    movingUnique(NFT_FOR_CONTRACT_TESTS, 9).between(OWNER, mutableContract),
                                    moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract2),
                                    movingUnique(NFT_FOR_CONTRACT_TESTS, 10).between(OWNER, mutableContract2))
                            .payingWith(OWNER),
                    getAccountBalance(mutableContract).hasTokenBalance(FUNGIBLE_TOKEN, 1),
                    getAccountBalance(mutableContract).hasTokenBalance(NFT_FOR_CONTRACT_TESTS, 1),
                    getAccountBalance(mutableContract2).hasTokenBalance(FUNGIBLE_TOKEN, 1),
                    getAccountBalance(mutableContract2).hasTokenBalance(NFT_FOR_CONTRACT_TESTS, 1)));
        }

        @HapiTest
        @DisplayName("when token is frozen")
        final Stream<DynamicTest> whenTokenIsFrozen() {
            final String ALICE = "alice";
            var mutableContract = "PayReceivable";
            final String FUNGIBLE_TOKEN_A = "fungibleTokenA";
            return hapiTest(flattened(
                    newKeyNamed("freezeKey"),
                    cryptoCreate(ALICE).balance(ONE_HBAR),
                    deployMutableContract(mutableContract, 2),
                    tokenCreate(FUNGIBLE_TOKEN_A)
                            .treasury(ALICE)
                            .tokenType(FUNGIBLE_COMMON)
                            .freezeKey("freezeKey")
                            .initialSupply(15L),
                    tokenFreeze(FUNGIBLE_TOKEN_A, ALICE),
                    tokenAssociate(mutableContract, FUNGIBLE_TOKEN_A),
                    tokenAirdrop(moving(10, FUNGIBLE_TOKEN_A).between(ALICE, mutableContract))
                            .payingWith(ALICE)
                            .signedByPayerAnd(ALICE)
                            .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN)));
        }

        @HapiTest
        @DisplayName("when airdrop to not associated contract with no free associations - crypto transfer should fail")
        final Stream<DynamicTest> airdropToNotAssociatedContractWithNoFreeAssociations() {
            var mutableContract = "PayReceivable";
            return hapiTest(flattened(
                    deployMutableContract(mutableContract, 0),
                    tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract))
                            .payingWith(OWNER),
                    cryptoTransfer(moving(1, FUNGIBLE_TOKEN).between(OWNER, mutableContract))
                            .payingWith(OWNER)
                            .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT)));
        }

        @HapiTest
        @LeakyHapiTest(
                requirement = PROPERTY_OVERRIDES,
                overrides = {"entities.unlimitedAutoAssociationsEnabled"})
        @DisplayName("airdrop NFT to hollow account remains when we deploy a contract on it's address")
        final Stream<DynamicTest> nftToHollowAccountRemainsOnCreate2() {
            final var contract = "Create2Factory";
            final var adminKey = "adminKey";
            final var salt = BigInteger.valueOf(42);
            final AtomicReference<String> factoryEvmAddress = new AtomicReference<>();
            final AtomicReference<String> expectedCreate2Address = new AtomicReference<>();
            final AtomicReference<byte[]> testContractInitcode = new AtomicReference<>();
            return hapiTest(flattened(
                    // turning this off so when we create the contract it's with maxAutoAssociation value of 0
                    overriding("entities.unlimitedAutoAssociationsEnabled", "false"),
                    newKeyNamed(adminKey),
                    uploadInitCode(contract),
                    contractCreate(contract)
                            .payingWith(GENESIS)
                            .adminKey(adminKey)
                            .exposingContractIdTo(id -> factoryEvmAddress.set(asHexedSolidityAddress(id))),

                    // GET BYTECODE OF THE CREATE2 CONTRACT
                    sourcing(() -> contractCallLocal(
                                    contract, GET_BYTECODE, asHeadlongAddress(factoryEvmAddress.get()), salt)
                            .exposingTypedResultsTo(results -> {
                                final var tcInitcode = (byte[]) results[0];
                                testContractInitcode.set(tcInitcode);
                            })
                            .payingWith(GENESIS)
                            .nodePayment(ONE_HBAR)),

                    // GET THE ADDRESS WHERE THE CONTRACT WILL BE DEPLOYED
                    sourcing(() ->
                            setExpectedCreate2Address(contract, salt, expectedCreate2Address, testContractInitcode)),

                    // Creating the hollow account
                    newKeyNamed(expectedCreate2Address.toString()),
                    cryptoTransfer(moving(1, FUNGIBLE_TOKEN).between(OWNER, expectedCreate2Address.toString()))
                            .payingWith(OWNER),

                    // Making the first airdrop to the hollow account
                    tokenAirdrop(movingUnique(NFT_FOR_CONTRACT_TESTS, 11)
                                    .between(OWNER, expectedCreate2Address.toString()))
                            .payingWith(OWNER),

                    // deploy create2
                    sourcing(() -> contractCall(contract, DEPLOY, testContractInitcode.get(), salt)
                            .payingWith(GENESIS)
                            .gas(4_000_000L)
                            .sending(1_234L)),

                    // Making the same airdrop to the contract and verifying that there is an existing airdrop
                    tokenAirdrop(movingUnique(NFT_FOR_CONTRACT_TESTS, 11)
                                    .between(OWNER, expectedCreate2Address.toString()))
                            .payingWith(OWNER)
                            .hasKnownStatus(PENDING_NFT_AIRDROP_ALREADY_EXISTS)));
        }
    }
}
