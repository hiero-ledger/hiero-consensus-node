// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asIdWithAlias;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.isEndOfStakingPeriodRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdateAliased;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_RECEIVER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.crypto.AutoAccountCreationSuite.AutoAccountCreate.assertAliasBalanceAndFeeInChildRecord;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

class AtomicBatchAutoAccountCreationBasicTests {

    private static final double BASE_FEE_BATCH_TRANSACTION = 0.001;
    private static final String FT_FOR_AUTO_ACCOUNT = "ftForAutoAccount";
    private static final String NFT_FOR_AUTO_ACCOUNT = "nftForAutoAccount";
    private static final String CIVILIAN = "civilian";
    private static final String VALID_ALIAS_ED25519 = "validAliasED25519";
    private static final String VALID_ALIAS_ECDSA = "validAliasECDSA";
    private static final String VALID_ALIAS_HOLLOW = "validAliasHollow";
    private static final String PAYER_NO_FUNDS = "payerNoFunds";
    private static final String AUTO_MEMO = "";

    private static final String OWNER = "owner";
    private static final String BATCH_OPERATOR = "batchOperator";

    private static final String nftSupplyKey = "nftSupplyKey";
    private static final String adminKey = "adminKey";

    @HapiTest
    @DisplayName("Auto Create ED25519 Account with FT Transfer success in Atomic Batch")
    Stream<DynamicTest> autoCreateED25519AccountWithFT_TransferSuccessInBatch() {

        // create transfer to alias inner transaction
        final var tokenTransferToAlias = cryptoTransfer(
                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ED25519))
                .payingWith(OWNER)
                .via("cryptoTransferToAlias")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                // create keys and accounts,
                createAccountsAndKeys(),

                // create fungible token
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                withOpContext((spec, opLog) -> {

                    // perform the atomic batch transaction
                    final var atomicBatchTransaction = atomicBatch(tokenTransferToAlias)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS);

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION);

                    // validate account is created and has the expected balance
                    final var senderBalanceCheck = getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 99L);
                    final var aliasAccountBalanceCheck = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                            .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                            .has(accountWith()
                                    .key(VALID_ALIAS_ED25519)
                                    .alias(VALID_ALIAS_ED25519)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO));

                    allRunFor(
                            spec,
                            atomicBatchTransaction,
                            batchTxnFeeCheck,
                            senderBalanceCheck,
                            aliasAccountBalanceCheck);

                    final var accountInfo =
                            getAliasedAccountInfo(VALID_ALIAS_ED25519).logged();
                    allRunFor(spec, accountInfo);

                    final var newAccountId = accountInfo
                            .getResponse()
                            .getCryptoGetInfo()
                            .getAccountInfo()
                            .getAccountID();
                    spec.registry().saveAccountId(VALID_ALIAS_ED25519, newAccountId);

                    final var getAccountBalance = getAccountBalance(VALID_ALIAS_ED25519)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 1L)
                            .logged();
                    allRunFor(spec, getAccountBalance);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create ECDSA Account with FT Transfer success in Atomic Batch")
    @Tag(MATS)
    Stream<DynamicTest> autoCreateECDSA_AccountWithFT_TransferSuccessInBatch() {

        // create transfer to alias inner transaction
        final var tokenTransferToAlias = cryptoTransfer(
                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ECDSA))
                .payingWith(OWNER)
                .via("cryptoTransferToAlias")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                // create keys and accounts,
                createAccountsAndKeys(),

                // create fungible token
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                withOpContext((spec, opLog) -> {

                    // perform the atomic batch transaction
                    final var atomicBatchTransaction = atomicBatch(tokenTransferToAlias)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(SUCCESS);

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION);

                    // validate account is created and has the expected balance
                    final var senderBalanceCheck = getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 99L);
                    final var aliasAccountBalanceCheck = getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                            .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                            .has(accountWith()
                                    .key(VALID_ALIAS_ECDSA)
                                    .alias(VALID_ALIAS_ECDSA)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO));
                    allRunFor(
                            spec,
                            atomicBatchTransaction,
                            batchTxnFeeCheck,
                            senderBalanceCheck,
                            aliasAccountBalanceCheck);

                    final var accountInfo =
                            getAliasedAccountInfo(VALID_ALIAS_ECDSA).logged();
                    allRunFor(spec, accountInfo);

                    final var newAccountId = accountInfo
                            .getResponse()
                            .getCryptoGetInfo()
                            .getAccountInfo()
                            .getAccountID();
                    spec.registry().saveAccountId(VALID_ALIAS_ECDSA, newAccountId);

                    final var getAccountBalance = getAccountBalance(VALID_ALIAS_ECDSA)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 1L)
                            .logged();
                    allRunFor(spec, getAccountBalance);
                })));
    }

    @HapiTest
    @DisplayName("Auto Account Create with Public Key ED25519 and ECDSA and HBAR Transfer success in "
            + "Atomic Batch - Parametrized")
    Stream<DynamicTest> autoCreateAccountWithHBAR_TransferSuccessInBatch_Parametrized() {
        record AliasTestCase(String displayName, String aliasKeyName, String aliasType) {}

        final List<AliasTestCase> aliasTypes = List.of(
                new AliasTestCase(
                        "Auto Create ED25519 Account with HBAR Transfer in Atomic Batch",
                        VALID_ALIAS_ED25519,
                        "ED25519"),
                new AliasTestCase(
                        "Auto Create ECDSA Account with HBAR Transfer in Atomic Batch", VALID_ALIAS_ECDSA, "ECDSA"));

        return aliasTypes.stream().flatMap(testCase -> {
            final var alias = testCase.aliasKeyName();

            final var batchTxnName = "batchTxn_" + testCase.displayName().replaceAll("\\s+", "_");
            final var transferTxnName =
                    "cryptoTransferToAlias_" + testCase.displayName().replaceAll("\\s+", "_");

            final var tokenTransferToAlias = cryptoTransfer(movingHbar(1L).between(OWNER, alias))
                    .payingWith(OWNER)
                    .via(transferTxnName)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts,
                    createAccountsAndKeys(),

                    // perform the atomic batch transaction
                    atomicBatch(tokenTransferToAlias)
                            .payingWith(BATCH_OPERATOR)
                            .via(batchTxnName)
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd(batchTxnName, BASE_FEE_BATCH_TRANSACTION),

                    // validate account is created and has the expected balance
                    getAliasedAccountInfo(alias)
                            .has(accountWith()
                                    .key(alias)
                                    .alias(alias)
                                    .balance(1L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))));
        });
    }

    @HapiTest
    @DisplayName("Auto Account Create with Public Key ED25519 and ECDSA and FT Transfer success in "
            + "Atomic Batch - Parametrized")
    Stream<DynamicTest> autoCreateAccountWithFT_TransferSuccessInBatch_Parametrized() {
        record AliasTestCase(String displayName, String aliasKeyName, String aliasType) {}

        final List<AliasTestCase> aliasTypes = List.of(
                new AliasTestCase(
                        "Auto Create ED25519 Account with FT Transfer in Atomic Batch", VALID_ALIAS_ED25519, "ED25519"),
                new AliasTestCase(
                        "Auto Create ECDSA Account with FT Transfer in Atomic Batch", VALID_ALIAS_ECDSA, "ECDSA"));

        return aliasTypes.stream().flatMap(testCase -> {
            final var alias = testCase.aliasKeyName();

            final var batchTxnName = "batchTxn_" + testCase.displayName().replaceAll("\\s+", "_");
            final var transferTxnName =
                    "cryptoTransferToAlias_" + testCase.displayName().replaceAll("\\s+", "_");

            final var tokenTransferToAlias = cryptoTransfer(
                            moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, alias))
                    .payingWith(OWNER)
                    .via(transferTxnName)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts,
                    createAccountsAndKeys(),

                    // create fungible token
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),

                    // perform the atomic batch transaction
                    atomicBatch(tokenTransferToAlias)
                            .payingWith(BATCH_OPERATOR)
                            .via(batchTxnName)
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd(batchTxnName, BASE_FEE_BATCH_TRANSACTION),

                    // validate account is created and has the expected balance
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 99L),
                    getAliasedAccountInfo(alias)
                            .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT))
                            .has(accountWith()
                                    .key(alias)
                                    .alias(alias)
                                    .balance(0L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO)),
                    withOpContext((spec, opLog) -> {
                        final var accountInfo = getAliasedAccountInfo(alias).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(alias, newAccountId);

                        final var getAccountBalance = getAccountBalance(alias)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 1L)
                                .logged();
                        allRunFor(spec, getAccountBalance);
                    })));
        });
    }

    @HapiTest
    @DisplayName("Auto Account Create with Public Key ED25519 and ECDSA and NFT Transfer success in "
            + "Atomic Batch - Parametrized")
    Stream<DynamicTest> autoCreateAccountWithNFT_TransferSuccessInBatch_Parametrized() {
        record AliasTestCase(String displayName, String aliasKeyName, String aliasType) {}

        final List<AliasTestCase> aliasTypes = List.of(
                new AliasTestCase(
                        "Auto Create ED25519 Account with NFT Transfer in Atomic Batch",
                        VALID_ALIAS_ED25519,
                        "ED25519"),
                new AliasTestCase(
                        "Auto Create ECDSA Account with NFT Transfer in Atomic Batch", VALID_ALIAS_ECDSA, "ECDSA"));

        return aliasTypes.stream().flatMap(testCase -> {
            final var alias = testCase.aliasKeyName();

            final var batchTxnName = "batchTxn_" + testCase.displayName().replaceAll("\\s+", "_");
            final var transferTxnName =
                    "cryptoTransferToAlias_" + testCase.displayName().replaceAll("\\s+", "_");

            final var tokenTransferToAlias = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, alias))
                    .payingWith(OWNER)
                    .via(transferTxnName)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts,
                    createAccountsAndKeys(),

                    // create and mint NFT token
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 3),

                    // perform the atomic batch transaction
                    atomicBatch(tokenTransferToAlias)
                            .payingWith(BATCH_OPERATOR)
                            .via(batchTxnName)
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd(batchTxnName, BASE_FEE_BATCH_TRANSACTION),

                    // validate account is created and has the expected balance
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L),
                    getAliasedAccountInfo(alias)
                            .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                            .has(accountWith()
                                    .key(alias)
                                    .alias(alias)
                                    .balance(0L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasOwnedNfts(1L),
                    withOpContext((spec, opLog) -> {
                        final var accountInfo = getAliasedAccountInfo(alias).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(alias, newAccountId);

                        final var getAccountBalance = getAccountBalance(alias)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L)
                                .logged();
                        allRunFor(spec, getAccountBalance);
                    })));
        });
    }

    @HapiTest
    @DisplayName("Auto Account Create with Public Key ED25519 and ECDSA and HBAR, FT and NFT Transfers success in "
            + "Atomic Batch - Parametrized")
    Stream<DynamicTest> autoCreateAccountWithHBAR_FT_NFT_TransferSuccessInBatch_Parametrized() {
        record AliasTestCase(String displayName, String aliasKeyName, String aliasType) {}

        final List<AliasTestCase> aliasTypes = List.of(
                new AliasTestCase(
                        "Auto Create ED25519 Account with NFT Transfer in Atomic Batch",
                        VALID_ALIAS_ED25519,
                        "ED25519"),
                new AliasTestCase(
                        "Auto Create ECDSA Account with NFT Transfer in Atomic Batch", VALID_ALIAS_ECDSA, "ECDSA"));

        return aliasTypes.stream().flatMap(testCase -> {
            final var alias = testCase.aliasKeyName();

            final var batchTxnName = "batchTxn_" + testCase.displayName().replaceAll("\\s+", "_");
            final var transferTxnName =
                    "cryptoTransferToAlias_" + testCase.displayName().replaceAll("\\s+", "_");

            final var tokenTransferToAlias = cryptoTransfer(
                            movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, alias),
                            moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, alias),
                            movingHbar(1L).between(OWNER, alias))
                    .payingWith(OWNER)
                    .via(transferTxnName)
                    .batchKey(BATCH_OPERATOR);

            return hapiTest(flattened(
                    // create keys and accounts,
                    createAccountsAndKeys(),

                    // create and mint NFT token
                    createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                    mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 3),
                    // create fungible token
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),

                    // perform the atomic batch transaction
                    atomicBatch(tokenTransferToAlias)
                            .payingWith(BATCH_OPERATOR)
                            .via(batchTxnName)
                            .hasKnownStatus(SUCCESS),
                    validateChargedUsd(batchTxnName, BASE_FEE_BATCH_TRANSACTION),

                    // validate account is created and has the expected balance
                    getAccountBalance(OWNER).hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L),
                    getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 99L),
                    getAliasedAccountInfo(alias)
                            .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                            .has(accountWith()
                                    .key(alias)
                                    .alias(alias)
                                    .balance(1L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasOwnedNfts(1L),
                    withOpContext((spec, opLog) -> {
                        final var accountInfo = getAliasedAccountInfo(alias).logged();
                        allRunFor(spec, accountInfo);

                        final var newAccountId = accountInfo
                                .getResponse()
                                .getCryptoGetInfo()
                                .getAccountInfo()
                                .getAccountID();
                        spec.registry().saveAccountId(alias, newAccountId);

                        final var getAccountBalance = getAccountBalance(alias)
                                .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L)
                                .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 1L)
                                .logged();
                        allRunFor(spec, getAccountBalance);
                    })));
        });
    }

    @HapiTest
    @DisplayName("Auto Create Hollow Account with HBAR Transfer success in Atomic Batch")
    Stream<DynamicTest> autoCreateHollowAccountWithHBAR_TransferSuccessInBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),
                registerEvmAddressAliasFrom(VALID_ALIAS_ECDSA, evmAlias),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 3),

                // Associate and supply tokens to accounts
                tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                cryptoTransfer(
                                moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L, 2L).between(OWNER, CIVILIAN))
                        .payingWith(OWNER)
                        .via("associateAndSupplyTokens"),
                withOpContext((spec, opLog) -> {

                    // Create a hollow account with the EVM alias within an atomic batch
                    final var atomicBatchTransaction = atomicBatch(
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                    CIVILIAN,
                                                    evmAlias.get(),
                                                    1L,
                                                    0L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias" + VALID_ALIAS_ECDSA,
                                                    SUCCESS)
                                            .getFirst())
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn_" + VALID_ALIAS_ECDSA)
                            .hasKnownStatus(SUCCESS);

                    final var batchTxnFeeCheck =
                            validateChargedUsd("batchTxn_" + VALID_ALIAS_ECDSA, BASE_FEE_BATCH_TRANSACTION);

                    // validate the hollow account creation and transfers
                    final var infoCheck = getAliasedAccountInfo(evmAlias.get())
                            .isHollow()
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .balance(1L)
                                    .noAlias()
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                            .hasNoTokenRelationship(NFT_FOR_AUTO_ACCOUNT)
                            .hasOwnedNfts(0L);

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 10L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L);

                    allRunFor(spec, atomicBatchTransaction, batchTxnFeeCheck, infoCheck, senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create Hollow Account with FT Transfer success in Atomic Batch")
    Stream<DynamicTest> autoCreateHollowAccountWithFT_TransferSuccessInBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),
                registerEvmAddressAliasFrom(VALID_ALIAS_ECDSA, evmAlias),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 3),

                // Associate and supply tokens to accounts
                tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                cryptoTransfer(
                                moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L, 2L).between(OWNER, CIVILIAN))
                        .payingWith(OWNER)
                        .via("associateAndSupplyTokens"),
                withOpContext((spec, opLog) -> {

                    // Create a hollow account with the EVM alias within an atomic batch
                    final var atomicBatchTransaction = atomicBatch(
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                    CIVILIAN,
                                                    evmAlias.get(),
                                                    0L,
                                                    5L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias" + VALID_ALIAS_ECDSA,
                                                    SUCCESS)
                                            .getFirst())
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn_" + VALID_ALIAS_ECDSA)
                            .hasKnownStatus(SUCCESS);

                    final var batchTxnFeeCheck =
                            validateChargedUsd("batchTxn_" + VALID_ALIAS_ECDSA, BASE_FEE_BATCH_TRANSACTION);

                    // validate the hollow account creation and transfers
                    final var infoCheck = getAliasedAccountInfo(evmAlias.get())
                            .isHollow()
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .balance(0L)
                                    .noAlias()
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT).balance(5L))
                            .hasNoTokenRelationship(NFT_FOR_AUTO_ACCOUNT)
                            .hasOwnedNfts(0L);

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 5L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L);

                    allRunFor(spec, atomicBatchTransaction, batchTxnFeeCheck, infoCheck, senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create Hollow Account with NFT Transfer success in Atomic Batch")
    @Tag(MATS)
    Stream<DynamicTest> autoCreateHollowAccountWithNFT_TransferSuccessInBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),
                registerEvmAddressAliasFrom(VALID_ALIAS_ECDSA, evmAlias),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 3),

                // Associate and supply tokens to accounts
                tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                cryptoTransfer(
                                moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L, 2L).between(OWNER, CIVILIAN))
                        .payingWith(OWNER)
                        .via("associateAndSupplyTokens"),
                withOpContext((spec, opLog) -> {

                    // Create a hollow account with the EVM alias within an atomic batch
                    final var atomicBatchTransaction = atomicBatch(
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                    CIVILIAN,
                                                    evmAlias.get(),
                                                    0L,
                                                    0L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(1L, 2L), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias" + VALID_ALIAS_ECDSA,
                                                    SUCCESS)
                                            .getFirst())
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn_" + VALID_ALIAS_ECDSA)
                            .hasKnownStatus(SUCCESS);

                    final var batchTxnFeeCheck =
                            validateChargedUsd("batchTxn_" + VALID_ALIAS_ECDSA, BASE_FEE_BATCH_TRANSACTION);

                    // validate the hollow account creation and transfers
                    final var infoCheck = getAliasedAccountInfo(evmAlias.get())
                            .isHollow()
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .balance(0L)
                                    .noAlias()
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                            .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                            .hasOwnedNfts(2L);

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 10L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                    allRunFor(spec, atomicBatchTransaction, batchTxnFeeCheck, infoCheck, senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create Hollow Account with HBAR, FT And NFT Transfers success in Atomic Batch")
    Stream<DynamicTest> autoCreateHollowAccountWithHBAR_FT_NFT_TransferSuccessInBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),
                registerEvmAddressAliasFrom(VALID_ALIAS_ECDSA, evmAlias),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 3),

                // Associate and supply tokens to accounts
                tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                cryptoTransfer(
                                moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L, 2L).between(OWNER, CIVILIAN))
                        .payingWith(OWNER)
                        .via("associateAndSupplyTokens"),
                withOpContext((spec, opLog) -> {

                    // Create a hollow account with the EVM alias within an atomic batch
                    final var atomicBatchTransaction = atomicBatch(
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                    CIVILIAN,
                                                    evmAlias.get(),
                                                    1L,
                                                    1L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(1L, 2L), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias" + VALID_ALIAS_ECDSA,
                                                    SUCCESS)
                                            .getFirst())
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn_" + VALID_ALIAS_ECDSA)
                            .hasKnownStatus(SUCCESS);

                    final var batchTxnFeeCheck =
                            validateChargedUsd("batchTxn_" + VALID_ALIAS_ECDSA, BASE_FEE_BATCH_TRANSACTION);

                    // validate the hollow account creation and transfers
                    final var infoCheck = getAliasedAccountInfo(evmAlias.get())
                            .isHollow()
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .balance(1L)
                                    .noAlias()
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT).balance(1L))
                            .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                            .hasOwnedNfts(2L);

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 9L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                    allRunFor(spec, atomicBatchTransaction, batchTxnFeeCheck, infoCheck, senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create All Types - Public Key and Hollow Accounts with HBAR Transfer success in Atomic Batch")
    Stream<DynamicTest> autoCreatePublicKeyAndHollowAccountWithHBAR_TransferSuccessInBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        // create transfer to ED25519 alias inner transaction
        final var tokenTransferTo_ED25519_Alias = cryptoTransfer(movingHbar(1L).between(OWNER, VALID_ALIAS_ED25519))
                .payingWith(OWNER)
                .via("cryptoTransferTo_ED25519_Alias")
                .batchKey(BATCH_OPERATOR);

        // create transfer to ECDSA alias inner transaction
        final var tokenTransferTo_ECDSA_Alias = cryptoTransfer(movingHbar(1L).between(OWNER, VALID_ALIAS_ECDSA))
                .payingWith(OWNER)
                .via("cryptoTransferTo_ECDSA_Alias")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),
                registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAlias),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 3),

                // Associate and supply tokens to accounts
                tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                cryptoTransfer(
                                moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L, 2L).between(OWNER, CIVILIAN))
                        .payingWith(OWNER)
                        .via("associateAndSupplyTokens"),
                withOpContext((spec, opLog) -> {

                    // Create all types public key and hollow accounts with HBAR transfers in one atomic batch
                    final var atomicBatchTransaction = atomicBatch(
                                    tokenTransferTo_ED25519_Alias,
                                    tokenTransferTo_ECDSA_Alias,
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                    CIVILIAN,
                                                    evmAlias.get(),
                                                    1L,
                                                    0L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias" + VALID_ALIAS_HOLLOW,
                                                    SUCCESS)
                                            .getFirst())
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxnAllAccounts")
                            .hasKnownStatus(SUCCESS);

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxnAllAccounts", BASE_FEE_BATCH_TRANSACTION);

                    // validate ED25519 account is created and has the expected balance
                    final var ED25519_AccountCheck = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                            .has(accountWith()
                                    .key(VALID_ALIAS_ED25519)
                                    .alias(VALID_ALIAS_ED25519)
                                    .balance(1L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO));

                    // validate ECDSA account is created and has the expected balance
                    final var ECDSA_AccountCheck = getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                            .has(accountWith()
                                    .key(VALID_ALIAS_ECDSA)
                                    .alias(VALID_ALIAS_ECDSA)
                                    .balance(1L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO));

                    // validate the hollow account creation and transfers
                    final var infoCheck = getAliasedAccountInfo(evmAlias.get())
                            .isHollow()
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .balance(1L)
                                    .noAlias()
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                            .hasNoTokenRelationship(NFT_FOR_AUTO_ACCOUNT)
                            .hasOwnedNfts(0L);

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 10L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L);

                    allRunFor(
                            spec,
                            atomicBatchTransaction,
                            batchTxnFeeCheck,
                            ED25519_AccountCheck,
                            ECDSA_AccountCheck,
                            infoCheck,
                            senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create All Types - Public Key and Hollow Accounts with FT Transfer success in Atomic Batch")
    Stream<DynamicTest> autoCreatePublicKeyAndHollowAccountWithFT_TransferSuccessInBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        // create transfer to ED25519 alias inner transaction
        final var tokenTransferTo_ED25519_Alias = cryptoTransfer(
                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ED25519))
                .payingWith(OWNER)
                .via("cryptoTransferTo_ED25519_Alias")
                .batchKey(BATCH_OPERATOR);

        // create transfer to ECDSA alias inner transaction
        final var tokenTransferTo_ECDSA_Alias = cryptoTransfer(
                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ECDSA))
                .payingWith(OWNER)
                .via("cryptoTransferTo_ECDSA_Alias")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),
                registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAlias),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 3),

                // Associate and supply tokens to accounts
                tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                cryptoTransfer(
                                moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L, 2L).between(OWNER, CIVILIAN))
                        .payingWith(OWNER)
                        .via("associateAndSupplyTokens"),
                withOpContext((spec, opLog) -> {

                    // Create all types public key and hollow accounts with HBAR transfers in one atomic batch
                    final var atomicBatchTransaction = atomicBatch(
                                    tokenTransferTo_ED25519_Alias,
                                    tokenTransferTo_ECDSA_Alias,
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                    CIVILIAN,
                                                    evmAlias.get(),
                                                    0L,
                                                    1L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias" + VALID_ALIAS_HOLLOW,
                                                    SUCCESS)
                                            .getFirst())
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxnAllAccounts")
                            .hasKnownStatus(SUCCESS);

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxnAllAccounts", BASE_FEE_BATCH_TRANSACTION);

                    // validate ED25519 account is created and has the expected balance
                    final var ED25519_AccountCheck = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                            .has(accountWith()
                                    .key(VALID_ALIAS_ED25519)
                                    .alias(VALID_ALIAS_ED25519)
                                    .balance(0L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT));

                    // validate ECDSA account is created and has the expected balance
                    final var ECDSA_AccountCheck = getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                            .has(accountWith()
                                    .key(VALID_ALIAS_ECDSA)
                                    .alias(VALID_ALIAS_ECDSA)
                                    .balance(0L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasToken(relationshipWith(FT_FOR_AUTO_ACCOUNT));

                    // validate the hollow account creation and transfers
                    final var infoCheck = getAliasedAccountInfo(evmAlias.get())
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

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 9L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L);

                    // validate OWNER account balance after transfers
                    final var ownerBalanceCheck = getAccountBalance(OWNER)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 88L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                    allRunFor(
                            spec,
                            atomicBatchTransaction,
                            batchTxnFeeCheck,
                            ED25519_AccountCheck,
                            ECDSA_AccountCheck,
                            infoCheck,
                            senderBalanceCheck,
                            ownerBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create All Types - Public Key and Hollow Accounts with NFT Transfer success in Atomic Batch")
    Stream<DynamicTest> autoCreatePublicKeyAndHollowAccountWithNFT_TransferSuccessInBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        // create transfer to ED25519 alias inner transaction
        final var tokenTransferTo_ED25519_Alias = cryptoTransfer(
                        movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L).between(OWNER, VALID_ALIAS_ED25519))
                .payingWith(OWNER)
                .via("cryptoTransferTo_ED25519_Alias")
                .batchKey(BATCH_OPERATOR);

        // create transfer to ECDSA alias inner transaction
        final var tokenTransferTo_ECDSA_Alias = cryptoTransfer(
                        movingUnique(NFT_FOR_AUTO_ACCOUNT, 2L).between(OWNER, VALID_ALIAS_ECDSA))
                .payingWith(OWNER)
                .via("cryptoTransferTo_ECDSA_Alias")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                // create keys and accounts register alias
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

                    // Create all types public key and hollow accounts with HBAR transfers in one atomic batch
                    final var atomicBatchTransaction = atomicBatch(
                                    tokenTransferTo_ED25519_Alias,
                                    tokenTransferTo_ECDSA_Alias,
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                    CIVILIAN,
                                                    evmAlias.get(),
                                                    0L,
                                                    0L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(3L, 4L), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias" + VALID_ALIAS_HOLLOW,
                                                    SUCCESS)
                                            .getFirst())
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxnAllAccounts")
                            .hasKnownStatus(SUCCESS);

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxnAllAccounts", BASE_FEE_BATCH_TRANSACTION);

                    // validate ED25519 account is created and has the expected balance
                    final var ED25519_AccountCheck = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                            .has(accountWith()
                                    .key(VALID_ALIAS_ED25519)
                                    .alias(VALID_ALIAS_ED25519)
                                    .balance(0L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                            .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                            .hasOwnedNfts(1L);

                    // validate ECDSA account is created and has the expected balance
                    final var ECDSA_AccountCheck = getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                            .has(accountWith()
                                    .key(VALID_ALIAS_ECDSA)
                                    .alias(VALID_ALIAS_ECDSA)
                                    .balance(0L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                            .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                            .hasOwnedNfts(1L);

                    // validate the hollow account creation and transfers
                    final var infoCheck = getAliasedAccountInfo(evmAlias.get())
                            .isHollow()
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .balance(0L)
                                    .noAlias()
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasToken(relationshipWith(NFT_FOR_AUTO_ACCOUNT))
                            .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                            .hasOwnedNfts(2L);

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 10L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 0L);

                    // validate OWNER account balance after transfers
                    final var ownerBalanceCheck = getAccountBalance(OWNER)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 90L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 1L);

                    allRunFor(
                            spec,
                            atomicBatchTransaction,
                            batchTxnFeeCheck,
                            ED25519_AccountCheck,
                            ECDSA_AccountCheck,
                            infoCheck,
                            senderBalanceCheck,
                            ownerBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create Hollow Account and Transfer to the same ECDSA Public Key success in Atomic Batch")
    Stream<DynamicTest> autoCreateHollowAccountAndTransferToSameECDSA_InBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        // create transfer to ECDSA alias inner transaction
        final var tokenTransferTo_ECDSA_Alias = cryptoTransfer(movingHbar(1L).between(OWNER, VALID_ALIAS_ECDSA))
                .payingWith(OWNER)
                .via("cryptoTransferTo_ECDSA_Alias")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),
                registerEvmAddressAliasFrom(VALID_ALIAS_ECDSA, evmAlias),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 3),

                // Associate and supply tokens to accounts
                tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                cryptoTransfer(
                                moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L, 2L).between(OWNER, CIVILIAN))
                        .payingWith(OWNER)
                        .via("associateAndSupplyTokens"),
                withOpContext((spec, opLog) -> {

                    // Create hollow account and transfer to the same ECDSA key in one atomic batch
                    final var atomicBatchTransaction = atomicBatch(
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                    CIVILIAN,
                                                    evmAlias.get(),
                                                    1L,
                                                    0L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias",
                                                    SUCCESS)
                                            .getFirst(),
                                    tokenTransferTo_ECDSA_Alias)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxnAllAccounts")
                            .hasKnownStatus(SUCCESS);

                    var batchTxnRecord = getTxnRecord("createHollowAccountWithCryptoTransferToAlias")
                            .andAllChildRecords()
                            .logged();

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxnAllAccounts", BASE_FEE_BATCH_TRANSACTION);

                    // validate the hollow account creation and transfers resulting in accumulated HBAR amount
                    final var infoCheck = getAliasedAccountInfo(evmAlias.get())
                            .isHollow()
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .balance(2L)
                                    .noAlias()
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                            .hasNoTokenRelationship(NFT_FOR_AUTO_ACCOUNT)
                            .hasOwnedNfts(0L)
                            .logged();

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 10L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L);

                    allRunFor(
                            spec,
                            atomicBatchTransaction,
                            batchTxnRecord,
                            batchTxnFeeCheck,
                            infoCheck,
                            senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create Account from Public Key and Transfer to evm alias derived from the same ECDSA Public Key "
            + "success in Atomic Batch")
    Stream<DynamicTest> autoCreatePublicKeyAccountAndTransferToEvmAliasFromTheSameECDSA_InBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        // create transfer to ECDSA alias inner transaction
        final var tokenTransferTo_ECDSA_Alias = cryptoTransfer(movingHbar(1L).between(OWNER, VALID_ALIAS_ECDSA))
                .payingWith(OWNER)
                .via("cryptoTransferTo_ECDSA_Alias")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),
                registerEvmAddressAliasFrom(VALID_ALIAS_ECDSA, evmAlias),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 3),

                // Associate and supply tokens to accounts
                tokenAssociate(CIVILIAN, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                cryptoTransfer(
                                moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, CIVILIAN),
                                movingUnique(NFT_FOR_AUTO_ACCOUNT, 1L, 2L).between(OWNER, CIVILIAN))
                        .payingWith(OWNER)
                        .via("associateAndSupplyTokens"),
                withOpContext((spec, opLog) -> {

                    // Create hollow account and transfer to the same ECDSA key in one atomic batch
                    final var atomicBatchTransaction = atomicBatch(
                                    tokenTransferTo_ECDSA_Alias,
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_RealTransfersOnly(
                                                    CIVILIAN,
                                                    evmAlias.get(),
                                                    1L,
                                                    0L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias",
                                                    SUCCESS)
                                            .getFirst())
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxnAllAccounts")
                            .hasKnownStatus(SUCCESS);

                    var batchTxnRecord = getTxnRecord("createHollowAccountWithCryptoTransferToAlias")
                            .andAllChildRecords()
                            .logged();

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxnAllAccounts", BASE_FEE_BATCH_TRANSACTION);

                    // validate the hollow account creation and transfers resulting in accumulated HBAR amount
                    final var infoCheck = getAliasedAccountInfo(evmAlias.get())
                            .isNotHollow()
                            .has(accountWith()
                                    .key(VALID_ALIAS_ECDSA)
                                    .alias(VALID_ALIAS_ECDSA)
                                    .balance(2L)
                                    .maxAutoAssociations(-1)
                                    .memo(AUTO_MEMO))
                            .hasNoTokenRelationship(FT_FOR_AUTO_ACCOUNT)
                            .hasNoTokenRelationship(NFT_FOR_AUTO_ACCOUNT)
                            .hasOwnedNfts(0L)
                            .logged();

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 10L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L);

                    allRunFor(
                            spec,
                            atomicBatchTransaction,
                            batchTxnRecord,
                            batchTxnFeeCheck,
                            infoCheck,
                            senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create Account from Transfer to Invalid evm alias fails in Batch ")
    Stream<DynamicTest> autoCreateAccountFromTransferToInvalidEvmAliasFailsInBatch_Parametrized() {

        record InvalidAliasCase(String description, byte[] invalidAliasBytes) {}

        final List<InvalidAliasCase> invalidAliasCases = List.of(
                new InvalidAliasCase("Too Short EVM Alias", new byte[] {0x12, 0x34}),
                new InvalidAliasCase("Too Long EVM Alias", new byte[64]),
                new InvalidAliasCase("Non-hex EVM Alias", "invalid-alias".getBytes()));

        return invalidAliasCases.stream().flatMap(testCase -> {
            final var invalidEvmAliasBytes = ByteString.copyFrom(testCase.invalidAliasBytes());
            final var transferTxn =
                    "cryptoTransferToInvalidEvmAlias_" + testCase.description().replaceAll("\\s+", "_");
            final var batchTxn =
                    "batchTxnInvalidEvmAlias_" + testCase.description().replaceAll("\\s+", "_");

            final var tokenTransferToInvalidEvm_Alias = cryptoTransfer(
                            moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, invalidEvmAliasBytes))
                    .payingWith(OWNER)
                    .via(transferTxn)
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_ALIAS_KEY);

            return hapiTest(flattened(
                    // create keys and accounts register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    withOpContext((spec, opLog) -> {

                        // Transfer to invalid evm alias in atomic batch
                        final var atomicBatchTransaction = atomicBatch(tokenTransferToInvalidEvm_Alias)
                                .payingWith(BATCH_OPERATOR)
                                .via(batchTxn)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        var batchTxnRecord = getTxnRecord(transferTxn)
                                .andAllChildRecords()
                                .hasPriority(recordWith().status(INVALID_ALIAS_KEY))
                                .logged();

                        final var batchTxnFeeCheck = validateChargedUsd(batchTxn, BASE_FEE_BATCH_TRANSACTION);

                        final var invalidAliasCheck = getAliasedAccountInfo(invalidEvmAliasBytes)
                                .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                                .logged();

                        // validate sender account balance after transfers
                        final var senderBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 100L);

                        allRunFor(
                                spec,
                                atomicBatchTransaction,
                                batchTxnRecord,
                                batchTxnFeeCheck,
                                invalidAliasCheck,
                                senderBalanceCheck);
                    })));
        });
    }

    @HapiTest
    @DisplayName("Auto Create Account from Transfer to Invalid ECDSA and ED25519 public keys fails in Batch ")
    Stream<DynamicTest> autoCreateAccountFromTransferToInvalidPublicKeysFailsInBatch_Parametrized() {

        record InvalidKeyCase(String description, byte[] invalidKeyBytes) {}

        final List<InvalidKeyCase> invalidKeyCases = List.of(
                new InvalidKeyCase("Too Short ECDSA", Hex.decode("03abcd")),
                new InvalidKeyCase("Too Long ECDSA", new byte[40]),
                new InvalidKeyCase("ECDSA Invalid Bytes", "not-a-key-123".getBytes()),
                new InvalidKeyCase("Malformed ED25519 - wrong prefix", Hex.decode("0a2101abcdef")),
                new InvalidKeyCase("Too Short ED25519", Hex.decode("0a2001")));

        return invalidKeyCases.stream().flatMap(testCase -> {
            final var invalidKeyBytes = ByteString.copyFrom(testCase.invalidKeyBytes());
            final var transferTxn =
                    "cryptoTransferToInvalidKeyAlias_" + testCase.description().replaceAll("\\s+", "_");
            final var batchTxn =
                    "batchTxnInvalidKeyAlias_" + testCase.description().replaceAll("\\s+", "_");

            final var tokenTransferToInvalidKey_Alias = cryptoTransfer(
                            moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, invalidKeyBytes))
                    .payingWith(OWNER)
                    .via(transferTxn)
                    .batchKey(BATCH_OPERATOR)
                    .hasKnownStatus(INVALID_ALIAS_KEY);

            return hapiTest(flattened(
                    // create keys and accounts register alias
                    createAccountsAndKeys(),

                    // create and mint tokens
                    createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                    withOpContext((spec, opLog) -> {

                        // Transfer to invalid evm alias in atomic batch
                        final var atomicBatchTransaction = atomicBatch(tokenTransferToInvalidKey_Alias)
                                .payingWith(BATCH_OPERATOR)
                                .via(batchTxn)
                                .hasKnownStatus(INNER_TRANSACTION_FAILED);

                        var batchTxnRecord = getTxnRecord(transferTxn)
                                .andAllChildRecords()
                                .hasPriority(recordWith().status(INVALID_ALIAS_KEY))
                                .logged();

                        final var batchTxnFeeCheck = validateChargedUsd(batchTxn, BASE_FEE_BATCH_TRANSACTION);

                        final var invalidAliasCheck = getAliasedAccountInfo(invalidKeyBytes)
                                .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                                .logged();

                        // validate sender account balance after transfers
                        final var senderBalanceCheck =
                                getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 100L);

                        allRunFor(
                                spec,
                                atomicBatchTransaction,
                                batchTxnRecord,
                                batchTxnFeeCheck,
                                invalidAliasCheck,
                                senderBalanceCheck);
                    })));
        });
    }

    // transfer with 0 HBAR amount
    @HapiTest
    @DisplayName("Auto Create ECDSA Public Key Account with 0 Transfer fails in Atomic Batch")
    Stream<DynamicTest> autoCreateECDSAKeyAccountWithZeroTransferFailsInBatch() {

        // create transfer to ECDSA alias inner transaction
        final var tokenTransferTo_ECDSA_Alias = cryptoTransfer(
                        moving(0L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ECDSA))
                .payingWith(OWNER)
                .via("cryptoTransferTo_ECDSA_Alias")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_ACCOUNT_ID);

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                withOpContext((spec, opLog) -> {

                    // Create atomic batch txn
                    final var atomicBatchTransaction = atomicBatch(tokenTransferTo_ECDSA_Alias)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION);

                    final var invalidAliasCheck = getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                            .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                            .logged();

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 100L);

                    allRunFor(spec, atomicBatchTransaction, batchTxnFeeCheck, invalidAliasCheck, senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create ED25519 Public Key Account with 0 Transfer fails in Atomic Batch")
    Stream<DynamicTest> autoCreateED25519KeyAccountWithZeroTransferFailsInBatch() {

        // create transfer to ED25519 alias inner transaction
        final var tokenTransferTo_ED25519_Alias = cryptoTransfer(movingHbar(0L).between(OWNER, VALID_ALIAS_ED25519))
                .payingWith(OWNER)
                .via("cryptoTransferTo_ED25519_Alias")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_ACCOUNT_ID);

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                withOpContext((spec, opLog) -> {

                    // Create atomic batch txn
                    final var atomicBatchTransaction = atomicBatch(tokenTransferTo_ED25519_Alias)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION);

                    final var invalidAliasCheck = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                            .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                            .logged();

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 100L);

                    allRunFor(spec, atomicBatchTransaction, batchTxnFeeCheck, invalidAliasCheck, senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create Hollow Account with 0 Transfer fails in Atomic Batch")
    Stream<DynamicTest> autoCreateHollowAccountWithZeroTransferFailsInBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        return hapiTest(flattened(
                // create keys and accounts register alias
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
                    final var transferZeroToHollow = cryptoTransfer(
                                    moving(0L, FT_FOR_AUTO_ACCOUNT).between(OWNER, evmAlias.get()))
                            .payingWith(OWNER)
                            .via("cryptoTransferTo_ECDSA_Alias");

                    // Create atomic batch txn
                    final var atomicBatchTransaction = atomicBatch(
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_AllowEmptyTransfers(
                                                    CIVILIAN,
                                                    evmAlias.get(),
                                                    0L,
                                                    0L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias" + VALID_ALIAS_HOLLOW,
                                                    INVALID_ACCOUNT_ID)
                                            .getFirst())
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION);

                    final var invalidAliasCheck = getAliasedAccountInfo(evmAlias.get())
                            .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                            .logged();

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(CIVILIAN)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 10L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L);

                    // validate sender account balance after transfers
                    final var ownerBalanceCheck = getAccountBalance(OWNER)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 90L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L);

                    allRunFor(
                            spec,
                            atomicBatchTransaction,
                            batchTxnFeeCheck,
                            invalidAliasCheck,
                            senderBalanceCheck,
                            ownerBalanceCheck);
                })));
    }

    // Insufficient funds for transfer
    @HapiTest
    @DisplayName("Auto Create ECDSA Public Key Account with Sender with Insufficient funds fails in Atomic Batch")
    Stream<DynamicTest> autoCreateECDSAKeyAccountWithInsufficientFundsSenderFailsInBatch() {

        // create transfer to ECDSA alias inner transaction
        final var tokenTransferTo_ECDSA_Alias = cryptoTransfer(
                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ECDSA))
                .payingWith(PAYER_NO_FUNDS)
                .via("cryptoTransferTo_ECDSA_Alias")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                withOpContext((spec, opLog) -> {

                    // Create atomic batch txn
                    final var atomicBatchTransaction = atomicBatch(tokenTransferTo_ECDSA_Alias)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE);

                    final var invalidAliasCheck = getAliasedAccountInfo(VALID_ALIAS_ECDSA)
                            .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                            .logged();

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 100L);

                    allRunFor(spec, atomicBatchTransaction, invalidAliasCheck, senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create ED25519 Public Key Account with Sender with Insufficient funds fails in Atomic Batch")
    Stream<DynamicTest> autoCreateED25519KeyAccountWithInsufficientFundsSenderFailsInBatch() {

        // create transfer to ED25519 alias inner transaction
        final var tokenTransferTo_ED25519_Alias = cryptoTransfer(
                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, VALID_ALIAS_ED25519))
                .payingWith(PAYER_NO_FUNDS)
                .via("cryptoTransferTo_ED25519_Alias")
                .batchKey(BATCH_OPERATOR);

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                withOpContext((spec, opLog) -> {

                    // Create atomic batch txn
                    final var atomicBatchTransaction = atomicBatch(tokenTransferTo_ED25519_Alias)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE);

                    final var invalidAliasCheck = getAliasedAccountInfo(VALID_ALIAS_ED25519)
                            .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                            .logged();

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 100L);

                    allRunFor(spec, atomicBatchTransaction, invalidAliasCheck, senderBalanceCheck);
                })));
    }

    @HapiTest
    @DisplayName("Auto Create Hollow Account with Sender with Insufficient funds fails in Atomic Batch")
    Stream<DynamicTest> autoCreateHollowAccountWithInsufficientFundsSenderFailsInBatch() {

        final AtomicReference<ByteString> evmAlias = new AtomicReference<>();

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),
                registerEvmAddressAliasFrom(VALID_ALIAS_HOLLOW, evmAlias),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                createImmutableNFT(NFT_FOR_AUTO_ACCOUNT, OWNER, nftSupplyKey),
                mintNFT(NFT_FOR_AUTO_ACCOUNT, 0, 5),

                // Associate and supply tokens to accounts
                tokenAssociate(PAYER_NO_FUNDS, FT_FOR_AUTO_ACCOUNT, NFT_FOR_AUTO_ACCOUNT),
                cryptoTransfer(
                                moving(10L, FT_FOR_AUTO_ACCOUNT).between(OWNER, PAYER_NO_FUNDS),
                                movingUnique(NFT_FOR_AUTO_ACCOUNT, 3L, 4L).between(OWNER, PAYER_NO_FUNDS))
                        .payingWith(OWNER)
                        .via("associateAndSupplyTokens"),
                withOpContext((spec, opLog) -> {

                    // Create atomic batch txn
                    final var atomicBatchTransaction = atomicBatch(
                                    createHollowAccountWithCryptoTransferWithBatchKeyToAlias_AllowEmptyTransfers(
                                                    PAYER_NO_FUNDS,
                                                    evmAlias.get(),
                                                    0L,
                                                    0L,
                                                    FT_FOR_AUTO_ACCOUNT,
                                                    List.of(), // NFT serials
                                                    NFT_FOR_AUTO_ACCOUNT,
                                                    "createHollowAccountWithCryptoTransferToAlias" + VALID_ALIAS_HOLLOW,
                                                    INSUFFICIENT_PAYER_BALANCE)
                                            .getFirst())
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE);

                    final var invalidAliasCheck = getAliasedAccountInfo(evmAlias.get())
                            .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                            .logged();

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(PAYER_NO_FUNDS)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 10L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 2L);

                    // validate sender account balance after transfers
                    final var ownerBalanceCheck = getAccountBalance(OWNER)
                            .hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 90L)
                            .hasTokenBalance(NFT_FOR_AUTO_ACCOUNT, 3L);

                    allRunFor(spec, atomicBatchTransaction, invalidAliasCheck, senderBalanceCheck, ownerBalanceCheck);
                })));
    }

    // valid length alias that is not recoverable from a public key
    @HapiTest
    @DisplayName("Auto Create Account with Unrecoverable Valid Alias fails in Atomic Batch")
    Stream<DynamicTest> autoCreateAccountWithUnrecoverableValidAliasFailsInBatch() {

        final var aliasBytes = ByteString.copyFrom(new byte[20]); // valid length but not recoverable

        // create transfer inner transaction
        final var tokenTransferToUnrecoverableAlias = cryptoTransfer(
                        moving(1L, FT_FOR_AUTO_ACCOUNT).between(OWNER, aliasBytes))
                .payingWith(OWNER)
                .via("cryptoTransferTo_Unrecoverable_Alias")
                .batchKey(BATCH_OPERATOR)
                .hasKnownStatus(INVALID_ALIAS_KEY);

        return hapiTest(flattened(
                // create keys and accounts register alias
                createAccountsAndKeys(),

                // create and mint tokens
                createMutableFT(FT_FOR_AUTO_ACCOUNT, OWNER, adminKey),
                withOpContext((spec, opLog) -> {

                    // Create atomic batch txn
                    final var atomicBatchTransaction = atomicBatch(tokenTransferToUnrecoverableAlias)
                            .payingWith(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED);

                    final var batchTxnFeeCheck = validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION);

                    final var invalidAliasCheck = getAliasedAccountInfo(aliasBytes)
                            .hasCostAnswerPrecheck(INVALID_ACCOUNT_ID)
                            .logged();

                    // validate sender account balance after transfers
                    final var senderBalanceCheck = getAccountBalance(OWNER).hasTokenBalance(FT_FOR_AUTO_ACCOUNT, 100L);

                    allRunFor(spec, atomicBatchTransaction, batchTxnFeeCheck, invalidAliasCheck, senderBalanceCheck);
                })));
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

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HBAR),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(3),
                cryptoCreate(PAYER_NO_FUNDS).balance(0L),
                newKeyNamed(VALID_ALIAS_ED25519).shape(KeyShape.ED25519),
                newKeyNamed(VALID_ALIAS_ECDSA).shape(SECP_256K1_SHAPE),
                newKeyNamed(VALID_ALIAS_HOLLOW).shape(SECP_256K1_SHAPE),
                newKeyNamed(adminKey),
                newKeyNamed(nftSupplyKey));
    }

    /**
     * Tests for atomic batch auto account updates.
     * These tests verify that auto-created accounts can be properly updated within atomic batches.
     */
    @Nested
    @DisplayName("Atomic batch auto account update")
    @HapiTestLifecycle
    class AtomicAutoAccountUpdate {

        private static final String PAYER = "payer";
        private static final String ALIAS = "testAlias";
        private static final String TRANSFER_TXN = "transferTxn";
        private static final String TRANSFER_TXN_2 = "transferTxn2";
        private static final String TRANSFER_TXN_3 = "transferTxn3";
        private static final long INITIAL_BALANCE = 1000L;

        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
            testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
        }

        @HapiTest
        final Stream<DynamicTest> modifySigRequiredAfterAutoAccountCreation() {
            return hapiTest(
                    newKeyNamed(ALIAS),
                    cryptoCreate(PAYER).balance(INITIAL_BALANCE * ONE_HBAR),
                    atomicBatch(cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                                    .via(TRANSFER_TXN)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    withOpContext((spec, opLog) -> AutoCreateUtils.updateSpecFor(spec, ALIAS)),
                    getTxnRecord(TRANSFER_TXN)
                            .andAllChildRecords()
                            .hasNonStakingChildRecordCount(1)
                            .hasNoAliasInChildRecord(0)
                            .logged(),
                    getAliasedAccountInfo(ALIAS)
                            .has(accountWith()
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .expectedBalanceWithChargedUsd((ONE_HUNDRED_HBARS), 0, 0)),
                    cryptoUpdateAliased(ALIAS).receiverSigRequired(true).signedBy(ALIAS, PAYER, DEFAULT_PAYER),
                    getAliasedAccountInfo(ALIAS)
                            .has(accountWith()
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(true)
                                    .expectedBalanceWithChargedUsd((ONE_HUNDRED_HBARS), 0, 0)),
                    atomicBatch(cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                                    .via(TRANSFER_TXN_2)
                                    .signedBy(PAYER, DEFAULT_PAYER)
                                    .hasKnownStatus(INVALID_SIGNATURE)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    atomicBatch(cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                                    .via(TRANSFER_TXN_3)
                                    .signedBy(ALIAS, PAYER, DEFAULT_PAYER)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getTxnRecord(TRANSFER_TXN_3).andAllChildRecords().hasNonStakingChildRecordCount(0),
                    getAliasedAccountInfo(ALIAS)
                            .has(accountWith().expectedBalanceWithChargedUsd((2 * ONE_HUNDRED_HBARS), 0, 0)));
        }

        @HapiTest
        final Stream<DynamicTest> updateKeyOnAutoCreatedAccount() {
            final var complexKey = "complexKey";

            SigControl ENOUGH_UNIQUE_SIGS = KeyShape.threshSigs(
                    2,
                    KeyShape.threshSigs(1, OFF, OFF, OFF, OFF, OFF, OFF, ON),
                    KeyShape.threshSigs(3, ON, ON, ON, OFF, OFF, OFF, OFF));

            return hapiTest(
                    newKeyNamed(ALIAS),
                    newKeyNamed(complexKey).shape(ENOUGH_UNIQUE_SIGS),
                    cryptoCreate(PAYER).balance(INITIAL_BALANCE * ONE_HBAR),
                    atomicBatch(cryptoTransfer(tinyBarsFromToWithAlias(PAYER, ALIAS, ONE_HUNDRED_HBARS))
                                    .payingWith(PAYER)
                                    .via(TRANSFER_TXN)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    withOpContext((spec, opLog) -> AutoCreateUtils.updateSpecFor(spec, ALIAS)),
                    getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
                    getAliasedAccountInfo(ALIAS)
                            .has(accountWith()
                                    .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)
                                    .alias(ALIAS)),
                    cryptoUpdateAliased(ALIAS)
                            .key(complexKey)
                            .payingWith(PAYER)
                            .signedBy(ALIAS, complexKey, PAYER, DEFAULT_PAYER),
                    getAliasedAccountInfo(ALIAS)
                            .has(accountWith()
                                    .expectedBalanceWithChargedUsd((ONE_HUNDRED_HBARS), 0, 0)
                                    .key(complexKey)));
        }
    }

    /**
     * Tests for atomic batch auto account creation with unlimited associations.
     * These tests verify proper handling of accounts with unlimited token associations.
     */
    @Nested
    @DisplayName("Atomic batch unlimited associations")
    @HapiTestLifecycle
    class AtomicAutoAccountCreationUnlimitedAssociations {

        private static final String TRUE_VALUE = "true";
        private static final String FALSE_VALUE = "false";
        private static final String LAZY_MEMO = "";
        private static final String VALID_ALIAS = "validAlias";
        private static final String A_TOKEN = "tokenA";
        private static final String PARTY = "party";
        private static final String PAYER = "payer";
        private static final String TRANSFER_TXN = "transferTxn";
        private static final String MULTI_KEY = "multi";
        private static final long INITIAL_BALANCE = 1000L;
        private static final String AUTO_MEMO = "";
        private static final String SPONSOR = "autoCreateSponsor";
        private static final long EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE = 39_376_619L;
        private static final String HBAR_XFER = "hbarXfer";
        private static final String FT_XFER = "ftXfer";
        private static final String NFT_XFER = "nftXfer";
        private static final String NFT_CREATE = "nftCreateTxn";
        private static final String NFT_INFINITE_SUPPLY_TOKEN = "nftA";

        @BeforeAll
        static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
            testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
        }

        @HapiTest
        final Stream<DynamicTest> autoAccountCreationsUnlimitedAssociationHappyPath() {
            final var creationTime = new AtomicLong();
            final long transferFee = 188608L;
            return customizedHapiTest(
                    Map.of("memo.useSpecName", "false"),
                    newKeyNamed(VALID_ALIAS),
                    cryptoCreate(CIVILIAN).balance(10 * ONE_HBAR),
                    cryptoCreate(PAYER).balance(10 * ONE_HBAR),
                    cryptoCreate(SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                    atomicBatch(cryptoTransfer(
                                            tinyBarsFromToWithAlias(SPONSOR, VALID_ALIAS, ONE_HUNDRED_HBARS),
                                            tinyBarsFromToWithAlias(CIVILIAN, VALID_ALIAS, ONE_HBAR))
                                    .via(TRANSFER_TXN)
                                    .payingWith(PAYER)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getReceipt(TRANSFER_TXN).andAnyChildReceipts().hasChildAutoAccountCreations(1),
                    getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
                    getAccountInfo(SPONSOR)
                            .has(accountWith()
                                    .balance((INITIAL_BALANCE * ONE_HBAR) - ONE_HUNDRED_HBARS)
                                    .noAlias()),
                    childRecordsCheck(
                            TRANSFER_TXN,
                            SUCCESS,
                            recordWith().status(SUCCESS).fee(EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE)),
                    assertionsHold((spec, opLog) -> {
                        final var lookup = getTxnRecord(TRANSFER_TXN)
                                .andAllChildRecords()
                                .hasNonStakingChildRecordCount(1)
                                .hasNoAliasInChildRecord(0)
                                .logged();
                        allRunFor(spec, lookup);
                        final var sponsor = spec.registry().getAccountID(SPONSOR);
                        final var payer = spec.registry().getAccountID(PAYER);
                        final var parent = lookup.getResponseRecord();
                        var child = lookup.getChildRecord(0);
                        if (isEndOfStakingPeriodRecord(child)) {
                            child = lookup.getChildRecord(1);
                        }
                        assertAliasBalanceAndFeeInChildRecord(
                                parent, child, sponsor, payer, ONE_HUNDRED_HBARS + ONE_HBAR, transferFee, 0);
                        creationTime.set(child.getConsensusTimestamp().getSeconds());
                    }),
                    sourcing(() -> getAliasedAccountInfo(VALID_ALIAS)
                            .has(accountWith()
                                    .key(VALID_ALIAS)
                                    .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS + ONE_HBAR, 0, 0)
                                    .alias(VALID_ALIAS)
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .expiry(creationTime.get() + THREE_MONTHS_IN_SECONDS, 0)
                                    .memo(AUTO_MEMO)
                                    .maxAutoAssociations(-1))
                            .logged()));
        }

        @LeakyHapiTest(overrides = {"entities.unlimitedAutoAssociationsEnabled"})
        final Stream<DynamicTest> autoAccountCreationsUnlimitedAssociationsDisabled() {
            final var creationTime = new AtomicLong();
            final long transferFee = 188608L;
            return customizedHapiTest(
                    Map.of("memo.useSpecName", "false"),
                    overriding("entities.unlimitedAutoAssociationsEnabled", FALSE_VALUE),
                    newKeyNamed(VALID_ALIAS),
                    cryptoCreate(CIVILIAN).balance(10 * ONE_HBAR),
                    cryptoCreate(PAYER).balance(10 * ONE_HBAR),
                    cryptoCreate(SPONSOR).balance(INITIAL_BALANCE * ONE_HBAR),
                    atomicBatch(cryptoTransfer(
                                            tinyBarsFromToWithAlias(SPONSOR, VALID_ALIAS, ONE_HUNDRED_HBARS),
                                            tinyBarsFromToWithAlias(CIVILIAN, VALID_ALIAS, ONE_HBAR))
                                    .via(TRANSFER_TXN)
                                    .payingWith(PAYER)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR),
                    getReceipt(TRANSFER_TXN).andAnyChildReceipts().hasChildAutoAccountCreations(1),
                    getTxnRecord(TRANSFER_TXN).andAllChildRecords().logged(),
                    getAccountInfo(SPONSOR)
                            .has(accountWith()
                                    .balance((INITIAL_BALANCE * ONE_HBAR) - ONE_HUNDRED_HBARS)
                                    .noAlias()),
                    childRecordsCheck(
                            TRANSFER_TXN,
                            SUCCESS,
                            recordWith().status(SUCCESS).fee(EXPECTED_HBAR_TRANSFER_AUTO_CREATION_FEE)),
                    assertionsHold((spec, opLog) -> {
                        final var lookup = getTxnRecord(TRANSFER_TXN)
                                .andAllChildRecords()
                                .hasNonStakingChildRecordCount(1)
                                .hasNoAliasInChildRecord(0)
                                .logged();
                        allRunFor(spec, lookup);
                        final var sponsor = spec.registry().getAccountID(SPONSOR);
                        final var payer = spec.registry().getAccountID(PAYER);
                        final var parent = lookup.getResponseRecord();
                        var child = lookup.getChildRecord(0);
                        if (isEndOfStakingPeriodRecord(child)) {
                            child = lookup.getChildRecord(1);
                        }
                        assertAliasBalanceAndFeeInChildRecord(
                                parent, child, sponsor, payer, ONE_HUNDRED_HBARS + ONE_HBAR, transferFee, 0);
                        creationTime.set(child.getConsensusTimestamp().getSeconds());
                    }),
                    sourcing(() -> getAliasedAccountInfo(VALID_ALIAS)
                            .has(accountWith()
                                    .key(VALID_ALIAS)
                                    .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS + ONE_HBAR, 0, 0)
                                    .alias(VALID_ALIAS)
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .expiry(creationTime.get() + THREE_MONTHS_IN_SECONDS, 0)
                                    .memo(AUTO_MEMO)
                                    .maxAutoAssociations(0))
                            .logged()));
        }

        @HapiTest
        final Stream<DynamicTest> transferHbarsToEVMAddressAliasUnlimitedAssociations() {
            final AtomicReference<AccountID> partyId = new AtomicReference<>();
            final AtomicReference<byte[]> partyAlias = new AtomicReference<>();
            final AtomicReference<byte[]> counterAlias = new AtomicReference<>();

            return hapiTest(
                    cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    withOpContext((spec, opLog) -> {
                        final var registry = spec.registry();
                        final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                        final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                        final var addressBytes = recoverAddressFromPubKey(tmp);
                        assert addressBytes != null;
                        partyId.set(registry.getAccountID(PARTY));
                        partyAlias.set(asSolidityAddress(partyId.get()));
                        counterAlias.set(addressBytes);
                    }),
                    withOpContext((spec, opLog) -> {
                        final var counterAliasStr = ByteString.copyFrom(counterAlias.get());

                        var accountCreate = cryptoCreate("testAccount")
                                .key(SECP_256K1_SOURCE_KEY)
                                .maxAutomaticTokenAssociations(-1)
                                .alias(counterAliasStr);

                        var getAccountInfoOp = getAccountInfo("testAccount")
                                .has(accountWith().key(SECP_256K1_SOURCE_KEY).maxAutoAssociations(-1));

                        var accountDelete = cryptoDelete("testAccount").hasKnownStatus(SUCCESS);

                        allRunFor(spec, accountCreate, getAccountInfoOp, accountDelete);

                        var hollowAccount = cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                        .addAccountAmounts(Utils.aaWith(s, partyAlias.get(), -2 * ONE_HBAR))
                                        .addAccountAmounts(Utils.aaWith(s, counterAlias.get(), +2 * ONE_HBAR))))
                                .signedBy(DEFAULT_PAYER, PARTY)
                                .via(HBAR_XFER);

                        var hollowAccountCreated = getAliasedAccountInfo(counterAliasStr)
                                .has(accountWith()
                                        .expectedBalanceWithChargedUsd(2 * ONE_HBAR, 0, 0)
                                        .hasEmptyKey()
                                        .noAlias()
                                        .autoRenew(THREE_MONTHS_IN_SECONDS)
                                        .receiverSigReq(false)
                                        .maxAutoAssociations(-1)
                                        .memo(LAZY_MEMO));

                        final var txnRequiringHollowAccountSignature = tokenCreate(A_TOKEN)
                                .adminKey(SECP_256K1_SOURCE_KEY)
                                .signedBy(SECP_256K1_SOURCE_KEY)
                                .hasPrecheck(INVALID_SIGNATURE);

                        final HapiGetTxnRecord hapiGetTxnRecord =
                                getTxnRecord(HBAR_XFER).andAllChildRecords().assertingNothingAboutHashes();

                        allRunFor(
                                spec,
                                hollowAccount,
                                hollowAccountCreated,
                                txnRequiringHollowAccountSignature,
                                hapiGetTxnRecord);

                        if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {
                            final var newAccountID = hapiGetTxnRecord
                                    .getFirstNonStakingChildRecord()
                                    .getReceipt()
                                    .getAccountID();
                            spec.registry().saveAccountId(SECP_256K1_SOURCE_KEY, newAccountID);
                        }

                        final var completion = atomicBatch(
                                        cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, DEFAULT_CONTRACT_RECEIVER, 1))
                                                .hasKnownStatusFrom(SUCCESS)
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(SECP_256K1_SOURCE_KEY)
                                .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                                .signedBy(SECP_256K1_SOURCE_KEY, BATCH_OPERATOR);
                        allRunFor(spec, completion);

                        var completedAccount = getAliasedAccountInfo(ByteString.copyFrom(counterAlias.get()))
                                .has(accountWith()
                                        .key(SECP_256K1_SOURCE_KEY)
                                        .noAlias()
                                        .autoRenew(THREE_MONTHS_IN_SECONDS)
                                        .receiverSigReq(false)
                                        .maxAutoAssociations(-1)
                                        .newAssociationsFromSnapshot(SECP_256K1_SOURCE_KEY, Collections.EMPTY_LIST)
                                        .memo(LAZY_MEMO));

                        allRunFor(spec, completedAccount);
                    }),
                    getTxnRecord(HBAR_XFER)
                            .hasNonStakingChildRecordCount(1)
                            .hasChildRecords(recordWith().status(SUCCESS).memo(LAZY_MEMO)),
                    cryptoTransfer(tinyBarsFromToWithAlias(PARTY, SECP_256K1_SOURCE_KEY, ONE_HBAR))
                            .hasKnownStatus(SUCCESS)
                            .via(TRANSFER_TXN),
                    getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                            .has(accountWith().maxAutoAssociations(-1)));
        }

        @HapiTest
        @Tag(MATS)
        final Stream<DynamicTest> transferNftToEVMAddressAliasUnlimitedAssociations() {
            final AtomicReference<AccountID> partyId = new AtomicReference<>();
            final AtomicReference<byte[]> partyAlias = new AtomicReference<>();
            final AtomicReference<AccountID> treasuryId = new AtomicReference<>();
            final AtomicReference<byte[]> counterAlias = new AtomicReference<>();
            final AtomicReference<TokenID> nftId = new AtomicReference<>();

            return hapiTest(
                    cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                    cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(2),
                    newKeyNamed(MULTI_KEY),
                    tokenCreate(NFT_INFINITE_SUPPLY_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .adminKey(MULTI_KEY)
                            .supplyKey(MULTI_KEY)
                            .supplyType(TokenSupplyType.INFINITE)
                            .initialSupply(0)
                            .treasury(TOKEN_TREASURY)
                            .via(NFT_CREATE),
                    mintToken(
                            NFT_INFINITE_SUPPLY_TOKEN,
                            List.of(ByteString.copyFromUtf8("a"), ByteString.copyFromUtf8("b"))),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    withOpContext((spec, opLog) -> {
                        final var registry = spec.registry();
                        final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                        final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                        final var addressBytes = recoverAddressFromPubKey(tmp);
                        assert addressBytes != null;
                        counterAlias.set(addressBytes);
                        nftId.set(registry.getTokenID(NFT_INFINITE_SUPPLY_TOKEN));
                        partyId.set(registry.getAccountID(PARTY));
                        treasuryId.set(registry.getAccountID(TOKEN_TREASURY));
                        partyAlias.set(asSolidityAddress(partyId.get()));
                    }),
                    withOpContext((spec, opLog) -> {
                        final var counterAliasStr = ByteString.copyFrom(counterAlias.get());
                        var accountCreate = atomicBatch(cryptoCreate("testAccount")
                                        .key(SECP_256K1_SOURCE_KEY)
                                        .maxAutomaticTokenAssociations(-1)
                                        .alias(counterAliasStr)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);

                        var getAccountInfoOp = getAccountInfo("testAccount")
                                .hasAlreadyUsedAutomaticAssociations(0)
                                .has(accountWith().key(SECP_256K1_SOURCE_KEY).maxAutoAssociations(-1));

                        var accountDelete = cryptoDelete("testAccount").hasKnownStatus(SUCCESS);
                        allRunFor(spec, accountCreate, getAccountInfoOp, accountDelete);

                        final var hollowAccount = atomicBatch(
                                        cryptoTransfer((s, b) -> b.addTokenTransfers(TokenTransferList.newBuilder()
                                                        .setToken(nftId.get())
                                                        .addNftTransfers(NftTransfer.newBuilder()
                                                                .setSerialNumber(1L)
                                                                .setSenderAccountID(treasuryId.get())
                                                                .setReceiverAccountID(asIdWithAlias(counterAliasStr)))))
                                                .signedBy(MULTI_KEY, DEFAULT_PAYER, TOKEN_TREASURY)
                                                .via(NFT_XFER)
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);

                        var hollowAccountCreated = getAliasedAccountInfo(counterAliasStr)
                                .has(accountWith()
                                        .hasEmptyKey()
                                        .noAlias()
                                        .autoRenew(THREE_MONTHS_IN_SECONDS)
                                        .receiverSigReq(false)
                                        .maxAutoAssociations(-1)
                                        .memo(LAZY_MEMO));

                        final HapiGetTxnRecord hapiGetTxnRecord =
                                getTxnRecord(NFT_XFER).andAllChildRecords().assertingNothingAboutHashes();

                        allRunFor(spec, hollowAccount, hollowAccountCreated, hapiGetTxnRecord);

                        if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {
                            final var newAccountID = hapiGetTxnRecord
                                    .getFirstNonStakingChildRecord()
                                    .getReceipt()
                                    .getAccountID();
                            spec.registry().saveAccountId(SECP_256K1_SOURCE_KEY, newAccountID);
                        }

                        var hollowAccountTransfer = atomicBatch(
                                        cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                                        .addAccountAmounts(
                                                                Utils.aaWith(s, partyAlias.get(), -2 * ONE_HBAR))
                                                        .addAccountAmounts(
                                                                Utils.aaWith(s, counterAlias.get(), +2 * ONE_HBAR))))
                                                .signedBy(DEFAULT_PAYER, PARTY)
                                                .via(HBAR_XFER)
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);

                        final var completion = atomicBatch(
                                        cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, DEFAULT_CONTRACT_RECEIVER, 1))
                                                .payingWith(SECP_256K1_SOURCE_KEY)
                                                .sigMapPrefixes(uniqueWithFullPrefixesFor(SECP_256K1_SOURCE_KEY))
                                                .hasKnownStatusFrom(SUCCESS)
                                                .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR);

                        final var completed = getAccountInfo(SECP_256K1_SOURCE_KEY)
                                .hasAlreadyUsedAutomaticAssociations(1)
                                .has(accountWith().maxAutoAssociations(-1).memo(LAZY_MEMO));

                        allRunFor(spec, hollowAccountTransfer, completion, completed);
                    }));
        }
    }
}
