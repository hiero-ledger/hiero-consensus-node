// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrParamFunction;
import static com.hedera.services.bdd.suites.contract.evm.Evm46ValidationSuite.systemAccounts;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.Hash;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ExtCodeHashOperationSuite {
    @SuppressWarnings("java:S5960")
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> verifiesExistence() {
        final var contract = "ExtCodeOperationsChecker";
        final var invalidAddress = "0x0000000000000000000000000000000000123456";
        final var expectedAccountHash =
                ByteString.copyFrom(Hash.keccak256(Bytes.EMPTY).toArray());
        final var hashOf = "hashOf";

        final String account = "account";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                cryptoCreate(account),
                contractCall(contract, hashOf, asHeadlongAddress(invalidAddress))
                        .hasKnownStatus(SUCCESS),
                contractCallLocal(contract, hashOf, asHeadlongAddress(invalidAddress))
                        .hasAnswerOnlyPrecheck(OK),
                withOpContext((spec, opLog) -> {
                    final var accountID = spec.registry().getAccountID(account);
                    final var contractID = spec.registry().getContractId(contract);
                    final var accountSolidityAddress = asHexedSolidityAddress(accountID);
                    final var contractAddress = asHexedSolidityAddress(contractID);

                    final var call = contractCall(contract, hashOf, asHeadlongAddress(accountSolidityAddress))
                            .via("callRecord");
                    final var callRecord = getTxnRecord("callRecord");

                    final var accountCodeHashCallLocal = contractCallLocal(
                                    contract, hashOf, asHeadlongAddress(accountSolidityAddress))
                            .saveResultTo("accountCodeHash");

                    final var contractCodeHash = contractCallLocal(contract, hashOf, asHeadlongAddress(contractAddress))
                            .saveResultTo("contractCodeHash");

                    final var getBytecode = getContractBytecode(contract).saveResultTo("contractBytecode");

                    allRunFor(spec, call, callRecord, accountCodeHashCallLocal, contractCodeHash, getBytecode);

                    final var recordResult = callRecord.getResponseRecord().getContractCallResult();
                    final var accountCodeHash = spec.registry().getBytes("accountCodeHash");

                    final var contractCodeResult = spec.registry().getBytes("contractCodeHash");
                    final var contractBytecode = spec.registry().getBytes("contractBytecode");
                    final var expectedContractCodeHash = ByteString.copyFrom(
                                    Hash.keccak256(Bytes.of(contractBytecode)).toArray())
                            .toByteArray();

                    Assertions.assertEquals(expectedAccountHash, recordResult.getContractCallResult());
                    Assertions.assertArrayEquals(expectedAccountHash.toByteArray(), accountCodeHash);
                    Assertions.assertArrayEquals(expectedContractCodeHash, contractCodeResult);
                }));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> testExtCodeHashWithSystemAccounts() {
        final var contract = "ExtCodeOperationsChecker";
        final var hashOf = "hashOf";
        final String account = "account";
        final HapiSpecOperation[] opsArray = new HapiSpecOperation[systemAccounts.size() * 2];

        for (int i = 0; i < systemAccounts.size(); i++) {
            final var index = i;
            // add contract call for all accounts in the list
            opsArray[i] = contractCall(contract, hashOf, mirrorAddrParamFunction(systemAccounts.get(index)))
                    .hasKnownStatus(SUCCESS);

            // add contract call local for all accounts in the list
            opsArray[systemAccounts.size() + i] = contractCallLocal(
                            contract, hashOf, mirrorAddrParamFunction(systemAccounts.get(index)))
                    .has(ContractFnResultAsserts.resultWith()
                            .resultThruAbi(
                                    getABIFor(FUNCTION, hashOf, contract),
                                    ContractFnResultAsserts.isLiteralResult(new Object[] {new byte[32]})));
        }

        return hapiTest(flattened(uploadInitCode(contract), contractCreate(contract), cryptoCreate(account), opsArray));
    }
}
