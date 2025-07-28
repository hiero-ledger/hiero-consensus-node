// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token.batch;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingNftPendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.FreezeNotApplicable;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Frozen;
import static com.hederahashgraph.api.proto.java.TokenFreezeStatus.Unfrozen;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Granted;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.KycNotApplicable;
import static com.hederahashgraph.api.proto.java.TokenKycStatus.Revoked;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import com.hederahashgraph.api.proto.java.TokenPauseStatus;
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
@Tag(TOKEN)
public class AtomicBatchTokenServiceEndToEndTests {

    private static final double BASE_FEE_BATCH_TRANSACTION = 0.001;
    private static final String FT_FOR_END_TO_END = "ftForEndToEnd";
    private static final String NFT_FOR_END_TO_END = "nftForEndToEnd";
    private static final String FT_FOR_TOKEN_PAUSE = "ftForTokenPause";
    private static final String NFT_FOR_TOKEN_FREEZE = "nftForTokenFreeze";
    private static final String FT_FOR_TOKEN_BURN = "ftForTokenBurn";
    private static final String FT_FOR_TOKEN_KYC = "ftForTokenKyc";
    private static final String DUMMY = "dummy";
    private static final String OWNER = "owner";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String RECEIVER_NOT_ASSOCIATED = "receiverNotAssociated";
    private static final String RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS = "receiverWithFreeAutoAssociations";
    private static final String RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS = "receiverWithUnlimitedAutoAssociations";
    private static final String RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS = "receiverWithoutFreeAutoAssociations";
    private static final String RECEIVER_WITH_0_AUTO_ASSOCIATIONS = "receiverWith0AutoAssociations";
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String nftSupplyKey = "nftSupplyKey";
    private static final String pauseKey = "pauseKey";
    private static final String newPauseKey = "newPauseKey";
    private static final String freezeKey = "freezeKey";
    private static final String newFreezeKey = "newFreezeKey";
    private static final String adminKey = "adminKey";
    private static final String newAdminKey = "newAdminKey";
    private static final String supplyKey = "supplyKey";
    private static final String newSupplyKey = "newSupplyKey";
    private static final String wipeKey = "wipeKey";
    private static final String newWipeKey = "newWipeKey";
    private static final String kycKey = "kycKey";
    private static final String newKycKey = "newKycKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    // Token Lifecycle tests
    @Nested
    @DisplayName("Token Associate and Transfer Tests - including auto-associations and airdrops")
    class TokenAssociateAndTransferTests {
        @HapiTest
        @DisplayName("Associate and multiple transfers of FT token without custom fees success in batch")
        public Stream<DynamicTest> associateAndMultipleTransfersFTWithoutCustomFeesSuccessInBatch() {

            // create associate receiver inner transactions
            final var associateFirstReceiver = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var associateSecondReceiver = tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            // create transfer tokens inner transactions
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferSecondReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("transferSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            final var tokenAirdrops = tokenAirdrop(
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS),
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                    .payingWith(OWNER)
                    .via("airdropsTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts
                    createAccountsAndKeys(),

                    // fill in the 1 free auto-association slot
                    createDummyToken(),
                    cryptoTransfer(moving(10, DUMMY).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)),

                    // create fungible token
                    createImmutableFT(FT_FOR_END_TO_END, OWNER),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    associateFirstReceiver,
                                    associateSecondReceiver,
                                    tokenTransferFirstReceiver,
                                    tokenTransferSecondReceiver,
                                    tokenAirdrops)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 60L),

                    // validate pending token airdrops
                    getTxnRecord("airdropsTxn")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moving(10, FT_FOR_END_TO_END)
                                                    .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                            moving(10, FT_FOR_END_TO_END)
                                                    .between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))))));
        }

        @HapiTest
        @DisplayName("Associate and multiple transfers of NFT token without custom fees success in batch")
        public Stream<DynamicTest> associateAndMultipleTransfersNFTWithoutCustomFeesSuccessInBatch() {

            // create associate receiver inner transactions
            final var associateFirstReceiver = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var associateSecondReceiver = tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            // create transfer tokens inner transactions
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            movingUnique(NFT_FOR_END_TO_END, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferSecondReceiver = cryptoTransfer(
                            movingUnique(NFT_FOR_END_TO_END, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("transferSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            final var tokenAirdrops = tokenAirdrop(
                            movingUnique(NFT_FOR_END_TO_END, 3L).between(OWNER, RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS),
                            movingUnique(NFT_FOR_END_TO_END, 4L)
                                    .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                            movingUnique(NFT_FOR_END_TO_END, 5L)
                                    .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                            movingUnique(NFT_FOR_END_TO_END, 6L).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                    .payingWith(OWNER)
                    .via("airdropsTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts,
                    createAccountsAndKeys(),

                    // fill in the 1 free auto-association slot
                    createDummyToken(),
                    cryptoTransfer(moving(10, DUMMY).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)),

                    // create and mint non-fungible token
                    createImmutableNFT(NFT_FOR_END_TO_END, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_END_TO_END, 0, 10),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    associateFirstReceiver,
                                    associateSecondReceiver,
                                    tokenTransferFirstReceiver,
                                    tokenTransferSecondReceiver,
                                    tokenAirdrops)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_END_TO_END, 6L),

                    // validate pending token airdrops
                    getTxnRecord("airdropsTxn")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingNftPendingAirdrop(
                                            movingUnique(NFT_FOR_END_TO_END, 5L)
                                                    .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                            movingUnique(NFT_FOR_END_TO_END, 6L)
                                                    .between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))))));
        }

        @HapiTest
        @DisplayName("Mint, Associate and Transfer NFT token without custom fees success in batch")
        public Stream<DynamicTest> mintAssociateAndTransferNFTWithoutCustomFeesSuccessInBatch() {

            // create token mint transaction
            final var mintNFT = mintNFT(NFT_FOR_END_TO_END, 0, 10)
                    .payingWith(OWNER)
                    .via("mintTxn")
                    .batchKey(BATCH_OPERATOR);

            // create associate receiver inner transactions
            final var associateFirstReceiver = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var associateSecondReceiver = tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            // create transfer tokens inner transactions
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            movingUnique(NFT_FOR_END_TO_END, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferSecondReceiver = cryptoTransfer(
                            movingUnique(NFT_FOR_END_TO_END, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("transferSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            final var tokenAirdrops = tokenAirdrop(
                            movingUnique(NFT_FOR_END_TO_END, 3L).between(OWNER, RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS),
                            movingUnique(NFT_FOR_END_TO_END, 4L)
                                    .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                            movingUnique(NFT_FOR_END_TO_END, 5L)
                                    .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                            movingUnique(NFT_FOR_END_TO_END, 6L).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                    .payingWith(OWNER)
                    .via("airdropsTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts,
                    createAccountsAndKeys(),

                    // fill in the 1 free auto-association slot
                    createDummyToken(),
                    cryptoTransfer(moving(10, DUMMY).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)),

                    // create non-fungible token
                    createImmutableNFT(NFT_FOR_END_TO_END, OWNER, nftSupplyKey),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    mintNFT,
                                    associateFirstReceiver,
                                    associateSecondReceiver,
                                    tokenTransferFirstReceiver,
                                    tokenTransferSecondReceiver,
                                    tokenAirdrops)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_END_TO_END, 6L),

                    // validate pending token airdrops
                    getTxnRecord("airdropsTxn")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingNftPendingAirdrop(
                                            movingUnique(NFT_FOR_END_TO_END, 5L)
                                                    .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                            movingUnique(NFT_FOR_END_TO_END, 6L)
                                                    .between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))))));
        }

        @HapiTest
        @DisplayName("Mint, Associate and Transfer Non-Existent NFT token without custom fees fails in batch")
        public Stream<DynamicTest> mintAssociateAndTransferNonExistentNFTWithoutCustomFeesFailsInBatch() {

            // create token mint transaction
            final var mintNFT = mintNFT(NFT_FOR_END_TO_END, 0, 10)
                    .payingWith(OWNER)
                    .via("mintTxn")
                    .batchKey(BATCH_OPERATOR);

            // create associate receiver inner transactions
            final var associateFirstReceiver = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            // create transfer tokens inner transactions
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            movingUnique(NFT_FOR_END_TO_END, 15L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_NFT_ID);

            return hapiTest(flattened(
                    // create keys and accounts,
                    createAccountsAndKeys(),

                    // create non-fungible token
                    createImmutableNFT(NFT_FOR_END_TO_END, OWNER, nftSupplyKey),

                    // perform the atomic batch transaction
                    atomicBatch(mintNFT, associateFirstReceiver, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_END_TO_END, 0L)));
        }

        @HapiTest
        @DisplayName("Mint, Associate, Transfer and Delete NFT token without custom fees success in batch")
        public Stream<DynamicTest> mintAssociateTransferAndDeleteNFTWithoutCustomFeesSuccessInBatch() {

            // create token mint transaction
            final var mintNFT = mintNFT(NFT_FOR_END_TO_END, 0, 10)
                    .payingWith(OWNER)
                    .via("mintTxn")
                    .batchKey(BATCH_OPERATOR);

            // create associate receiver inner transactions
            final var associateFirstReceiver = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var associateSecondReceiver = tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            // create transfer tokens inner transactions
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            movingUnique(NFT_FOR_END_TO_END, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferSecondReceiver = cryptoTransfer(
                            movingUnique(NFT_FOR_END_TO_END, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("transferSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            // delete token inner transaction
            final var deleteToken = tokenDelete(NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("deleteTxn")
                    .signedBy(OWNER, adminKey)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts,
                    createAccountsAndKeys(),

                    // create non-fungible token
                    createMutableNFT(NFT_FOR_END_TO_END, OWNER, nftSupplyKey, adminKey),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    mintNFT,
                                    associateFirstReceiver,
                                    associateSecondReceiver,
                                    tokenTransferFirstReceiver,
                                    tokenTransferSecondReceiver,
                                    deleteToken)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_END_TO_END, 8L),

                    // confirm token was deleted
                    cryptoTransfer(movingUnique(NFT_FOR_END_TO_END, 5L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .hasKnownStatus(TOKEN_WAS_DELETED)));
        }

        @HapiTest
        @DisplayName("Mint, Burn and Delete NFT token without custom fees success in batch")
        public Stream<DynamicTest> mintBurnAndDeleteNFTWithoutCustomFeesSuccessInBatch() {

            // create token mint transaction
            final var mintNFT = mintNFT(NFT_FOR_END_TO_END, 0, 10)
                    .payingWith(OWNER)
                    .via("mintTxn")
                    .batchKey(BATCH_OPERATOR);

            // create token burn inner transaction
            final var burnNFT = burnToken(NFT_FOR_END_TO_END, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L))
                    .payingWith(OWNER)
                    .via("burnTxn")
                    .signedBy(OWNER, nftSupplyKey)
                    .batchKey(BATCH_OPERATOR);

            // delete token inner transaction
            final var deleteToken = tokenDelete(NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("deleteTxn")
                    .signedBy(OWNER, adminKey)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts,
                    createAccountsAndKeys(),

                    // create non-fungible token
                    createMutableNFT(NFT_FOR_END_TO_END, OWNER, nftSupplyKey, adminKey),

                    // perform the atomic batch transaction
                    atomicBatch(mintNFT, burnNFT, deleteToken)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_END_TO_END, 0L),

                    // confirm token was deleted
                    cryptoTransfer(movingUnique(NFT_FOR_END_TO_END, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .hasKnownStatus(TOKEN_WAS_DELETED)));
        }

        @HapiTest
        @DisplayName("Associate, Grant KYC and Transfer FT success in batch")
        public Stream<DynamicTest> associateGrantKYCAndTransferFTSuccessInBatch() {

            // create associate receiver inner transactions
            final var associateFirstReceiver = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey, RECEIVER_ASSOCIATED_FIRST)
                    .via("associateFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            // grant KYC to the fungible token
            final var grantTokenKYC = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createImmutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, kycKey),

                    // perform the atomic batch transaction
                    atomicBatch(associateFirstReceiver, grantTokenKYC, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 10L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 90L),
                    getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Granted))));
        }

        @HapiTest
        @DisplayName("Associate, Freeze Token and Transfer FT fails in batch")
        public Stream<DynamicTest> associateFreezeAndTransferFTFailsInBatch() {

            // create associate receiver inner transactions
            final var associateFirstReceiver = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE)
                    .payingWith(OWNER)
                    .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                    .via("associateFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            // freeze the fungible token
            final var freezeToken = tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, freezeKey)
                    .via("freezeTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createMutableNFTWithFreezeKey(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, freezeKey, adminKey),
                    mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),

                    // perform the atomic batch transaction
                    atomicBatch(associateFirstReceiver, freezeToken, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 10L)));
        }

        @HapiTest
        @DisplayName("Associate, Unfreeze Token and Transfer FT success in batch")
        public Stream<DynamicTest> associateUnfreezeAndTransferFTSuccessInBatch() {

            // create associate receiver inner transactions
            final var associateFirstReceiver = tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE)
                    .payingWith(OWNER)
                    .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                    .via("associateFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            // unfreeze the fungible token
            final var unfreezeToken = tokenUnfreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, freezeKey)
                    .via("freezeTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createMutableNFTWithFreezeKey(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, freezeKey, adminKey),
                    mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),

                    // perform the atomic batch transaction
                    atomicBatch(associateFirstReceiver, unfreezeToken, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 9L)));
        }

        @HapiTest
        @DisplayName("Associate and multiple transfers of FT and NFT tokens without custom fees success in batch")
        public Stream<DynamicTest> associateAndMultipleTransfersFTAndNFTWithoutCustomFeesSuccessInBatch() {

            // create associate receiver inner transactions
            final var associateFirstReceiver = tokenAssociate(
                            RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END, NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var associateSecondReceiver = tokenAssociate(
                            RECEIVER_ASSOCIATED_SECOND, FT_FOR_END_TO_END, NFT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("associateSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            // create transfer tokens inner transactions
            final var tokenTransfers = cryptoTransfer(
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                            movingUnique(NFT_FOR_END_TO_END, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                            movingUnique(NFT_FOR_END_TO_END, 2L).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("transferTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts
                    createAccountsAndKeys(),

                    // fill in the 1 free auto-association slot
                    createDummyToken(),
                    cryptoTransfer(moving(10, DUMMY).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)),

                    // create fungible token
                    createImmutableFT(FT_FOR_END_TO_END, OWNER),
                    // create and mint non-fungible token,
                    createImmutableNFT(NFT_FOR_END_TO_END, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_END_TO_END, 0, 10),

                    // perform the atomic batch transaction
                    atomicBatch(associateFirstReceiver, associateSecondReceiver, tokenTransfers)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 80L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_END_TO_END, 8L)));
        }

        @HapiTest
        @DisplayName("Multiple airdrops of FT and NFT tokens without custom fees success in batch")
        public Stream<DynamicTest> multipleAirdropsFTAndNFTWithoutCustomFeesSuccessInBatch() {

            final var tokenAirdrops = tokenAirdrop(
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS),
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                            movingUnique(NFT_FOR_END_TO_END, 1L).between(OWNER, RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS),
                            movingUnique(NFT_FOR_END_TO_END, 2L)
                                    .between(OWNER, RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS),
                            movingUnique(NFT_FOR_END_TO_END, 3L)
                                    .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                            movingUnique(NFT_FOR_END_TO_END, 4L).between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))
                    .payingWith(OWNER)
                    .via("airdropsTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts
                    createAccountsAndKeys(),

                    // fill in the 1 free auto-association slot
                    createDummyToken(),
                    cryptoTransfer(moving(10, DUMMY).between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS)),

                    // create fungible token
                    createImmutableFT(FT_FOR_END_TO_END, OWNER),
                    // create and mint non-fungible token,
                    createImmutableNFT(NFT_FOR_END_TO_END, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_END_TO_END, 0, 10),

                    // perform the atomic batch transaction
                    atomicBatch(tokenAirdrops)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances
                    getAccountBalance(RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS).hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(NFT_FOR_END_TO_END, 1L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 80L),
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_END_TO_END, 8L),

                    // validate pending token airdrops
                    getTxnRecord("airdropsTxn")
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingFungiblePendingAirdrop(
                                            moving(10, FT_FOR_END_TO_END)
                                                    .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                            moving(10, FT_FOR_END_TO_END)
                                                    .between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))))
                            .hasPriority(recordWith()
                                    .pendingAirdrops(includingNftPendingAirdrop(
                                            movingUnique(NFT_FOR_END_TO_END, 3L)
                                                    .between(OWNER, RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS),
                                            movingUnique(NFT_FOR_END_TO_END, 4L)
                                                    .between(OWNER, RECEIVER_WITH_0_AUTO_ASSOCIATIONS))))));
        }

        @HapiTest
        @DisplayName("Dissociate and transfer of FT token without custom fees fails in batch")
        public Stream<DynamicTest> dissociateAndTransferFTWithoutCustomFeesFailsInBatch() {

            // create dissociate receiver inner transactions
            final var dissociateFirstReceiver = tokenDissociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .via("dissociateSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            // create transfer tokens inner transactions
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);

            return hapiTest(flattened(
                    // create keys and accounts
                    createAccountsAndKeys(),

                    // create fungible token
                    createImmutableFT(FT_FOR_END_TO_END, OWNER),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(dissociateFirstReceiver, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L)));
        }
    }

    @Nested
    @DisplayName("Token Operations Tests - including Pause, Unpause, Freeze, Unfreeze, Burn, Wipe and Delete")
    class TokenOperationsTests {

        @Nested
        @DisplayName("Pause and Unpause Tests")
        class PauseAndUnpauseTests {
            @HapiTest
            @DisplayName("Unpause and Transfer fungible token success in batch")
            public Stream<DynamicTest> unpauseAndTransferFTSuccessInBatch() {

                // unpause the fungible token
                final var unpauseFungibleToken = tokenUnpause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER, pauseKey)
                        .via("unpauseTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                final var tokenTransferSecondReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                        .payingWith(OWNER)
                        .via("transferSecondTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithPauseKey(FT_FOR_TOKEN_PAUSE, OWNER, adminKey, pauseKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_PAUSE),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_TOKEN_PAUSE),
                        tokenPause(FT_FOR_TOKEN_PAUSE),

                        // perform the atomic batch transaction
                        atomicBatch(unpauseFungibleToken, tokenTransferFirstReceiver, tokenTransferSecondReceiver)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 10L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 10L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 80L),
                        getTokenInfo(FT_FOR_TOKEN_PAUSE)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasPauseStatus(TokenPauseStatus.Unpaused)));
            }

            @HapiTest
            @DisplayName("Transfer and Pause fungible token success in batch")
            public Stream<DynamicTest> transferAndPauseFTSuccessInBatch() {

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                final var tokenTransferSecondReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                        .payingWith(OWNER)
                        .via("transferSecondTxn")
                        .batchKey(BATCH_OPERATOR);

                // pause the fungible token
                final var pauseFungibleToken = tokenPause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER, pauseKey)
                        .via("pauseTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithPauseKey(FT_FOR_TOKEN_PAUSE, OWNER, adminKey, pauseKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_PAUSE),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_TOKEN_PAUSE),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, tokenTransferSecondReceiver, pauseFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 10L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 10L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 80L),
                        getTokenInfo(FT_FOR_TOKEN_PAUSE)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasPauseStatus(TokenPauseStatus.Paused)));
            }

            @HapiTest
            @DisplayName("Unpause, Transfer and Pause fungible token success in batch")
            public Stream<DynamicTest> unpauseTransferAndPauseFTSuccessInBatch() {

                // unpause the fungible token
                final var unpauseFungibleToken = tokenUnpause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER, pauseKey)
                        .via("unpauseTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                final var tokenTransferSecondReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                        .payingWith(OWNER)
                        .via("transferSecondTxn")
                        .batchKey(BATCH_OPERATOR);

                // pause the fungible token
                final var pauseFungibleToken = tokenPause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER, pauseKey)
                        .via("pauseTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithPauseKey(FT_FOR_TOKEN_PAUSE, OWNER, adminKey, pauseKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_PAUSE),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_TOKEN_PAUSE),
                        tokenPause(FT_FOR_TOKEN_PAUSE),

                        // perform the atomic batch transaction
                        atomicBatch(
                                        unpauseFungibleToken,
                                        tokenTransferFirstReceiver,
                                        tokenTransferSecondReceiver,
                                        pauseFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 10L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 10L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 80L),
                        getTokenInfo(FT_FOR_TOKEN_PAUSE)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasPauseStatus(TokenPauseStatus.Paused)));
            }

            @HapiTest
            @DisplayName("Pause fungible token not signed by pause key fails in batch")
            public Stream<DynamicTest> pauseFTNotSignedByPauseKeyFailsInBatch() {

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                final var tokenTransferSecondReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                        .payingWith(OWNER)
                        .via("transferSecondTxn")
                        .batchKey(BATCH_OPERATOR);

                // pause the fungible token
                final var pauseFungibleToken = tokenPause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER)
                        .via("pauseTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(INVALID_SIGNATURE);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithPauseKey(FT_FOR_TOKEN_PAUSE, OWNER, adminKey, pauseKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_PAUSE),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_TOKEN_PAUSE),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, tokenTransferSecondReceiver, pauseFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 0L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 100L),
                        getTokenInfo(FT_FOR_TOKEN_PAUSE)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasPauseStatus(TokenPauseStatus.Unpaused)));
            }

            @HapiTest
            @DisplayName("Unpause fungible token not signed by pause key fails in batch")
            public Stream<DynamicTest> unpauseFTNotSignedByPauseKeyFailsInBatch() {

                // unpause the fungible token
                final var unpauseFungibleToken = tokenUnpause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER)
                        .via("unpauseTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(INVALID_SIGNATURE);

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                final var tokenTransferSecondReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                        .payingWith(OWNER)
                        .via("transferSecondTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithPauseKey(FT_FOR_TOKEN_PAUSE, OWNER, adminKey, pauseKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_PAUSE),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_TOKEN_PAUSE),
                        tokenPause(FT_FOR_TOKEN_PAUSE),

                        // perform the atomic batch transaction
                        atomicBatch(unpauseFungibleToken, tokenTransferFirstReceiver, tokenTransferSecondReceiver)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 0L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 100L),
                        getTokenInfo(FT_FOR_TOKEN_PAUSE)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasPauseStatus(TokenPauseStatus.Paused)));
            }

            @HapiTest
            @DisplayName("Transfer of Paused token fails in batch")
            public Stream<DynamicTest> tokenTransferOfPausedFTFailsInBatch() {

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_IS_PAUSED);

                // pause the fungible token
                final var pauseFungibleToken = tokenPause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER, pauseKey)
                        .via("pauseTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithPauseKey(FT_FOR_TOKEN_PAUSE, OWNER, adminKey, pauseKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_PAUSE),

                        // perform the atomic batch transaction
                        atomicBatch(pauseFungibleToken, tokenTransferFirstReceiver)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 100L),
                        getTokenInfo(FT_FOR_TOKEN_PAUSE)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasPauseStatus(TokenPauseStatus.Unpaused)));
            }

            @HapiTest
            @DisplayName("Transfer and Pause of token without pause key fails in batch")
            public Stream<DynamicTest> tokenTransferAndPauseOfFTWithoutPauseKeyFailsInBatch() {

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                // pause the fungible token
                final var pauseFungibleToken = tokenPause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER, pauseKey)
                        .via("pauseTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_HAS_NO_PAUSE_KEY);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFT(FT_FOR_TOKEN_PAUSE, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_PAUSE),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, pauseFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 100L),
                        getTokenInfo(FT_FOR_TOKEN_PAUSE)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasPauseStatus(TokenPauseStatus.PauseNotApplicable)));
            }

            @HapiTest
            @DisplayName("Unpause and Transfer of token without pause key fails in batch")
            public Stream<DynamicTest> unpauseAndTransferOfFTWithoutPauseKeyFailsInBatch() {

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                // unpause the fungible token
                final var unpauseFungibleToken = tokenUnpause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER, pauseKey)
                        .via("pauseTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_HAS_NO_PAUSE_KEY);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFT(FT_FOR_TOKEN_PAUSE, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_PAUSE),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, unpauseFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 100L),
                        getTokenInfo(FT_FOR_TOKEN_PAUSE)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasPauseStatus(TokenPauseStatus.PauseNotApplicable)));
            }

            @HapiTest
            @DisplayName("Update Pause key, Transfer and Pause token successfully in batch")
            public Stream<DynamicTest> updatePauseKeyTransferAndPauseOfFTSuccessInBatchTest() {

                // update the pause key
                final var updatePauseKey = tokenUpdate(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .pauseKey(newPauseKey)
                        .signedBy(OWNER, adminKey)
                        .via("updatePauseTxn")
                        .batchKey(BATCH_OPERATOR);

                // pause the fungible token
                final var pauseFungibleToken = tokenPause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newPauseKey)
                        .via("unpauseTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_PAUSE).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithPauseKey(FT_FOR_TOKEN_PAUSE, OWNER, adminKey, pauseKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_PAUSE),

                        // perform the atomic batch transaction
                        atomicBatch(updatePauseKey, tokenTransferFirstReceiver, pauseFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 10L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_PAUSE, 90L),
                        withOpContext((spec, opLog) -> {
                            final var pauseKeyFromRegistry = spec.registry().getKey(newPauseKey);
                            final var tokenInfoOperation = getTokenInfo(FT_FOR_TOKEN_PAUSE)
                                    .hasPauseStatus(TokenPauseStatus.Paused)
                                    .hasPauseKey(toPbj(pauseKeyFromRegistry));
                            allRunFor(spec, tokenInfoOperation);
                        })));
            }

            @HapiTest
            @DisplayName("Update Pause key of token created without pause key fails in batch")
            public Stream<DynamicTest> updatePauseKeyOfFTCreatedWithoutPauseKeyFailsInBatch() {

                // update the pause key
                final var updatePauseKey = tokenUpdate(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .pauseKey(newPauseKey)
                        .signedBy(OWNER, adminKey)
                        .via("updatePauseTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_HAS_NO_PAUSE_KEY);

                // pause the fungible token
                final var pauseFungibleToken = tokenPause(FT_FOR_TOKEN_PAUSE)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newPauseKey)
                        .via("unpauseTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFT(FT_FOR_TOKEN_PAUSE, OWNER, adminKey),

                        // perform the atomic batch transaction
                        atomicBatch(updatePauseKey, pauseFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),
                        getTokenInfo(FT_FOR_TOKEN_PAUSE)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasPauseStatus(TokenPauseStatus.PauseNotApplicable)));
            }
        }

        @Nested
        @DisplayName("Freeze and Unfreeze Tests")
        class FreezeAndUnfreezeTests {
            @HapiTest
            @DisplayName("Unfreeze and Transfer non-fungible token success in batch")
            public Stream<DynamicTest> unfreezeAndTransferNFTSuccessInBatch() {

                // unfreeze the non-fungible token
                final var unfreezeNonFungibleToken = tokenUnfreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER, freezeKey)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create keys, accounts and token
                        createAccountsAndKeys(),
                        createImmutableNFTWithFreezeKey(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, freezeKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),
                        tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST),

                        // perform the atomic batch transaction
                        atomicBatch(unfreezeNonFungibleToken, tokenTransferFirstReceiver)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 1L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 9L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(Unfrozen))));
            }

            @HapiTest
            @DisplayName("Transfer and Freeze non-fungible token success in batch")
            public Stream<DynamicTest> transferAndFreezeNFTSuccessInBatch() {

                // freeze the non-fungible token
                final var freezeNonFungibleToken = tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER, freezeKey)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createImmutableNFTWithFreezeKey(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, freezeKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, freezeNonFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 1L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 9L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(Frozen))));
            }

            @HapiTest
            @DisplayName("Unfreeze, Transfer and Freeze non-fungible token success in batch")
            public Stream<DynamicTest> unfreezeTransferAndFreezeNFTSuccessInBatch() {

                // unfreeze the non-fungible token
                final var unfreezeNonFungibleToken = tokenUnfreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER, freezeKey)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                // freeze the non-fungible token
                final var freezeNonFungibleToken = tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER, freezeKey)
                        .via("freezeTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createImmutableNFTWithFreezeKey(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, freezeKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),
                        tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST),

                        // perform the atomic batch transaction
                        atomicBatch(unfreezeNonFungibleToken, tokenTransferFirstReceiver, freezeNonFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 1L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 9L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(Frozen))));
            }

            @HapiTest
            @DisplayName("Freeze non-fungible token not signed by freeze key fails in batch")
            public Stream<DynamicTest> freezeNFTNotSignedByFreezeKeyFailsInBatch() {

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                // freeze the non-fungible token
                final var freezeNonFungibleToken = tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER)
                        .via("freezeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(INVALID_SIGNATURE);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableNFTWithFreezeKey(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, freezeKey, adminKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, freezeNonFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 10L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(Unfrozen))));
            }

            @HapiTest
            @DisplayName("Unfreeze non-fungible token not signed by freeze key fails in batch")
            public Stream<DynamicTest> unfreezeNFTNotSignedByFreezeKeyFailsInBatch() {

                // unfreeze the non-fungible token
                final var unfreezeNonFungibleToken = tokenUnfreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(INVALID_SIGNATURE);

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableNFTWithFreezeKey(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, freezeKey, adminKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),
                        tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST),

                        // perform the atomic batch transaction
                        atomicBatch(unfreezeNonFungibleToken, tokenTransferFirstReceiver)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 10L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(Frozen))));
            }

            @HapiTest
            @DisplayName("Transfer of Freezed NFT token fails in batch")
            public Stream<DynamicTest> tokenTransferOfFreezedNFTFailsInBatch() {

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN);

                // freeze the non-fungible token
                final var freezeNonFungibleToken = tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER, freezeKey)
                        .via("freezeTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableNFTWithFreezeKey(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, freezeKey, adminKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),

                        // perform the atomic batch transaction
                        atomicBatch(freezeNonFungibleToken, tokenTransferFirstReceiver)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 10L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(Unfrozen))));
            }

            @HapiTest
            @DisplayName("Unfreeze and Transfer non-fungible token without freeze key fails in batch")
            public Stream<DynamicTest> tokenUnfreezeAndTransferOfNFTWithoutFreezeKeyFailsInBatch() {

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                // unfreeze the non-fungible token
                final var unfreezeNonFungibleToken = tokenUnfreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER, freezeKey)
                        .via("freezeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY);

                return hapiTest(flattened(
                        // create accounts, keys and token
                        createAccountsAndKeys(),
                        createMutableNFT(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, adminKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),

                        // perform the atomic batch transaction
                        atomicBatch(unfreezeNonFungibleToken, tokenTransferFirstReceiver)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 10L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(FreezeNotApplicable))));
            }

            @HapiTest
            @DisplayName("Transfer and Freeze non-fungible token without freeze key fails in batch")
            public Stream<DynamicTest> tokenTransferAndFreezeOfNFTWithoutFreezeKeyFailsInBatch() {

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                // freeze the non-fungible token
                final var freezeNonFungibleToken = tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER, freezeKey)
                        .via("freezeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableNFT(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, adminKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, freezeNonFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 10L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(FreezeNotApplicable))));
            }

            @HapiTest
            @DisplayName("Multiple transfers of non-fungible token Unfreezed for one account only fails in batch")
            public Stream<DynamicTest> multipleTransferOfNFTUnfreezedForOneAccountOnlyFailInBatch() {

                // unfreeze the non-fungible token
                final var unfreezeNonFungibleTokenForOneAccount = tokenUnfreeze(
                                NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER, freezeKey)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                final var tokenTransferSecondReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN);

                // freeze the non-fungible token for one of the accounts
                final var freezeNonFungibleToken = tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .via("freezeTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableNFTWithFreezeKey(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, freezeKey, adminKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),
                        tokenAssociate(RECEIVER_ASSOCIATED_SECOND, NFT_FOR_TOKEN_FREEZE),
                        tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST),
                        tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_SECOND),

                        // perform the atomic batch transaction
                        atomicBatch(
                                        unfreezeNonFungibleTokenForOneAccount,
                                        tokenTransferFirstReceiver,
                                        tokenTransferSecondReceiver,
                                        freezeNonFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 0L),
                        getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 10L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(Frozen))));
            }

            @HapiTest
            @DisplayName("Update Freeze key, Transfer and Freeze non-fungible token successfully in batch")
            public Stream<DynamicTest> updateFreezeKeyTransferAndFreezeNFTSuccessInBatch() {

                // update the non-fungible token
                final var updateFreezeKey = tokenUpdate(NFT_FOR_TOKEN_FREEZE)
                        .payingWith(OWNER)
                        .freezeKey(newFreezeKey)
                        .signedBy(OWNER, adminKey)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated accounts
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                movingUnique(NFT_FOR_TOKEN_FREEZE, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                // freeze the non-fungible token
                final var freezeNonFungibleToken = tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newFreezeKey)
                        .via("freezeTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableNFTWithFreezeKey(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, freezeKey, adminKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),

                        // perform the atomic batch transaction
                        atomicBatch(updateFreezeKey, tokenTransferFirstReceiver, freezeNonFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 1L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 9L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(Frozen))));
            }

            @HapiTest
            @DisplayName("Update Freeze key of non-fungible token created without freeze key fails in batch")
            public Stream<DynamicTest> updateFreezeKeyOfNFTCreatedWithoutFreezeKeyFailsInBatch() {

                // update the non-fungible token
                final var updateFreezeKey = tokenUpdate(NFT_FOR_TOKEN_FREEZE)
                        .payingWith(OWNER)
                        .freezeKey(newFreezeKey)
                        .signedBy(OWNER, adminKey)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_HAS_NO_FREEZE_KEY);

                // freeze the non-fungible token
                final var freezeNonFungibleToken = tokenFreeze(NFT_FOR_TOKEN_FREEZE, RECEIVER_ASSOCIATED_FIRST)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newFreezeKey)
                        .via("freezeTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableNFT(NFT_FOR_TOKEN_FREEZE, OWNER, nftSupplyKey, adminKey),
                        mintNFT(NFT_FOR_TOKEN_FREEZE, 0, 10),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_TOKEN_FREEZE),

                        // perform the atomic batch transaction
                        atomicBatch(updateFreezeKey, freezeNonFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_TOKEN_FREEZE, 10L),
                        getTokenInfo(NFT_FOR_TOKEN_FREEZE).hasTokenType(NON_FUNGIBLE_UNIQUE),
                        getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(NFT_FOR_TOKEN_FREEZE).freeze(FreezeNotApplicable))));
            }
        }

        @Nested
        @DisplayName("Burn, Wipe and Delete Tests")
        class BurnWipeAndDeleteTests {
            @HapiTest
            @DisplayName("Burn and Wipe fungible token success in batch")
            public Stream<DynamicTest> burnAndWipeFTSuccessInBatch() {

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, supplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, wipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyAndWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, burnFungibleToken, wipeFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 5L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 80L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).hasTokenType(FUNGIBLE_COMMON)));
            }

            @HapiTest
            @DisplayName("Delete after Burn and Wipe fungible token success in batch")
            public Stream<DynamicTest> deleteAfterBurnAndWipeFTSuccessInBatch() {

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, supplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, wipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR);

                // delete the fungible token
                final var deleteFungibleToken = tokenDelete(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .signedBy(OWNER, adminKey)
                        .via("deleteTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyAndWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(
                                        tokenTransferFirstReceiver,
                                        burnFungibleToken,
                                        wipeFungibleToken,
                                        deleteFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // confirm the token is deleted
                        cryptoTransfer(moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(OWNER)
                                .via("transferAfterDeleteTxn")
                                .hasKnownStatus(TOKEN_WAS_DELETED),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 5L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 80L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).logged().hasTokenType(FUNGIBLE_COMMON)));
            }

            @HapiTest
            @DisplayName("Token Transfer after Burn fungible token when there is insufficient balance fails in batch")
            public Stream<DynamicTest> transferAfterBurnFTWhenInsufficientBalanceFailsInBatch() {

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 100L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, supplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                final var tokenTransferOwner = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(RECEIVER_ASSOCIATED_FIRST, OWNER))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyAndWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, burnFungibleToken, tokenTransferOwner)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasTotalSupply(100L)));
            }

            @HapiTest
            @DisplayName("Token Transfer after Wipe fungible token when there is insufficient balance fails in batch")
            public Stream<DynamicTest> transferAfterWipeFTWhenInsufficientBalanceFailsInBatch() {

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, wipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                final var tokenTransferOwner = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(RECEIVER_ASSOCIATED_FIRST, OWNER))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyAndWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, wipeFungibleToken, tokenTransferOwner)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasTotalSupply(100L)));
            }

            @HapiTest
            @DisplayName(
                    "Token Transfer after Burn and Wipe fungible token when there is insufficient balance fails in batch")
            public Stream<DynamicTest> transferAfterBurnAndWipeFTWhenInsufficientBalanceFailsInBatch() {

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 90L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, supplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, wipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                final var tokenTransferOwner = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(RECEIVER_ASSOCIATED_FIRST, OWNER))
                        .payingWith(OWNER)
                        .via("transferSecondTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyAndWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(
                                        tokenTransferFirstReceiver,
                                        burnFungibleToken,
                                        wipeFungibleToken,
                                        tokenTransferOwner)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasTotalSupply(100L)));
            }

            @HapiTest
            @DisplayName("Token Transfer after Burn, Wipe and Delete fungible token fails in batch")
            public Stream<DynamicTest> transferAfterBurnWipeAndDeleteFTFailsInBatch() {

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, supplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, wipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR);

                // delete the fungible token
                final var deleteFungibleToken = tokenDelete(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .signedBy(OWNER, adminKey)
                        .via("deleteTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                final var tokenTransferOwner = cryptoTransfer(
                                moving(1L, FT_FOR_TOKEN_BURN).between(RECEIVER_ASSOCIATED_FIRST, OWNER))
                        .payingWith(OWNER)
                        .via("transferSecondTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_WAS_DELETED);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyAndWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(
                                        tokenTransferFirstReceiver,
                                        burnFungibleToken,
                                        wipeFungibleToken,
                                        deleteFungibleToken,
                                        tokenTransferOwner)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasTotalSupply(100L)));
            }

            @HapiTest
            @DisplayName("Token Burn after Delete fungible token fails in batch")
            public Stream<DynamicTest> burnAfterDeleteFTFailsInBatch() {

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, supplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_WAS_DELETED);

                // delete the fungible token
                final var deleteFungibleToken = tokenDelete(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .signedBy(OWNER, adminKey)
                        .via("deleteTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(deleteFungibleToken, burnFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasTotalSupply(100L)));
            }

            @HapiTest
            @DisplayName("Token Wipe after Delete fungible token fails in batch")
            public Stream<DynamicTest> WipeAfterDeleteFTFailsInBatch() {

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, wipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_WAS_DELETED);

                // delete the fungible token
                final var deleteFungibleToken = tokenDelete(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .signedBy(OWNER, adminKey)
                        .via("deleteTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(deleteFungibleToken, wipeFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN)
                                .hasTokenType(FUNGIBLE_COMMON)
                                .hasTotalSupply(100L)));
            }

            @HapiTest
            @DisplayName("Burn and Wipe fungible token not signed by supply key fails in batch")
            public Stream<DynamicTest> burnAndWipeFTNotSignedBySupplyKeyInBatch() {

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(INVALID_SIGNATURE);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, wipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyAndWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, burnFungibleToken, wipeFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).hasTokenType(FUNGIBLE_COMMON)));
            }

            @HapiTest
            @DisplayName("Burn and Wipe fungible token not signed by wipe key fails in batch")
            public Stream<DynamicTest> burnAndWipeFTNotSignedByWipeKeyInBatch() {

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, supplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(INVALID_SIGNATURE);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyAndWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, burnFungibleToken, wipeFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).hasTokenType(FUNGIBLE_COMMON)));
            }

            @HapiTest
            @DisplayName("Burn and Wipe of fungible token without supply key fails in batch")
            public Stream<DynamicTest> burnAndWipeFTWithoutSupplyKeyFailsInBatch() {

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, supplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, wipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, burnFungibleToken, wipeFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).hasTokenType(FUNGIBLE_COMMON)));
            }

            @HapiTest
            @DisplayName("Burn and Wipe of fungible token without wipe key fails in batch")
            public Stream<DynamicTest> burnAndWipeFTWithoutWipeKeyFailsInBatch() {

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, supplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, wipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_HAS_NO_WIPE_KEY);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(tokenTransferFirstReceiver, burnFungibleToken, wipeFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).hasTokenType(FUNGIBLE_COMMON)));
            }

            @HapiTest
            @DisplayName("Burn and Wipe fungible token after Update Burn and Wipe keys success in batch")
            public Stream<DynamicTest> burnAndWipeFTAfterUpdateOfKeysSuccessInBatch() {

                // update the fungible token
                final var updateBurnAndWipeKeys = tokenUpdate(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .supplyKey(newSupplyKey)
                        .wipeKey(newWipeKey)
                        .signedBy(OWNER, adminKey)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR);

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newSupplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newWipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyAndWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(
                                        tokenTransferFirstReceiver,
                                        updateBurnAndWipeKeys,
                                        burnFungibleToken,
                                        wipeFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 5L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 80L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).hasTokenType(FUNGIBLE_COMMON)));
            }

            @HapiTest
            @DisplayName("Delete token after Update Admin key success in batch")
            public Stream<DynamicTest> DeleteFTAfterUpdateOfAdminKeySuccessInBatch() {

                // update the fungible token
                final var updateAdminKey = tokenUpdate(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .adminKey(newAdminKey)
                        .signedBy(OWNER, adminKey, newAdminKey)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR);

                // delete the fungible token
                final var deleteFungibleToken = tokenDelete(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newAdminKey)
                        .via("deleteTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFT(FT_FOR_TOKEN_BURN, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(updateAdminKey, deleteFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // confirm the token is deleted
                        cryptoTransfer(moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                .payingWith(OWNER)
                                .via("transferAfterDeleteTxn")
                                .hasKnownStatus(TOKEN_WAS_DELETED),

                        // validate account balances and token info
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).hasTokenType(FUNGIBLE_COMMON)));
            }

            @HapiTest
            @DisplayName("Update Burn and Wipe keys of fungible token not signed by the admin key fails in batch")
            public Stream<DynamicTest> updateBurnAndWipeKeysNotSignedByAdminKeyFailsInBatch() {

                // update the fungible token
                final var updateBurnAndWipeKeys = tokenUpdate(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .supplyKey(newSupplyKey)
                        .wipeKey(newWipeKey)
                        .signedBy(OWNER)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(INVALID_SIGNATURE);

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newSupplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newWipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFTWithSupplyAndWipeKey(FT_FOR_TOKEN_BURN, OWNER, adminKey, supplyKey, wipeKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(
                                        tokenTransferFirstReceiver,
                                        updateBurnAndWipeKeys,
                                        burnFungibleToken,
                                        wipeFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).hasTokenType(FUNGIBLE_COMMON)));
            }

            @HapiTest
            @DisplayName(
                    "Update Burn and Wipe keys of fungible token created without burn and wipe keys fails in batch")
            public Stream<DynamicTest> updateBurnAndWipeKeysOfFTWithoutKeysFailsInBatch() {

                // update the fungible token
                final var updateBurnAndWipeKeys = tokenUpdate(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .supplyKey(newSupplyKey)
                        .wipeKey(newWipeKey)
                        .signedBy(OWNER)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_HAS_NO_SUPPLY_KEY);

                // burn the fungible token
                final var burnFungibleToken = burnToken(FT_FOR_TOKEN_BURN, 10L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newSupplyKey)
                        .via("burnTxn")
                        .batchKey(BATCH_OPERATOR);

                // wipe the fungible token
                final var wipeFungibleToken = wipeTokenAccount(FT_FOR_TOKEN_BURN, RECEIVER_ASSOCIATED_FIRST, 5L)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newWipeKey)
                        .via("wipeTxn")
                        .batchKey(BATCH_OPERATOR);

                // transfer tokens to associated account
                final var tokenTransferFirstReceiver = cryptoTransfer(
                                moving(10L, FT_FOR_TOKEN_BURN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                        .payingWith(OWNER)
                        .via("transferFirstTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createMutableFT(FT_FOR_TOKEN_BURN, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(
                                        tokenTransferFirstReceiver,
                                        updateBurnAndWipeKeys,
                                        burnFungibleToken,
                                        wipeFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_BURN, 0L),
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).hasTokenType(FUNGIBLE_COMMON)));
            }

            @HapiTest
            @DisplayName("Delete token after Update Admin key of FT created without admin key fails in batch")
            public Stream<DynamicTest> DeleteAfterUpdateOfAdminKeyOfFTCreatedWithoutAdminKeyFailsInBatch() {

                // update the fungible token
                final var updateAdminKey = tokenUpdate(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .adminKey(newAdminKey)
                        .signedBy(OWNER, adminKey, newAdminKey)
                        .via("unfreezeTxn")
                        .batchKey(BATCH_OPERATOR)
                        .hasKnownStatus(TOKEN_IS_IMMUTABLE);

                // delete the fungible token
                final var deleteFungibleToken = tokenDelete(FT_FOR_TOKEN_BURN)
                        .payingWith(OWNER)
                        .signedBy(OWNER, newAdminKey)
                        .via("deleteTxn")
                        .batchKey(BATCH_OPERATOR);

                return hapiTest(flattened(
                        // create accounts and token
                        createAccountsAndKeys(),
                        createImmutableFT(FT_FOR_TOKEN_BURN, OWNER),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_BURN),

                        // perform the atomic batch transaction
                        atomicBatch(updateAdminKey, deleteFungibleToken)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                        // validate account balances and token info
                        getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_BURN, 100L),
                        getTokenInfo(FT_FOR_TOKEN_BURN).hasTokenType(FUNGIBLE_COMMON)));
            }
        }
    }

    @Nested
    @DisplayName("Token KYC Tests - including Grant, Revoke, Transfer and Update KYC")
    class TokenKycTests {

        @HapiTest
        @DisplayName("Grant KYC, Transfer FT and Revoke kYC success in batch")
        public Stream<DynamicTest> grantKYCTransferAndRevokeKycForFTSuccessInBatch() {

            // grant KYC to the fungible token
            final var grantTokenKYC = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // revoke KYC to the fungible token
            final var revokeTokenKYC = revokeTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("revokeKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createImmutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, kycKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),

                    // perform the atomic batch transaction
                    atomicBatch(grantTokenKYC, tokenTransferFirstReceiver, revokeTokenKYC)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 10L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 90L),
                    getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Revoked))));
        }

        @HapiTest
        @DisplayName("Grant KYC, Transfer FT and Second Transfer from Account With kYC success in batch")
        public Stream<DynamicTest> grantKYCTransferAndSecondTransferFromAccountWithKYCForFTSuccessInBatch() {

            // grant KYC to the fungible token
            final var grantTokenKYCFirstReceiver = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxnFirst")
                    .batchKey(BATCH_OPERATOR);

            final var grantTokenKYCSecondReceiver = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_SECOND)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxnSecond")
                    .batchKey(BATCH_OPERATOR);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferSecondReceiver = cryptoTransfer(
                            moving(5L, FT_FOR_TOKEN_KYC).between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("transferSecondTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createImmutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, kycKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_TOKEN_KYC),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    grantTokenKYCFirstReceiver,
                                    grantTokenKYCSecondReceiver,
                                    tokenTransferFirstReceiver,
                                    tokenTransferSecondReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 5L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_TOKEN_KYC, 5L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 90L),
                    getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Granted))));
        }

        @HapiTest
        @DisplayName("Grant KYC And Transfer FT not signed by kYC key fails in batch")
        public Stream<DynamicTest> grantKYCAndTransferNotSignedByKycKeyForFTFailsInBatch() {

            // grant KYC to the fungible token
            final var grantTokenKYC = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createImmutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, kycKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),

                    // perform the atomic batch transaction
                    atomicBatch(grantTokenKYC, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 100L),
                    getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Revoked))));
        }

        @HapiTest
        @DisplayName("Transfer FT and Revoke KYC not signed by kYC key fails in batch")
        public Stream<DynamicTest> transferAndRevokeKYCNotSignedByKycKeyForFTFailsInBatch() {

            // revoke KYC to the fungible token
            final var revokeTokenKYC = revokeTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("revokeKycTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createImmutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, kycKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),
                    grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST),

                    // perform the atomic batch transaction
                    atomicBatch(revokeTokenKYC, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 100L),
                    getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Granted))));
        }

        @HapiTest
        @DisplayName("Transfer FT after Revoke kYC fails in batch")
        public Stream<DynamicTest> transferFTAfterRevokeKYCFailsInBatch() {

            // grant KYC to the fungible token
            final var grantTokenKYCFirstReceiver = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxnFirst")
                    .batchKey(BATCH_OPERATOR);

            final var grantTokenKYCSecondReceiver = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_SECOND)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxnSecond")
                    .batchKey(BATCH_OPERATOR);

            // revoke KYC to the fungible token
            final var revokeTokenKYCSecondReceiver = revokeTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_SECOND)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("revokeKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferSecondReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("transferSecondTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createImmutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, kycKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_TOKEN_KYC),

                    // perform the atomic batch transaction
                    atomicBatch(
                                    grantTokenKYCFirstReceiver,
                                    grantTokenKYCSecondReceiver,
                                    tokenTransferFirstReceiver,
                                    revokeTokenKYCSecondReceiver,
                                    tokenTransferSecondReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_TOKEN_KYC, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 100L),
                    getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Revoked)),
                    getAccountDetails(RECEIVER_ASSOCIATED_SECOND)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Revoked))));
        }

        @HapiTest
        @DisplayName("Grant kYC and Transfer to not Associated Account fails in batch")
        public Stream<DynamicTest> GrantKYCAndTransferToNotAssociatedAccountFailsInBatch() {

            // grant KYC to the fungible token
            final var grantTokenKYC = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_NOT_ASSOCIATED)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT);

            // transfer tokens to not associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_NOT_ASSOCIATED))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createImmutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, kycKey),

                    // perform the atomic batch transaction
                    atomicBatch(grantTokenKYC, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_NOT_ASSOCIATED).hasTokenBalance(FT_FOR_TOKEN_KYC, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 100L),
                    getAccountDetails(RECEIVER_ASSOCIATED_FIRST).hasNoTokenRelationship(FT_FOR_TOKEN_KYC)));
        }

        @HapiTest
        @DisplayName("Grant KYC and Transfer to Receiver for FT without KYC key fails in batch")
        public Stream<DynamicTest> grantKycForFTWithoutKYCKeyFailsInBatch() {

            // grant KYC to the fungible token for one account only
            final var grantTokenKYCFirstReceiver = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(TOKEN_HAS_NO_KYC_KEY);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createImmutableFT(FT_FOR_TOKEN_KYC, OWNER),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),

                    // perform the atomic batch transaction
                    atomicBatch(grantTokenKYCFirstReceiver, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 100L),
                    getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(KycNotApplicable))));
        }

        @HapiTest
        @DisplayName("Transfer FT with KYC to Receiver without KYC granted fails in batch")
        public Stream<DynamicTest> transferFTWithKYCToReceiverWithoutKYCFailsInBatch() {

            // grant KYC to the fungible token for one account only
            final var grantTokenKYCFirstReceiver = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer tokens to associated account but without KYC granted
            final var tokenTransferSecondReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("transferSecondTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createImmutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, kycKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_TOKEN_KYC),

                    // perform the atomic batch transaction
                    atomicBatch(grantTokenKYCFirstReceiver, tokenTransferFirstReceiver, tokenTransferSecondReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_TOKEN_KYC, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 100L),
                    getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Revoked)),
                    getAccountDetails(RECEIVER_ASSOCIATED_SECOND)
                            .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Revoked))));
        }

        // Update KYC key tests

        @HapiTest
        @DisplayName("Grant KYC, Update KYC key for FT and Transfer success in batch")
        public Stream<DynamicTest> grantUpdateKYCAndTransferFTSuccessInBatch() {

            // grant KYC to the fungible token
            final var grantTokenKYCFirstReceiver = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // update the KYC key for the fungible token
            final var updateTokenKYCKey = tokenUpdate(FT_FOR_TOKEN_KYC)
                    .kycKey(newKycKey)
                    .signedBy(adminKey)
                    .payingWith(OWNER)
                    .via("updateKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createMutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, adminKey, kycKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),

                    // perform the atomic batch transaction
                    atomicBatch(grantTokenKYCFirstReceiver, updateTokenKYCKey, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 10L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 90L),
                    withOpContext((spec, opLog) -> {
                        final var newKycFromRegistry = spec.registry().getKey(newKycKey);
                        final var tokenInfoOperation =
                                getTokenInfo(FT_FOR_TOKEN_KYC).hasKycKey(toPbj(newKycFromRegistry));
                        final var tokenKycStatusForAccount = getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Granted));
                        allRunFor(spec, tokenInfoOperation, tokenKycStatusForAccount);
                    })));
        }

        @HapiTest
        @DisplayName("Update KYC key for FT, Grant and Transfer success in batch")
        public Stream<DynamicTest> updateKYCGrantAndTransferFTSuccessInBatch() {

            // update the KYC key for the fungible token
            final var updateTokenKYCKey = tokenUpdate(FT_FOR_TOKEN_KYC)
                    .kycKey(newKycKey)
                    .signedBy(adminKey)
                    .payingWith(OWNER)
                    .via("updateKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // grant KYC to the fungible token
            final var grantTokenKYCFirstReceiver = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, newKycKey)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createMutableFTWithKyc(FT_FOR_TOKEN_KYC, OWNER, adminKey, kycKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenKYCKey, grantTokenKYCFirstReceiver, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 10L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 90L),
                    withOpContext((spec, opLog) -> {
                        final var newKycFromRegistry = spec.registry().getKey(newKycKey);
                        final var tokenInfoOperation =
                                getTokenInfo(FT_FOR_TOKEN_KYC).hasKycKey(toPbj(newKycFromRegistry));
                        final var tokenKycStatusForAccount = getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Granted));
                        allRunFor(spec, tokenInfoOperation, tokenKycStatusForAccount);
                    })));
        }

        @HapiTest
        @DisplayName("Update KYC key for FT not signed by admin key fails in batch")
        public Stream<DynamicTest> updateKYCNotSignedByAdminKeyFailsInBatch() {

            // grant KYC to the fungible token
            final var grantTokenKYCFirstReceiver = grantTokenKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_FIRST)
                    .payingWith(OWNER)
                    .signedBy(OWNER, kycKey)
                    .via("grantKycTxn")
                    .batchKey(BATCH_OPERATOR);

            // update the KYC key for the fungible token
            final var updateTokenKYCKey = tokenUpdate(FT_FOR_TOKEN_KYC)
                    .kycKey(newKycKey)
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("updateKycTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            // transfer tokens to associated account
            final var tokenTransferFirstReceiver = cryptoTransfer(
                            moving(10L, FT_FOR_TOKEN_KYC).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create accounts and token
                    createAccountsAndKeys(),
                    createMutableFTWithKyc(FT_FOR_TOKEN_KYC, RECEIVER_ASSOCIATED_SECOND, adminKey, kycKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_TOKEN_KYC),
                    tokenAssociate(OWNER, FT_FOR_TOKEN_KYC),
                    grantTokenKyc(FT_FOR_TOKEN_KYC, OWNER).payingWith(OWNER).via("grantKycTxn"),
                    cryptoTransfer(moving(100L, FT_FOR_TOKEN_KYC).between(RECEIVER_ASSOCIATED_SECOND, OWNER)),

                    // perform the atomic batch transaction
                    atomicBatch(grantTokenKYCFirstReceiver, updateTokenKYCKey, tokenTransferFirstReceiver)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_TOKEN_KYC, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_TOKEN_KYC, 100L),
                    withOpContext((spec, opLog) -> {
                        final var kycFromRegistry = spec.registry().getKey(kycKey);
                        final var tokenInfoOperation =
                                getTokenInfo(FT_FOR_TOKEN_KYC).hasKycKey(toPbj(kycFromRegistry));
                        final var tokenKycStatusForAccount = getAccountDetails(RECEIVER_ASSOCIATED_FIRST)
                                .hasToken(relationshipWith(FT_FOR_TOKEN_KYC).kyc(Revoked));
                        allRunFor(spec, tokenInfoOperation, tokenKycStatusForAccount);
                    })));
        }
    }

    private HapiTokenCreate createDummyToken() {
        return tokenCreate(DUMMY).tokenType(FUNGIBLE_COMMON).treasury(OWNER).initialSupply(100L);
    }

    private HapiTokenCreate createImmutableFT(String tokenName, String treasury) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(treasury)
                .initialSupply(100L);
    }

    private HapiTokenCreate createImmutableFTWithKyc(String tokenName, String treasury, String kycKey) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(treasury)
                .initialSupply(100L)
                .kycKey(kycKey);
    }

    private HapiTokenCreate createMutableFTWithKyc(String tokenName, String treasury, String adminKey, String kycKey) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(treasury)
                .initialSupply(100L)
                .adminKey(adminKey)
                .kycKey(kycKey);
    }

    private HapiTokenCreate createMutableFT(String tokenName, String treasury, String adminKey) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(treasury)
                .initialSupply(100L)
                .adminKey(adminKey);
    }

    private HapiTokenCreate createMutableFTWithPauseKey(
            String tokenName, String treasury, String adminKey, String pauseKey) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(treasury)
                .initialSupply(100L)
                .adminKey(adminKey)
                .pauseKey(pauseKey);
    }

    private HapiTokenCreate createMutableFTWithSupplyAndWipeKey(
            String tokenName, String treasury, String adminKey, String supplyKey, String wipeKey) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(treasury)
                .initialSupply(100L)
                .adminKey(adminKey)
                .supplyKey(supplyKey)
                .wipeKey(wipeKey);
    }

    private HapiTokenCreate createMutableFTWithSupplyKey(
            String tokenName, String treasury, String adminKey, String supplyKey) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(treasury)
                .initialSupply(100L)
                .adminKey(adminKey)
                .supplyKey(supplyKey);
    }

    private HapiTokenCreate createMutableFTWithWipeKey(
            String tokenName, String treasury, String adminKey, String wipeKey) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(treasury)
                .initialSupply(100L)
                .adminKey(adminKey)
                .wipeKey(wipeKey);
    }

    private HapiTokenCreate createImmutableNFT(String tokenName, String treasury, String supplyKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey);
    }

    private HapiTokenCreate createMutableNFT(String tokenName, String treasury, String supplyKey, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .adminKey(adminKey)
                .supplyKey(supplyKey);
    }

    private HapiTokenCreate createImmutableNFTWithFreezeKey(
            String tokenName, String treasury, String supplyKey, String freezeKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .freezeKey(freezeKey);
    }

    private HapiTokenCreate createMutableNFTWithFreezeKey(
            String tokenName, String treasury, String supplyKey, String freezeKey, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .adminKey(adminKey)
                .supplyKey(supplyKey)
                .freezeKey(freezeKey);
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
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND),
                cryptoCreate(RECEIVER_NOT_ASSOCIATED),
                cryptoCreate(RECEIVER_WITH_FREE_AUTO_ASSOCIATIONS).maxAutomaticTokenAssociations(10),
                cryptoCreate(RECEIVER_WITH_UNLIMITED_AUTO_ASSOCIATIONS).maxAutomaticTokenAssociations(-1),
                cryptoCreate(RECEIVER_WITH_0_AUTO_ASSOCIATIONS).maxAutomaticTokenAssociations(0),
                cryptoCreate(RECEIVER_WITHOUT_FREE_AUTO_ASSOCIATIONS).maxAutomaticTokenAssociations(1),
                newKeyNamed(nftSupplyKey),
                newKeyNamed(supplyKey),
                newKeyNamed(newSupplyKey),
                newKeyNamed(pauseKey),
                newKeyNamed(newPauseKey),
                newKeyNamed(freezeKey),
                newKeyNamed(newFreezeKey),
                newKeyNamed(adminKey),
                newKeyNamed(newAdminKey),
                newKeyNamed(kycKey),
                newKeyNamed(newKycKey),
                newKeyNamed(wipeKey),
                newKeyNamed(newWipeKey));
    }
}
