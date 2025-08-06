// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ByteStringUtils;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
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

@HapiTestLifecycle
public class AtomicBatchEndToEndConsensusAndTokenServiceTests {
    private static final double BASE_FEE_BATCH_TRANSACTION = 0.001;
    private static final String FT_FOR_END_TO_END = "ftForEndToEnd";
    private static final String NFT_FOR_END_TO_END = "nftForEndToEnd";
    private static final String OWNER = "owner";
    private static final String NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS =
            "newTreasuryWithUnlimitedAutoAssociations";
    private static final String NEW_TREASURY_WITHOUT_FREE_AUTO_ASSOCIATIONS = "newTreasuryWithoutFreeAutoAssociations";
    private static final String NEW_TREASURY_WITH_ZERO_AUTO_ASSOCIATIONS = "newTreasuryWithZeroAutoAssociations";
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String TEST_TOPIC = "testTopic";

    private static final String adminKey = "adminKey";
    private static final String newAdminKey = "newAdminKey";
    private static final String supplyKey = "supplyKey";
    private static final String submitKey = "submitKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    @DisplayName("Token Transfer And Submit Message to Topic with the Transfer Details Success in Atomic Batch")
    public Stream<DynamicTest> tokenTransferAndSubmitMessageToTopicWithTheTransferDetailsSuccessInBatch() {

        // token transfer inner transaction
        final var transferTokensToAssociatedAccount = cryptoTransfer(
                        moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                .payingWith(OWNER)
                .via("transferTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(SUCCESS);

        // send message to topic with the transfer details inner transaction
        final var messageContent = "Transfer of 10 tokens " + FT_FOR_END_TO_END + " from " + OWNER + " to "
                + RECEIVER_ASSOCIATED_FIRST + " in atomic batch transaction";
        final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                .message(messageContent)
                .via("submitMessageTxn")
                .payingWith(OWNER)
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(SUCCESS);

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
    @DisplayName("Mint NFT with Metadata And Submit Message to Topic with the NFT Metadata Success in Atomic Batch")
    public Stream<DynamicTest> mintNFTWithMetadataAndSubmitMessageToTopicWithTheNFTMetadataSuccessInBatch() {

        final var nftMetadata = "ipfs://test-nft-uri-1";
        final var metadataBytes = nftMetadata.getBytes();
        final var messageContent = "Minted NFT with metadata: " + nftMetadata;

        // mint NFT inner transaction
        final var mintNftInnerTxn = mintToken(NFT_FOR_END_TO_END, List.of(ByteStringUtils.wrapUnsafely(metadataBytes)))
                .payingWith(OWNER)
                .via("mintTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(SUCCESS);

        // send message to topic with the transfer details inner transaction
        final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                .message(messageContent)
                .via("submitMessageTxn")
                .payingWith(OWNER)
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(SUCCESS);

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
        final var mintNftInnerTxn = mintToken(NFT_FOR_END_TO_END, List.of(ByteStringUtils.wrapUnsafely(metadataBytes)))
                .payingWith(OWNER)
                .via("mintNftTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(SUCCESS);

        final var createFungibleTokenInnerTxn = createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey)
                .payingWith(OWNER)
                .via("createFungibleTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(SUCCESS);

        // send message to topic with the transfer details inner transaction
        final var submitFirstMessageToTopic = submitMessageTo(TEST_TOPIC)
                .message(messageContentNFT)
                .message(messageContentFT)
                .via("submitMessageNFT_Txn")
                .payingWith(OWNER)
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(SUCCESS);

        final var submitSecondMessageToTopic = submitMessageTo(TEST_TOPIC)
                .message(messageContentFT)
                .via("submitMessageFT_Txn")
                .payingWith(OWNER)
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(SUCCESS);

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
    @DisplayName("Token Associate And Submit Message to Topic with the Association Details Success in Atomic Batch")
    public Stream<DynamicTest> tokenAssociateAndSubmitMessageToTopicWithTheAssociationDetailsSuccessInBatch() {

        // token associate inner transaction
        final var tokensAssociateToAccount = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END)
                .payingWith(OWNER)
                .via("associateTxn")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(SUCCESS);

        // send message to topic with the transfer details inner transaction
        final var messageContent =
                "Account " + RECEIVER_ASSOCIATED_FIRST + " associated with token " + FT_FOR_END_TO_END;
        final var submitMessageToTopic = submitMessageTo(TEST_TOPIC)
                .message(messageContent)
                .via("submitMessageTxn")
                .payingWith(OWNER)
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(SUCCESS);

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

    private HapiTokenCreate createFungibleTokenWithAdminKey(
            String tokenName, long supply, String treasury, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .tokenType(FUNGIBLE_COMMON);
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

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HBAR),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(-1),
                cryptoCreate(NEW_TREASURY_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(1),
                cryptoCreate(NEW_TREASURY_WITH_ZERO_AUTO_ASSOCIATIONS)
                        .balance(ONE_HUNDRED_HBARS)
                        .maxAutomaticTokenAssociations(0),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND).balance(ONE_HBAR),
                newKeyNamed(adminKey),
                newKeyNamed(newAdminKey),
                newKeyNamed(supplyKey),
                newKeyNamed(submitKey));
    }
}
