// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.node.app.service.token.AliasUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
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
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
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
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class AtomicBatchAutoAccountCreationBasicTests {

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

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    @DisplayName("Auto Create ED25519 Account with FT Transfer success in Atomic Batch")
    public Stream<DynamicTest> autoCreateED25519AccountWithFT_TransferSuccessInBatch() {

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
    public Stream<DynamicTest> autoCreateECDSA_AccountWithFT_TransferSuccessInBatch() {

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
    public Stream<DynamicTest> autoCreateAccountWithHBAR_TransferSuccessInBatch_Parametrized() {
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
    public Stream<DynamicTest> autoCreateAccountWithFT_TransferSuccessInBatch_Parametrized() {
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
    public Stream<DynamicTest> autoCreateAccountWithNFT_TransferSuccessInBatch_Parametrized() {
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
    public Stream<DynamicTest> autoCreateAccountWithHBAR_FT_NFT_TransferSuccessInBatch_Parametrized() {
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
    public Stream<DynamicTest> autoCreateHollowAccountWithHBAR_TransferSuccessInBatch() {

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
    public Stream<DynamicTest> autoCreateHollowAccountWithFT_TransferSuccessInBatch() {

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
    public Stream<DynamicTest> autoCreateHollowAccountWithNFT_TransferSuccessInBatch() {

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
    public Stream<DynamicTest> autoCreateHollowAccountWithHBAR_FT_NFT_TransferSuccessInBatch() {

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
    public Stream<DynamicTest> autoCreatePublicKeyAndHollowAccountWithHBAR_TransferSuccessInBatch() {

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
    public Stream<DynamicTest> autoCreatePublicKeyAndHollowAccountWithFT_TransferSuccessInBatch() {

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
    public Stream<DynamicTest> autoCreatePublicKeyAndHollowAccountWithNFT_TransferSuccessInBatch() {

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
    public Stream<DynamicTest> autoCreateHollowAccountAndTransferToSameECDSA_InBatch() {

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
    public Stream<DynamicTest> autoCreatePublicKeyAccountAndTransferToEvmAliasFromTheSameECDSA_InBatch() {

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
    public Stream<DynamicTest> autoCreateAccountFromTransferToInvalidEvmAliasFailsInBatch_Parametrized() {

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
    public Stream<DynamicTest> autoCreateAccountFromTransferToInvalidPublicKeysFailsInBatch_Parametrized() {

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
    public Stream<DynamicTest> autoCreateECDSAKeyAccountWithZeroTransferFailsInBatch() {

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
    public Stream<DynamicTest> autoCreateED25519KeyAccountWithZeroTransferFailsInBatch() {

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
    public Stream<DynamicTest> autoCreateHollowAccountWithZeroTransferFailsInBatch() {

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
    public Stream<DynamicTest> autoCreateECDSAKeyAccountWithInsufficientFundsSenderFailsInBatch() {

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
    public Stream<DynamicTest> autoCreateED25519KeyAccountWithInsufficientFundsSenderFailsInBatch() {

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
    public Stream<DynamicTest> autoCreateHollowAccountWithInsufficientFundsSenderFailsInBatch() {

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
    public Stream<DynamicTest> autoCreateAccountWithUnrecoverableValidAliasFailsInBatch() {

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
}
