// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_MESSAGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MESSAGE_SIZE_TOO_LARGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Granted;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TopicID;
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

@HapiTestLifecycle
public class AtomicBatchEndToEndConsensusAndTokenServiceTests {
    private static final double BASE_FEE_BATCH_TRANSACTION = 0.001;
    private static final long HBAR_FEE = 1L;
    private static final String FT_FOR_END_TO_END = "ftForEndToEnd";
    private static final String FT_FOR_TOKEN_KYC = "ftForTokenKyc";
    private static final String NFT_FOR_END_TO_END = "nftForEndToEnd";
    private static final String OWNER = "owner";
    private static final String NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS =
            "newTreasuryWithUnlimitedAutoAssociations";
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String RECEIVER = "receiver";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String PAYER = "payer";
    private static final String PAYER_INSUFFICIENT_BALANCE = "payerInsufficientBalance";
    private static final String HBAR_COLLECTOR = "hbarCollector";
    private static final String TEST_TOPIC = "testTopic";
    private static final String TEST_TOPIC_SECOND = "testTopicSecond";
    private static final String TEST_TOPIC_THIRD = "testTopicThird";
    private static final String TEST_MEMO = "testMemo";
    private static final String TOPIC_MESSAGE = "testMessage";
    private static final String EMPTY_TOPIC_MESSAGE = "";

    private static final String adminKey = "adminKey";
    private static final String newAdminKey = "newAdminKey";
    private static final String feeScheduleKey = "feeScheduleKey";
    private static final String supplyKey = "supplyKey";
    private static final String submitKey = "submitKey";
    private static final String newSubmitKey = "newSubmitKey";
    private static final String kycKey = "kycKey";
    private static final String irrelevantKey1 = "irrelevantKey1";
    private static final String irrelevantKey2 = "irrelevantKey2";
    private static final String irrelevantKey3 = "irrelevantKey3";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @Nested
    @DisplayName(
            "Atomic Batch End-to-End Test Cases for Consensus and Token Service with Token Operations and Topic Message Submissions")
    class AtomicBatchEndToEndTestsWithTokenOperationsAndTopicMessageSubmissions {
        @HapiTest
        @DisplayName(
                "Token Transfer From Treasury to Receiver and Submit Message to Topic with the Transfer Details Success "
                        + "in Atomic Batch")
        public Stream<DynamicTest>
                tokenTransferFromTreasuryAndSubmitMessageToTopicWithTheTransferDetailsSuccessInBatch() {

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + RECEIVER_ASSOCIATED_FIRST + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName("Token Transfer From Account to Receiver and Submit Message to Topic with the Transfer "
                + "Details Success in Atomic Batch")
        public Stream<DynamicTest>
                tokenTransferFromAccountToReceiverAndSubmitMessageToTopicWithTheTransferDetailsSuccessInBatch() {

            // token transfers inner transactions
            final var transferTokensToFromOwnerToAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            final var transferTokensFromFirstToSecondAssociatedAccount = cryptoTransfer(
                            moving(1, FT_FOR_END_TO_END).between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                    .via("transferTxn2")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from "
                    + RECEIVER_ASSOCIATED_FIRST + " to " + RECEIVER_ASSOCIATED_FIRST + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    transferTokensToFromOwnerToAssociatedAccount,
                                    transferTokensFromFirstToSecondAssociatedAccount,
                                    submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 9L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_END_TO_END, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName("Mint NFT with Metadata and Submit Message to Topic with the NFT Metadata Success in Atomic Batch")
        public Stream<DynamicTest> mintNFTWithMetadataAndSubmitMessageToTopicWithTheNFTMetadataSuccessInBatch() {

            final var nftMetadata = "ipfs://test-nft-uri-1";
            final var metadataBytes = nftMetadata.getBytes();
            final var messageContent = "Minted NFT with metadata: " + nftMetadata;

            // mint NFT inner transaction
            final var mintNftInnerTxn = mintToken(
                            NFT_FOR_END_TO_END, List.of(ByteStringUtils.wrapUnsafely(metadataBytes)))
                    .payingWith(OWNER)
                    .via("mintTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithAdminKey(NFT_FOR_END_TO_END, OWNER, supplyKey),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(mintNftInnerTxn, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate NFT metadata
                    getTokenNftInfo(NFT_FOR_END_TO_END, 1L).hasMetadata(ByteString.copyFrom(metadataBytes)),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName("Submit Messages to Topic for FT and NFT and Create and Mint the Tokens Success in Atomic Batch")
        public Stream<DynamicTest> submitMessageToTopicForFT_And_NFTCreateAndMintTokensSuccessInBatch() {

            final var nftMetadata = "ipfs://test-nft-uri-1";
            final var metadataBytes = nftMetadata.getBytes();
            final var messageContentNFT = "Minted NFT " + NFT_FOR_END_TO_END + " with metadata: " + nftMetadata;
            final var messageContentFT = "Created FT " + FT_FOR_END_TO_END + " with initial supply of 100";

            // mint NFT inner transaction
            final var mintNftInnerTxn = mintToken(
                            NFT_FOR_END_TO_END, List.of(ByteStringUtils.wrapUnsafely(metadataBytes)))
                    .payingWith(OWNER)
                    .via("mintNftTxn")
                    .batchKey(BATCH_OPERATOR);

            final var createFungibleTokenInnerTxn = createFungibleTokenWithAdminKey(
                            FT_FOR_END_TO_END, 100, OWNER, adminKey)
                    .payingWith(OWNER)
                    .via("createFungibleTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var submitFirstMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContentNFT)
                    .message(messageContentFT)
                    .via("submitMessageNFT_Txn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            final var submitSecondMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContentFT)
                    .via("submitMessageFT_Txn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithAdminKey(NFT_FOR_END_TO_END, OWNER, supplyKey),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    mintNftInnerTxn,
                                    createFungibleTokenInnerTxn,
                                    submitFirstMessageToTopic,
                                    submitSecondMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate NFT metadata
                    getTokenNftInfo(NFT_FOR_END_TO_END, 1L).hasMetadata(ByteString.copyFrom(metadataBytes)),
                    // validate treasury balances
                    getAccountBalance(OWNER)
                            .hasTokenBalance(NFT_FOR_END_TO_END, 1L)
                            .hasTokenBalance(FT_FOR_END_TO_END, 100L),
                    // Confirm two messages are submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(2)));
        }

        @HapiTest
        @DisplayName("Token Associate and Submit Message to Topic with the Association Details Success in Atomic Batch")
        public Stream<DynamicTest> tokenAssociateAndSubmitMessageToTopicWithTheAssociationDetailsSuccessInBatch() {

            // token associate inner transaction
            final var tokensAssociateToAccount = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent =
                    "Account " + RECEIVER_ASSOCIATED_FIRST + " associated with token " + FT_FOR_END_TO_END;
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(tokensAssociateToAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate user is associated with the token
                    getAccountInfo(RECEIVER_ASSOCIATED_FIRST).hasToken(relationshipWith(FT_FOR_END_TO_END)),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName(
                "Grant KYC to Account and Submit Message to Topic with the Transfer Details Success in Atomic Batch")
        public Stream<DynamicTest> grantKYCToAccountAndSubmitMessageToTopicWithTheKYCDetailsSuccessInBatch() {

            // grant KYC inner transaction
            final var grantKYCToAccount = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "KYC for token " + FT_FOR_TOKEN_KYC + " granted to " + RECEIVER_ASSOCIATED_FIRST;
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createImmutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, kycKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(grantKYCToAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountInfo(RECEIVER_ASSOCIATED_FIRST)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Granted)),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName(
                "Associate Token, Grant KYC to Account and Submit Message to Topic with the Transfer Details Success in Atomic Batch")
        public Stream<DynamicTest>
                associateTokenGrantKYCToAccountAndSubmitMessageToTopicWithTheKYCDetailsSuccessInBatch() {

            // associate token inner transaction
            final var associateTokenToAccount = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC)
                    .payingWith(OWNER)
                    .via("associateTxn")
                    .batchKey(BATCH_OPERATOR);

            // grant KYC inner transaction
            final var grantKYCToAccount = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "KYC for token " + FT_FOR_TOKEN_KYC + " granted to " + RECEIVER_ASSOCIATED_FIRST;
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createImmutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, kycKey),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(associateTokenToAccount, grantKYCToAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountInfo(RECEIVER_ASSOCIATED_FIRST)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Granted)),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName(
                "Submit Message to Topic, Token Transfer From Treasury to Receiver, Submit New Message to Topic with the Transfer Details"
                        + "and delete the topic Success in Atomic Batch")
        public Stream<DynamicTest>
                submitMessageToTopicTokenTransferSubmitSecondMessageAndDeleteTheTopicSuccessInBatch() {

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send first message to topic
            final var submitMessageToTopic_First = submitMessageTo(TEST_TOPIC)
                    .message(TOPIC_MESSAGE)
                    .via("submitMessageTxnFirst")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + RECEIVER_ASSOCIATED_FIRST + " in atomic batch transaction";
            final var submitMessageToTopic_Second = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxnSecond")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            // delete topic inner transaction
            final var deleteTopic = deleteTopic(TEST_TOPIC)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("deleteTopicTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).adminKeyName(adminKey).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    submitMessageToTopic_First,
                                    transferTokensToAssociatedAccount,
                                    submitMessageToTopic_Second,
                                    deleteTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    // confirm topic is deleted
                    getTopicInfo(TEST_TOPIC)
                            .hasCostAnswerPrecheck(INVALID_TOPIC_ID)
                            .logged()));
        }

        @HapiTest
        @DisplayName("Token Transfer and Submit Message to Topic created without Submit Key Success in Atomic Batch")
        public Stream<DynamicTest> tokenTransferAndSubmitMessageToTopicCreatedWithoutSubmitKeySuccessInBatch() {

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(PAYER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with wrong submit key inner transaction
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(TOPIC_MESSAGE)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 0L),

                    // confirm no message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasNoAdminKey().hasNoSubmitKey().hasSeqNo(1L)));
        }

        @HapiTest
        @DisplayName(
                "Submit Messages to Multiple Topics with Custom Fees and Transfer the collected fees from the Collector "
                        + "Account Success in Atomic Batch")
        public Stream<DynamicTest>
                submitMessagesToMultipleTopicsWithCustomFeesAndTransferTheCollectedFeesSuccessInBatch() {

            // submit message to topics inner transactions
            final var submitMessageToFirstTopic = submitMessageTo(TEST_TOPIC)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("submitMessageFirstTopicInnerTxn")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToSecondTopic = submitMessageTo(TEST_TOPIC_SECOND)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("submitMessageSecondTopicInnerTxn")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToThirdTopic = submitMessageTo(TEST_TOPIC_THIRD)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("submitMessageThirdTopicInnerTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer fee collector account balance inner transaction
            final var transferCollectedFees = cryptoTransfer(
                            TokenMovement.movingHbar(3L).between(HBAR_COLLECTOR, RECEIVER))
                    .payingWith(PAYER)
                    .via("transferCollectedFeeInnerTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),

                    // create topics with submit key
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TEST_TOPIC),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(
                            adminKey, submitKey, feeScheduleKey, TEST_TOPIC_SECOND),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(
                            adminKey, submitKey, feeScheduleKey, TEST_TOPIC_THIRD),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    submitMessageToFirstTopic,
                                    submitMessageToSecondTopic,
                                    submitMessageToThirdTopic,
                                    transferCollectedFees)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER).hasTinyBars(3L),
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(0L),
                    // confirm topics messages
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(1),
                    getTopicInfo(TEST_TOPIC_SECOND).hasSubmitKey(submitKey).hasSeqNo(1),
                    getTopicInfo(TEST_TOPIC_THIRD).hasSubmitKey(submitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName(
                "Token Transfer From Treasury to Receiver and Submit Message to Topic with Insufficient Payer Balance "
                        + "Fails in Atomic Batch")
        public Stream<DynamicTest>
                tokenTransferFromTreasuryAndSubmitMessageToTopicWithInsufficientPayerBalanceFailsInBatch() {

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(PAYER_INSUFFICIENT_BALANCE)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + RECEIVER_ASSOCIATED_FIRST + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    // Confirm no message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(0)));
        }

        @HapiTest
        @DisplayName(
                "Token Transfer From Treasury to Not Associated Receiver and Submit Message to Topic Fails in Atomic Batch")
        public Stream<DynamicTest>
                tokenTransferFromTreasuryToNotAssociatedReceiverAndSubmitMessageToTopicFailsInBatch() {

            // token transfer inner transaction
            final var transferTokensToNotAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER))
                    .payingWith(PAYER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + RECEIVER + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokensToNotAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    // Confirm no message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(0)));
        }

        @HapiTest
        @DisplayName(
                "Token Transfer From Treasury to Associated Receiver and Submit Message to Topic Not Signed by Payer "
                        + "Fails in Atomic Batch")
        public Stream<DynamicTest> tokenTransferFromTreasuryToAssociatedReceiverNotSignedByPayerFailsInBatch() {

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(PAYER)
                    .signedBy(RECEIVER_ASSOCIATED_FIRST)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + RECEIVER_ASSOCIATED_FIRST + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INVALID_SIGNATURE),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    // Confirm no message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(0)));
        }

        @HapiTest
        @DisplayName(
                "Token Transfer From Treasury to Associated Receiver and Submit Message to Topic Not Signed by Submit Key "
                        + "Fails in Atomic Batch")
        public Stream<DynamicTest> tokenTransferFromTreasuryToAssociatedReceiverNotSignedBySubmitKeyFailsInBatch() {

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(PAYER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + RECEIVER_ASSOCIATED_FIRST + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    // Confirm no message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(0)));
        }

        @HapiTest
        @DisplayName("Mint NFT and Submit Too Long Message to Topic Fails in Atomic Batch")
        public Stream<DynamicTest> mintNFTAndSubmitTooLongMessageToTopicFailsInBatch() {

            final var tooLongMessageContent = "a".repeat(2000);

            // mint NFT inner transaction
            final var mintNftInnerTxn = mintNFT(NFT_FOR_END_TO_END, 0, 10)
                    .payingWith(OWNER)
                    .via("mintTxn")
                    .batchKey(BATCH_OPERATOR);

            // send too long message to topic inner transaction
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(tooLongMessageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(MESSAGE_SIZE_TOO_LARGE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithAdminKey(NFT_FOR_END_TO_END, OWNER, supplyKey),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(mintNftInnerTxn, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate NFT was not minted
                    getTokenInfo(NFT_FOR_END_TO_END).hasTotalSupply(0L),
                    // Confirm no message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(0)));
        }

        @HapiTest
        @DisplayName("Mint NFT and Submit Empty Message to Topic Fails in Atomic Batch")
        public Stream<DynamicTest> mintNFTAndSubmitEmptyMessageToTopicFailsInBatch() {

            // mint NFT inner transaction
            final var mintNftInnerTxn = mintNFT(NFT_FOR_END_TO_END, 0, 10)
                    .payingWith(OWNER)
                    .via("mintTxn")
                    .batchKey(BATCH_OPERATOR);

            // send too long message to topic inner transaction
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(EMPTY_TOPIC_MESSAGE)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createNFTWithAdminKey(NFT_FOR_END_TO_END, OWNER, supplyKey),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(mintNftInnerTxn, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INVALID_TOPIC_MESSAGE),

                    // validate NFT was not minted
                    getTokenInfo(NFT_FOR_END_TO_END).hasTotalSupply(0L),
                    // Confirm no message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(0)));
        }

        @HapiTest
        @DisplayName(
                "Token Transfer to Associated Receiver and Submit Message to Topic signed with Multiple Irrelevant Keys "
                        + "Fails in Atomic Batch")
        public Stream<DynamicTest> tokenTransferAndSendMessageToTopicSignedByMultipleIrrelevantKeysFailsInBatch() {

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(PAYER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(TOPIC_MESSAGE)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER, irrelevantKey1, irrelevantKey2, irrelevantKey3)
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    // Confirm no message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(0)));
        }

        @HapiTest
        @DisplayName("Token Transfer and Submit Message to Invalid Topic ID Fails in Atomic Batch")
        public Stream<DynamicTest> tokenTransferAndSendMessageToInvalidTopicIdFailsInBatch() {

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(PAYER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // build invalid topic ID
            final var invalidTopicId = TopicID.newBuilder()
                    .setShardNum(0)
                    .setRealmNum(0)
                    .setTopicNum(99999999L)
                    .build();

            // send message to topic with the transfer details inner transaction
            final var submitMessageToTopic = submitMessageTo(spec -> invalidTopicId)
                    .message(TOPIC_MESSAGE)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_TOPIC_ID);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 10L)));
        }

        @HapiTest
        @DisplayName("Delete Topic and Submit Message to the Deleted Topic Fails in Atomic Batch")
        public Stream<DynamicTest> deleteTopicAndSubmitMessageToTheDeletedTopicFailsInBatch() {

            // delete topic inner transaction
            final var deleteTopic = deleteTopic(TEST_TOPIC)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("deleteTopicTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to deleted topic
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(TOPIC_MESSAGE)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_TOPIC_ID);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).adminKeyName(adminKey).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(deleteTopic, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm topic is not deleted
                    getTopicInfo(TEST_TOPIC)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasSeqNo(0L)));
        }

        @HapiTest
        @DisplayName("Token Transfer and Submit Message to Topic with Wrong Submit Key Fails in Atomic Batch")
        public Stream<DynamicTest> tokenTransferAndSubmitMessageWithWrongSubmitKeyFailsInBatch() {

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(PAYER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with wrong submit key inner transaction
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(TOPIC_MESSAGE)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER, newSubmitKey)
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).adminKeyName(adminKey).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm no message is submitted to the topic
                    getTopicInfo(TEST_TOPIC)
                            .hasAdminKey(adminKey)
                            .hasSubmitKey(submitKey)
                            .hasSeqNo(0L)));
        }

        @HapiTest
        @DisplayName(
                "Submit Invalid Messages to Multiple Topics with Custom Fees and Transfer the collected fees from the Collector "
                        + "Account Fails in Atomic Batch")
        public Stream<DynamicTest>
                submitInvalidMessagesToMultipleTopicsWithCustomFeesAndTransferTheCollectedFeesFailsInBatch() {

            // submit invalid messages to topics inner transactions
            final var submitMessageToFirstTopic = submitMessageTo(TEST_TOPIC)
                    .message(TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("submitMessageFirstTopicInnerTxn")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToSecondTopic = submitMessageTo(TEST_TOPIC_SECOND)
                    .message(EMPTY_TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("submitMessageSecondTopicInnerTxn")
                    .batchKey(BATCH_OPERATOR);

            final var submitMessageToThirdTopic = submitMessageTo(TEST_TOPIC_THIRD)
                    .message(EMPTY_TOPIC_MESSAGE)
                    .signedBy(PAYER, submitKey)
                    .payingWith(PAYER)
                    .via("submitMessageThirdTopicInnerTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer fee collector account balance inner transaction
            final var transferCollectedFees = cryptoTransfer(
                            TokenMovement.movingHbar(3L).between(HBAR_COLLECTOR, RECEIVER))
                    .payingWith(PAYER)
                    .via("transferCollectedFeeInnerTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),

                    // create topics with submit key
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(adminKey, submitKey, feeScheduleKey, TEST_TOPIC),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(
                            adminKey, submitKey, feeScheduleKey, TEST_TOPIC_SECOND),
                    createMutableTopicWithSubmitKeyAndHBARFixedFee(
                            adminKey, submitKey, feeScheduleKey, TEST_TOPIC_THIRD),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    submitMessageToFirstTopic,
                                    submitMessageToSecondTopic,
                                    submitMessageToThirdTopic,
                                    transferCollectedFees)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INVALID_TOPIC_MESSAGE),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER).hasTinyBars(0L),
                    getAccountBalance(HBAR_COLLECTOR).hasTinyBars(0L),
                    // confirm topics messages
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(0),
                    getTopicInfo(TEST_TOPIC_SECOND).hasSubmitKey(submitKey).hasSeqNo(0),
                    getTopicInfo(TEST_TOPIC_THIRD).hasSubmitKey(submitKey).hasSeqNo(0)));
        }
    }

    @Nested
    @DisplayName("Atomic Batch End-to-End Test Cases for Consensus and Token Service with Token and Topic Updates")
    class AtomicBatchEndToEndTestsWithTokenAndTopicUpdates {
        @HapiTest
        @DisplayName(
                "Update Token Admin Key and Submit Message to Topic with the Updated Token Details Success in Atomic Batch")
        public Stream<DynamicTest> updateTokenAdminKeyAndSubmitMessageToTopicSuccessInBatch() {

            // token update inner transaction
            final var updateToken = tokenUpdate(FT_FOR_END_TO_END)
                    .adminKey(newAdminKey)
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Updated key for token " + FT_FOR_END_TO_END + " from " + adminKey + " to "
                    + newAdminKey + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(updateToken, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName(
                "Update Token Treasury, Token Transfer from new Treasury and Submit Message to Topic with the Update Details"
                        + " Success in Atomic Batch")
        public Stream<DynamicTest>
                updateTokenTreasuryTokenTransferFromNewTreasuryAndSubmitMessageToTopicSuccessInBatch() {

            // token update inner transaction
            final var updateToken = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(moving(1, FT_FOR_END_TO_END)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Updated treasury for token " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(updateToken, transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 9L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 1L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName(
                "Update Topic Submit Key, Token Transfer and Submit Message to the Updated Topic with the Transfer Details"
                        + " Success in Atomic Batch")
        @Tag(MATS)
        public Stream<DynamicTest> updateTopicTokenTransferAndSubmitMessageToTopicSuccessInBatch() {

            // topic update inner transaction
            final var updateTopic = updateTopic(TEST_TOPIC)
                    .submitKey(newSubmitKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(1, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Updated treasury for token " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER, newSubmitKey)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).adminKeyName(adminKey).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(updateTopic, transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 9L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 1L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(newSubmitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName(
                "Update Token Treasury, Update Topic, Token Transfer from new Treasury and Submit Message to Topic with the Update Details"
                        + " Success in Atomic Batch")
        public Stream<DynamicTest>
                updateTokenTreasuryUpdateTokenTransferTokenFromNewTreasuryAndSubmitMessageToTopicSuccessInBatch() {

            // token update inner transaction
            final var updateToken = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // topic update inner transaction
            final var updateTopic = updateTopic(TEST_TOPIC)
                    .submitKey(newSubmitKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(moving(1, FT_FOR_END_TO_END)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Updated treasury for token " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER, newSubmitKey)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).adminKeyName(adminKey).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(updateToken, updateTopic, transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 9L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 1L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(newSubmitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName(
                "Update Token Treasury, Token Transfer from new Treasury, Update Topic and Submit Message to Topic with the Update Details"
                        + " Success in Atomic Batch")
        public Stream<DynamicTest>
                updateTokenTreasuryTransferTokenFromNewTreasuryUpdateTokenAndSubmitMessageToTopicSuccessInBatch() {

            // token update inner transaction
            final var updateToken = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // topic update inner transaction
            final var updateTopic = updateTopic(TEST_TOPIC)
                    .submitKey(newSubmitKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(moving(1, FT_FOR_END_TO_END)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Updated treasury for token " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER, newSubmitKey)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).adminKeyName(adminKey).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(updateToken, transferTokensToAssociatedAccount, updateTopic, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 9L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 1L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(newSubmitKey).hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName(
                "Update Topic Admin Key, Update Topic Submit Key, Token Transfer and Submit Message to Topic with the "
                        + "Transfer Details Success in Atomic Batch")
        public Stream<DynamicTest>
                updateTopicAdminKeyUpdateTopicSubmitKeyTransferTokenAndSubmitMessageToTopicSuccessInBatch() {

            // topic update inner transactions
            final var updateTopicAdminKey = updateTopic(TEST_TOPIC)
                    .adminKey(newAdminKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey, newAdminKey)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            final var updateTopicSubmitKey = updateTopic(TEST_TOPIC)
                    .submitKey(newSubmitKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER, newAdminKey)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(1, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + RECEIVER_ASSOCIATED_FIRST + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER, newSubmitKey)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).adminKeyName(adminKey).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    updateTopicAdminKey,
                                    updateTopicSubmitKey,
                                    transferTokensToAssociatedAccount,
                                    submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 9L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 1L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC)
                            .hasSubmitKey(newSubmitKey)
                            .hasAdminKey(newAdminKey)
                            .hasSeqNo(1)));
        }

        @HapiTest
        @DisplayName(
                "Update Topic Submit Key, Token Transfer and Submit Message to the Updated Topic Signed by the old "
                        + "Submit Key Fails in Atomic Batch")
        public Stream<DynamicTest>
                updateTopicSubmitKeyTokenTransferAndSubmitMessageToTopicSignedByOldSubmitKeyFailsInBatch() {

            // topic update inner transaction
            final var updateTopic = updateTopic(TEST_TOPIC)
                    .submitKey(newSubmitKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(1, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Updated treasury for token " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER, submitKey)
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).adminKeyName(adminKey).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(updateTopic, transferTokensToAssociatedAccount, submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC).hasSubmitKey(submitKey).hasSeqNo(0)));
        }

        @HapiTest
        @DisplayName(
                "Update Topic Admin Key, Update Topic Submit Key not signed with New Admin Key, Token Transfer and Submit Message to Topic "
                        + "Fails in Atomic Batch")
        public Stream<DynamicTest>
                updateTopicAdminKeyUpdateTopicSubmitKeySignedWithOldAdminKeyTransferTokenAndSubmitMessageToTopicFailsInBatch() {

            // topic update inner transactions
            final var updateTopicAdminKey = updateTopic(TEST_TOPIC)
                    .adminKey(newAdminKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey, newAdminKey)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            final var updateTopicSubmitKey = updateTopic(TEST_TOPIC)
                    .submitKey(newSubmitKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(1, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + RECEIVER_ASSOCIATED_FIRST + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER, newSubmitKey)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).adminKeyName(adminKey).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    updateTopicAdminKey,
                                    updateTopicSubmitKey,
                                    transferTokensToAssociatedAccount,
                                    submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC)
                            .hasSubmitKey(submitKey)
                            .hasAdminKey(adminKey)
                            .hasSeqNo(0)));
        }

        @HapiTest
        @DisplayName(
                "Update Topic Admin Key not Signed By New Admin Key, Update Topic Submit Key, Token Transfer and Submit Message to Topic "
                        + "Fails in Atomic Batch")
        public Stream<DynamicTest>
                updateTopicAdminKeySignedWithOldAdminKeyOnlyUpdateTopicSubmitKeyTransferTokenAndSubmitMessageToTopicFailsInBatch() {

            // topic update inner transactions
            final var updateTopicAdminKey = updateTopic(TEST_TOPIC)
                    .adminKey(newAdminKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            final var updateTopicSubmitKey = updateTopic(TEST_TOPIC)
                    .submitKey(newSubmitKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER, newAdminKey)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // token transfer inner transaction
            final var transferTokensToAssociatedAccount = cryptoTransfer(
                            moving(1, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            // send message to topic with the transfer details inner transaction
            final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                    + RECEIVER_ASSOCIATED_FIRST + " in atomic batch transaction";
            final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                    .message(messageContent)
                    .via("submitMessageTxn")
                    .payingWith(OWNER)
                    .signedBy(OWNER, newSubmitKey)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // create topic with submit key
                    createTopic(TEST_TOPIC).adminKeyName(adminKey).submitKeyName(submitKey),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    updateTopicAdminKey,
                                    updateTopicSubmitKey,
                                    transferTokensToAssociatedAccount,
                                    submitMessageToTopic)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    // Confirm one message is submitted to the topic
                    getTopicInfo(TEST_TOPIC)
                            .hasSubmitKey(submitKey)
                            .hasAdminKey(adminKey)
                            .hasSeqNo(0)));
        }
    }

    private HapiTokenCreate createFungibleTokenWithAdminKey(
            String tokenName, long supply, String treasury, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .tokenType(FUNGIBLE_COMMON);
    }

    private HapiTokenCreate createImmutableFTWithKyc(String tokenName, String treasury, String kycKey) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(treasury)
                .initialSupply(100L)
                .kycKey(kycKey);
    }

    private HapiTokenCreate createNFTWithAdminKey(String tokenName, String treasury, String supplyKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey);
    }

    private HapiTokenMint mintNFT(String tokenName, int rangeStart, int rangeEnd) {
        return mintToken(
                tokenName,
                IntStream.range(rangeStart, rangeEnd)
                        .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                        .toList());
    }

    private HapiTopicCreate createMutableTopicWithSubmitKeyAndHBARFixedFee(
            String adminKey, String submitKey, String feeScheduleKey, String topicId) {
        return new HapiTopicCreate(topicId)
                .adminKeyName(adminKey)
                .feeScheduleKeyName(feeScheduleKey)
                .submitKeyName(submitKey)
                .topicMemo(TEST_MEMO)
                .withConsensusCustomFee(fixedConsensusHbarFee(HBAR_FEE, HBAR_COLLECTOR))
                .via("createTopicTxn");
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HBAR),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PAYER_INSUFFICIENT_BALANCE).balance(0L),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(-1),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND).balance(ONE_HBAR),
                cryptoCreate(HBAR_COLLECTOR).balance(0L),
                newKeyNamed(adminKey),
                newKeyNamed(newAdminKey),
                newKeyNamed(feeScheduleKey),
                newKeyNamed(supplyKey),
                newKeyNamed(submitKey),
                newKeyNamed(newSubmitKey),
                newKeyNamed(kycKey),
                newKeyNamed(irrelevantKey1),
                newKeyNamed(irrelevantKey2),
                newKeyNamed(irrelevantKey3));
    }
}
