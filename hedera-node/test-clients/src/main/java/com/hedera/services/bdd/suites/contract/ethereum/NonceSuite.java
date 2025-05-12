// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asHexedSolidityAddress;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumContractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NEGATIVE_ALLOWANCE_AMOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hederahashgraph.api.proto.java.AccountID;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@SuppressWarnings("java:S5960")
public class NonceSuite {
    private static final long LOW_GAS_PRICE = 1L;
    private static final long ENOUGH_GAS_PRICE = 75L;
    private static final long ENOUGH_GAS_LIMIT = 215_000L;
    private static final String RECEIVER = "receiver";
    private static final String INTERNAL_CALLEE_CONTRACT = "InternalCallee";
    private static final String INTERNAL_CALLER_CONTRACT = "InternalCaller";
    private static final String MANY_CHILDREN_CONTRACT = "ManyChildren";
    private static final String FACTORY_CONTRACT = "FactoryContract";
    private static final String REVERTER_CONSTRUCTOR_CONTRACT = "ReverterConstructor";
    private static final String REVERTER_CONSTRUCTOR_TRANSFER_CONTRACT = "ReverterConstructorTransfer";
    private static final String REVERTER_CONSTRUCTOR_CALL_WITH_VALUE_TO_ETH_PRECOMPILE_CONTRACT =
            "ConsValueEthPrecompile";
    private static final String REVERTER_CONSTRUCTOR_CALL_WITH_VALUE_TO_HEDERA_PRECOMPILE_CONTRACT =
            "ConsValueSysContract";
    private static final String EXTERNAL_FUNCTION = "externalFunction";
    private static final String REVERT_WITH_REVERT_REASON_FUNCTION = "revertWithRevertReason";
    private static final String TRANSFER_TO_FUNCTION = "transferTo";
    private static final String DEPLOYMENT_SUCCESS_FUNCTION = "deploymentSuccess";
    private static final String CHECK_BALANCE_REPEATEDLY_FUNCTION = "checkBalanceRepeatedly";
    private static final String TX = "tx";

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenSignerDoesExistPrecheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .hasRetryPrecheckFrom(BUSY)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .hasPrecheck(INVALID_ACCOUNT_ID),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).hasCostAnswerPrecheck(INVALID_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenPayerHasInsufficientBalancePrecheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(1L),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(5644L)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)));
    }

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenNegativeMaxGasAllowancePrecheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxGasAllowance(-1L)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .hasPrecheck(NEGATIVE_ALLOWANCE_AMOUNT),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)));
    }

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenInsufficientIntrinsicGasPrecheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(0L)
                        .hasPrecheck(INSUFFICIENT_GAS),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)));
    }

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenMaxGasPerSecPrecheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                sourcing(() -> ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(Long.MAX_VALUE)
                        .hasPrecheckFrom(MAX_GAS_LIMIT_EXCEEDED, BUSY)),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)));
    }

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenIntrinsicGasHandlerCheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(21_000L)
                        .hasPrecheck(INSUFFICIENT_GAS)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)));
    }

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenUserOfferedGasPriceAndAllowanceAreZeroHandlerCheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .maxFeePerGas(0L)
                        .maxGasAllowance(0L)
                        .hasKnownStatus(INSUFFICIENT_TX_FEE)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INSUFFICIENT_TX_FEE)
                                .contractCallResult(resultWith().signerNonce(0L))));
    }

    @HapiTest
    final Stream<DynamicTest>
            nonceNotUpdatedWhenOfferedGasPriceIsLessThanCurrentAndSenderDoesNotHaveEnoughBalanceHandlerCheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, 1L)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .maxFeePerGas(LOW_GAS_PRICE)
                        .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INSUFFICIENT_PAYER_BALANCE)
                                .contractCallResult(resultWith().signerNonce(0L))));
    }

    @HapiTest
    final Stream<DynamicTest>
            nonceNotUpdatedWhenOfferedGasPriceIsLessThanCurrentAndGasAllowanceIsLessThanRemainingFeeHandlerCheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .maxFeePerGas(LOW_GAS_PRICE)
                        .maxGasAllowance(0L)
                        .hasKnownStatus(INSUFFICIENT_TX_FEE)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INSUFFICIENT_TX_FEE)
                                .contractCallResult(resultWith().signerNonce(0L))));
    }

    @HapiTest
    final Stream<DynamicTest>
            nonceNotUpdatedWhenOfferedGasPriceIsBiggerThanCurrentAndSenderDoesNotHaveEnoughBalanceHandlerCheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, 1L)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .maxFeePerGas(ENOUGH_GAS_PRICE)
                        .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INSUFFICIENT_PAYER_BALANCE)
                                .contractCallResult(resultWith().signerNonce(0L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenSenderDoesNotHaveEnoughBalanceHandlerCheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, 1L)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .maxFeePerGas(0L)
                        .sending(5L)
                        .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INSUFFICIENT_PAYER_BALANCE)
                                .contractCallResult(resultWith().signerNonce(0L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedForNonEthereumTransaction() {
        return hapiTest(
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                contractCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .gas(ENOUGH_GAS_LIMIT)
                        .payingWith(RELAYER)
                        .signingWith(RELAYER)
                        .via(TX),
                getAccountInfo(RELAYER).has(accountWith().nonce(0L)));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterSuccessfulInternalCall() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueContractLogic() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, REVERT_WITH_REVERT_REASON_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(CONTRACT_REVERT_EXECUTED)
                                .contractCallResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueInsufficientGas() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(21_064L)
                        .hasKnownStatus(INSUFFICIENT_GAS)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INSUFFICIENT_GAS)
                                .contractCallResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueInsufficientTransferAmount() {
        AtomicReference<AccountID> receiverId = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(RECEIVER).balance(0L).exposingCreatedIdTo(receiverId::set),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        ethereumCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        TRANSFER_TO_FUNCTION,
                                        mirrorAddrWith(spec, receiverId.get().getAccountNum()))
                                .type(EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0)
                                .gasLimit(ENOUGH_GAS_LIMIT)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via(TX))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(CONTRACT_REVERT_EXECUTED)
                                .contractCallResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueSendingValueToEthereumPrecompile0x2() {
        AccountID eth0x2 = AccountID.newBuilder().setAccountNum(2).build();
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        ethereumCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        TRANSFER_TO_FUNCTION,
                                        mirrorAddrWith(spec, eth0x2.getAccountNum()))
                                .type(EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0)
                                .gasLimit(ENOUGH_GAS_LIMIT)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via(TX))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(CONTRACT_REVERT_EXECUTED)
                                .contractCallResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueSendingValueToHederaPrecompile0x167() {
        AccountID eth0x167 = AccountID.newBuilder().setAccountNum(2).build();
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        ethereumCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        TRANSFER_TO_FUNCTION,
                                        mirrorAddrWith(spec, eth0x167.getAccountNum()))
                                .type(EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0)
                                .gasLimit(ENOUGH_GAS_LIMIT)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via(TX))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(CONTRACT_REVERT_EXECUTED)
                                .contractCallResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueMaxChildRecordsExceeded() {
        final String TOKEN_TREASURY = "treasury";
        final String FUNGIBLE_TOKEN = "fungibleToken";
        final AtomicReference<String> treasuryMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> treasuryMirrorAddr.set(asHexedSolidityAddress(id))),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(MANY_CHILDREN_CONTRACT),
                contractCreate(MANY_CHILDREN_CONTRACT),
                tokenCreate(FUNGIBLE_TOKEN)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(1_000_000L)
                        .exposingCreatedIdTo(
                                id -> tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(id)))),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        ethereumCall(
                                        MANY_CHILDREN_CONTRACT,
                                        CHECK_BALANCE_REPEATEDLY_FUNCTION,
                                        asHeadlongAddress(tokenMirrorAddr.get()),
                                        asHeadlongAddress(treasuryMirrorAddr.get()),
                                        BigInteger.valueOf(spec.startupProperties()
                                                        .getInteger("consensus.handle.maxFollowingRecords")
                                                + 1))
                                .type(EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0)
                                .gasLimit(ENOUGH_GAS_LIMIT)
                                .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED)
                                .via(TX))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(MAX_CHILD_RECORDS_EXCEEDED)
                                .contractCallResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterSuccessfulInternalTransfer() {
        AtomicReference<AccountID> receiverId = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(RECEIVER).balance(0L).exposingCreatedIdTo(receiverId::set),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        ethereumCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        TRANSFER_TO_FUNCTION,
                                        mirrorAddrWith(spec, receiverId.get().getAccountNum()))
                                .type(EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0)
                                .gasLimit(ENOUGH_GAS_LIMIT)
                                .via(TX))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterSuccessfulInternalContractDeployment() {
        return hapiTest(
                cryptoCreate(RECEIVER).balance(0L),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(FACTORY_CONTRACT),
                contractCreate(FACTORY_CONTRACT),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        ethereumCall(FACTORY_CONTRACT, DEPLOYMENT_SUCCESS_FUNCTION)
                                .type(EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0)
                                .gasLimit(ENOUGH_GAS_LIMIT)
                                .via(TX))),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .logged()
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenIntrinsicGasHandlerCheckFailedEthContractCreate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_MILLION_HBARS)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                ethereumContractCreate(INTERNAL_CALLEE_CONTRACT)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(21_000L)
                        .hasPrecheck(INSUFFICIENT_GAS)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)));
    }

    @HapiTest
    final Stream<DynamicTest>
            nonceNotUpdatedWhenUserOfferedGasPriceAndAllowanceAreZeroHandlerCheckFailedEthContractCreate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_MILLION_HBARS)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                ethereumContractCreate(INTERNAL_CALLEE_CONTRACT)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .maxFeePerGas(0L)
                        .maxGasAllowance(0L)
                        .hasKnownStatus(INSUFFICIENT_TX_FEE)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INSUFFICIENT_TX_FEE)
                                .contractCallResult(resultWith().signerNonce(0L))));
    }

    @HapiTest
    final Stream<DynamicTest>
            nonceNotUpdatedWhenOfferedGasPriceIsLessThanCurrentAndSenderDoesNotHaveEnoughBalanceHandlerCheckFailedEthContractCreate() {
        return defaultHapiSpec(
                        "nonceNotUpdatedWhenOfferedGasPriceIsLessThanCurrentAndSenderDoesNotHaveEnoughBalanceHandlerCheckFailedEthContractCreate")
                .given(
                        newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                        cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                        cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, 1L)),
                        uploadInitCode(INTERNAL_CALLEE_CONTRACT))
                .when(ethereumContractCreate(INTERNAL_CALLEE_CONTRACT)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .maxFeePerGas(LOW_GAS_PRICE)
                        .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE)
                        .via(TX))
                .then(
                        getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                                .has(accountWith().nonce(0L)),
                        getTxnRecord(TX)
                                .hasPriority(recordWith()
                                        .status(INSUFFICIENT_PAYER_BALANCE)
                                        .contractCallResult(resultWith().signerNonce(0L))));
    }

    @HapiTest
    final Stream<DynamicTest>
            nonceNotUpdatedWhenOfferedGasPriceIsLessThanCurrentAndGasAllowanceIsLessThanRemainingFeeHandlerCheckFailedEthContractCreate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_MILLION_HBARS)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                ethereumContractCreate(INTERNAL_CALLEE_CONTRACT)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .maxFeePerGas(LOW_GAS_PRICE)
                        .maxGasAllowance(0L)
                        .hasKnownStatus(INSUFFICIENT_TX_FEE)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INSUFFICIENT_TX_FEE)
                                .contractCallResult(resultWith().signerNonce(0L))));
    }

    @HapiTest
    final Stream<DynamicTest>
            nonceNotUpdatedWhenOfferedGasPriceIsBiggerThanCurrentAndSenderDoesNotHaveEnoughBalanceHandlerCheckFailedEthContractCreate() {
        return defaultHapiSpec(
                        "nonceNotUpdatedWhenOfferedGasPriceIsBiggerThanCurrentAndSenderDoesNotHaveEnoughBalanceHandlerCheckFailedEthContractCreate")
                .given(
                        newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                        cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                        cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, 1L)),
                        uploadInitCode(INTERNAL_CALLEE_CONTRACT))
                .when(ethereumContractCreate(INTERNAL_CALLEE_CONTRACT)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .maxFeePerGas(ENOUGH_GAS_PRICE)
                        .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE)
                        .via(TX))
                .then(
                        getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                                .has(accountWith().nonce(0L)),
                        getTxnRecord(TX)
                                .hasPriority(recordWith()
                                        .status(INSUFFICIENT_PAYER_BALANCE)
                                        .contractCallResult(resultWith().signerNonce(0L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenSenderDoesNotHaveEnoughBalanceHandlerCheckFailedEthContractCreate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, 1L)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                ethereumContractCreate(INTERNAL_CALLEE_CONTRACT)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .maxFeePerGas(0L)
                        .balance(5L)
                        .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INSUFFICIENT_PAYER_BALANCE)
                                .contractCallResult(resultWith().signerNonce(0L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueContractLogicEthContractCreate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_MILLION_HBARS)),
                uploadInitCode(REVERTER_CONSTRUCTOR_CONTRACT),
                ethereumContractCreate(REVERTER_CONSTRUCTOR_CONTRACT)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(CONTRACT_REVERT_EXECUTED)
                                .contractCreateResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueInsufficientGasEthContractCreate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_MILLION_HBARS)),
                uploadInitCode(REVERTER_CONSTRUCTOR_TRANSFER_CONTRACT),
                ethereumContractCreate(REVERTER_CONSTRUCTOR_TRANSFER_CONTRACT)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(60_000L)
                        .hasKnownStatus(INSUFFICIENT_GAS)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INSUFFICIENT_GAS)
                                .contractCreateResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueInsufficientTransferAmountEthContractCreate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_MILLION_HBARS)),
                uploadInitCode(REVERTER_CONSTRUCTOR_TRANSFER_CONTRACT),
                ethereumContractCreate(REVERTER_CONSTRUCTOR_TRANSFER_CONTRACT)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(
                                recordWith().contractCreateResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueSendingValueToEthereumPrecompileEthContractCreate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_MILLION_HBARS)),
                uploadInitCode(REVERTER_CONSTRUCTOR_CALL_WITH_VALUE_TO_ETH_PRECOMPILE_CONTRACT),
                ethereumContractCreate(REVERTER_CONSTRUCTOR_CALL_WITH_VALUE_TO_ETH_PRECOMPILE_CONTRACT)
                        .balance(1L)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .hasKnownStatus(INVALID_CONTRACT_ID)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX).hasNonStakingChildRecordCount(0));
    }

    // depends on https://github.com/hashgraph/hedera-services/pull/11359
    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueSendingValueToHederaPrecompileEthContractCreate() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_MILLION_HBARS)),
                uploadInitCode(REVERTER_CONSTRUCTOR_CALL_WITH_VALUE_TO_HEDERA_PRECOMPILE_CONTRACT),
                ethereumContractCreate(REVERTER_CONSTRUCTOR_CALL_WITH_VALUE_TO_HEDERA_PRECOMPILE_CONTRACT)
                        .balance(1L)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(ENOUGH_GAS_LIMIT)
                        .hasKnownStatus(INVALID_CONTRACT_ID)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(recordWith()
                                .status(INVALID_CONTRACT_ID)
                                .contractCreateResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> nonceUpdatedAfterSuccessfulEthereumContractCreation() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_MILLION_HBARS)),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                ethereumContractCreate(INTERNAL_CALLER_CONTRACT)
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(4_000_000L)
                        .via(TX),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(1L)),
                getTxnRecord(TX)
                        .hasPriority(
                                recordWith().contractCreateResult(resultWith().signerNonce(1L))));
    }

    @HapiTest
    final Stream<DynamicTest> revertsWhenSenderDoesNotExist() {
        AtomicReference<AccountID> receiverId = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(RECEIVER).balance(0L).exposingCreatedIdTo(receiverId::set),
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                withOpContext((spec, op) -> allRunFor(
                        spec,
                        ethereumCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        TRANSFER_TO_FUNCTION,
                                        mirrorAddrWith(spec, receiverId.get().getAccountNum()))
                                .type(EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0)
                                .gasLimit(ENOUGH_GAS_LIMIT)
                                .hasKnownStatus(INVALID_ACCOUNT_ID)
                                .via(TX))),
                getTxnRecord(TX)
                        .hasPriority(
                                recordWith().contractCallResult(resultWith().signerNonce(0L))));
    }
}
