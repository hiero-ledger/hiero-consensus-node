// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
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
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class AtomicBatchEndToEndCryptoAndTokenServiceTests {
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

    private static final String adminKey = "adminKey";
    private static final String newAdminKey = "newAdminKey";
    private static final String supplyKey = "supplyKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @Nested
    @DisplayName("Token and Crypto Service operations in Atomic Batch")
    class TokenAndCryptoServiceOperationsInAtomicBatch {

        // Token Treasury Management test cases
        @HapiTest
        @DisplayName("Transfer all Token Supply and Delete Token and Treasury Accounts Successfully in Atomic Batch")
        public Stream<DynamicTest> transferAllTokenSupplyAndDeleteTokenAndTreasuryAccountSuccessInAtomicBatch() {

            // delete token inner transaction
            final var deleteToken = tokenDelete(FT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("deleteTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            // delete treasury account inner transaction
            final var deleteTreasuryAccount = cryptoDelete(OWNER)
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("deleteTreasuryAccountTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer all token supply from treasury to receiver associated account
            final var transferAllTokenSupply = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferAllTokenSupplyTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(transferAllTokenSupply, deleteToken, deleteTreasuryAccount)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 10L),

                    // confirm treasury is deleted
                    cryptoTransfer(movingHbar(1).between(RECEIVER_ASSOCIATED_FIRST, OWNER))
                            .hasKnownStatus(ACCOUNT_DELETED),

                    // confirm token is deleted
                    cryptoTransfer(moving(10, FT_FOR_END_TO_END)
                                    .between(RECEIVER_ASSOCIATED_FIRST, RECEIVER_ASSOCIATED_SECOND))
                            .hasKnownStatus(TOKEN_WAS_DELETED)));
        }

        @HapiTest
        @DisplayName("Delete One Token and Transfer Second Token from Treasury Account Successfully in Atomic Batch")
        public Stream<DynamicTest> deleteTokenAndTransferOtherTokenFromTreasuryAccountSuccessInAtomicBatch() {

            // delete token inner transaction
            final var deleteToken = tokenDelete(FT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("deleteFirstTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer other token from treasury to receiver associated account
            final var transferNftToken = cryptoTransfer(
                            movingUnique(NFT_FOR_END_TO_END, 1L).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferSecondTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 10, OWNER, adminKey),
                    createNFTWithAdminKey(NFT_FOR_END_TO_END, OWNER, supplyKey),
                    mintNFT(NFT_FOR_END_TO_END, 0, 10),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NFT_FOR_END_TO_END),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(deleteToken, transferNftToken)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(NFT_FOR_END_TO_END, 1L),

                    // confirm token is deleted
                    cryptoTransfer(moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .hasKnownStatus(TOKEN_WAS_DELETED)));
        }

        @HapiTest
        @DisplayName("Token Transfer from Treasury Account after Token is Deleted Fails in Atomic Batch")
        public Stream<DynamicTest> tokenTransferAfterTokenDeletedFromTreasuryAccountFailsInAtomicBatch() {

            // delete token inner transaction
            final var deleteToken = tokenDelete(FT_FOR_END_TO_END)
                    .payingWith(OWNER)
                    .signedBy(OWNER, adminKey)
                    .via("deleteTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer token from treasury to receiver associated account
            final var transferTokenFromTreasury = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTokenTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(TOKEN_WAS_DELETED);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(deleteToken, transferTokenFromTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm token is not deleted and validate account balances and token info
                    cryptoTransfer(moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                            .hasKnownStatus(SUCCESS),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 90L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 10L)));
        }

        @HapiTest
        @DisplayName("Token Transfer from Treasury Account with Insufficient Token Balance Fails in Atomic Batch")
        public Stream<DynamicTest> tokenTransferFromTreasuryAccountWithInsufficientBalanceFailsInAtomicBatch() {

            // transfer token from treasury to receiver associated account
            final var transferTokenFromTreasuryWithBalance = cryptoTransfer(
                            moving(98, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTokenFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var transferTokenFromTreasuryWithoutBalance = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("transferTokenSecondTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokenFromTreasuryWithBalance, transferTokenFromTreasuryWithoutBalance)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm token transfer is not successful and validate account balances
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 100L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_END_TO_END, 0L)));
        }

        @HapiTest
        @DisplayName("Token Transfer from Treasury Account with Zero Token Balance Fails in Atomic Batch")
        public Stream<DynamicTest> tokenTransferFromTreasuryAccountWithZeroBalanceFailsInAtomicBatch() {

            // transfer token from treasury to receiver associated account
            final var transferTokenFromTreasuryWithBalance = cryptoTransfer(
                            moving(100, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .via("transferTokenFirstTxn")
                    .batchKey(BATCH_OPERATOR);

            final var transferTokenFromTreasuryWithoutBalance = cryptoTransfer(
                            moving(1, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                    .payingWith(OWNER)
                    .via("transferTokenSecondTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),
                    tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(transferTokenFromTreasuryWithBalance, transferTokenFromTreasuryWithoutBalance)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm token transfer is not successful and validate account balances
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 100L),
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(RECEIVER_ASSOCIATED_SECOND).hasTokenBalance(FT_FOR_END_TO_END, 0L)));
        }

        @HapiTest
        @DisplayName("Update Token Treasury Account and Token Transfer from new Treasury Success in Atomic Batch")
        public Stream<DynamicTest> updateTokenTreasuryAndTokenTransferSuccessInAtomicBatch() {

            // update token inner transaction
            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer token from new treasury to receiver associated account
            final var tokenTransferFromNewTreasury = cryptoTransfer(moving(10, FT_FOR_END_TO_END)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .via("transferFromNewTreasuryTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenTreasury, tokenTransferFromNewTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 10L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 90L),

                    // confirm treasury is updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)));
        }

        @HapiTest
        @DisplayName(
                "Update Token Treasury with Account without Free Auto-association Slots and Token Transfer from new Treasury Fails in Atomic Batch")
        public Stream<DynamicTest> updateTokenTreasuryWithoutFreeAutoAssociationsAndTokenTransferFailsInAtomicBatch() {

            // update token inner transaction
            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                    .payingWith(NEW_TREASURY_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS);

            // transfer token from new treasury to receiver associated account
            final var tokenTransferFromNewTreasury = cryptoTransfer(moving(10, FT_FOR_END_TO_END)
                            .between(NEW_TREASURY_WITHOUT_FREE_AUTO_ASSOCIATIONS, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(NEW_TREASURY_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                    .signedBy(NEW_TREASURY_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                    .via("transferFromNewTreasuryTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),
                    // fill in the 1 free auto-association slot
                    tokenCreate("dummy")
                            .treasury(OWNER)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(100L),
                    cryptoTransfer(moving(10, "dummy").between(OWNER, NEW_TREASURY_WITHOUT_FREE_AUTO_ASSOCIATIONS)),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenTreasury, tokenTransferFromNewTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 100L),
                    getAccountBalance(NEW_TREASURY_WITHOUT_FREE_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 0L),

                    // confirm treasury is not updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(OWNER)));
        }

        @HapiTest
        @DisplayName(
                "Update Token Treasury with Account with Zero Auto-association Slots and Token Transfer from new Treasury Fails in Atomic Batch")
        public Stream<DynamicTest> updateTokenTreasuryWithZeroFreeAutoAssociationsAndTokenTransferFailsInAtomicBatch() {

            // update token inner transaction
            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_ZERO_AUTO_ASSOCIATIONS)
                    .payingWith(NEW_TREASURY_WITH_ZERO_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_ZERO_AUTO_ASSOCIATIONS)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS);

            // transfer token from new treasury to receiver associated account
            final var tokenTransferFromNewTreasury = cryptoTransfer(moving(10, FT_FOR_END_TO_END)
                            .between(NEW_TREASURY_WITH_ZERO_AUTO_ASSOCIATIONS, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(NEW_TREASURY_WITH_ZERO_AUTO_ASSOCIATIONS)
                    .signedBy(NEW_TREASURY_WITH_ZERO_AUTO_ASSOCIATIONS)
                    .via("transferFromNewTreasuryTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenTreasury, tokenTransferFromNewTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 100L),
                    getAccountBalance(NEW_TREASURY_WITH_ZERO_AUTO_ASSOCIATIONS).hasTokenBalance(FT_FOR_END_TO_END, 0L),

                    // confirm treasury is not updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(OWNER)));
        }

        @HapiTest
        @DisplayName(
                "Update Token Treasury Account and Token Transfer from new Treasury with Update Signed by the old Treasury Fails in Atomic Batch")
        public Stream<DynamicTest>
                updateTokenTreasuryAndTokenTransferWithUpdateSignedByOldTreasuryFailsInAtomicBatch() {

            // update token inner transaction
            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, OWNER)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer token from new treasury to receiver associated account
            final var tokenTransferFromNewTreasury = cryptoTransfer(moving(10, FT_FOR_END_TO_END)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .via("transferFromNewTreasuryTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenTreasury, tokenTransferFromNewTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INVALID_SIGNATURE),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 100L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 0L),

                    // confirm treasury is not updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(OWNER)));
        }

        @HapiTest
        @DisplayName(
                "Update Token Treasury Account and Token Transfer from new Treasury with Transfer Signed by the old Treasury Fails in Atomic Batch")
        public Stream<DynamicTest>
                updateTokenTreasuryAndTokenTransferWithTransferSignedByOldTreasuryFailsInAtomicBatch() {

            // update token inner transaction
            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer token from new treasury to receiver associated account
            final var tokenTransferFromNewTreasury = cryptoTransfer(moving(10, FT_FOR_END_TO_END)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("transferFromNewTreasuryTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenTreasury, tokenTransferFromNewTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 100L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 0L),

                    // confirm treasury is not updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(OWNER)));
        }

        @HapiTest
        @DisplayName("Update Token Treasury Account and Token Transfer from old Treasury Fails in Atomic Batch")
        public Stream<DynamicTest> updateTokenTreasuryAndTokenTransferFromOldTreasuryFailsInAtomicBatch() {

            // update token inner transaction
            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer token from new treasury to receiver associated account
            final var tokenTransferFromNewTreasury = cryptoTransfer(
                            moving(10, FT_FOR_END_TO_END).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(OWNER)
                    .signedBy(OWNER)
                    .via("transferFromNewTreasuryTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenTreasury, tokenTransferFromNewTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 100L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 0L),

                    // confirm treasury is not updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(OWNER)));
        }

        @HapiTest
        @DisplayName(
                "Update Token Treasury Account and Token Transfer from new Treasury with Update Not Signed by the Admin Key Fails in Atomic Batch")
        public Stream<DynamicTest>
                updateTokenTreasuryAndTokenTransferWithUpdateNotSignedByAdminKeyFailsInAtomicBatch() {

            // update token inner transaction
            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            // transfer token from new treasury to receiver associated account
            final var tokenTransferFromNewTreasury = cryptoTransfer(moving(10, FT_FOR_END_TO_END)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .via("transferFromNewTreasuryTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenTreasury, tokenTransferFromNewTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 100L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 0L),

                    // confirm treasury is not updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(OWNER)));
        }

        @HapiTest
        @DisplayName(
                "Update Token Treasury Account and Token Transfer from new Treasury with Update Not Signed by the New Treasury Key Fails in Atomic Batch")
        public Stream<DynamicTest>
                updateTokenTreasuryAndTokenTransferWithUpdateNotSignedByNewTreasuryKeyFailsInAtomicBatch() {

            // update token inner transaction
            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            // transfer token from new treasury to receiver associated account
            final var tokenTransferFromNewTreasury = cryptoTransfer(moving(10, FT_FOR_END_TO_END)
                            .between(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS, RECEIVER_ASSOCIATED_FIRST))
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .via("transferFromNewTreasuryTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FT_FOR_END_TO_END),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenTreasury, tokenTransferFromNewTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INVALID_SIGNATURE),

                    // validate account balances and token info
                    getAccountBalance(RECEIVER_ASSOCIATED_FIRST).hasTokenBalance(FT_FOR_END_TO_END, 0L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_END_TO_END, 100L),
                    getAccountBalance(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                            .hasTokenBalance(FT_FOR_END_TO_END, 0L),

                    // confirm treasury is not updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(OWNER)));
        }

        @HapiTest
        @DisplayName("Update Token Admin Key And Update Treasury Account Success in Atomic Batch")
        public Stream<DynamicTest> updateTokenAdminKeyAndTreasuryAccountSuccessInAtomicBatch() {

            // update token inner transactions
            final var updateTokenAdminKey = tokenUpdate(FT_FOR_END_TO_END)
                    .adminKey(newAdminKey)
                    .payingWith(OWNER)
                    .signedBy(adminKey, newAdminKey, OWNER)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(newAdminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenAdminKey, updateTokenTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm token is updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS),
                    withOpContext((spec, opLog) -> {
                        final var newAdminKeyFromRegistry = spec.registry().getKey(newAdminKey);
                        final var tokenInfoOperation =
                                getTokenInfo(FT_FOR_END_TO_END).hasAdminKey(toPbj(newAdminKeyFromRegistry));
                        allRunFor(spec, tokenInfoOperation);
                    })));
        }

        @HapiTest
        @DisplayName("Update Token Admin Key And Update Treasury Account Signed by Old Admin Key Fails in Atomic Batch")
        public Stream<DynamicTest> updateTokenAdminKeyAndTreasuryAccountSignedByOldAdminKeyFailsInAtomicBatch() {

            // update token inner transactions
            final var updateTokenAdminKey = tokenUpdate(FT_FOR_END_TO_END)
                    .adminKey(newAdminKey)
                    .payingWith(OWNER)
                    .signedBy(adminKey, newAdminKey, OWNER)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .signedBy(adminKey, NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenAdminKey, updateTokenTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm token is not updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(OWNER),
                    withOpContext((spec, opLog) -> {
                        final var adminKeyFromRegistry = spec.registry().getKey(adminKey);
                        final var tokenInfoOperation =
                                getTokenInfo(FT_FOR_END_TO_END).hasAdminKey(toPbj(adminKeyFromRegistry));
                        allRunFor(spec, tokenInfoOperation);
                    })));
        }

        @HapiTest
        @DisplayName("Update Token Admin Key And Update Treasury Account Signed by Old Treasury Fails in Atomic Batch")
        public Stream<DynamicTest> updateTokenAdminKeyAndTreasuryAccountSignedByOldTreasuryFailsInAtomicBatch() {

            // update token inner transactions
            final var updateTokenAdminKey = tokenUpdate(FT_FOR_END_TO_END)
                    .adminKey(newAdminKey)
                    .payingWith(OWNER)
                    .signedBy(adminKey, newAdminKey, OWNER)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .signedBy(newAdminKey, OWNER)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenAdminKey, updateTokenTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm token is not updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(OWNER),
                    withOpContext((spec, opLog) -> {
                        final var adminKeyFromRegistry = spec.registry().getKey(adminKey);
                        final var tokenInfoOperation =
                                getTokenInfo(FT_FOR_END_TO_END).hasAdminKey(toPbj(adminKeyFromRegistry));
                        allRunFor(spec, tokenInfoOperation);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Update Token Admin Key And Update Treasury Account Signed by Old Admin Key and Old Treasury Fails in Atomic Batch")
        public Stream<DynamicTest>
                updateTokenAdminKeyAndTreasuryAccountSignedByOldAdminAndTreasuryFailsInAtomicBatch() {

            // update token inner transactions
            final var updateTokenAdminKey = tokenUpdate(FT_FOR_END_TO_END)
                    .adminKey(newAdminKey)
                    .payingWith(OWNER)
                    .signedBy(adminKey, newAdminKey, OWNER)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR);

            final var updateTokenTreasury = tokenUpdate(FT_FOR_END_TO_END)
                    .treasury(NEW_TREASURY_WITH_UNLIMITED_AUTO_ASSOCIATIONS)
                    .payingWith(OWNER)
                    .signedBy(adminKey, OWNER)
                    .via("updateTokenTxn")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_SIGNATURE);

            return hapiTest(flattened(
                    // create keys, tokens and accounts
                    createAccountsAndKeys(),
                    createFungibleTokenWithAdminKey(FT_FOR_END_TO_END, 100, OWNER, adminKey),

                    // perform the atomic batch transaction
                    atomicBatch(updateTokenAdminKey, updateTokenTreasury)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),

                    // confirm token is not updated
                    getTokenInfo(FT_FOR_END_TO_END).hasTreasury(OWNER),
                    withOpContext((spec, opLog) -> {
                        final var adminKeyFromRegistry = spec.registry().getKey(adminKey);
                        final var tokenInfoOperation =
                                getTokenInfo(FT_FOR_END_TO_END).hasAdminKey(toPbj(adminKeyFromRegistry));
                        allRunFor(spec, tokenInfoOperation);
                    })));
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
                newKeyNamed(supplyKey));
    }
}
