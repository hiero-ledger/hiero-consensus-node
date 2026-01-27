// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.ContextRequirement.FEE_SCHEDULE_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.reduceFeeFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCreateSuite.EMPTY_CONSTRUCTOR_CONTRACT;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.ANOTHER_SPENDER;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.FUNGIBLE_TOKEN;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.FUNGIBLE_TOKEN_MINT_TXN;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.NFT_TOKEN_MINT_TXN;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.NON_FUNGIBLE_TOKEN;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.OWNER;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.SECOND_SPENDER;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.SPENDER;
import static com.hedera.services.bdd.suites.crypto.CryptoApproveAllowanceSuite.THIRD_SPENDER;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.TRANSFER_TXN;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_ALLOWANCES_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.NodeStakeUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;

/**
 * Helper operations for isolated/leaky tests that previously lived in {@code LeakyCryptoTestsSuite}.
 *
 * <p>These methods are intentionally not JUnit tests; they are invoked by {@link IsolatedSuite}.
 */
final class LeakyCryptoIsolatedOps {
    private LeakyCryptoIsolatedOps() {}

    private static final Logger log = LogManager.getLogger(LeakyCryptoIsolatedOps.class);

    private static final String FACTORY_MIRROR_CONTRACT = "FactoryMirror";
    public static final String AUTO_ACCOUNT = "autoAccount";
    public static final String LAZY_ACCOUNT_RECIPIENT = "lazyAccountRecipient";
    public static final String PAY_TXN = "payTxn";
    public static final String CREATE_TX = "createTX";

    static Stream<DynamicTest> autoAssociationPropertiesWorkAsExpected() {
        final var shortLivedAutoAssocUser = "shortLivedAutoAssocUser";
        final var longLivedAutoAssocUser = "longLivedAutoAssocUser";
        final var payerBalance = 100 * ONE_HUNDRED_HBARS;
        final var updateWithExpiredAccount = "updateWithExpiredAccount";
        final var baseFee = 0.000214;
        return customizedHapiTest(
                Map.of("memo.useSpecName", "false"),
                overridingTwo("ledger.maxAutoAssociations", "100", "ledger.autoRenewPeriod.minDuration", "1"),
                cryptoCreate(longLivedAutoAssocUser).balance(payerBalance).autoRenewSecs(THREE_MONTHS_IN_SECONDS),
                cryptoCreate(shortLivedAutoAssocUser).balance(payerBalance).autoRenewSecs(1),
                cryptoUpdate(longLivedAutoAssocUser)
                        .payingWith(longLivedAutoAssocUser)
                        .maxAutomaticAssociations(101)
                        .hasKnownStatus(REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT),
                cryptoUpdate(shortLivedAutoAssocUser)
                        .payingWith(shortLivedAutoAssocUser)
                        .maxAutomaticAssociations(10)
                        .via(updateWithExpiredAccount),
                validateChargedUsd(updateWithExpiredAccount, baseFee, 5));
    }

    static Stream<DynamicTest> cannotDissociateFromExpiredTokenWithNonZeroBalance() {
        final var civilian = "civilian";
        final long initialSupply = 100L;
        final long nonZeroXfer = 10L;
        final var numTokens = 10;
        final IntFunction<String> tokenNameFn = i -> "fungible" + i;
        final String[] assocOrder = new String[numTokens];
        Arrays.setAll(assocOrder, tokenNameFn);
        final String[] dissocOrder = new String[numTokens];
        Arrays.setAll(dissocOrder, i -> tokenNameFn.apply(numTokens - 1 - i));

        return hapiTest(
                overriding("ledger.autoRenewPeriod.minDuration", "1"),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(civilian).balance(0L),
                blockingOrder(IntStream.range(0, numTokens)
                        .mapToObj(i -> tokenCreate(tokenNameFn.apply(i))
                                .autoRenewAccount(DEFAULT_PAYER)
                                .autoRenewPeriod(1L)
                                .initialSupply(initialSupply)
                                .treasury(TOKEN_TREASURY))
                        .toArray(HapiSpecOperation[]::new)),
                tokenAssociate(civilian, List.of(assocOrder)),
                blockingOrder(IntStream.range(0, numTokens)
                        .mapToObj(i -> cryptoTransfer(
                                moving(nonZeroXfer, tokenNameFn.apply(i)).between(TOKEN_TREASURY, civilian)))
                        .toArray(HapiSpecOperation[]::new)),
                sleepFor(2_000L),
                com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate(civilian, dissocOrder)
                        .hasKnownStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES));
    }

    static Stream<DynamicTest> cannotExceedAccountAllowanceLimit() {
        return hapiTest(
                overridingTwo("hedera.allowances.maxAccountLimit", "3", "hedera.allowances.maxTransactionLimit", "5"),
                newKeyNamed(com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .addCryptoAllowance(OWNER, SECOND_SPENDER, 100L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .fee(ONE_HBAR),
                com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails(OWNER)
                        .payingWith(GENESIS)
                        .has(com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith()
                                .cryptoAllowancesCount(2)
                                .tokenAllowancesCount(1)
                                .nftApprovedForAllAllowancesCount(0)),
                cryptoCreate(THIRD_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, ANOTHER_SPENDER, 100L)
                        .fee(ONE_HBAR)
                        .hasKnownStatus(MAX_ALLOWANCES_EXCEEDED));
    }

    static Stream<DynamicTest> cannotExceedAllowancesTransactionLimit() {
        return hapiTest(
                newKeyNamed(com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY),
                overridingTwo(
                        "hedera.allowances.maxTransactionLimit", "4",
                        "hedera.allowances.maxAccountLimit", "5"),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(ANOTHER_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_SPENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY).balance(100 * ONE_HUNDRED_HBARS).maxAutomaticTokenAssociations(10),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.FINITE)
                        .supplyKey(com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY)
                        .maxSupply(1000L)
                        .initialSupply(10L)
                        .treasury(TOKEN_TREASURY),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .maxSupply(10L)
                        .initialSupply(0)
                        .supplyType(TokenSupplyType.FINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .supplyKey(com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(OWNER, FUNGIBLE_TOKEN),
                tokenAssociate(OWNER, NON_FUNGIBLE_TOKEN),
                mintToken(
                                NON_FUNGIBLE_TOKEN,
                                List.of(
                                        ByteString.copyFromUtf8("a"),
                                        ByteString.copyFromUtf8("b"),
                                        ByteString.copyFromUtf8("c")))
                        .via(NFT_TOKEN_MINT_TXN),
                mintToken(FUNGIBLE_TOKEN, 500L).via(FUNGIBLE_TOKEN_MINT_TXN),
                cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L).between(TOKEN_TREASURY, OWNER)),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .addCryptoAllowance(OWNER, ANOTHER_SPENDER, 100L)
                        .addCryptoAllowance(OWNER, SECOND_SPENDER, 100L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L))
                        .hasPrecheckFrom(OK, MAX_ALLOWANCES_EXCEEDED)
                        .hasKnownStatus(MAX_ALLOWANCES_EXCEEDED),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addNftAllowance(OWNER, NON_FUNGIBLE_TOKEN, SPENDER, false, List.of(1L, 1L, 1L, 1L, 1L))
                        .hasPrecheckFrom(OK, MAX_ALLOWANCES_EXCEEDED)
                        .hasKnownStatus(MAX_ALLOWANCES_EXCEEDED),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .addCryptoAllowance(OWNER, SPENDER, 200L)
                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                        .addCryptoAllowance(OWNER, SPENDER, 200L)
                        .addCryptoAllowance(OWNER, SPENDER, 200L)
                        .hasPrecheckFrom(OK, MAX_ALLOWANCES_EXCEEDED)
                        .hasKnownStatus(MAX_ALLOWANCES_EXCEEDED),
                cryptoApproveAllowance()
                        .payingWith(OWNER)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, SPENDER, 100L)
                        .hasPrecheckFrom(OK, MAX_ALLOWANCES_EXCEEDED)
                        .hasKnownStatus(MAX_ALLOWANCES_EXCEEDED));
    }

    static Stream<DynamicTest> hollowAccountCreationChargesExpectedFees() {
        final long REDUCED_NODE_FEE = 2L;
        final long REDUCED_NETWORK_FEE = 3L;
        final long REDUCED_SERVICE_FEE = 3L;
        final long REDUCED_TOTAL_FEE = REDUCED_NODE_FEE + REDUCED_NETWORK_FEE + REDUCED_SERVICE_FEE;
        final var payer = "payer";
        final var secondKey = "secondKey";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(secondKey).shape(SECP_256K1_SHAPE),
                cryptoCreate(payer).balance(0L),
                reduceFeeFor(
                        List.of(
                                com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer,
                                com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate,
                                com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate),
                        REDUCED_NODE_FEE,
                        REDUCED_NETWORK_FEE,
                        REDUCED_SERVICE_FEE),
                withOpContext((spec, opLog) -> {
                    // crypto transfer fees check
                    final HapiCryptoTransfer transferToPayerAgain =
                            cryptoTransfer(tinyBarsFromTo(GENESIS, payer, ONE_HUNDRED_HBARS + 2 * REDUCED_TOTAL_FEE));
                    final var secondEvmAddress = ByteString.copyFrom(recoverAddressFromPubKey(spec.registry()
                            .getKey(secondKey)
                            .getECDSASecp256K1()
                            .toByteArray()));
                    // try to create the hollow account without having enough
                    // balance to pay for the finalization (CryptoUpdate) fee
                    final var op5 = cryptoTransfer(tinyBarsFromTo(payer, secondEvmAddress, ONE_HUNDRED_HBARS))
                            .payingWith(payer)
                            .hasKnownStatusFrom(INSUFFICIENT_PAYER_BALANCE, INSUFFICIENT_ACCOUNT_BALANCE)
                            .via(TRANSFER_TXN);
                    final var op5FeeAssertion = getTxnRecord(TRANSFER_TXN)
                            .logged()
                            .exposingTo(record -> {
                                Assertions.assertEquals(REDUCED_TOTAL_FEE, record.getTransactionFee());
                            });
                    final var notExistingAccountInfo =
                            getAliasedAccountInfo(secondKey)
                                    .hasCostAnswerPrecheck(com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID);
                    // transfer the needed balance for the finalization fee to the
                    // sponsor; we need + 2 * TOTAL_FEE, not 1, since we paid for
                    // the failed crypto transfer
                    final var op6 = cryptoTransfer(tinyBarsFromTo(GENESIS, payer, 2 * REDUCED_TOTAL_FEE));
                    // now the sponsor can successfully create the hollow account
                    final var op7 = cryptoTransfer(tinyBarsFromTo(payer, secondEvmAddress, ONE_HUNDRED_HBARS))
                            .payingWith(payer)
                            .via(TRANSFER_TXN);
                    final var op7FeeAssertion = getTxnRecord(TRANSFER_TXN)
                            .logged()
                            .andAllChildRecords()
                            .exposingTo(record -> {
                                Assertions.assertEquals(REDUCED_TOTAL_FEE, record.getTransactionFee());
                            });
                    final var op8 = getAliasedAccountInfo(secondKey)
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0)
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .memo(AutoAccountCreationSuite.LAZY_MEMO));
                    final var op9 = getAccountBalance(payer).hasTinyBars(0);
                    allRunFor(
                            spec,
                            transferToPayerAgain,
                            op5,
                            op5FeeAssertion,
                            notExistingAccountInfo,
                            op6,
                            op7,
                            op7FeeAssertion,
                            op8,
                            op9);
                }));
    }

    static Stream<DynamicTest> contractDeployAfterEthereumTransferLazyCreate() {
        final var RECIPIENT_KEY = LAZY_ACCOUNT_RECIPIENT;
        final var lazyCreateTxn = PAY_TXN;
        return hapiTest(
                overriding("contracts.evm.version", "v0.34"),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(RECIPIENT_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT),
                getTxnRecord(AUTO_ACCOUNT).andAllChildRecords(),
                uploadInitCode(FACTORY_MIRROR_CONTRACT),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        TxnVerbs.ethereumCryptoTransferToAlias(
                                        spec.registry().getKey(RECIPIENT_KEY).getECDSASecp256K1(), FIVE_HBARS)
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0L)
                                .maxFeePerGas(0L)
                                .maxGasAllowance(FIVE_HBARS)
                                .gasLimit(2_000_000L)
                                .via(lazyCreateTxn)
                                .hasKnownStatus(SUCCESS),
                        getTxnRecord(lazyCreateTxn).andAllChildRecords().logged())),
                withOpContext((spec, opLog) -> {
                    final var contractCreateTxn = contractCreate(FACTORY_MIRROR_CONTRACT).via(CREATE_TX).balance(20);

                    final var expectedTxnRecord = getTxnRecord(CREATE_TX)
                            .hasPriority(recordWith()
                                    .contractCreateResult(com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts
                                            .resultWith()
                                            .createdContractIdsCount(2)))
                            .logged();

                    allRunFor(spec, contractCreateTxn, expectedTxnRecord);
                }));
    }

    static Stream<DynamicTest> contractCallAfterEthereumTransferLazyCreate() {
        final var RECIPIENT_KEY = LAZY_ACCOUNT_RECIPIENT;
        final var lazyCreateTxn = PAY_TXN;
        return hapiTest(
                overriding("contracts.evm.version", "v0.34"),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(RECIPIENT_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT),
                getTxnRecord(AUTO_ACCOUNT).andAllChildRecords(),
                uploadInitCode(FACTORY_MIRROR_CONTRACT),
                contractCreate(FACTORY_MIRROR_CONTRACT).via(CREATE_TX).balance(20).gas(6_000_000),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        TxnVerbs.ethereumCryptoTransferToAlias(
                                        spec.registry().getKey(RECIPIENT_KEY).getECDSASecp256K1(), FIVE_HBARS)
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0L)
                                .maxFeePerGas(0L)
                                .maxGasAllowance(FIVE_HBARS)
                                .gasLimit(4_000_000L)
                                .via(lazyCreateTxn)
                                .hasKnownStatus(SUCCESS),
                        getTxnRecord(lazyCreateTxn).logged())),
                withOpContext((spec, opLog) -> {
                    final var contractCallTxn = contractCall(FACTORY_MIRROR_CONTRACT, "createChild", BigInteger.TEN)
                            .via("callTX")
                            .gas(6_000_000L);

                    final var expectedContractCallRecord = getTxnRecord("callTX")
                            .hasPriority(recordWith()
                                    .contractCallResult(com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts
                                            .resultWith()
                                            .createdContractIdsCount(1)))
                            .logged();

                    allRunFor(spec, contractCallTxn, expectedContractCallRecord);
                }));
    }
}

