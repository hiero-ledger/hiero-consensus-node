// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.records;

import static com.hedera.services.bdd.junit.ContextRequirement.SYSTEM_ACCOUNT_BALANCES;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithFunctionAbi;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCallWithTuple;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.addLogInfo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.takeBalanceSnapshots;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateRecordTransactionFees;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateTransferListForBalances;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.NODE;
import static com.hedera.services.bdd.suites.HapiSuite.NODE_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.STAKING_REWARD;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.asSolidityAddress;
import static java.util.function.Function.identity;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractRecordsSanityCheckSuite {
    private static final String BALANCE_LOOKUP = "BalanceLookup";
    public static final String PAYABLE_CONTRACT = "PayReceivable";
    public static final String ALTRUISTIC_TXN = "altruisticTxn";

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_BALANCES)
    final Stream<DynamicTest> contractDeleteRecordSanityChecks() {
        return hapiTest(flattened(
                uploadInitCode(BALANCE_LOOKUP),
                contractCreate(BALANCE_LOOKUP).balance(1_000L),
                takeBalanceSnapshots(BALANCE_LOOKUP, FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER),
                contractDelete(BALANCE_LOOKUP).via("txn").transferAccount(DEFAULT_PAYER),
                validateTransferListForBalances(
                        "txn",
                        List.of(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER, BALANCE_LOOKUP),
                        Set.of(BALANCE_LOOKUP)),
                withOpContext((spec, opLog) -> validateRecordTransactionFees(spec, "txn"))));
    }

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_BALANCES)
    final Stream<DynamicTest> contractCreateRecordSanityChecks() {
        return hapiTest(flattened(
                uploadInitCode(BALANCE_LOOKUP),
                takeBalanceSnapshots(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER),
                contractCreate(BALANCE_LOOKUP).balance(1_000L).via("txn"),
                validateTransferListForBalances(
                        "txn", List.of(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER, BALANCE_LOOKUP)),
                withOpContext((spec, opLog) -> validateRecordTransactionFees(spec, "txn"))));
    }

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_BALANCES)
    final Stream<DynamicTest> contractCallWithSendRecordSanityChecks() {
        return hapiTest(flattened(
                uploadInitCode(PAYABLE_CONTRACT),
                contractCreate(PAYABLE_CONTRACT),
                UtilVerbs.takeBalanceSnapshots(
                        PAYABLE_CONTRACT, FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER),
                contractCall(PAYABLE_CONTRACT, "deposit", BigInteger.valueOf(1_000L))
                        .via("txn")
                        .sending(1_000L),
                validateTransferListForBalances(
                        "txn", List.of(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER, PAYABLE_CONTRACT)),
                withOpContext((spec, opLog) -> validateRecordTransactionFees(spec, "txn"))));
    }

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_BALANCES)
    final Stream<DynamicTest> circularTransfersRecordSanityChecks() {
        final var contractName = "CircularTransfers";
        int numAltruists = 3;
        ToLongFunction<String> initBalanceFn = ignore -> 1_000_000L;
        long initKeepAmountDivisor = 2;
        BigInteger stopBalance = BigInteger.valueOf(399_999L);

        String[] canonicalAccounts = {FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER};
        String[] altruists = IntStream.range(0, numAltruists)
                .mapToObj(i -> String.format("Altruist%s", (char) ('A' + i)))
                .toArray(String[]::new);

        return hapiTest(flattened(
                uploadInitCode(contractName),
                Stream.of(altruists)
                        .map(suffix ->
                                createDefaultContract(contractName + suffix).bytecode(contractName))
                        .toArray(HapiSpecOperation[]::new),
                Stream.of(altruists)
                        .map(suffix -> contractCallWithTuple(
                                        contractName + suffix,
                                        SET_NODES_ABI,
                                        spec -> Tuple.singleton(Stream.of(altruists)
                                                .map(a -> new BigInteger(asSolidityAddress(
                                                        spec.registry().getContractId(contractName + a))))
                                                .toArray(BigInteger[]::new)))
                                .gas(120_000)
                                .via("txnFor" + contractName + suffix)
                                .sending(initBalanceFn.applyAsLong(contractName + suffix)))
                        .toArray(HapiSpecOperation[]::new),
                UtilVerbs.takeBalanceSnapshots(Stream.of(
                                Stream.of(altruists).map(suffix -> contractName + suffix), Stream.of(canonicalAccounts))
                        .flatMap(identity())
                        .toArray(String[]::new)),
                contractCallWithFunctionAbi(
                                contractName + altruists[0], RECEIVE_AND_SEND_ABI, initKeepAmountDivisor, stopBalance)
                        .via(ALTRUISTIC_TXN),
                validateTransferListForBalances(
                        ALTRUISTIC_TXN,
                        Stream.concat(
                                        Stream.of(canonicalAccounts),
                                        Stream.of(altruists).map(suffix -> contractName + suffix))
                                .toList()),
                withOpContext((spec, opLog) -> validateRecordTransactionFees(spec, ALTRUISTIC_TXN)),
                addLogInfo((spec, infoLog) -> {
                    long[] finalBalances = IntStream.range(0, numAltruists)
                            .mapToLong(ignore -> initBalanceFn.applyAsLong(""))
                            .toArray();
                    int i = 0;
                    long divisor = initKeepAmountDivisor;
                    while (true) {
                        long toKeep = finalBalances[i] / divisor;
                        if (toKeep < stopBalance.longValue()) {
                            break;
                        }
                        int j = (i + 1) % numAltruists;
                        finalBalances[j] += (finalBalances[i] - toKeep);
                        finalBalances[i] = toKeep;
                        i = j;
                        divisor++;
                    }

                    infoLog.info("Expected Final Balances");
                    infoLog.info("-----------------------");
                    for (i = 0; i < numAltruists; i++) {
                        infoLog.info("  {} = {} tinyBars", i, finalBalances[i]);
                    }
                })));
    }

    @LeakyHapiTest(requirement = SYSTEM_ACCOUNT_BALANCES)
    final Stream<DynamicTest> contractUpdateRecordSanityChecks() {
        return hapiTest(flattened(
                newKeyNamed("newKey").type(KeyFactory.KeyType.SIMPLE),
                uploadInitCode(BALANCE_LOOKUP),
                contractCreate(BALANCE_LOOKUP).balance(1_000L),
                takeBalanceSnapshots(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER),
                contractUpdate(BALANCE_LOOKUP).newKey("newKey").via("txn").fee(95_000_000L),
                validateTransferListForBalances(
                        "txn", List.of(FUNDING, NODE, STAKING_REWARD, NODE_REWARD, DEFAULT_PAYER)),
                withOpContext((spec, opLog) -> validateRecordTransactionFees(spec, "txn"))));
    }

    private static final String SET_NODES_ABI =
            "{ \"constant\": false, \"inputs\": [ { \"internalType\": \"uint64[]\", \"name\":"
                    + " \"accounts\", \"type\": \"uint160[]\" }     ], \"name\": \"setNodes\","
                    + " \"outputs\": [], \"payable\": true, \"stateMutability\": \"payable\", \"type\":"
                    + " \"function\" }";

    private static final String RECEIVE_AND_SEND_ABI =
            "{ \"constant\": false, \"inputs\": [ { \"internalType\": \"uint32\", \"name\":"
                    + " \"keepAmountDivisor\", \"type\": \"uint32\" }, { \"internalType\": \"uint256\","
                    + " \"name\": \"stopBalance\", \"type\": \"uint256\" } ], \"name\":"
                    + " \"receiveAndSend\", \"outputs\": [], \"payable\": true, \"stateMutability\":"
                    + " \"payable\", \"type\": \"function\" }";
}
