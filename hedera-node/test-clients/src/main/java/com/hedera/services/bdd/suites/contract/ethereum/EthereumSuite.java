// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContractIdWithEvmAddress;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.namedHapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.unchangedFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.includingDeduction;
import static com.hedera.services.bdd.spec.keys.KeyFactory.KeyType.THRESHOLD;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAutoCreatedAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdateAliased;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ETH_HASH_KEY;
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
import static com.hedera.services.bdd.suites.HapiSuite.WEIBARS_IN_A_TINYBAR;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.getResourcePath;
import static com.hedera.services.bdd.suites.contract.ethereum.HelloWorldEthereumSuite.depositAmount;
import static com.hedera.services.bdd.suites.contract.hapi.ContractCallSuite.DEPOSIT;
import static com.hedera.services.bdd.suites.contract.leaky.LeakyContractTestsSuite.RECEIVER;
import static com.hedera.services.bdd.suites.contract.precompile.CreatePrecompileSuite.TOKEN_NAME;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.crypto.CryptoCreateSuite.ACCOUNT;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.MULTI_KEY;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.token.TokenTransactSpecs.SUPPLY_KEY;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.WRONG_NONCE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.hiero.base.utility.CommonUtils.unhex;
import static org.hyperledger.besu.datatypes.Address.contractAddress;
import static org.hyperledger.besu.datatypes.Address.fromHexString;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.google.common.io.Files;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransferList;
import java.io.File;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.bouncycastle.util.encoders.Hex;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@SuppressWarnings("java:S5960")
public class EthereumSuite {
    public static final long GAS_LIMIT = 1_000_000;
    public static final String ERC20_CONTRACT = "ERC20Contract";
    public static final String EMIT_SENDER_ORIGIN_CONTRACT = "EmitSenderOrigin";
    private static final long DEPOSIT_AMOUNT = 20_000L;
    private static final long ETH_TXN_FAILURE_FEE = 83_333L;
    private static final String PARTY = "party";
    private static final String LAZY_MEMO = "";
    private static final String PAY_RECEIVABLE_CONTRACT = "PayReceivable";
    private static final String TOKEN_CREATE_CONTRACT = "NewTokenCreateContract";
    private static final String HELLO_WORLD_MINT_CONTRACT = "HelloWorldMint";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String AUTO_ACCOUNT_TRANSACTION_NAME = "autoAccount";
    private static final String TOKEN = "token";
    private static final String MINT_TXN = "mintTxn";
    private static final String PAY_TXN = "payTxn";
    private static final String TOTAL_SUPPLY_TX = "totalSupplyTx";
    private static final String ERC20_ABI = "ERC20ABI";

    // This test must be run first to ensure the record file is as expected.
    @HapiTest
    final Stream<DynamicTest> sendingLargerBalanceThanAvailableFailsGracefully() {
        final AtomicReference<Address> tokenCreateContractAddress = new AtomicReference<>();
        final AtomicReference<ContractID> tokenCreateContractID = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS - 1)),
                createLargeFile(GENESIS, TOKEN_CREATE_CONTRACT, TxnUtils.literalInitcodeFor(TOKEN_CREATE_CONTRACT)),
                ethereumContractCreate(TOKEN_CREATE_CONTRACT)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .bytecode(TOKEN_CREATE_CONTRACT)
                        .gasPrice(10L)
                        .maxGasAllowance(ONE_HUNDRED_HBARS)
                        .gasLimit(4_000_000L)
                        .hasKnownStatusFrom(SUCCESS)
                        .via("deployTokenCreateContract"),
                getContractInfo(TOKEN_CREATE_CONTRACT)
                        .exposingEvmAddress(cb -> tokenCreateContractAddress.set(asHeadlongAddress(cb)))
                        .exposingContractId(tokenCreateContractID::set)
                        .has(contractWith().defaultAdminKey()),
                withOpContext((spec, opLog) -> {
                    var call = ethereumCall(
                                    TOKEN_CREATE_CONTRACT,
                                    "createNonFungibleTokenPublic",
                                    tokenCreateContractAddress.get())
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .payingWith(RELAYER)
                            .hasRetryPrecheckFrom(BUSY)
                            .nonce(1)
                            .gasPrice(10L)
                            .sending(ONE_HUNDRED_HBARS)
                            .gasLimit(1_000_000L)
                            .via("createTokenTxn")
                            .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE);
                    allRunFor(spec, call);
                }),
                // Quick assertion to verify top-level HAPI fees were still charged after aborting
                getTxnRecord("createTokenTxn")
                        .hasPriority(recordWith().transfers(includingDeduction("HAPI fees", RELAYER))));
    }

    @HapiTest
    final Stream<DynamicTest> baseRelayerCostAsExpected() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                overriding("contracts.evm.ethTransaction.zeroHapiFees.enabled", "true"),
                ethereumCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(depositAmount))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasPrice(100L)
                        .maxFeePerGas(100L)
                        .maxPriorityGas(2_000_000L)
                        .gasLimit(1_000_000L)
                        .sending(depositAmount),
                // The relayer's cost is no longer applicable for successful transactions.
                getAccountInfo(RELAYER).has(accountWith().expectedBalanceWithChargedUsd(ONE_HUNDRED_HBARS, 0, 0.1)));
    }

    @HapiTest
    final Stream<DynamicTest> etx010TransferToCryptoAccountSucceeds() {
        String RECEIVER = "RECEIVER";
        final String aliasBalanceSnapshot = "aliasBalance";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                getTxnRecord(AUTO_ACCOUNT_TRANSACTION_NAME).andAllChildRecords(),
                balanceSnapshot(aliasBalanceSnapshot, SECP_256K1_SOURCE_KEY).accountIsAlias(),
                ethereumCryptoTransfer(RECEIVER, FIVE_HBARS)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxFeePerGas(0L)
                        .maxGasAllowance(FIVE_HBARS)
                        .gasLimit(2_000_000L)
                        .via(PAY_TXN)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord(PAY_TXN)
                                .logged()
                                .hasPriority(recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .logs(inOrder())
                                                .senderId(spec.registry()
                                                        .getAccountID(spec.registry()
                                                                .keyAliasIdFor(spec, SECP_256K1_SOURCE_KEY)
                                                                .getAlias()
                                                                .toStringUtf8())))
                                        .ethereumHash(ByteString.copyFrom(
                                                spec.registry().getBytes(ETH_HASH_KEY)))))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getAccountBalance(RECEIVER).hasTinyBars(FIVE_HBARS),
                getAutoCreatedAccountBalance(SECP_256K1_SOURCE_KEY)
                        .hasTinyBars(changeFromSnapshot(aliasBalanceSnapshot, -FIVE_HBARS)));
    }

    List<Stream<DynamicTest>> feePaymentMatrix() {
        final long gasPrice = 71;
        final long chargedGasLimit = GAS_LIMIT * 4 / 5;

        final long noPayment = 0L;
        final long thirdOfFee = gasPrice / 3;
        final long thirdOfPayment = thirdOfFee * chargedGasLimit;
        final long thirdOfLimit = thirdOfFee * GAS_LIMIT;
        final long fullAllowance = gasPrice * chargedGasLimit * 5 / 4;
        final long fullPayment = gasPrice * chargedGasLimit;
        final long ninetyPercentFee = gasPrice * 9 / 10;

        return Stream.of(
                        new Object[] {false, noPayment, noPayment, noPayment},
                        new Object[] {false, noPayment, thirdOfPayment, noPayment},
                        new Object[] {true, noPayment, fullAllowance, noPayment},
                        new Object[] {false, thirdOfFee, noPayment, noPayment},
                        new Object[] {false, thirdOfFee, thirdOfPayment, noPayment},
                        new Object[] {true, thirdOfFee, fullAllowance, thirdOfLimit},
                        new Object[] {true, thirdOfFee, fullAllowance * 9 / 10, thirdOfLimit},
                        new Object[] {false, ninetyPercentFee, noPayment, noPayment},
                        new Object[] {true, ninetyPercentFee, thirdOfPayment, fullPayment},
                        new Object[] {true, gasPrice, noPayment, fullPayment},
                        new Object[] {true, gasPrice, thirdOfPayment, fullPayment},
                        new Object[] {true, gasPrice, fullAllowance, fullPayment})
                .map(params ->
                        // [0] - success
                        // [1] - sender gas price
                        // [2] - relayer offered
                        // [3] - sender charged amount
                        // relayer charged amount can easily be calculated via
                        // wholeTransactionFee - senderChargedAmount
                        matrixedPayerRelayerTest(
                                (boolean) params[0], (long) params[1], (long) params[2], (long) params[3]))
                .toList();
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest1() {
        return feePaymentMatrix().get(0);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest2() {
        return feePaymentMatrix().get(1);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest3() {
        return feePaymentMatrix().get(2);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest4() {
        return feePaymentMatrix().get(3);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest5() {
        return feePaymentMatrix().get(4);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest6() {
        return feePaymentMatrix().get(5);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest7() {
        return feePaymentMatrix().get(6);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest8() {
        return feePaymentMatrix().get(7);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest9() {
        return feePaymentMatrix().get(8);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest10() {
        return feePaymentMatrix().get(9);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest11() {
        return feePaymentMatrix().get(10);
    }

    @HapiTest
    final Stream<DynamicTest> matrixedPayerRelayerTest12() {
        return feePaymentMatrix().get(11);
    }

    final Stream<DynamicTest> matrixedPayerRelayerTest(
            final boolean success, final long senderGasPrice, final long relayerOffered, final long senderCharged) {
        return Stream.of(namedHapiTest(
                "feePaymentMatrix " + (success ? "Success/" : "Failure/") + senderGasPrice + "/" + relayerOffered,
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                getTxnRecord(AUTO_ACCOUNT_TRANSACTION_NAME).andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                overriding("contracts.evm.ethTransaction.zeroHapiFees.enabled", "true"),
                withOpContext((spec, ignore) -> {
                    final String senderBalance = "senderBalance";
                    final String payerBalance = "payerBalance";
                    final var subop1 = balanceSnapshot(senderBalance, SECP_256K1_SOURCE_KEY)
                            .accountIsAlias();
                    final var subop2 = balanceSnapshot(payerBalance, RELAYER);
                    final var subop3 = ethereumCall(
                                    PAY_RECEIVABLE_CONTRACT, "deposit", BigInteger.valueOf(DEPOSIT_AMOUNT))
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .payingWith(RELAYER)
                            .via(PAY_TXN)
                            .nonce(0)
                            .maxGasAllowance(relayerOffered)
                            .maxFeePerGas(senderGasPrice)
                            .gasLimit(GAS_LIMIT)
                            .sending(DEPOSIT_AMOUNT)
                            .hasKnownStatus(success ? ResponseCodeEnum.SUCCESS : ResponseCodeEnum.INSUFFICIENT_TX_FEE);

                    final HapiGetTxnRecord hapiGetTxnRecord =
                            getTxnRecord(PAY_TXN).logged();
                    allRunFor(spec, subop1, subop2, subop3, hapiGetTxnRecord);

                    final long wholeTransactionFee =
                            hapiGetTxnRecord.getResponseRecord().getTransactionFee();
                    final var subop4 = getAutoCreatedAccountBalance(SECP_256K1_SOURCE_KEY)
                            .hasTinyBars(
                                    changeFromSnapshot(senderBalance, success ? (-DEPOSIT_AMOUNT - senderCharged) : 0));
                    // The relayer is not charged with Hapi fee unless the relayed transaction failed
                    final var subop5 = getAccountBalance(RELAYER)
                            .hasTinyBars(changeFromSnapshot(
                                    payerBalance,
                                    success ? -(wholeTransactionFee - senderCharged) : -wholeTransactionFee));
                    allRunFor(spec, subop4, subop5);
                })));
    }

    @HapiTest
    final Stream<DynamicTest> invalidTxData() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                getTxnRecord(AUTO_ACCOUNT_TRANSACTION_NAME).andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                ethereumContractCreate(PAY_RECEIVABLE_CONTRACT)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasPrice(10L)
                        .maxGasAllowance(5L)
                        .maxPriorityGas(2L)
                        .invalidateEthereumData()
                        .gasLimit(1_000_000L)
                        .hasPrecheck(INVALID_ETHEREUM_TRANSACTION)
                        .via(PAY_TXN));
    }

    @HapiTest
    final Stream<DynamicTest> etx014ContractCreateInheritsSignerProperties() {
        final AtomicReference<String> contractNum = new AtomicReference<>();
        final String MEMO = "memo";
        final String PROXY = "proxy";
        final long INITIAL_BALANCE = 100L;
        final long AUTO_RENEW_PERIOD = THREE_MONTHS_IN_SECONDS + 60;
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                getTxnRecord(AUTO_ACCOUNT_TRANSACTION_NAME).andAllChildRecords(),
                cryptoCreate(PROXY),
                cryptoUpdateAliased(SECP_256K1_SOURCE_KEY)
                        .autoRenewPeriod(AUTO_RENEW_PERIOD)
                        .entityMemo(MEMO)
                        .payingWith(GENESIS)
                        .signedBy(SECP_256K1_SOURCE_KEY, GENESIS),
                withOpContext((spec, opLog) -> {
                    ethereumContractCreate(PAY_RECEIVABLE_CONTRACT)
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .payingWith(RELAYER)
                            .nonce(0)
                            .balance(INITIAL_BALANCE)
                            .gasPrice(10L)
                            .maxGasAllowance(ONE_HUNDRED_HBARS)
                            .exposingNumTo(num -> contractNum.set(String.valueOf(num)))
                            .gasLimit(1_000_000L)
                            .hasKnownStatus(SUCCESS);
                    getContractInfo(PAY_RECEIVABLE_CONTRACT).has(contractWith().defaultAdminKey());
                    ethereumCall(PAY_RECEIVABLE_CONTRACT, "getBalance")
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .payingWith(RELAYER)
                            .nonce(1L)
                            .gasPrice(10L)
                            .gasLimit(1_000_000L)
                            .hasKnownStatus(SUCCESS);
                    getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).logged();
                    sourcing(() -> getContractInfo(contractNum.get())
                            .has(contractWith()
                                    .defaultAdminKey()
                                    .autoRenew(AUTO_RENEW_PERIOD)
                                    .balance(INITIAL_BALANCE)
                                    .memo(MEMO)));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> etx031InvalidNonceEthereumTxFailsAndChargesRelayer() {
        final var relayerSnapshot = "relayer";
        final var senderSnapshot = "sender";
        final AtomicLong balanceRef = new AtomicLong();
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                getTxnRecord(AUTO_ACCOUNT_TRANSACTION_NAME).andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                balanceSnapshot(relayerSnapshot, RELAYER),
                balanceSnapshot(senderSnapshot, SECP_256K1_SOURCE_KEY).accountIsAlias(),
                overriding("contracts.evm.ethTransaction.zeroHapiFees.enabled", "true"),
                ethereumCall(PAY_RECEIVABLE_CONTRACT, "deposit", BigInteger.valueOf(DEPOSIT_AMOUNT))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(999L)
                        .via(PAY_TXN)
                        .hasKnownStatus(WRONG_NONCE),
                withOpContext((spec, opLog) -> {
                    final var payTxn = getTxnRecord(PAY_TXN)
                            .logged()
                            .hasPriority(recordWith()
                                    .ethereumHash(
                                            ByteString.copyFrom(spec.registry().getBytes(ETH_HASH_KEY))));
                    allRunFor(spec, payTxn);
                    final var fee = payTxn.getResponseRecord().getTransactionFee();
                    // The relayer account is charged on error for 0.0001$ - 83_333 tinybars with the testing exchange
                    // rate conversion
                    final var relayerBalance =
                            getAccountBalance(RELAYER).hasTinyBars(changeFromSnapshot(relayerSnapshot, -fee));
                    final var senderBalance = getAutoCreatedAccountBalance(SECP_256K1_SOURCE_KEY)
                            .hasTinyBars(unchangedFromSnapshot(senderSnapshot));
                    allRunFor(spec, relayerBalance, senderBalance);
                }),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)),
                // But the failing call attempt is rejected at ingest if the relayer's balance is too low
                getAccountBalance(RELAYER).exposingBalanceTo(balanceRef::set),
                sourcing(() ->
                        cryptoTransfer(tinyBarsFromTo(RELAYER, FUNDING, balanceRef.get() - ETH_TXN_FAILURE_FEE + 1))),
                ethereumCall(PAY_RECEIVABLE_CONTRACT, "deposit", BigInteger.valueOf(DEPOSIT_AMOUNT))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .fee(810000000L)
                        .maxGasAllowance(0L)
                        .nonce(999L)
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE));
    }

    @HapiTest
    final Stream<DynamicTest> etx013PrecompileCallFailsWhenSignatureMissingFromBothEthereumAndHederaTxn() {
        final AtomicReference<TokenID> fungible = new AtomicReference<>();
        final String fungibleToken = TOKEN;
        final String mintTxn = MINT_TXN;
        final String MULTI_KEY = "MULTI_KEY";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                getTxnRecord(AUTO_ACCOUNT_TRANSACTION_NAME).andAllChildRecords(),
                uploadInitCode(HELLO_WORLD_MINT_CONTRACT),
                tokenCreate(fungibleToken)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(0)
                        .adminKey(MULTI_KEY)
                        .supplyKey(MULTI_KEY)
                        .exposingCreatedIdTo(idLit -> fungible.set(asToken(idLit))),
                sourcing(() -> contractCreate(HELLO_WORLD_MINT_CONTRACT, asHeadlongAddress(asAddress(fungible.get())))),
                ethereumCall(HELLO_WORLD_MINT_CONTRACT, "brrr", BigInteger.valueOf(5))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .nonce(0)
                        .via(mintTxn)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord(mintTxn)
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .logs(inOrder())
                                                .senderId(spec.registry()
                                                        .getAccountID(spec.registry()
                                                                .keyAliasIdFor(spec, SECP_256K1_SOURCE_KEY)
                                                                .getAlias()
                                                                .toStringUtf8())))
                                        .ethereumHash(ByteString.copyFrom(
                                                spec.registry().getBytes(ETH_HASH_KEY)))))),
                childRecordsCheck(
                        mintTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith().status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)));
    }

    @HapiTest
    final Stream<DynamicTest> etx009CallsToTokenAddresses() {
        final AtomicReference<String> tokenNum = new AtomicReference<>();
        final var totalSupply = 50;

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(totalSupply)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(SECP_256K1_SOURCE_KEY)
                        .supplyKey(SECP_256K1_SOURCE_KEY)
                        .exposingCreatedIdTo(tokenNum::set),
                uploadInitCode(ERC20_CONTRACT),
                contractCreate(ERC20_CONTRACT).adminKey(THRESHOLD),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        ethereumCallWithFunctionAbi(
                                        true,
                                        FUNGIBLE_TOKEN,
                                        getABIFor(Utils.FunctionType.FUNCTION, "totalSupply", ERC20_ABI))
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .via(TOTAL_SUPPLY_TX)
                                .nonce(0)
                                .gasPrice(50L)
                                .maxGasAllowance(FIVE_HBARS)
                                .maxPriorityGas(2L)
                                .gasLimit(1_000_000L)
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS))),
                childRecordsCheck(
                        TOTAL_SUPPLY_TX,
                        SUCCESS,
                        recordWith()
                                .status(SUCCESS)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .forFunction(FunctionType.ERC_TOTAL_SUPPLY)
                                                .withTotalSupply(totalSupply)))));
    }

    // ETX-011 and ETX-030
    @HapiTest
    final Stream<DynamicTest> originAndSenderAreEthereumSigner() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                getTxnRecord(AUTO_ACCOUNT_TRANSACTION_NAME).andAllChildRecords(),
                uploadInitCode(EMIT_SENDER_ORIGIN_CONTRACT),
                contractCreate(EMIT_SENDER_ORIGIN_CONTRACT),
                ethereumCall(EMIT_SENDER_ORIGIN_CONTRACT, "logNow")
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxFeePerGas(50L)
                        .gasLimit(1_000_000L)
                        .via(PAY_TXN),
                withOpContext((spec, ignore) -> allRunFor(
                        spec,
                        getTxnRecord(PAY_TXN)
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .logs(inOrder(logWith()
                                                        .ecdsaAliasStartingAt(SECP_256K1_SOURCE_KEY, 12)
                                                        .ecdsaAliasStartingAt(SECP_256K1_SOURCE_KEY, 44)
                                                        .withTopicsInOrder(
                                                                List.of(eventSignatureOf("Info(address,address)")))))
                                                .senderId(spec.registry()
                                                        .getAccountID(spec.registry()
                                                                .keyAliasIdFor(spec, SECP_256K1_SOURCE_KEY)
                                                                .getAlias()
                                                                .toStringUtf8())))
                                        .ethereumHash(ByteString.copyFrom(
                                                spec.registry().getBytes(ETH_HASH_KEY)))))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)));
    }

    @HapiTest
    final Stream<DynamicTest> etx008ContractCreateExecutesWithExpectedRecord() {
        final var txn = "creation";
        final var contract = "Fuse";

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                uploadInitCode(contract),
                ethereumContractCreate(contract)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(GAS_LIMIT)
                        .via(txn),
                getContractInfo(contract).has(contractWith().defaultAdminKey()),
                withOpContext((spec, opLog) -> {
                    final var op = getTxnRecord(txn);
                    allRunFor(spec, op);
                    final var record = op.getResponseRecord();
                    final var creationResult = record.getContractCreateResult();
                    final var createdIds = creationResult.getCreatedContractIDsList().stream()
                            .sorted(Comparator.comparing(ContractID::getContractNum))
                            .toList();
                    assertEquals(4, createdIds.size(), "Expected four creations but got " + createdIds);

                    final var ecdsaKey = spec.registry()
                            .getKey(SECP_256K1_SOURCE_KEY)
                            .getECDSASecp256K1()
                            .toByteArray();
                    final var senderAddress = CommonUtils.hex(recoverAddressFromPubKey(ecdsaKey));
                    final var senderNonce = 0;

                    final var expectedParentContractAddress =
                            contractAddress(fromHexString(senderAddress), senderNonce);
                    final var expectedFirstChildContractAddress = contractAddress(expectedParentContractAddress, 1);
                    final var expectedSecondChildContractAddress = contractAddress(expectedParentContractAddress, 2);
                    final var expectedThirdChildContractAddress = contractAddress(expectedParentContractAddress, 3);

                    final var parentId = createdIds.get(0);
                    final var parentContractId = CommonUtils.hex(
                            asEvmAddress(parentId.getShardNum(), parentId.getRealmNum(), parentId.getContractNum()));
                    final var firstChildId = createdIds.get(1);
                    final var firstChildContractId = CommonUtils.hex(asEvmAddress(
                            firstChildId.getShardNum(), firstChildId.getRealmNum(), firstChildId.getContractNum()));
                    final var secondChildId = createdIds.get(2);
                    final var secondChildContractId = CommonUtils.hex(asEvmAddress(
                            secondChildId.getShardNum(), secondChildId.getRealmNum(), secondChildId.getContractNum()));
                    final var thirdChildId = createdIds.get(3);
                    final var thirdChildContractId = CommonUtils.hex(asEvmAddress(
                            thirdChildId.getShardNum(), thirdChildId.getRealmNum(), thirdChildId.getContractNum()));

                    final var parentContractInfo = getContractInfo(parentContractId)
                            .has(contractWith().addressOrAlias(expectedParentContractAddress.toUnprefixedHexString()));
                    final var firstChildContractInfo = getContractInfo(firstChildContractId)
                            .has(contractWith()
                                    .addressOrAlias(expectedFirstChildContractAddress.toUnprefixedHexString()));
                    final var secondChildContractInfo = getContractInfo(secondChildContractId)
                            .has(contractWith()
                                    .addressOrAlias(expectedSecondChildContractAddress.toUnprefixedHexString()));
                    final var thirdChildContractInfo = getContractInfo(thirdChildContractId)
                            .has(contractWith()
                                    .addressOrAlias(expectedThirdChildContractAddress.toUnprefixedHexString()))
                            .logged();

                    allRunFor(
                            spec,
                            parentContractInfo,
                            firstChildContractInfo,
                            secondChildContractInfo,
                            thirdChildContractInfo);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> etxSvc003ContractGetBytecodeQueryReturnsDeployedCode() {
        final var txn = "creation";
        final var contract = "EmptyConstructor";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                uploadInitCode(contract),
                ethereumContractCreate(contract)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .gasLimit(GAS_LIMIT)
                        .via(txn),
                getContractInfo(contract).has(contractWith().defaultAdminKey()),
                withOpContext((spec, opLog) -> {
                    final var getBytecode = getContractBytecode(contract).saveResultTo("contractByteCode");
                    allRunFor(spec, getBytecode);

                    final var originalBytecode =
                            Hex.decode(Files.toByteArray(new File(getResourcePath(contract, ".bin"))));
                    final var actualBytecode = spec.registry().getBytes("contractByteCode");
                    // The original bytecode is modified on deployment
                    final var expectedBytecode = Arrays.copyOfRange(originalBytecode, 29, originalBytecode.length);
                    Assertions.assertArrayEquals(expectedBytecode, actualBytecode);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> directTransferWorksForERC20() {
        final var tokenSymbol = "FDFGF";
        final var tokenTotalSupply = 5;
        final var tokenTransferAmount = 3;
        final var transferTxn = "decimalsTxn";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(tokenTotalSupply)
                        .name(TOKEN_NAME)
                        .symbol(tokenSymbol)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(moving(tokenTransferAmount, FUNGIBLE_TOKEN)
                                .between(TOKEN_TREASURY, SECP_256K1_SOURCE_KEY))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                withOpContext((spec, ignore) -> allRunFor(
                        spec,
                        ethereumCallWithFunctionAbi(
                                        true,
                                        FUNGIBLE_TOKEN,
                                        getABIFor(Utils.FunctionType.FUNCTION, "transfer", ERC20_ABI),
                                        asHeadlongAddress(asHexedSolidityAddress(
                                                spec.registry().getAccountID(ACCOUNT))),
                                        BigInteger.valueOf(tokenTransferAmount))
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .nonce(0)
                                .gasPrice(50L)
                                .via(transferTxn)
                                .gasLimit(1_000_000)
                                .maxFeePerGas(0)
                                .type(EthTransactionType.EIP1559)
                                .maxGasAllowance(ONE_HBAR * 5)
                                .payingWith(ACCOUNT))),
                withOpContext((spec, ignore) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                transferTxn,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_TRANSFER)
                                                        .withErcFungibleTransferStatus(true)))))));
    }

    @HapiTest
    final Stream<DynamicTest> transferHbarsViaEip2930TxSuccessfully() {
        final String RECEIVER = "RECEIVER";
        final String aliasBalanceSnapshot = "aliasBalance";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                getTxnRecord(AUTO_ACCOUNT_TRANSACTION_NAME).andAllChildRecords(),
                balanceSnapshot(aliasBalanceSnapshot, SECP_256K1_SOURCE_KEY).accountIsAlias(),
                ethereumCryptoTransfer(RECEIVER, FIVE_HBARS)
                        .type(EthTxData.EthTransactionType.EIP2930)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasPrice(0L)
                        .gasLimit(2_000_000L)
                        .via(PAY_TXN),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord(PAY_TXN)
                                .logged()
                                .hasPriority(recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .logs(inOrder())
                                                .senderId(spec.registry()
                                                        .getAccountID(spec.registry()
                                                                .keyAliasIdFor(spec, SECP_256K1_SOURCE_KEY)
                                                                .getAlias()
                                                                .toStringUtf8())))
                                        .ethereumHash(ByteString.copyFrom(
                                                spec.registry().getBytes(ETH_HASH_KEY)))))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getAccountBalance(RECEIVER).hasTinyBars(FIVE_HBARS),
                getAutoCreatedAccountBalance(SECP_256K1_SOURCE_KEY)
                        .hasTinyBars(changeFromSnapshot(aliasBalanceSnapshot, -FIVE_HBARS)));
    }

    @HapiTest
    final Stream<DynamicTest> callToTokenAddressViaEip2930TxSuccessfully() {
        final AtomicReference<String> tokenNum = new AtomicReference<>();
        final var totalSupply = 50;

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(totalSupply)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(SECP_256K1_SOURCE_KEY)
                        .supplyKey(SECP_256K1_SOURCE_KEY)
                        .exposingCreatedIdTo(tokenNum::set),
                uploadInitCode(ERC20_CONTRACT),
                contractCreate(ERC20_CONTRACT).adminKey(THRESHOLD),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        ethereumCallWithFunctionAbi(
                                        true,
                                        FUNGIBLE_TOKEN,
                                        getABIFor(Utils.FunctionType.FUNCTION, "totalSupply", ERC20_ABI))
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .hasRetryPrecheckFrom(BUSY)
                                .via(TOTAL_SUPPLY_TX)
                                .nonce(0)
                                .gasPrice(0L)
                                .gasLimit(1_000_000L),
                        childRecordsCheck(
                                TOTAL_SUPPLY_TX,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_TOTAL_SUPPLY)
                                                        .withTotalSupply(totalSupply)))))));
    }

    @HapiTest
    final Stream<DynamicTest> transferTokensViaEip2930TxSuccessfully() {
        final var tokenSymbol = "FDFGF";
        final var tokenTotalSupply = 5;
        final var tokenTransferAmount = 3;
        final var transferTxn = "decimalsTxn";
        return hapiTest(
                newKeyNamed(MULTI_KEY),
                cryptoCreate(ACCOUNT),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(tokenTotalSupply)
                        .name(TOKEN_NAME)
                        .symbol(tokenSymbol)
                        .treasury(TOKEN_TREASURY),
                tokenAssociate(ACCOUNT, FUNGIBLE_TOKEN),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(moving(tokenTransferAmount, FUNGIBLE_TOKEN)
                                .between(TOKEN_TREASURY, SECP_256K1_SOURCE_KEY))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                withOpContext((spec, ignore) -> allRunFor(
                        spec,
                        ethereumCallWithFunctionAbi(
                                        true,
                                        FUNGIBLE_TOKEN,
                                        getABIFor(Utils.FunctionType.FUNCTION, "transfer", ERC20_ABI),
                                        asHeadlongAddress(asHexedSolidityAddress(
                                                spec.registry().getAccountID(ACCOUNT))),
                                        BigInteger.valueOf(tokenTransferAmount))
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .nonce(0)
                                .gasPrice(0L)
                                .via(transferTxn)
                                .gasLimit(1_000_000)
                                .type(EthTransactionType.EIP2930)
                                .payingWith(RELAYER))),
                withOpContext((spec, ignore) -> allRunFor(
                        spec,
                        childRecordsCheck(
                                transferTxn,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.ERC_TRANSFER)
                                                        .withErcFungibleTransferStatus(true)))))));
    }

    @HapiTest
    final Stream<DynamicTest> accountDeletionResetsTheAliasNonce() {

        final AtomicReference<AccountID> partyId = new AtomicReference<>();
        final AtomicReference<byte[]> partyAlias = new AtomicReference<>();
        final AtomicReference<byte[]> counterAlias = new AtomicReference<>();
        final AtomicReference<AccountID> aliasedAccountId = new AtomicReference<>();
        final AtomicReference<String> tokenNum = new AtomicReference<>();
        final var totalSupply = 50;
        final var ercUser = "ercUser";
        final var HBAR_XFER = "hbarXfer";

        return hapiTest(
                cryptoCreate(PARTY).maxAutomaticTokenAssociations(2),
                cryptoCreate(TOKEN_TREASURY),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                withOpContext((spec, opLog) -> {
                    final var registry = spec.registry();
                    final var ecdsaKey = registry.getKey(SECP_256K1_SOURCE_KEY);
                    final var tmp = ecdsaKey.getECDSASecp256K1().toByteArray();
                    final var addressBytes = recoverAddressFromPubKey(tmp);
                    partyId.set(registry.getAccountID(PARTY));
                    partyAlias.set(asSolidityAddress(partyId.get()));
                    counterAlias.set(addressBytes);
                }),
                tokenCreate("token")
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(totalSupply)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(SECP_256K1_SOURCE_KEY)
                        .supplyKey(SECP_256K1_SOURCE_KEY)
                        .exposingCreatedIdTo(tokenNum::set),
                withOpContext((spec, opLog) -> {
                    var op1 = cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(s, partyAlias.get(), -2 * ONE_HBAR))
                                    .addAccountAmounts(Utils.aaWith(s, counterAlias.get(), +2 * ONE_HBAR))))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .via(HBAR_XFER);

                    var op2 = getAliasedAccountInfo(ByteString.copyFrom(counterAlias.get()))
                            .logged()
                            .exposingIdTo(aliasedAccountId::set)
                            .has(accountWith()
                                    .hasEmptyKey()
                                    .noAlias()
                                    .nonce(0)
                                    .autoRenew(THREE_MONTHS_IN_SECONDS)
                                    .receiverSigReq(false)
                                    .memo(LAZY_MEMO));

                    // send eth transaction signed by the ecdsa key
                    var op3 = ethereumCallWithFunctionAbi(
                                    true, "token", getABIFor(Utils.FunctionType.FUNCTION, "totalSupply", ERC20_ABI))
                            .type(EthTxData.EthTransactionType.EIP1559)
                            .signingWith(SECP_256K1_SOURCE_KEY)
                            .payingWith(GENESIS)
                            .nonce(0)
                            .gasPrice(50L)
                            .maxGasAllowance(FIVE_HBARS)
                            .maxPriorityGas(2L)
                            .gasLimit(1_000_000L)
                            .hasKnownStatus(ResponseCodeEnum.SUCCESS);

                    // assert account nonce is increased to 1
                    var op4 = getAliasedAccountInfo(ByteString.copyFrom(counterAlias.get()))
                            .logged()
                            .has(accountWith().nonce(1));

                    allRunFor(spec, op1, op2, op3, op4);

                    spec.registry().saveAccountId(ercUser, aliasedAccountId.get());
                    spec.registry().saveKey(ercUser, spec.registry().getKey(SECP_256K1_SOURCE_KEY));
                }),
                // delete the account currently holding the alias
                cryptoDelete(ercUser),
                // try to create a new account with the same alias
                withOpContext((spec, opLog) -> {
                    var op1 = cryptoTransfer((s, b) -> b.setTransfers(TransferList.newBuilder()
                                    .addAccountAmounts(Utils.aaWith(s, partyAlias.get(), -2 * ONE_HBAR))
                                    .addAccountAmounts(Utils.aaWith(s, counterAlias.get(), +2 * ONE_HBAR))))
                            .signedBy(DEFAULT_PAYER, PARTY)
                            .hasKnownStatus(SUCCESS);

                    var op2 = getAliasedAccountInfo(ByteString.copyFrom(counterAlias.get()))
                            // TBD: balance should be 4 or 2 hbars
                            .has(accountWith().nonce(0).balance(2 * ONE_HBAR));

                    allRunFor(spec, op1, op2);
                }));
    }

    // test unprotected legacy ethereum transactions before EIP155,
    // without changing `CHAIN_ID` bootstrap property
    // this tests the behaviour when the `v` field
    // is calculated -> v = {0,1} + 27
    // source: https://eips.ethereum.org/EIPS/eip-155
    @HapiTest
    final Stream<DynamicTest> legacyUnprotectedEtxBeforeEIP155WithDefaultChainId() {
        final String DEPOSIT = "deposit";
        final long depositAmount = 20_000L;
        final Integer chainId = 0;

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via("autoAccount"),
                getTxnRecord("autoAccount").andAllChildRecords(),
                uploadInitCode(PAY_RECEIVABLE_CONTRACT),
                contractCreate(PAY_RECEIVABLE_CONTRACT).adminKey(THRESHOLD),
                ethereumCall(PAY_RECEIVABLE_CONTRACT, DEPOSIT, BigInteger.valueOf(depositAmount))
                        .type(EthTransactionType.LEGACY_ETHEREUM)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .via("legacyBeforeEIP155")
                        .nonce(0)
                        .chainId(chainId)
                        .gasPrice(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(1_000_000L)
                        .sending(depositAmount),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord("legacyBeforeEIP155")
                                .logged()
                                .hasPriority(recordWith().status(SUCCESS)))));
    }

    @HapiTest
    final Stream<DynamicTest> etx007FungibleTokenCreateWithFeesHappyPath() {
        final var createdTokenNum = new AtomicLong();
        final var feeCollectorAndAutoRenew = "feeCollectorAndAutoRenew";
        final var contract = "TokenCreateContract";
        final var EXISTING_TOKEN = "EXISTING_TOKEN";
        final var firstTxn = "firstCreateTxn";
        final long DEFAULT_AMOUNT_TO_SEND = 20 * ONE_HBAR;
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                cryptoCreate(feeCollectorAndAutoRenew)
                        .keyShape(SigControl.ED25519_ON)
                        .balance(ONE_MILLION_HBARS),
                uploadInitCode(contract),
                contractCreate(contract).gas(4_000_000L),
                tokenCreate(EXISTING_TOKEN).decimals(5),
                tokenAssociate(feeCollectorAndAutoRenew, EXISTING_TOKEN),
                cryptoUpdate(feeCollectorAndAutoRenew).key(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        ethereumCall(
                                        contract,
                                        "createTokenWithAllCustomFeesAvailable",
                                        spec.registry()
                                                .getKey(SECP_256K1_SOURCE_KEY)
                                                .getECDSASecp256K1()
                                                .toByteArray(),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(feeCollectorAndAutoRenew))),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(EXISTING_TOKEN))),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(feeCollectorAndAutoRenew))),
                                        8_000_000L)
                                .via(firstTxn)
                                .gasLimit(GAS_LIMIT)
                                .payingWith(feeCollectorAndAutoRenew)
                                .sending(DEFAULT_AMOUNT_TO_SEND)
                                .hasKnownStatus(SUCCESS)
                                .exposingResultTo(result -> {
                                    opLog.info("Explicit create result" + " is {}", result[0]);
                                }))),
                getTxnRecord(firstTxn).andAllChildRecords().logged(),
                childRecordsCheck(
                        firstTxn, SUCCESS, TransactionRecordAsserts.recordWith().status(ResponseCodeEnum.SUCCESS)),
                withOpContext((spec, ignore) -> {
                    final var op = getTxnRecord(firstTxn);
                    allRunFor(spec, op);

                    final var callResult = op.getResponseRecord().getContractCallResult();
                    final var gasUsed = callResult.getGasUsed();
                    final var amount = callResult.getAmount();
                    final var gasLimit = callResult.getGas();
                    Assertions.assertEquals(DEFAULT_AMOUNT_TO_SEND, amount);
                    Assertions.assertEquals(GAS_LIMIT, gasLimit);
                    Assertions.assertTrue(gasUsed > 0L);
                    Assertions.assertTrue(callResult.hasContractID() && callResult.hasSenderId());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> fungibleTokenCreateWithAmountLookingNegativeInTwosComplement() {
        final var createdTokenNum = new AtomicLong();
        final var feeCollectorAndAutoRenew = "feeCollectorAndAutoRenew";
        final var contract = "TokenCreateContract";
        final var EXISTING_TOKEN = "EXISTING_TOKEN";
        final var firstTxn = "firstCreateTxn";
        // RAW_BIG_INTEGER_WEIBAR has high bit _ON_: Negative in two's complement but positive in Ethereum
        final var RAW_BIG_INTEGER_WEIBAR =
                new BigInteger(Bytes.fromHex("FAC7230489E80000").toByteArray());
        //             ^^^^ 10000000000000000000 wasn't enough to pay the tx fee, so changed the leading `8` to an `F`
        final var BIG_INTEGER_WEIBAR = new BigInteger("18070450532247928832"); // this is the actual value
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, 20 * ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                cryptoCreate(feeCollectorAndAutoRenew)
                        .keyShape(SigControl.ED25519_ON)
                        .balance(ONE_MILLION_HBARS),
                uploadInitCode(contract),
                contractCreate(contract).gas(4_000_000L),
                tokenCreate(EXISTING_TOKEN).decimals(5),
                tokenAssociate(feeCollectorAndAutoRenew, EXISTING_TOKEN),
                cryptoUpdate(feeCollectorAndAutoRenew).key(SECP_256K1_SOURCE_KEY),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        ethereumCall(
                                        contract,
                                        "createTokenWithAllCustomFeesAvailable",
                                        spec.registry()
                                                .getKey(SECP_256K1_SOURCE_KEY)
                                                .getECDSASecp256K1()
                                                .toByteArray(),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(feeCollectorAndAutoRenew))),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(EXISTING_TOKEN))),
                                        asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(feeCollectorAndAutoRenew))),
                                        8_000_000L)
                                .via(firstTxn)
                                .gasLimit(GAS_LIMIT)
                                .payingWith(feeCollectorAndAutoRenew)
                                .sendingWeibars(RAW_BIG_INTEGER_WEIBAR)
                                .hasKnownStatus(SUCCESS)
                                .exposingResultTo(result -> {
                                    opLog.info("Explicit create result is {}", result[0]);
                                }))),
                getTxnRecord(firstTxn).andAllChildRecords().logged(),
                childRecordsCheck(
                        firstTxn, SUCCESS, TransactionRecordAsserts.recordWith().status(ResponseCodeEnum.SUCCESS)),
                withOpContext((spec, ignore) -> {
                    final var op = getTxnRecord(firstTxn);
                    allRunFor(spec, op);

                    final var callResult = op.getResponseRecord().getContractCallResult();
                    final var gasUsed = callResult.getGasUsed();
                    final var amountTinybar = callResult.getAmount();
                    final var gasLimit = callResult.getGas();
                    Assertions.assertEquals(
                            BIG_INTEGER_WEIBAR.divide(WEIBARS_IN_A_TINYBAR).longValueExact(), amountTinybar);
                    Assertions.assertEquals(GAS_LIMIT, gasLimit);
                    Assertions.assertTrue(gasUsed > 0L);
                    Assertions.assertTrue(callResult.hasContractID() && callResult.hasSenderId());
                }));
    }

    @HapiTest
    final Stream<DynamicTest> callHtsSystemContractTest() {
        final var callHtsSystemContractTxn = "callHtsSystemContractTxn";
        final var function = getABIFor(FUNCTION, "transferToken", "IHederaTokenService");
        final var HTS_SYSTEM_CONTRACT = "hts";
        final var HTS_SYSTEM_CONTRACT_ADDRESS = "0000000000000000000000000000000000000167";

        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                newKeyNamed(SUPPLY_KEY),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoCreate(ACCOUNT).balance(6 * ONE_MILLION_HBARS),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(RECEIVER),
                tokenCreate(VANILLA_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .supplyKey(SUPPLY_KEY)
                        .initialSupply(1_000),
                tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                tokenAssociate(RECEIVER, VANILLA_TOKEN),
                cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)),
                withOpContext((spec, opLog) -> {
                    final var receiver1 =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(RECEIVER)));
                    final var sender =
                            asHeadlongAddress(asAddress(spec.registry().getAccountID(ACCOUNT)));

                    spec.registry()
                            .saveContractId(
                                    HTS_SYSTEM_CONTRACT,
                                    asContractIdWithEvmAddress(
                                            ByteString.copyFrom(unhex(HTS_SYSTEM_CONTRACT_ADDRESS))));
                    allRunFor(
                            spec,
                            ethereumCallWithFunctionAbi(
                                            false,
                                            HTS_SYSTEM_CONTRACT,
                                            function,
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getTokenID(VANILLA_TOKEN))),
                                            sender,
                                            receiver1,
                                            1L)
                                    .payingWith(RELAYER)
                                    .type(EthTransactionType.EIP1559)
                                    .via(callHtsSystemContractTxn)
                                    .hasKnownStatus(SUCCESS));
                }));
    }

    @HapiTest
    final Stream<DynamicTest> negativeGasFailsGracefully() {
        String receiver = "RECEIVER";
        final String aliasBalanceSnapshot = "aliasBalance";
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(receiver).balance(0L),
                cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                        .via(AUTO_ACCOUNT_TRANSACTION_NAME),
                withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                getTxnRecord(AUTO_ACCOUNT_TRANSACTION_NAME).andAllChildRecords(),
                balanceSnapshot(aliasBalanceSnapshot, SECP_256K1_SOURCE_KEY).accountIsAlias(),
                ethereumCryptoTransfer(receiver, FIVE_HBARS)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxFeePerGas(0L)
                        .maxGasAllowance(FIVE_HBARS)
                        .gasLimit(2_000_000L * -1)
                        .via(PAY_TXN)
                        .hasPrecheck(INVALID_ETHEREUM_TRANSACTION));
    }
}
