// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.contractListWithPropertiesInheritedFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hederahashgraph.api.proto.java.ContractGetInfoResponse;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class CreateOperationSuite {
    private static final String CONTRACT = "FactoryContract";
    private static final String CALL_RECORD_TRANSACTION_NAME = "callRecord";
    private static final String DEPLOYMENT_SUCCESS_FUNCTION = "deploymentSuccess";
    private static final String DEPLOYMENT_SUCCESS_TXN = "deploymentSuccessTxn";
    private static final String CONTRACT_INFO = "contractInfo";
    private static final String PARENT_INFO = "parentInfo";

    @HapiTest
    final Stream<DynamicTest> factoryQuickSelfDestructContract() {
        final var contract = "FactoryQuickSelfDestruct";
        final var sender = "sender";
        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract),
                cryptoCreate(sender).balance(ONE_HUNDRED_HBARS),
                contractCall(contract, "createAndDeleteChild")
                        .gas(4_000_000)
                        .via(CALL_RECORD_TRANSACTION_NAME)
                        .payingWith(sender),
                getTxnRecord(CALL_RECORD_TRANSACTION_NAME)
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .logs(inOrder(
                                                logWith()
                                                        .withTopicsInOrder(
                                                                List.of(eventSignatureOf("ChildCreated(address)"))),
                                                logWith()
                                                        .withTopicsInOrder(
                                                                List.of(eventSignatureOf("ChildDeleted()"))))))));
    }

    @HapiTest
    final Stream<DynamicTest> inheritanceOfNestedCreatedContracts() {
        final var contract = "NestedChildren";
        return hapiTest(
                uploadInitCode(contract),
                // refuse eth conversion because ethereum transaction is missing admin key and memo is same as
                // parent
                contractCreate(contract).logged().via("createRecord").refusingEthConversion(),
                getContractInfo(contract).logged().saveToRegistry(PARENT_INFO),
                contractCall(contract, "callCreate").gas(780_000).via(CALL_RECORD_TRANSACTION_NAME),
                getTxnRecord("createRecord").saveCreatedContractListToRegistry("ctorChild"),
                getTxnRecord(CALL_RECORD_TRANSACTION_NAME).saveCreatedContractListToRegistry("callChild"),
                contractListWithPropertiesInheritedFrom("callChildCallResult", 2, PARENT_INFO),
                contractListWithPropertiesInheritedFrom("ctorChildCreateResult", 3, PARENT_INFO));
    }

    @HapiTest
    final Stream<DynamicTest> simpleFactoryWorks() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, DEPLOYMENT_SUCCESS_FUNCTION).gas(780_000).via(DEPLOYMENT_SUCCESS_TXN),
                withOpContext((spec, opLog) -> {
                    final var successTxn = getTxnRecord(DEPLOYMENT_SUCCESS_TXN);
                    final var parentContract = getContractInfo(CONTRACT).saveToRegistry(CONTRACT_INFO);
                    allRunFor(spec, successTxn, parentContract);

                    final var createdContractIDs = successTxn
                            .getResponseRecord()
                            .getContractCallResult()
                            .getCreatedContractIDsList();

                    Assertions.assertEquals(createdContractIDs.size(), 1);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> stackedFactoryWorks() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "stackedDeploymentSuccess")
                        .gas(1_000_000)
                        .via("stackedDeploymentSuccessTxn"),
                withOpContext((spec, opLog) -> {
                    final var successTxn = getTxnRecord("stackedDeploymentSuccessTxn");
                    final var parentContract = getContractInfo(CONTRACT).saveToRegistry(CONTRACT_INFO);
                    allRunFor(spec, successTxn, parentContract);

                    final var createdContractIDs = successTxn
                            .getResponseRecord()
                            .getContractCallResult()
                            .getCreatedContractIDsList();

                    Assertions.assertEquals(createdContractIDs.size(), 2);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> resetOnFactoryFailureWorks() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "stackedDeploymentFailure")
                        .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                        .gas(780_000)
                        .via("deploymentFailureTxn"),
                contractCall(CONTRACT, DEPLOYMENT_SUCCESS_FUNCTION).gas(780_000).via(DEPLOYMENT_SUCCESS_TXN),
                withOpContext((spec, opLog) -> {
                    final var revertTxn = getTxnRecord("deploymentFailureTxn");
                    final var deploymentSuccessTxn = getTxnRecord(DEPLOYMENT_SUCCESS_TXN);
                    final var parentContract = getContractInfo(CONTRACT).saveToRegistry(CONTRACT_INFO);
                    allRunFor(spec, revertTxn, parentContract, deploymentSuccessTxn);

                    final var createdContracts = deploymentSuccessTxn
                            .getResponseRecord()
                            .getContractCallResult()
                            .getCreatedContractIDsList();

                    Assertions.assertTrue(revertTxn
                            .getResponseRecord()
                            .getContractCallResult()
                            .getCreatedContractIDsList()
                            .isEmpty());
                    Assertions.assertEquals(createdContracts.size(), 1);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> resetOnFactoryFailureAfterDeploymentWorks() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "failureAfterDeploy")
                        .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                        .gas(780_000)
                        .via("failureAfterDeploymentTxn"),
                contractCall(CONTRACT, DEPLOYMENT_SUCCESS_FUNCTION).gas(780_000).via(DEPLOYMENT_SUCCESS_TXN),
                withOpContext((spec, opLog) -> {
                    final var revertTxn = getTxnRecord("failureAfterDeploymentTxn");
                    final var deploymentSuccessTxn = getTxnRecord(DEPLOYMENT_SUCCESS_TXN);
                    final var parentContract = getContractInfo(CONTRACT).saveToRegistry(CONTRACT_INFO);
                    allRunFor(spec, revertTxn, parentContract, deploymentSuccessTxn);

                    final var createdContracts = deploymentSuccessTxn
                            .getResponseRecord()
                            .getContractCallResult()
                            .getCreatedContractIDsList();

                    Assertions.assertTrue(revertTxn
                            .getResponseRecord()
                            .getContractCallResult()
                            .getCreatedContractIDsList()
                            .isEmpty());
                    Assertions.assertEquals(createdContracts.size(), 1);
                }));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> resetOnStackedFactoryFailureWorks() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                contractCall(CONTRACT, "stackedDeploymentFailure")
                        .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                        .gas(780_000)
                        .via("stackedDeploymentFailureTxn"),
                contractCall(CONTRACT, DEPLOYMENT_SUCCESS_FUNCTION).gas(780_000).via(DEPLOYMENT_SUCCESS_TXN),
                withOpContext((spec, opLog) -> {
                    final var revertTxn = getTxnRecord("stackedDeploymentFailureTxn");
                    final var deploymentSuccessTxn = getTxnRecord(DEPLOYMENT_SUCCESS_TXN);
                    final var parentContract = getContractInfo(CONTRACT).saveToRegistry(CONTRACT_INFO);
                    allRunFor(spec, revertTxn, parentContract, deploymentSuccessTxn);

                    final var createdContracts = deploymentSuccessTxn
                            .getResponseRecord()
                            .getContractCallResult()
                            .getCreatedContractIDsList();

                    Assertions.assertTrue(revertTxn
                            .getResponseRecord()
                            .getContractCallResult()
                            .getCreatedContractIDsList()
                            .isEmpty());
                    Assertions.assertEquals(createdContracts.size(), 1);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> contractCreateWithNewOpInConstructorAbandoningParent() {
        final var contract = "AbandoningParent";
        return hapiTest(
                // refuse eth conversion because ethereum transaction is missing admin key (the new contract has own key
                // - isSelfAdmin(parent))
                uploadInitCode(contract),
                contractCreate(contract).via("AbandoningParentTxn").refusingEthConversion(),
                getContractInfo(contract)
                        .saveToRegistry("AbandoningParentParentInfo")
                        .logged(),
                getTxnRecord("AbandoningParentTxn")
                        .saveCreatedContractListToRegistry(contract)
                        .logged(),
                UtilVerbs.contractListWithPropertiesInheritedFrom(
                        "AbandoningParentCreateResult", 6, "AbandoningParentParentInfo"));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> childContractStorageWorks() {
        final var contract = "CreateTrivial";
        final var CREATED_TRIVIAL_CONTRACT_RETURNS = 7;

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).via("firstContractTxn"),
                assertionsHold((spec, ctxLog) -> {
                    final var subop1 =
                            contractCall(contract, "create").gas(785_000).via("createContractTxn");

                    // First contract calls created contract and get an integer
                    // return value
                    final var subop2 =
                            contractCallLocal(contract, "getIndirect").saveResultTo("contractCallContractResultBytes");
                    CustomSpecAssert.allRunFor(spec, subop1, subop2);

                    var resultBytes = spec.registry().getBytes("contractCallContractResultBytes");
                    com.esaulpaugh.headlong.abi.Function function =
                            com.esaulpaugh.headlong.abi.Function.fromJson(getABIFor(FUNCTION, "getIndirect", contract));

                    var contractCallReturnVal = 0;
                    if (resultBytes != null && resultBytes.length > 0) {
                        final var retResults = function.decodeReturn(resultBytes);
                        if (retResults != null && retResults.size() > 0) {
                            final var retBi = (BigInteger) retResults.get(0);
                            contractCallReturnVal = retBi.intValue();
                        }
                    }

                    ctxLog.info("This contract call contract return value {}", contractCallReturnVal);
                    Assertions.assertEquals(
                            CREATED_TRIVIAL_CONTRACT_RETURNS,
                            contractCallReturnVal,
                            "This contract call contract return value should be 7");

                    // Get created contract's info with call to first contract
                    final var subop3 =
                            contractCallLocal(contract, "getAddress").saveResultTo("getCreatedContractInfoResultBytes");
                    CustomSpecAssert.allRunFor(spec, subop3);

                    resultBytes = spec.registry().getBytes("getCreatedContractInfoResultBytes");

                    function =
                            com.esaulpaugh.headlong.abi.Function.fromJson(getABIFor(FUNCTION, "getAddress", contract));

                    final var retResults = function.decodeReturn(resultBytes);
                    String contractIDString = null;
                    if (retResults != null && retResults.size() > 0) {
                        contractIDString =
                                ((Address) retResults.get(0)).toString().substring(2);
                    }
                    ctxLog.info("The created contract ID {}", contractIDString);
                    Assertions.assertNotEquals(
                            ContractID.newBuilder().getDefaultInstanceForType(),
                            TxnUtils.asContractId(contractIDString, spec),
                            "Created contract doesn't have valid Contract ID");

                    final var subop4 = getContractInfo(contractIDString).saveToRegistry("createdContractInfoSaved");

                    CustomSpecAssert.allRunFor(spec, subop4);

                    final ContractGetInfoResponse.ContractInfo createdContractInfo =
                            spec.registry().getContractInfo("createdContractInfoSaved");

                    Assertions.assertTrue(createdContractInfo.hasContractID());
                    Assertions.assertTrue(createdContractInfo.hasAccountID());
                    Assertions.assertTrue(createdContractInfo.hasExpirationTime());
                }));
    }
}
