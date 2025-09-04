// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.service.token.AliasUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdateAliased;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_REMAINING_AUTOMATIC_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ThresholdKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
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

@HapiTestLifecycle
public class AtomicBatchAutoAccountCreationEndToEndTests {

    private static final double BASE_FEE_BATCH_TRANSACTION = 0.001;
    private static final String FT_FOR_AUTO_ACCOUNT = "ftForAutoAccount";
    private static final String NFT_FOR_AUTO_ACCOUNT = "nftForAutoAccount";
    private static final String DUMMY_NFT = "dummyNft";
    private static final String CIVILIAN = "civilian";
    private static final String VALID_ALIAS_ED25519 = "validAliasED25519";
    private static final String VALID_ALIAS_ED25519_SECOND = "validAliasED25519Second";
    private static final String VALID_ALIAS_ECDSA = "validAliasECDSA";
    private static final String VALID_ALIAS_ECDSA_SECOND = "validAliasECDSASecond";
    private static final String VALID_ALIAS_HOLLOW = "validAliasHollow";
    private static final String VALID_ALIAS_HOLLOW_SECOND = "validAliasHollowSecond";
    private static final String VALID_ALIAS_HOLLOW_THIRD = "validAliasHollowThird";
    private static final String VALID_ALIAS_HOLLOW_FOURTH = "validAliasHollowFourth";
    private static final String VALID_ALIAS_HOLLOW_FIFTH = "validAliasHollowFifth";
    private static final String PAYER_NO_FUNDS = "payerNoFunds";
    private static final String AUTO_MEMO = "";
    private static final String INVALID_KEY = "invalidKey";
    private static final String VALID_THRESHOLD_KEY = "validThresholdKey";
    private static final String INVALID_THRESHOLD_KEY = "invalidThresholdKey";

    private static final String OWNER = "owner";
    private static final String BATCH_OPERATOR = "batchOperator";

    private static final String nftSupplyKey = "nftSupplyKey";
    private static final String adminKey = "adminKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @Nested
    @DisplayName("Atomic Batch Auto Account Creation End-to-End Tests - Multiple Accounts and Transfers Test Cases ")
    class AtomicBatchAutoAccountCreationMultipleAccountsAndTransfersTests {
        @HapiTest
        @DisplayName(
                "Auto Create Multiple Public Key and EVM Alias Accounts with Token Transfers success in Atomic Batch")
        public Stream<DynamicTest> autoCreateMultipleAccountsWithTokenTransfersSuccessInBatch() {

            final AtomicReference<ByteString> evmAliasFirst = new AtomicReference<>();
            final AtomicReference<ByteString> evmAliasSecond = new AtomicReference<>();

            // create FT transfers to ED25519 and ECDSA aliases in a batch
            final var tokenTransferFT_To_ED25519 = cryptoTransfer(
                            moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferNFT_To_ED25519_Second = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519_SECOND))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519_Second")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferFT_To_ECDSA = cryptoTransfer(
                            moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ECDSA))
                    .payingWith(OWNER)
                    .via("cryptoTransferFT_To_ECDSA")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferNFT_To_ECDSA_Second = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 2L).between(OWNER, VALID_ALIAS_ECDSA_SECOND))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ECDSA_Second")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAliasFirst),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW_SECOND, evmAliasSecond),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 5),

                    // Associate and supply tokens to accounts
                    tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                    cryptoTransfer(
                                    moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                    movingUnique(NFT_FOR_AUTO_ACCOUNT, 3L, 4L).between(OWNER, CIVILIAN))
                            .payingWith(OWNER)
                            .via("associateAndSupplyTokens"),
                    withOpContext((spec, opLog) -> {

                        // Create multiple accounts with token transfers in an atomic batch
                        final var atomicBatchTransaction = atomicBatch(
                                        tokenTransferFT_To_ED25519,
                                        tokenTransferNFT_To_ED25519_Second,
                                        tokenTransferFT_To_ECDSA,
                                        tokenTransferNFT_To_ECDSA_Second,
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAliasFirst.get(),
                                                        0L,
                                                        1L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW,
                                                        SUCCESS)
                                                .getFirst(),
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAliasSecond.get(),
                                                        0L,
                                                        0L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(3L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW_SECOND,
                                                        SUCCESS)
                                                .getFirst())
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS);

                        final var batchTxnFeeCheck = validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION);

                        // validate the public key accounts creation and transfers
                        final var infoCheckED2559First = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasNoTokenRelationship(NFT_FOR_AUTO_ACCOUNT)
                                .hasOwnedNfts(0L);

                        final var infoCheckED2559Second = getAliasedAccountInfo(VALID_ALIAS_ED25519_SECOND)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519_SECOND)
                                        .alias(VALID_ALIAS_ED25519_SECOND)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        final var infoCheckECDSAFirst = getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ECDSA)
                                        .alias(VALID_ALIAS_ECDSA)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasNoTokenRelationship(NFT_FOR_AUTO_ACCOUNT)
                                .hasOwnedNfts(0L);

                        final var infoCheckECDSASecond = getAliasedAccountInfo(VALID_ALIAS_ECDSA_SECOND)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ECDSA_SECOND)
                                        .alias(VALID_ALIAS_ECDSA_SECOND)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate the hollow accounts creation and transfers
                        final var infoCheckEVMFirst = getAliasedAccountInfo(evmAliasFirst.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .balance(0L)
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasNoTokenRelationship(NFT_FOR_AUTO_ACCOUNT)
                                .hasOwnedNfts(0L);

                        final var infoCheckEVMSecond = getAliasedAccountInfo(evmAliasSecond.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .balance(0L)
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasOwnedNfts(1L);

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 9L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 88L);

                        allRunFor(
                                spec,
                                atomicBatchTransaction,
                                batchTxnFeeCheck,
                                infoCheckED2559First,
                                infoCheckED2559Second,
                                infoCheckECDSAFirst,
                                infoCheckECDSASecond,
                                infoCheckEVMFirst,
                                infoCheckEVMSecond,
                                senderBalanceCheck,
                                ownerBalanceCheck);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Multiple EVM Alias Hollow Accounts with Multiple NFT Transfers success in Atomic Batch")
        public Stream<DynamicTest> autoCreateMultipleEVMAliasHollowAccountsWithMultipleNFTTransfersSuccessInBatch() {

            final AtomicReference<ByteString> evmAliasFirst = new AtomicReference<>();
            final AtomicReference<ByteString> evmAliasSecond = new AtomicReference<>();
            final AtomicReference<ByteString> evmAliasThird = new AtomicReference<>();
            final AtomicReference<ByteString> evmAliasFourth = new AtomicReference<>();
            final AtomicReference<ByteString> evmAliasFifth = new AtomicReference<>();

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAliasFirst),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW_SECOND, evmAliasSecond),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW_THIRD, evmAliasThird),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW_FOURTH, evmAliasFourth),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW_FIFTH, evmAliasFifth),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 10),

                    // Associate and supply tokens to accounts
                    tokenAssociate(CIVILIAN, NFT_FOR_AUTO_ACCOUNT),
                    cryptoTransfer(movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L, 2L, 3L, 4L, 5L, 6L, 7L)
                                    .between(OWNER, CIVILIAN))
                            .payingWith(OWNER)
                            .via("associateAndSupplyTokens"),
                    withOpContext((spec, opLog) -> {

                        // Create multiple accounts with NFT transfers in an atomic batch
                        final var atomicBatchTransaction = atomicBatch(
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAliasFirst.get(),
                                                        0L,
                                                        0L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(1L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW,
                                                        SUCCESS)
                                                .getFirst(),
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAliasSecond.get(),
                                                        0L,
                                                        0L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(2L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW_SECOND,
                                                        SUCCESS)
                                                .getFirst(),
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAliasThird.get(),
                                                        0L,
                                                        0L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(3L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW_THIRD,
                                                        SUCCESS)
                                                .getFirst(),
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAliasFourth.get(),
                                                        0L,
                                                        0L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(4L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW_FOURTH,
                                                        SUCCESS)
                                                .getFirst(),
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAliasFifth.get(),
                                                        0L,
                                                        0L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(5L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW_FIFTH,
                                                        SUCCESS)
                                                .getFirst())
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS);

                        final var batchTxnFeeCheck = validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION);

                        // validate the hollow accounts creation and transfers
                        final var infoCheckEVMFirst = getAliasedAccountInfo(evmAliasFirst.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .balance(0L)
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        final var infoCheckEVMSecond = getAliasedAccountInfo(evmAliasSecond.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .balance(0L)
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        final var infoCheckEVMThird = getAliasedAccountInfo(evmAliasThird.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .balance(0L)
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        final var infoCheckEVMFourth = getAliasedAccountInfo(evmAliasFourth.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .balance(0L)
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        final var infoCheckEVMFifth = getAliasedAccountInfo(evmAliasFifth.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .balance(0L)
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate sender account balance after transfers
                        final var senderBalanceCheck =
                                getAccountBalance(CIVILIAN).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L);

                        allRunFor(
                                spec,
                                atomicBatchTransaction,
                                batchTxnFeeCheck,
                                infoCheckEVMFirst,
                                infoCheckEVMSecond,
                                infoCheckEVMThird,
                                infoCheckEVMFourth,
                                infoCheckEVMFifth,
                                senderBalanceCheck,
                                ownerBalanceCheck);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Accounts with Multiple Transfers to valid Public Keys and evm alias with failing Transfer - "
                        + "Fails in Atomic Batch and no accounts are created")
        public Stream<DynamicTest> autoCreateECDSAAccountWithFailingTokenTransferFailsInBatch() {

            final AtomicReference<ByteString> evmAliasFirst = new AtomicReference<>();
            final AtomicReference<ByteString> evmAliasSecond = new AtomicReference<>();

            // create FT transfers to ED25519 aliases in a batch
            final var tokenTransferFT_To_ED25519 = cryptoTransfer(
                            moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferNFT_To_ED25519_Second = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519_SECOND))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519_Second")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferFT_To_ECDSA = cryptoTransfer(
                            moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ECDSA))
                    .payingWith(OWNER)
                    .via("cryptoTransferFT_To_ECDSA")
                    .batchKey(BATCH_OPERATOR);

            // failing inner transaction due to insufficient payer funds
            final var failingTokenTransferNFT_To_ECDSA_Second = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 2L).between(OWNER, VALID_ALIAS_ECDSA_SECOND))
                    .payingWith(PAYER_NO_FUNDS)
                    .via("cryptoTransferNFT_To_ECDSA_Second")
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAliasFirst),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW_SECOND, evmAliasSecond),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 5),

                    // Associate and supply tokens to accounts
                    tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                    cryptoTransfer(
                                    moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                    movingUnique(NFT_FOR_AUTO_ACCOUNT, 3L, 4L).between(OWNER, CIVILIAN))
                            .payingWith(OWNER)
                            .via("associateAndSupplyTokens"),
                    withOpContext((spec, opLog) -> {

                        // Create multiple accounts with token transfers in an atomic batch
                        final var atomicBatchTransaction = atomicBatch(
                                        tokenTransferFT_To_ED25519,
                                        tokenTransferNFT_To_ED25519_Second,
                                        tokenTransferFT_To_ECDSA,
                                        failingTokenTransferNFT_To_ECDSA_Second,
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAliasFirst.get(),
                                                        0L,
                                                        1L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW,
                                                        SUCCESS)
                                                .getFirst(),
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAliasSecond.get(),
                                                        0L,
                                                        0L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(3L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW_SECOND,
                                                        SUCCESS)
                                                .getFirst())
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE);

                        // validate the public key accounts creation and transfers
                        final var invalidED25519_AliasCheck = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                                .logged();

                        final var invalidED25519_SecondAliasCheck = getAliasedAccountInfo(VALID_ALIAS_ED25519_SECOND)
                                .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                                .logged();

                        final var invalidECDSA_AliasCheck = getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                                .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                                .logged();

                        final var invalidECDSA_SecondAliasCheck = getAliasedAccountInfo(VALID_ALIAS_ECDSA_SECOND)
                                .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                                .logged();

                        // validate the hollow accounts creation and transfers
                        final var invalidEVMFirstCheck = getAliasedAccountInfo(evmAliasFirst.get())
                                .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                                .logged();

                        final var invalidEVMSecondCheck = getAliasedAccountInfo(evmAliasSecond.get())
                                .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                                .logged();

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 10L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 90L);

                        allRunFor(
                                spec,
                                atomicBatchTransaction,
                                invalidED25519_AliasCheck,
                                invalidED25519_SecondAliasCheck,
                                invalidECDSA_AliasCheck,
                                invalidECDSA_SecondAliasCheck,
                                invalidEVMFirstCheck,
                                invalidEVMSecondCheck,
                                senderBalanceCheck,
                                ownerBalanceCheck);
                    })));
        }
    }

    @Nested
    @DisplayName("Atomic Batch Auto Account Creation End-to-End Tests - Hollow Accounts Test Cases ")
    class AtomicBatchAutoAccountCreationHollowAccountsTests {

        @HapiTest
        @DisplayName("Auto Create Hollow Account in one Batch and Finalize it in Another Atomic Batch")
        public Stream<DynamicTest> autoCreateHollowAccountInOneBatchAndFinalizeInAnotherBatch() {

            final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAlias),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 5),

                    // Associate and supply tokens to accounts
                    tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                    cryptoTransfer(
                                    moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                    movingUnique(NFT_FOR_AUTO_ACCOUNT, 3L, 4L).between(OWNER, CIVILIAN))
                            .payingWith(OWNER)
                            .via("associateAndSupplyTokens"),
                    withOpContext((spec, opLog) -> {

                        // Create hollow account with token transfers in one atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAlias.get(),
                                                        ONE_HBAR,
                                                        1L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(3L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW,
                                                        SUCCESS)
                                                .getFirst())
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the hollow account creation and transfers
                        final var infoCheckEVMAlias = getAliasedAccountInfo(evmAlias.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 9L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 90L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionFirst,
                                infoCheckEVMAlias,
                                senderBalanceCheck,
                                ownerBalanceCheck);

                        // Add the hollow account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(evmAlias.get()).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_HOLLOW, newAccountId);

                        // Finalize the hollow account in another atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        cryptoCreate("foo").batchKey(BATCH_OPERATOR))
                                .payingWith(VALID_ALIAS_HOLLOW)
                                .signedBy(BATCH_OPERATOR, VALID_ALIAS_HOLLOW)
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(VALID_ALIAS_HOLLOW))
                                .via("batchTxnSecond")
                                .hasKnownStatus(SUCCESS);

                        // validate finalized account info
                        final var finalisedAccountInfoCheck = getAccountInfo(VALID_ALIAS_HOLLOW)
                                .isNotHollow()
                                .has(accountWith()
                                        .key(VALID_ALIAS_HOLLOW)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionSecond, finalisedAccountInfoCheck);
                    })));
        }

        @HapiTest
        @DisplayName("Auto Create Hollow Account in one Batch, Finalize it and Token Transfer in Another Atomic Batch")
        @Tag(MATS)
        public Stream<DynamicTest> autoCreateHollowAccountInOneBatchFinalizeAndTokenTransferInAnotherBatch() {

            final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAlias),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 5),

                    // Associate and supply tokens to accounts
                    tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                    cryptoTransfer(
                                    moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                    movingUnique(NFT_FOR_AUTO_ACCOUNT, 3L, 4L).between(OWNER, CIVILIAN))
                            .payingWith(OWNER)
                            .via("associateAndSupplyTokens"),
                    withOpContext((spec, opLog) -> {

                        // Create hollow account with token transfer in one atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAlias.get(),
                                                        ONE_HBAR,
                                                        1L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(3L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW,
                                                        SUCCESS)
                                                .getFirst())
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the hollow account creation and transfers
                        final var infoCheckEVMAlias = getAliasedAccountInfo(evmAlias.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, infoCheckEVMAlias);

                        // Add the hollow account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(evmAlias.get()).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_HOLLOW, newAccountId);

                        // Create inner transaction to transfer tokens from the finalized hollow account
                        final var transferFromHollowAccount = cryptoTransfer(
                                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(evmAlias.get(), OWNER))
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_HOLLOW)
                                .via("transferFromHollowAccount")
                                .batchKey(BATCH_OPERATOR);

                        // Finalize the hollow account and transfer from it in another atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(transferFromHollowAccount)
                                .payingWith(VALID_ALIAS_HOLLOW)
                                .signedBy(BATCH_OPERATOR, VALID_ALIAS_HOLLOW)
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(VALID_ALIAS_HOLLOW))
                                .via("batchTxnSecond")
                                .hasKnownStatus(SUCCESS);

                        // validate finalized account info
                        final var finalisedAccountInfoCheck = getAccountInfo(VALID_ALIAS_HOLLOW)
                                .isNotHollow()
                                .has(accountWith()
                                        .key(VALID_ALIAS_HOLLOW)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 9L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 91L);

                        // validate the finalized hollow account token balance after transfer
                        final var getFinalizedAccountBalance = getAccountBalance(VALID_ALIAS_HOLLOW)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 0L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionSecond,
                                finalisedAccountInfoCheck,
                                senderBalanceCheck,
                                ownerBalanceCheck,
                                getFinalizedAccountBalance);
                    })));
        }

        @HapiTest
        @DisplayName("Auto Create Hollow Account in one Batch, Finalize it Outside Atomic Batch")
        public Stream<DynamicTest> autoCreateHollowAccountInOneBatchFinalizeOutsideBatch() {

            final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAlias),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 5),

                    // Associate and supply tokens to accounts
                    tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                    cryptoTransfer(
                                    moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                    movingUnique(NFT_FOR_AUTO_ACCOUNT, 3L, 4L).between(OWNER, CIVILIAN))
                            .payingWith(OWNER)
                            .via("associateAndSupplyTokens"),
                    withOpContext((spec, opLog) -> {

                        // Create hollow account with token transfer in one atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAlias.get(),
                                                        ONE_HBAR,
                                                        1L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(3L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW,
                                                        SUCCESS)
                                                .getFirst())
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the hollow account creation and transfers
                        final var infoCheckEVMAlias = getAliasedAccountInfo(evmAlias.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, infoCheckEVMAlias);

                        // Add the hollow account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(evmAlias.get()).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_HOLLOW, newAccountId);

                        // Finalize the hollow account outside the atomic batch
                        final var finalizeHollowAccount = cryptoCreate("foo")
                                .balance(10L)
                                .payingWith(VALID_ALIAS_HOLLOW)
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(VALID_ALIAS_HOLLOW))
                                .via("finalizingTxn")
                                .hasKnownStatus(SUCCESS);

                        // validate finalized account info
                        final var finalizedAccountInfoCheck = getAccountInfo(VALID_ALIAS_HOLLOW)
                                .isNotHollow()
                                .has(accountWith()
                                        .key(VALID_ALIAS_HOLLOW)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 9L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 90L);

                        // validate finalized account token balance
                        final var getFinalizedAccountBalance = getAccountBalance(VALID_ALIAS_HOLLOW)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 1L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        allRunFor(
                                spec,
                                finalizeHollowAccount,
                                finalizedAccountInfoCheck,
                                senderBalanceCheck,
                                ownerBalanceCheck,
                                getFinalizedAccountBalance);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Hollow Account in one Batch and Finalize it in another Batch Inner Transaction Fails in Atomic Batch")
        public Stream<DynamicTest> autoCreateHollowAccountAndFinalizeInInnerTxnFailsInBatch() {

            final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAlias),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 5),

                    // Associate and supply tokens to accounts
                    tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                    cryptoTransfer(
                                    moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                    movingUnique(NFT_FOR_AUTO_ACCOUNT, 3L, 4L).between(OWNER, CIVILIAN))
                            .payingWith(OWNER)
                            .via("associateAndSupplyTokens"),
                    withOpContext((spec, opLog) -> {

                        // Create inner transaction to finalize hollow account
                        final var finalizeHollowAccount = cryptoCreate("foo")
                                .balance(10L)
                                .payingWith(VALID_ALIAS_HOLLOW)
                                .signedBy(VALID_ALIAS_HOLLOW)
                                .via("finalizeHollowAccount")
                                .batchKey(BATCH_OPERATOR);

                        // Create hollow account with token transfer in one atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAlias.get(),
                                                        10L,
                                                        1L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(3L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW,
                                                        SUCCESS)
                                                .getFirst())
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the hollow account is created
                        final var infoCheckEVMAliasFirst = getAliasedAccountInfo(evmAlias.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .balance(10L)
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 9L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 90L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionFirst,
                                infoCheckEVMAliasFirst,
                                senderBalanceCheck,
                                ownerBalanceCheck);

                        // Add the hollow account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(evmAlias.get()).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_HOLLOW, newAccountId);

                        // try to finalize the auto-created hollow account with inner transaction only in second batch
                        final var atomicBatchTransactionSecond = atomicBatch(finalizeHollowAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasPrecheck(INVALID_SIGNATURE);

                        // validate the hollow account is not finalized but remains hollow
                        final var infoCheckEVMAliasSecond = getAliasedAccountInfo(evmAlias.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO));

                        allRunFor(spec, atomicBatchTransactionSecond, infoCheckEVMAliasSecond);
                    })));
        }
    }

    @Nested
    @DisplayName("Atomic Batch Auto Account Creation End-to-End Tests - Test Cases with Token Minting and Transfers")
    class AtomicBatchAutoAccountCreationTokensMintsAndTransfersTests {
        @HapiTest
        @DisplayName(
                "Mint Token and Transfer it to Public key and evm alias auto-creating accounts success in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountsWithTokenMintAndTransfersSuccessInBatch() {

            final AtomicReference<ByteString> evmAliasFirst = new AtomicReference<>();

            // create NFT transfers to ED25519 and ECDSA aliases in a batch
            final var tokenTransferFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 2L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferFT_To_ECDSA = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 3L).between(OWNER, VALID_ALIAS_ECDSA))
                    .payingWith(OWNER)
                    .via("cryptoTransferFT_To_ECDSA")
                    .batchKey(BATCH_OPERATOR);

            // create token mint inner transaction
            final var tokenMintTxn = mintNFT(NFT_FOR_AUTO_ACCOUNT, 1, 5)
                    .payingWith(OWNER)
                    .via("tokenMintTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAliasFirst),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),

                    // Associate and supply tokens to accounts
                    tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                    cryptoTransfer(
                                    moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                    movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, CIVILIAN))
                            .payingWith(OWNER)
                            .via("associateAndSupplyTokens"),
                    withOpContext((spec, opLog) -> {

                        // Token mint and create accounts with token transfers in an atomic batch
                        final var atomicBatchTransaction = atomicBatch(
                                        tokenMintTxn,
                                        tokenTransferFT_To_ED25519,
                                        tokenTransferFT_To_ECDSA,
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAliasFirst.get(),
                                                        0L,
                                                        0L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(1L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW,
                                                        SUCCESS)
                                                .getFirst())
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key accounts creation and transfers
                        final var infoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        final var infoCheckECDSA = getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ECDSA)
                                        .alias(VALID_ALIAS_ECDSA)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate the hollow accounts creation and transfers
                        final var infoCheckEVM = getAliasedAccountInfo(evmAliasFirst.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .balance(0L)
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 10L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 90L);

                        allRunFor(
                                spec,
                                atomicBatchTransaction,
                                infoCheckED2559,
                                infoCheckECDSA,
                                infoCheckEVM,
                                senderBalanceCheck,
                                ownerBalanceCheck);
                    })));
        }

        @HapiTest
        @DisplayName("Mint Token, Transfer to Public key alias and Transfer from the auto-created account to new alias"
                + " success in Atomic Batch")
        public Stream<DynamicTest>
                autoCreateAccountWithTokenMintAndTransferAndNewTransferToPublicKeyAliasSuccessInBatch() {

            // create NFT transfers to ED25519 and ECDSA aliases in a batch
            final var tokenTransferFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferFT_To_ECDSA = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 2L).between(OWNER, VALID_ALIAS_ECDSA))
                    .payingWith(OWNER)
                    .via("cryptoTransferFT_To_ECDSA")
                    .batchKey(BATCH_OPERATOR);

            // create token mint inner transaction
            final var tokenMintTxn = mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 5)
                    .payingWith(OWNER)
                    .via("tokenMintTxn")
                    .batchKey(BATCH_OPERATOR);

            // create additional token transfers to new alias inner transaction
            final var tokenTransferToNewAlias_ED25519 = cryptoTransfer(movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L)
                            .between(VALID_ALIAS_ED25519, VALID_ALIAS_ED25519_SECOND))
                    .payingWith(OWNER)
                    .via("tokenTransferToNewAliasED25519")
                    .batchKey(BATCH_OPERATOR);

            final var tokenTransferToNewAlias_ECDSA = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 2L).between(VALID_ALIAS_ECDSA, VALID_ALIAS_ECDSA_SECOND))
                    .payingWith(OWNER)
                    .via("tokenTransferToNewAliasECDSA")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    withOpContext((spec, opLog) -> {

                        // Token mint and create accounts with token transfers in an atomic batch
                        final var atomicBatchTransaction = atomicBatch(
                                        tokenMintTxn,
                                        tokenTransferFT_To_ED25519,
                                        tokenTransferFT_To_ECDSA,
                                        tokenTransferToNewAlias_ED25519,
                                        tokenTransferToNewAlias_ECDSA)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key accounts creation and transfers
                        final var infoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(0L);

                        final var infoCheckECDSA = getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ECDSA)
                                        .alias(VALID_ALIAS_ECDSA)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(0L);

                        final var infoCheckED2559_Second = getAliasedAccountInfo(VALID_ALIAS_ED25519_SECOND)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519_SECOND)
                                        .alias(VALID_ALIAS_ED25519_SECOND)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        final var infoCheckECDSA_Second = getAliasedAccountInfo(VALID_ALIAS_ECDSA_SECOND)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ECDSA_SECOND)
                                        .alias(VALID_ALIAS_ECDSA_SECOND)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L);

                        allRunFor(
                                spec,
                                atomicBatchTransaction,
                                infoCheckED2559,
                                infoCheckECDSA,
                                infoCheckED2559_Second,
                                infoCheckECDSA_Second,
                                ownerBalanceCheck);
                    })));
        }

        @HapiTest
        @DisplayName("Mint Token, Transfer to Public key alias and transfer from the auto-created account to evm alias"
                + "creating hollow account success in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountWithTokenMintAndTransferAndNewTransferToEvmAliasSuccessInBatch() {

            final AtomicReference<ByteString> evmAliasFirst = new AtomicReference<>();

            // create NFT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519),
                            movingHbar(ONE_HUNDRED_HBARS).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            // create token mint inner transaction
            final var tokenMintTxn = mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 5)
                    .payingWith(OWNER)
                    .via("tokenMintTxn")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAliasFirst),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    withOpContext((spec, opLog) -> {

                        // Token mint and create accounts with token transfers in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenMintTxn, tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key accounts creation and transfers
                        final var infoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, infoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        // Auto-create hollow account with transfer from auto-created account in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        VALID_ALIAS_ED25519,
                                                        evmAliasFirst.get(),
                                                        1L,
                                                        0L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(1L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW,
                                                        SUCCESS)
                                                .getFirst())
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(SUCCESS);

                        // validate the hollow account creation and transfers
                        final var infoCheckEVM = getAliasedAccountInfo(evmAliasFirst.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .noAlias()
                                        .balance(1L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(VALID_ALIAS_ED25519)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 0L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 4L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 100L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionSecond,
                                infoCheckEVM,
                                senderBalanceCheck,
                                ownerBalanceCheck);
                    })));
        }
    }

    @Nested
    @DisplayName("Atomic Batch Auto Account Creation End-to-End Tests - Test Cases with Account Edits and Transfers")
    class AtomicBatchAutoAccountCreationEditsAndTransfersTests {
        @HapiTest
        @DisplayName(
                "Auto Create Account in one batch, edit Account Key and transfer from the edited account in second "
                        + "batch success in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountEditAccountKeyAndTransferFromEditedAccountSuccessInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdateAliased(VALID_ALIAS_ED25519)
                                .key(VALID_ALIAS_ED25519_SECOND)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519, VALID_ALIAS_ED25519_SECOND)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR);

                        // transfer from the edited auto-created account inner transaction
                        final var transferFromAutoCreatedAccount = cryptoTransfer(
                                        movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(VALID_ALIAS_ED25519, CIVILIAN))
                                .payingWith(OWNER)
                                .via("transferFromAutoCreatedAccount")
                                .signedBy(OWNER, VALID_ALIAS_ED25519_SECOND)
                                .batchKey(BATCH_OPERATOR);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        accountEditTxn, transferFromAutoCreatedAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519_SECOND)
                                        .balance(0L)
                                        .maxAutoAssociations(-1))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(0L);

                        // validate receiver account balance after transfers
                        final var receiverBalanceCheck =
                                getAccountBalance(CIVILIAN).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate finalized account token balance
                        final var getFinalizedAccountBalance =
                                getAccountBalance(VALID_ALIAS_ED25519).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionSecond,
                                secondInfoCheckED2559,
                                receiverBalanceCheck,
                                ownerBalanceCheck,
                                getFinalizedAccountBalance);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Account in one batch, edit Account Key with the same key and transfer in second batch "
                        + "success in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountEditAccountKeyWithSameKeyAndTransferSuccessInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdateAliased(VALID_ALIAS_ED25519)
                                .key(VALID_ALIAS_ED25519)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR);

                        // transfer from the edited auto-created account inner transaction
                        final var transferFromAutoCreatedAccount = cryptoTransfer(
                                        movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(VALID_ALIAS_ED25519, CIVILIAN))
                                .payingWith(OWNER)
                                .via("transferFromAutoCreatedAccount")
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .batchKey(BATCH_OPERATOR);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        accountEditTxn, transferFromAutoCreatedAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(0L);

                        // validate receiver account balance after transfers
                        final var receiverBalanceCheck =
                                getAccountBalance(CIVILIAN).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate finalized account token balance
                        final var getFinalizedAccountBalance =
                                getAccountBalance(VALID_ALIAS_ED25519).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionSecond,
                                secondInfoCheckED2559,
                                receiverBalanceCheck,
                                ownerBalanceCheck,
                                getFinalizedAccountBalance);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Account in one batch, increase Auto-Association limit and Auto-Associate the edited account in second "
                        + "batch success in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountEditAutoAssociationLimitAndAssociateEditedAccountSuccessInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    createImmutableNFT(DUMMY_NFT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    mintNFT(DUMMY_NFT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdate(VALID_ALIAS_ED25519)
                                .maxAutomaticAssociations(2)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR);

                        // token transfer to auto-associate the edited auto-created account inner transaction
                        final var tokenTransferToAutoCreatedAccount = cryptoTransfer(
                                        movingUnique(DUMMY_NFT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                                .payingWith(OWNER)
                                .via("transferFromAutoCreatedAccount")
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .batchKey(BATCH_OPERATOR);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        accountEditTxn, tokenTransferToAutoCreatedAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .maxAutoAssociations(2)
                                        .balance(0L))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasToken(relationshipWith(DUMMY_NFT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(2L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L)
                                .hasTokenBalance(DUMMY_NFT, 0L);

                        allRunFor(spec, atomicBatchTransactionSecond, secondInfoCheckED2559, ownerBalanceCheck);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Account in one batch, edit both Account Key and Auto-Association limit in second batch "
                        + "success in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountEditBothAccountKeyAndAutoAssociationLimitSuccessInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdateAliased(VALID_ALIAS_ED25519)
                                .key(VALID_ALIAS_ED25519_SECOND)
                                .maxAutomaticAssociations(1)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519, VALID_ALIAS_ED25519_SECOND)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR);

                        // transfer from the edited auto-created account inner transaction
                        final var transferFromAutoCreatedAccount = cryptoTransfer(
                                        movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(VALID_ALIAS_ED25519, CIVILIAN))
                                .payingWith(OWNER)
                                .via("transferFromAutoCreatedAccount")
                                .signedBy(OWNER, VALID_ALIAS_ED25519_SECOND)
                                .batchKey(BATCH_OPERATOR);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        accountEditTxn, transferFromAutoCreatedAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519_SECOND)
                                        .balance(0L)
                                        .maxAutoAssociations(1))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(0L);

                        // validate receiver account balance after transfers
                        final var receiverBalanceCheck =
                                getAccountBalance(CIVILIAN).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate finalized account token balance
                        final var getFinalizedAccountBalance =
                                getAccountBalance(VALID_ALIAS_ED25519).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionSecond,
                                secondInfoCheckED2559,
                                receiverBalanceCheck,
                                ownerBalanceCheck,
                                getFinalizedAccountBalance);
                    })));
        }

        @HapiTest
        @DisplayName("Auto Create Hollow Account in one batch and edit its key in second batch success in Atomic Batch")
        public Stream<DynamicTest> autoCreateHollowAccountEditKeySuccessInBatch() {

            final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),
                    registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAlias),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 5),

                    // Associate and supply tokens to accounts
                    tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                    cryptoTransfer(
                                    moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                    movingUnique(NFT_FOR_AUTO_ACCOUNT, 3L, 4L).between(OWNER, CIVILIAN))
                            .payingWith(OWNER)
                            .via("associateAndSupplyTokens"),
                    withOpContext((spec, opLog) -> {

                        // Create hollow account with token transfer in one atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(
                                        createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                        CIVILIAN,
                                                        evmAlias.get(),
                                                        ONE_HBAR,
                                                        1L,
                                                        FT_FOR_AUTO_ACCOUNT,
                                                        List.of(3L), // NFT serials
                                                        NFT_FOR_AUTO_ACCOUNT,
                                                        "createHollowAccountWithCryptoTransferToAlias"
                                                                + VALID_ALIAS_HOLLOW,
                                                        SUCCESS)
                                                .getFirst())
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the hollow account creation and transfers
                        final var infoCheckEVMAlias = getAliasedAccountInfo(evmAlias.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, infoCheckEVMAlias);

                        // Add the hollow account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(evmAlias.get()).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_HOLLOW, newAccountId);

                        // Finalize the hollow account inner transaction
                        final var finalizeHollowAccount = cryptoCreate("foo")
                                .balance(10L)
                                .payingWith(VALID_ALIAS_HOLLOW)
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(VALID_ALIAS_HOLLOW))
                                .via("finalizingTxn")
                                .batchKey(BATCH_OPERATOR);

                        // update the hollow account key inner transaction
                        final var accountEditTxn = cryptoUpdate(VALID_ALIAS_HOLLOW)
                                .key(VALID_ALIAS_HOLLOW_SECOND)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_HOLLOW, VALID_ALIAS_HOLLOW_SECOND)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR);

                        // Finalize the hollow account and update its key in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(finalizeHollowAccount, accountEditTxn)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnSecond")
                                .hasKnownStatus(SUCCESS);

                        // validate finalized and updated account info
                        final var finalizedAccountInfoCheck = getAccountInfo(VALID_ALIAS_HOLLOW)
                                .isNotHollow()
                                .has(accountWith()
                                        .key(VALID_ALIAS_HOLLOW_SECOND)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 9L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 90L);

                        // validate finalized account token balance
                        final var getFinalizedAccountBalance = getAccountBalance(VALID_ALIAS_HOLLOW)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 1L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionSecond,
                                finalizedAccountInfoCheck,
                                senderBalanceCheck,
                                ownerBalanceCheck,
                                getFinalizedAccountBalance);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Account in one batch, edit Account Key with Threshold Key and Transfer from the edited account in second "
                        + "batch success in Atomic Batch")
        public Stream<DynamicTest>
                autoCreateAccountEditAccountKeyWithThresholdAndTransferFromEditedAccountSuccessInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        final var thresholdKey = Key.newBuilder()
                                .setThresholdKey(ThresholdKey.newBuilder()
                                        .setThreshold(2)
                                        .setKeys(KeyList.newBuilder()
                                                .addKeys(spec.registry().getKey(VALID_ALIAS_ED25519))
                                                .addKeys(spec.registry().getKey(VALID_ALIAS_ED25519_SECOND))
                                                .build()));
                        spec.registry().saveKey(VALID_THRESHOLD_KEY, thresholdKey.build());

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdateAliased(VALID_ALIAS_ED25519)
                                .key(VALID_THRESHOLD_KEY)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519, VALID_ALIAS_ED25519_SECOND)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR);

                        // transfer from the edited auto-created account inner transaction
                        final var transferFromAutoCreatedAccount = cryptoTransfer(
                                        movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(VALID_ALIAS_ED25519, CIVILIAN))
                                .payingWith(OWNER)
                                .via("transferFromAutoCreatedAccount")
                                .signedBy(OWNER, VALID_ALIAS_ED25519, VALID_ALIAS_ED25519_SECOND)
                                .batchKey(BATCH_OPERATOR);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        accountEditTxn, transferFromAutoCreatedAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_THRESHOLD_KEY)
                                        .balance(0L)
                                        .maxAutoAssociations(-1))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(0L);

                        // validate receiver account balance after transfers
                        final var receiverBalanceCheck =
                                getAccountBalance(CIVILIAN).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate finalized account token balance
                        final var getFinalizedAccountBalance =
                                getAccountBalance(VALID_ALIAS_ED25519).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionSecond,
                                secondInfoCheckED2559,
                                receiverBalanceCheck,
                                ownerBalanceCheck,
                                getFinalizedAccountBalance);
                    })));
        }

        @HapiTest
        @DisplayName("Auto Create Account in one batch, edit Account Key with new Key and edit again with the Old Key "
                + "success in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountEditAccountKeyWithNewAndEditAgainWithOldKeysSuccessInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        // update account inner transaction
                        final var accountEditTxnFirst = cryptoUpdate(VALID_ALIAS_ED25519)
                                .key(VALID_ALIAS_ED25519_SECOND)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519, VALID_ALIAS_ED25519_SECOND)
                                .via("accountEditTxnFirst")
                                .batchKey(BATCH_OPERATOR);

                        final var accountEditTxnSecond = cryptoUpdate(VALID_ALIAS_ED25519)
                                .key(VALID_ALIAS_ED25519)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519, VALID_ALIAS_ED25519_SECOND)
                                .via("accountEditTxnSecond")
                                .batchKey(BATCH_OPERATOR);

                        // token transfer to auto-associate the edited auto-created account inner transaction
                        final var tokenTransferToAutoCreatedAccount = cryptoTransfer(
                                        movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(VALID_ALIAS_ED25519, CIVILIAN))
                                .payingWith(OWNER)
                                .via("transferFromAutoCreatedAccount")
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .batchKey(BATCH_OPERATOR);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        accountEditTxnFirst, accountEditTxnSecond, tokenTransferToAutoCreatedAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        allRunFor(spec, atomicBatchTransactionSecond);

                        // sync the registry to ensure the key is updated
                        // (FUTURE) This shouldn't be necessary, but only _sometimes_ the key for
                        // `VALID_ALIAS_ED25519` gets changed in the registry to match `VALID_ALIAS_ED25519_SECOND`
                        // during test execution.
                        // We need to figure out why. Until then, this line syncs the expected original key value of
                        // `VALID_ALIAS_ED25519` to the registry.
                        syncRegistryKeyFromAccountInfo(spec, VALID_ALIAS_ED25519);

                        // get the actual account key
                        final var accountInfoSecond =
                                getAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfoSecond);

                        final var actualKey = accountInfoSecond
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getKey();

                        // validate the actual key is the expected one
                        final var expectedKey = spec.registry().getKey(VALID_ALIAS_ED25519);
                        assertEquals(
                                expectedKey,
                                actualKey,
                                "The account key after the second edit should match the original key");

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck =
                                getAccountBalance(CIVILIAN).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        allRunFor(spec, ownerBalanceCheck);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Account in one batch and edit Auto-Association limit to 0 in second batch fails in Atomic Batch")
        public Stream<DynamicTest>
                autoCreateAccountEditAutoAssociationLimitToZeroAndAssociateEditedAccountFailsInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdate(VALID_ALIAS_ED25519)
                                .maxAutomaticAssociations(0)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(EXISTING_AUTOMATIC_ASSOCIATIONS_EXCEED_GIVEN_LIMIT);

                        // can't update auto-association limit to 0 in batch when account already has automatic
                        // associations
                        // in use
                        final var atomicBatchTransactionSecond = atomicBatch(accountEditTxn)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .maxAutoAssociations(-1)
                                        .balance(0L))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionSecond, secondInfoCheckED2559);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Account in one batch, edit Auto-Association limit to 1 and Associate exceeding the edited limit "
                        + "in second batch fails in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountEditAutoAssociationLimitAndExceedAssociationNumberFailsInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    createImmutableNFT(DUMMY_NFT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    mintNFT(DUMMY_NFT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdate(VALID_ALIAS_ED25519)
                                .maxAutomaticAssociations(1)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR);

                        // token transfer to auto-associate the edited auto-created account inner transaction
                        final var tokenTransferToAutoCreatedAccount = cryptoTransfer(
                                        movingUnique(DUMMY_NFT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                                .payingWith(OWNER)
                                .via("transferFromAutoCreatedAccount")
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(NO_REMAINING_AUTOMATIC_ASSOCIATIONS);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        accountEditTxn, tokenTransferToAutoCreatedAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .maxAutoAssociations(-1)
                                        .balance(0L))
                                .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                                .hasNoTokenRelationship(DUMMY_NFT)
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L)
                                .hasTokenBalance(DUMMY_NFT, 1L);

                        allRunFor(spec, atomicBatchTransactionSecond, secondInfoCheckED2559, ownerBalanceCheck);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Account in one batch, edit Account Key and sign transfer from the edited account in second "
                        + "batch with old key fails in Atomic Batch")
        public Stream<DynamicTest>
                autoCreateAccountEditAccountKeyAndSignTransferFromEditedAccountWithOldKeyFailsInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdateAliased(VALID_ALIAS_ED25519)
                                .key(VALID_ALIAS_ED25519_SECOND)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519, VALID_ALIAS_ED25519_SECOND)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR);

                        // transfer from the edited auto-created account inner transaction
                        final var transferFromAutoCreatedAccount = cryptoTransfer(
                                        movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(VALID_ALIAS_ED25519, CIVILIAN))
                                .payingWith(OWNER)
                                .via("transferFromAutoCreatedAccount")
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_SIGNATURE);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        accountEditTxn, transferFromAutoCreatedAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate receiver account balance after transfers
                        final var receiverBalanceCheck =
                                getAccountBalance(CIVILIAN).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate finalized account token balance
                        final var getFinalizedAccountBalance =
                                getAccountBalance(VALID_ALIAS_ED25519).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionSecond,
                                secondInfoCheckED2559,
                                receiverBalanceCheck,
                                ownerBalanceCheck,
                                getFinalizedAccountBalance);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Account in one batch, edit Account Key and do not sign the edit with the new key in second "
                        + "batch fails in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountEditAccountKeyAndDoNotSignEditTxnWithNewKeyFailsInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdateAliased(VALID_ALIAS_ED25519)
                                .key(VALID_ALIAS_ED25519_SECOND)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_SIGNATURE);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(accountEditTxn)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionSecond, secondInfoCheckED2559);
                    })));
        }

        @HapiTest
        @DisplayName("Auto Create Account in one batch, edit Account Key with Invalid Key fails in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountEditAccountKeyWithInvalidKeyFailsInBatch() {

            final Key invalidKey =
                    Key.newBuilder().setKeyList(KeyList.newBuilder()).build();

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);
                        spec.registry().saveKey(INVALID_KEY, invalidKey);

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdateAliased(VALID_ALIAS_ED25519)
                                .key(INVALID_KEY)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ADMIN_KEY);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(accountEditTxn)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionSecond, secondInfoCheckED2559);
                    })));
        }

        @HapiTest
        @DisplayName(
                "Auto Create Account in one batch, edit Account Key with Invalid Threshold Key fails in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountEditAccountKeyWithInvalidThresholdKeyFailsInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        final var invalidThresholdKey = Key.newBuilder()
                                .setThresholdKey(ThresholdKey.newBuilder()
                                        .setThreshold(2)
                                        .setKeys(KeyList.newBuilder()
                                                .addKeys(spec.registry().getKey(VALID_ALIAS_ED25519))
                                                .build()));
                        spec.registry().saveKey(INVALID_THRESHOLD_KEY, invalidThresholdKey.build());

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdateAliased(VALID_ALIAS_ED25519)
                                .key(INVALID_THRESHOLD_KEY)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519, VALID_ALIAS_ED25519_SECOND)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_ADMIN_KEY);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(accountEditTxn)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate receiver account balance after transfers
                        final var receiverBalanceCheck =
                                getAccountBalance(CIVILIAN).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate finalized account token balance
                        final var getFinalizedAccountBalance =
                                getAccountBalance(VALID_ALIAS_ED25519).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionSecond,
                                secondInfoCheckED2559,
                                receiverBalanceCheck,
                                ownerBalanceCheck,
                                getFinalizedAccountBalance);
                    })));
        }

        @HapiTest
        @DisplayName("Auto Create Account in one batch, edit Account Key with Valid Threshold Key and sign with Old Key"
                + " fails in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountEditAccountKeyWithThresholdKeyAndSignWithOldFailsInBatch() {

            // create FT transfer to ED25519 alias in a batch
            final var tokenTransferNFT_To_ED25519 = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                    .payingWith(OWNER)
                    .via("cryptoTransferNFT_To_ED25519")
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts, register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 1),
                    withOpContext((spec, opLog) -> {

                        // Auto-create account with public key with token transfer in an atomic batch
                        final var atomicBatchTransactionFirst = atomicBatch(tokenTransferNFT_To_ED25519)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(SUCCESS);

                        // validate the public key account creation
                        final var firstInfoCheckED2559 = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .alias(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        allRunFor(spec, atomicBatchTransactionFirst, firstInfoCheckED2559);

                        // add the auto-created account to the registry
                        final var accountInfo =
                                getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                        final var validThresholdKey = Key.newBuilder()
                                .setThresholdKey(ThresholdKey.newBuilder()
                                        .setThreshold(2)
                                        .setKeys(KeyList.newBuilder()
                                                .addKeys(spec.registry().getKey(VALID_ALIAS_ED25519))
                                                .addKeys(spec.registry().getKey(VALID_ALIAS_ED25519_SECOND))
                                                .build()));
                        spec.registry().saveKey(VALID_THRESHOLD_KEY, validThresholdKey.build());

                        // update account inner transaction
                        final var accountEditTxn = cryptoUpdateAliased(VALID_ALIAS_ED25519)
                                .key(VALID_THRESHOLD_KEY)
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_ED25519)
                                .via("accountEditTxn")
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(INVALID_SIGNATURE);

                        // Edit the auto-create account and token transfer from it in an atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(accountEditTxn)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        // validate the public key account after the transfer
                        final var secondInfoCheckED2559 = getAccountInfo(VALID_ALIAS_ED25519)
                                .has(accountWith()
                                        .key(VALID_ALIAS_ED25519)
                                        .balance(0L)
                                        .maxAutoAssociations(-1))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate receiver account balance after transfers
                        final var receiverBalanceCheck =
                                getAccountBalance(CIVILIAN).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                        // validate finalized account token balance
                        final var getFinalizedAccountBalance =
                                getAccountBalance(VALID_ALIAS_ED25519).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionSecond,
                                secondInfoCheckED2559,
                                receiverBalanceCheck,
                                ownerBalanceCheck,
                                getFinalizedAccountBalance);
                    })));
        }
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

    private List<HapiTxnOp> createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
            String sender,
            ByteString evmAlias,
            long hbarAmount,
            long ftAmount,
            String ftToken,
            List<Long> nftSerials,
            String nftToken,
            String txnName,
            ResponseCodeEnum status) {

        final var transfers = new ArrayList<TokenMovement>();

        if (hbarAmount > 0) {
            transfers.add(movingHbar(hbarAmount).between(sender, evmAlias));
        }

        if (ftAmount > 0 && ftToken != null) {
            transfers.add(moving(ftAmount, ftToken).between(sender, evmAlias));
        }

        if (nftSerials != null && !nftSerials.isEmpty() && nftToken != null) {
            for (Long serial : nftSerials) {
                transfers.add(movingUnique(nftToken, serial).between(sender, evmAlias));
            }
        }

        final var cryptoTransfer = cryptoTransfer(transfers.toArray(TokenMovement[]::new))
                .payingWith(sender)
                .via(txnName)
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(status);

        // We do not want to create a crypto transfer with empty transfers
        if (transfers.isEmpty()) {
            throw new IllegalArgumentException("Cannot create cryptoTransfer with empty transfers");
        }

        return List.of(cryptoTransfer);
    }

    private List<HapiTxnOp> createHollowAccountWithCryptoTransferWithBatchKeyToAlias_AllowEmptyTransfers(
            String sender,
            ByteString evmAlias,
            long hbarAmount,
            long ftAmount,
            String ftToken,
            List<Long> nftSerials,
            String nftToken,
            String txnName,
            ResponseCodeEnum status) {

        final var transfers = new ArrayList<TokenMovement>();

        if (hbarAmount >= 0) {
            transfers.add(movingHbar(hbarAmount).between(sender, evmAlias));
        }

        if (ftAmount > 0 && ftToken != null) {
            transfers.add(moving(ftAmount, ftToken).between(sender, evmAlias));
        }

        if (nftSerials != null && !nftSerials.isEmpty() && nftToken != null) {
            for (Long serial : nftSerials) {
                transfers.add(movingUnique(nftToken, serial).between(sender, evmAlias));
            }
        }

        final var cryptoTransfer = cryptoTransfer(transfers.toArray(TokenMovement[]::new))
                .payingWith(sender)
                .via(txnName)
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(status);

        return List.of(cryptoTransfer);
    }

    private HapiTokenCreate createMutableFT(String tokenName, String treasury, String adminKey) {
        return tokenCreate(tokenName)
                .tokenType(FUNGIBLE_COMMON)
                .treasury(treasury)
                .initialSupply(100L)
                .adminKey(adminKey);
    }

    private HapiTokenCreate createImmutableNFT(String tokenName, String treasury, String supplyKey) {
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

    private void syncRegistryKeyFromAccountInfo(HapiSpec spec, String aliasName) {
        final var infoOp = getAccountInfo(aliasName);
        allRunFor(spec, infoOp);

        final var key = infoOp.getResponse().getCryptoGetInfo().getAccountInfo().getKey();

        spec.registry().saveKey(aliasName, key);
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OWNER).balance(ONE_MILLION_HBARS),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(3),
                cryptoCreate(PAYER_NO_FUNDS).balance(0L),
                newKeyNamed(VALID_ALIAS_ED25519).shape(KeyShape.ED25519),
                newKeyNamed(VALID_ALIAS_ED25519_SECOND).shape(KeyShape.ED25519),
                newKeyNamed(VALID_ALIAS_ECDSA).shape(SECP_256K1_SHAPE),
                newKeyNamed(VALID_ALIAS_ECDSA_SECOND).shape(SECP_256K1_SHAPE),
                newKeyNamed(VALID_ALIAS_HOLLOW).shape(SECP_256K1_SHAPE),
                newKeyNamed(VALID_ALIAS_HOLLOW_SECOND).shape(SECP_256K1_SHAPE),
                newKeyNamed(VALID_ALIAS_HOLLOW_THIRD).shape(SECP_256K1_SHAPE),
                newKeyNamed(VALID_ALIAS_HOLLOW_FOURTH).shape(SECP_256K1_SHAPE),
                newKeyNamed(VALID_ALIAS_HOLLOW_FIFTH).shape(SECP_256K1_SHAPE),
                newKeyNamed(adminKey),
                newKeyNamed(nftSupplyKey));
    }
}
