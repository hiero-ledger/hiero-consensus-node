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
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrParamFunction;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.contract.evm.Evm46ValidationSuite.systemAccounts;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ExtCodeCopyOperationSuite {
    @SuppressWarnings("java:S5960")
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> verifiesExistence() {
        final var contract = "ExtCodeOperationsChecker";
        final var invalidAddress = "0x0000000000000000000000000000000000123456";
        final var emptyBytecode = ByteString.EMPTY;
        final var codeCopyOf = "codeCopyOf";
        final var account = "account";

        return hapiTest(
                cryptoCreate(account),
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract, codeCopyOf, asHeadlongAddress(invalidAddress))
                        .hasKnownStatus(SUCCESS),
                contractCallLocal(contract, codeCopyOf, asHeadlongAddress(invalidAddress))
                        .hasAnswerOnlyPrecheck(OK),
                withOpContext((spec, opLog) -> {
                    final var accountID = spec.registry().getAccountID(account);
                    final var contractID = spec.registry().getContractId(contract);
                    final var accountSolidityAddress = asHexedSolidityAddress(accountID);
                    final var contractAddress = asHexedSolidityAddress(contractID);

                    final var call = contractCall(contract, codeCopyOf, asHeadlongAddress(accountSolidityAddress))
                            .via("callRecord");
                    final var callRecord = getTxnRecord("callRecord");

                    final var accountCodeCallLocal = contractCallLocal(
                                    contract, codeCopyOf, asHeadlongAddress(accountSolidityAddress))
                            .saveResultTo("accountCode");

                    final var contractCodeCallLocal = contractCallLocal(
                                    contract, codeCopyOf, asHeadlongAddress(contractAddress))
                            .saveResultTo("contractCode");

                    final var getBytecodeCall = getContractBytecode(contract).saveResultTo("contractGetBytecode");

                    allRunFor(spec, call, callRecord, accountCodeCallLocal, contractCodeCallLocal, getBytecodeCall);

                    final var recordResult = callRecord.getResponseRecord().getContractCallResult();
                    final var accountCode = spec.registry().getBytes("accountCode");
                    final var contractCode = spec.registry().getBytes("contractCode");
                    final var getBytecode = spec.registry().getBytes("contractGetBytecode");

                    Assertions.assertEquals(emptyBytecode, recordResult.getContractCallResult());
                    Assertions.assertArrayEquals(emptyBytecode.toByteArray(), accountCode);
                    Assertions.assertArrayEquals(getBytecode, contractCode);
                }));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> testExtCodeCopyWithSystemAccounts() {
        final var contract = "ExtCodeOperationsChecker";
        final var emptyBytecode = ByteString.EMPTY;
        final var codeCopyOf = "codeCopyOf";
        final var account = "account";
        final HapiSpecOperation[] opsArray = new HapiSpecOperation[systemAccounts.size() * 2];

        for (int i = 0; i < systemAccounts.size(); i++) {
            // add contract call for all accounts in the list
            final var index = i;
            opsArray[i] = contractCall(contract, codeCopyOf, mirrorAddrParamFunction(systemAccounts.get(index)))
                    .hasKnownStatus(SUCCESS);

            // add contract call local for all accounts in the list
            int finalI = i;
            opsArray[systemAccounts.size() + i] = withOpContext((spec, opLog) -> {
                final var accountSolidityAddress = mirrorAddrWith(spec, systemAccounts.get(finalI));

                final var accountCodeCallLocal = contractCallLocal(contract, codeCopyOf, accountSolidityAddress)
                        .saveResultTo("accountCode");
                allRunFor(spec, accountCodeCallLocal);

                final var accountCode = spec.registry().getBytes("accountCode");

                Assertions.assertArrayEquals(emptyBytecode.toByteArray(), accountCode);
            });
        }

        return hapiTest(flattened(uploadInitCode(contract), contractCreate(contract), cryptoCreate(account), opsArray));
    }
}
