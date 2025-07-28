// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.service.token.AliasUtils.recoverAddressFromPubKey;
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
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
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

@HapiTestLifecycle
public class AtomicBatchAutoAccountCreationEndToEndTests {

    private static final double BASE_FEE_BATCH_TRANSACTION = 0.001;
    private static final String FT_FOR_AUTO_ACCOUNT = "ftForAutoAccount";
    private static final String NFT_FOR_AUTO_ACCOUNT = "nftForAutoAccount";
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
                    createHollowAccountFrom(VALID_ALIAS_HOLLOW),
                    withOpContext((spec, opLog) -> {

                        // Create inner transaction to finalize hollow account
                        final var finalizeHollowAccount = cryptoTransfer(
                                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, evmAlias.get()))
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_HOLLOW)
                                .via("finalizeHollowAccount")
                                .batchKey(BATCH_OPERATOR);

                        // Create hollow account with token transfers in one atomic batch
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

                        final var batchTxnFeeCheckFirst =
                                validateChargedUsd("batchTxnFirst", BASE_FEE_BATCH_TRANSACTION);

                        // validate the hollow account creation and transfers
                        final var infoCheckEVMAlias = getAliasedAccountInfo(evmAlias.get())
                                .isHollow()
                                .has(accountWith()
                                        .hasEmptyKey()
                                        //                                        .balance(10L)
                                        .noAlias()
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // Finalize the hollow account in another atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(finalizeHollowAccount)
                                .payingWith(BATCH_OPERATOR)
                                .signedBy(BATCH_OPERATOR, VALID_ALIAS_HOLLOW)
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(VALID_ALIAS_HOLLOW))
                                .via("batchTxnSecond")
                                .hasKnownStatus(SUCCESS);

                        //                        final var transactionCheck = cryptoTransfer(
                        //                                moving(5L, FT_FOR_AUTO_ACCOUNT).between(OWNER,
                        // evmAlias.get()))
                        //                                .payingWith(OWNER)
                        //                                .signedBy(OWNER, VALID_ALIAS_HOLLOW)
                        //                                .via("batchTxnSecond");

                        final var batchTxnFeeCheckSecond =
                                validateChargedUsd("batchTxnSecond", BASE_FEE_BATCH_TRANSACTION);

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 9L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 89L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionFirst,
                                batchTxnFeeCheckFirst,
                                infoCheckEVMAlias,
                                //                                transactionCheck,
                                atomicBatchTransactionSecond,
                                batchTxnFeeCheckSecond,
                                senderBalanceCheck,
                                ownerBalanceCheck);

                        final var accountInfo =
                                getAliasedAccountInfo(evmAlias.get()).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_HOLLOW, newAccountId);

                        // validate finalized account info
                        final var finalisedAccountInfoCheck = getAccountInfo(VALID_ALIAS_HOLLOW)
                                .isHollow()
                                .has(accountWith()
                                        //                                        .key(VALID_ALIAS_HOLLOW)
                                        //                                        .alias(VALID_ALIAS_HOLLOW)
                                        .balance(10L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        final var getAccountBalance = getAccountBalance(VALID_ALIAS_HOLLOW)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 2L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);
                        allRunFor(spec, finalisedAccountInfoCheck, getAccountBalance);
                    })));
        }

        @HapiTest
        @DisplayName("Auto Create Hollow Account in one Batch, Finalize it and Token Transfer in Another Atomic Batch")
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

                        // Create inner transaction to finalize hollow account
                        final var finalizeHollowAccount = cryptoTransfer(
                                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, evmAlias.get()))
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_HOLLOW)
                                .via("finalizeHollowAccount")
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(SUCCESS);

                        // Create inner transaction to transfer tokens from the finalized hollow account
                        final var transferFromHollowAccount = cryptoTransfer(
                                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(evmAlias.get(), OWNER))
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_HOLLOW)
                                .via("transferFromHollowAccount")
                                .batchKey(BATCH_OPERATOR)
                                .hasKnownStatus(SUCCESS);

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

                        final var batchTxnFeeCheckFirst =
                                validateChargedUsd("batchTxnFirst", BASE_FEE_BATCH_TRANSACTION);

                        // validate the hollow account creation and transfers
                        final var infoCheckEVMAlias = getAliasedAccountInfo(evmAlias.get())
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

                        // Finalize the hollow account and transfer from it in another atomic batch
                        final var atomicBatchTransactionSecond = atomicBatch(
                                        finalizeHollowAccount, transferFromHollowAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnSecond")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);
                        //
                        //                        final var finalizeHollowAccount = cryptoTransfer(
                        //                                moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER,
                        // evmAlias.get()))
                        //                                .payingWith(OWNER)
                        //                                .signedBy(OWNER, VALID_ALIAS_HOLLOW)
                        //                                .via("finalizeHollowAccount");
                        //
                        //                        // Create inner transaction to transfer tokens from the finalized
                        // hollow account
                        //                        final var transferFromHollowAccount = cryptoTransfer(
                        //                                moving(1L, FT_FOR_AUTO_ACCOUNT).between(evmAlias.get(),
                        // OWNER))
                        //                                .payingWith(OWNER)
                        //                                .signedBy(OWNER, VALID_ALIAS_HOLLOW)
                        //                                .via("transferFromHollowAccount");

                        final var batchTxnFeeCheckSecond =
                                validateChargedUsd("batchTxnSecond", BASE_FEE_BATCH_TRANSACTION);

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
                                batchTxnFeeCheckFirst,
                                infoCheckEVMAlias,
                                atomicBatchTransactionSecond,
                                batchTxnFeeCheckSecond,
                                senderBalanceCheck,
                                ownerBalanceCheck);

                        //                        final var accountInfo =
                        // getAliasedAccountInfo(evmAlias.get()).logged();
                        //                        allRunFor(spec, accountInfo);
                        //
                        //                        final var newAccountId = accountInfo
                        //                                .getResponse()
                        //                                .getCryptoGetInfo()
                        //                                .getAccountInfo()
                        //                                .getAccountID();
                        //                        spec.registry().saveAccountId(VALID_ALIAS_HOLLOW, newAccountId);
                        //
                        //                        final var getAccountBalance = getAccountBalance(VALID_ALIAS_HOLLOW)
                        //                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 1L)
                        //                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);
                        //                        allRunFor(spec, getAccountBalance);
                        //
                        //                        // validate finalized account info
                        //                        getAccountInfo(VALID_ALIAS_HOLLOW)
                        //                                .has(accountWith()
                        //                                        .key(VALID_ALIAS_HOLLOW)
                        //                                        .alias(VALID_ALIAS_HOLLOW)
                        //                                        .balance(0L)
                        //                                        .maxAutoAssociations(-1)
                        //                                        .memo(AUTO_MEMO))
                        //                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                        //                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                        //                                .hasOwnedNfts(1L);
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

                        final var batchTxnFeeCheckFirst =
                                validateChargedUsd("batchTxnFirst", BASE_FEE_BATCH_TRANSACTION);

                        // validate the hollow account creation and transfers
                        final var infoCheckEVMAlias = getAliasedAccountInfo(evmAlias.get())
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

                        // Finalize the hollow account outside the atomic batch
                        final var finalizeHollowAccount = cryptoTransfer(
                                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, evmAlias.get()))
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_HOLLOW)
                                .via("finalizeHollowAccount");

                        // validate sender account balance after transfers
                        final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 9L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                        // validate owner account balance after transfers
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 89L);

                        allRunFor(
                                spec,
                                atomicBatchTransactionFirst,
                                batchTxnFeeCheckFirst,
                                infoCheckEVMAlias,
                                finalizeHollowAccount,
                                senderBalanceCheck,
                                ownerBalanceCheck);

                        final var accountInfo =
                                getAliasedAccountInfo(evmAlias.get()).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_HOLLOW, newAccountId);

                        // validate finalized account info
                        final var accountInfoCheck = getAccountInfo(VALID_ALIAS_HOLLOW)
                                .has(accountWith()
                                        .key(String.valueOf(evmAlias.get()))
                                        .alias(String.valueOf(evmAlias.get()))
                                        .balance(0L)
                                        .maxAutoAssociations(-1)
                                        .memo(AUTO_MEMO))
                                .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                                .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                                .hasOwnedNfts(1L);

                        // validate finalized account token balance
                        final var getAccountBalance = getAccountBalance(VALID_ALIAS_HOLLOW)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 2L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);
                        allRunFor(spec, accountInfoCheck, getAccountBalance);
                    })));
        }

        // Can't create and finalize hollow account in the same batch
        @HapiTest
        @DisplayName("Auto Create Hollow Account and Finalize it in the same Batch Not Possible in Atomic Batch")
        public Stream<DynamicTest> autoCreateHollowAccountAndFinalizeInTheSameBatchFailsInBatch() {

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
                        final var finalizeHollowAccount = cryptoTransfer(
                                        moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, evmAlias.get()))
                                .payingWith(OWNER)
                                .signedBy(OWNER, VALID_ALIAS_HOLLOW)
                                .via("finalizeHollowAccount")
                                .batchKey(BATCH_OPERATOR);

                        // Create hollow account with token transfer and finalize in one atomic batch
                        final var atomicBatchTransaction = atomicBatch(
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
                                                .getFirst(),
                                        finalizeHollowAccount)
                                .payingWith(BATCH_OPERATOR)
                                .via("batchTxnFirst")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        final var batchTxnFeeCheck = validateChargedUsd("batchTxnFirst", BASE_FEE_BATCH_TRANSACTION);

                        // validate the hollow account creation and transfers
                        final var infoCheckEVMAlias = getAliasedAccountInfo(evmAlias.get())
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

                        // validate owner account balance after transfers - owner did not receive the 1 FT back
                        final var ownerBalanceCheck = getAccountBalance(OWNER)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 80L);

                        allRunFor(
                                spec,
                                atomicBatchTransaction,
                                batchTxnFeeCheck,
                                infoCheckEVMAlias,
                                senderBalanceCheck,
                                ownerBalanceCheck);

                        final var accountInfo =
                                getAliasedAccountInfo(evmAlias.get()).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(VALID_ALIAS_HOLLOW, newAccountId);

                        final var getAccountBalance = getAccountBalance(VALID_ALIAS_HOLLOW)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 11L)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);
                        allRunFor(spec, getAccountBalance);
                    })));
        }
    }

    // With tokens:
    @Nested
    @DisplayName("Atomic Batch Auto Account Creation End-to-End Tests - Test Cases with Token Minting and Transfers")
    class AtomicBatchAutoAccountCreationTokensMintsAndTransfersTests {
        // Mint token and transfer it to a public key alias in the same batch
        @HapiTest
        @DisplayName("Mint Token and Transfer it to a Public key alias auto-creating account success in Atomic Batch")
        public Stream<DynamicTest> autoCreateAccountWithTokenMintAndTransferSuccessInBatch() {

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

        // Mint token, transfer it to a public key alias and transfer from the auto-created account to another account
        // in
        // the same batch

        // Mint token, transfer it to a public key alias and transfer from the auto-created account to another public
        // key
        // alias in a different batch

        // Mint token, transfer it to an evm alias resulting in a hollow account in the same batch

        // Mint token, transfer it to an evm alias resulting in a hollow account and try to finalize - should fail

    }

    // With auto-created account and edits:
    // Auto-create account and edit it in the same batch - should be possible
    // Auto-create account and edit it in the same batch - change max auto-association limit - should be possible?????
    // Auto-create account and edit it in the same batch - change auto-association limit to 1 - make new transfer
    // successfully from the account
    // Auto-create account and edit it in the same batch - change auto-association limit to 0 - make new transfer from
    // the account - should fail
    // Auto-create account and edit it in the same batch - update keys
    // Auto-create account and edit it in the same batch - update keys to multi-sig keys or threshold keys - should be
    // possible

    // With KYC grants:
    // Auto-create account and grant KYC in the same batch - should be possible
    // Auto-create account, grant KYC and perform transfers in the same batch

    // With airdrops:
    // Airdrop to public key alias in the same batch
    // Airdrop to public key alias and transfer from the auto-created account to another account in the same batch
    // Airdrop to public key alias and claim in the same batch
    // Airdrop to evm alias resulting in a hollow account in the same batch
    // Airdrop to hollow account in the batch and finalize it in another batch - should not be possible
    // AIrdrop to hollow account and try to claim with the hollow account

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

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HBAR),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
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
