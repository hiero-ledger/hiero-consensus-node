// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.openzeppelin;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractLogAsserts.logWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.evmAddressFromSecp256k1Key;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_RECEIVER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_RECEIVER_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.contract.Utils.asAddressInTopic;
import static com.hedera.services.bdd.suites.contract.Utils.eventSignatureOf;
import static com.hedera.services.bdd.suites.contract.Utils.parsedToByteString;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.hiero.base.utility.CommonUtils.unhex;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ERC20ContractInteractions {
    private static final String TRANSFER = "transfer";
    private static final String VALID_ALIAS = "validAlias";
    private static final String TRANSFER_FROM = "transferFrom";
    private static final String TX_STR_PREFIX = " tx - ";
    private static final String TRANSFER_ADDRESS_ADDRESS_UINT_256 = "Transfer(address,address,uint256)";

    @HapiTest
    final Stream<DynamicTest> callsERC20ContractInteractions() {
        final var CONTRACT = "GLDToken";
        final var CREATE_TX = "create";
        final var APPROVE_TX = "approve";
        final var TRANSFER_FROM_TX = "transferFromTxn";
        final var TRANSFER_MORE_THAN_APPROVED_FROM_TX = "transferMoreThanApproved";
        final var TRANSFER_TX = TRANSFER;
        final var TRANSFER_NON_EXISTENT_TX = "transferNonExistent";
        final var NOT_ENOUGH_BALANCE_TRANSFER_TX = "notEnoughBalanceTransfer";
        final var amount = BigInteger.valueOf(1_000);
        final var initialAmount = BigInteger.valueOf(6_000);

        return hapiTest(
                getAccountBalance(DEFAULT_CONTRACT_SENDER).logged(),
                uploadInitCode(CONTRACT),
                getAccountBalance(DEFAULT_CONTRACT_SENDER).logged(),
                contractCreate(CONTRACT, initialAmount)
                        .payingWith(DEFAULT_CONTRACT_SENDER)
                        .hasKnownStatus(SUCCESS)
                        .via(CREATE_TX)
                        .withTxnTransform(tx -> {
                            System.out.println(TX_STR_PREFIX + Bytes.wrap(tx.toByteArray()));
                            return tx;
                        }),
                newKeyNamed(VALID_ALIAS).shape(SECP_256K1_SHAPE),
                getAccountBalance(DEFAULT_CONTRACT_SENDER),
                getAccountInfo(DEFAULT_CONTRACT_SENDER).savingSnapshot(DEFAULT_CONTRACT_SENDER),
                getAccountInfo(DEFAULT_CONTRACT_RECEIVER).savingSnapshot(DEFAULT_CONTRACT_RECEIVER),
                withOpContext((spec, log) -> {
                    final var ownerInfo = spec.registry().getAccountInfo(DEFAULT_CONTRACT_SENDER);
                    final var receiverInfo = spec.registry().getAccountInfo(DEFAULT_CONTRACT_RECEIVER);
                    final var ownerContractId = ownerInfo.getContractAccountID();
                    final var receiverContractId = receiverInfo.getContractAccountID();
                    final var nonExistentAccountKey = spec.registry().getKey(VALID_ALIAS);

                    final var transferParams = new Object[] {asHeadlongAddress(receiverContractId), amount};
                    final var notEnoughBalanceTransferParams = new Object[] {
                        asHeadlongAddress(receiverContractId),
                        initialAmount.subtract(amount).add(BigInteger.ONE)
                    };
                    final var approveParams = new Object[] {asHeadlongAddress(receiverContractId), amount};
                    final var transferFromParams = new Object[] {
                        asHeadlongAddress(ownerContractId), asHeadlongAddress(receiverContractId), amount
                    };
                    final var transferNonExistentParams =
                            new Object[] {evmAddressFromSecp256k1Key(nonExistentAccountKey), amount};

                    final var transferMoreThanApprovedFromParams = new Object[] {
                        asHeadlongAddress(ownerContractId),
                        asHeadlongAddress(receiverContractId),
                        amount.add(BigInteger.ONE)
                    };

                    final var transfer = contractCall(CONTRACT, TRANSFER, transferParams)
                            .payingWith(DEFAULT_CONTRACT_SENDER)
                            .via(TRANSFER_TX)
                            .withTxnTransform(tx -> {
                                System.out.println(TX_STR_PREFIX + Bytes.wrap(tx.toByteArray()));
                                return tx;
                            });

                    final var transferNonExistent = contractCall(CONTRACT, TRANSFER, transferNonExistentParams)
                            .payingWith(DEFAULT_CONTRACT_SENDER)
                            .via(TRANSFER_NON_EXISTENT_TX)
                            .withTxnTransform(tx -> {
                                System.out.println(TX_STR_PREFIX + Bytes.wrap(tx.toByteArray()));
                                return tx;
                            });

                    final var notEnoughBalanceTransfer = contractCall(
                                    CONTRACT, TRANSFER, notEnoughBalanceTransferParams)
                            .payingWith(DEFAULT_CONTRACT_SENDER)
                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                            .via(NOT_ENOUGH_BALANCE_TRANSFER_TX)
                            .withTxnTransform(tx -> {
                                System.out.println(TX_STR_PREFIX + Bytes.wrap(tx.toByteArray()));
                                return tx;
                            });

                    final var approve = contractCall(CONTRACT, "approve", approveParams)
                            .payingWith(DEFAULT_CONTRACT_SENDER)
                            .via(APPROVE_TX)
                            .withTxnTransform(tx -> {
                                System.out.println(TX_STR_PREFIX + Bytes.wrap(tx.toByteArray()));
                                return tx;
                            });

                    final var transferFrom = contractCall(CONTRACT, TRANSFER_FROM, transferFromParams)
                            .payingWith(DEFAULT_CONTRACT_RECEIVER)
                            .signingWith(SECP_256K1_RECEIVER_SOURCE_KEY)
                            .via(TRANSFER_FROM_TX)
                            .withTxnTransform(tx -> {
                                System.out.println(TX_STR_PREFIX + Bytes.wrap(tx.toByteArray()));
                                return tx;
                            });

                    final var transferMoreThanApprovedFrom = contractCall(
                                    CONTRACT, TRANSFER_FROM, transferMoreThanApprovedFromParams)
                            .payingWith(DEFAULT_CONTRACT_RECEIVER)
                            .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                            .via(TRANSFER_MORE_THAN_APPROVED_FROM_TX)
                            .withTxnTransform(tx -> {
                                System.out.println(TX_STR_PREFIX + Bytes.wrap(tx.toByteArray()));
                                return tx;
                            });

                    final var getCreateRecord = getTxnRecord(CREATE_TX)
                            .hasPriority(
                                    recordWith()
                                            .contractCreateResult(
                                                    resultWith()
                                                            .logs(
                                                                    inOrder(
                                                                            logWith()
                                                                                    .longValue(
                                                                                            initialAmount
                                                                                                    .longValueExact())
                                                                                    .withTopicsInOrder(
                                                                                            List.of(
                                                                                                    eventSignatureOf(
                                                                                                            TRANSFER_ADDRESS_ADDRESS_UINT_256),
                                                                                                    parsedToByteString(
                                                                                                            0, 0, 0),
                                                                                                    ByteString.copyFrom(
                                                                                                            asAddressInTopic(
                                                                                                                    unhex(
                                                                                                                            ownerInfo
                                                                                                                                    .getContractAccountID())))))))))
                            .logged();
                    final var getTransferRecord = getTxnRecord(TRANSFER_TX)
                            .exposingTo(tr -> System.out.println(Bytes.of(tr.toByteArray())))
                            .hasPriority(
                                    recordWith()
                                            .contractCallResult(
                                                    resultWith()
                                                            .logs(
                                                                    inOrder(
                                                                            logWith()
                                                                                    .longValue(amount.longValueExact())
                                                                                    .withTopicsInOrder(
                                                                                            List.of(
                                                                                                    eventSignatureOf(
                                                                                                            TRANSFER_ADDRESS_ADDRESS_UINT_256),
                                                                                                    ByteString.copyFrom(
                                                                                                            asAddressInTopic(
                                                                                                                    unhex(
                                                                                                                            ownerInfo
                                                                                                                                    .getContractAccountID()))),
                                                                                                    ByteString.copyFrom(
                                                                                                            asAddressInTopic(
                                                                                                                    unhex(
                                                                                                                            receiverInfo
                                                                                                                                    .getContractAccountID())))))))))
                            .logged();
                    final var getTransferNonExistentRecord = getTxnRecord(TRANSFER_NON_EXISTENT_TX)
                            .exposingTo(tr -> System.out.println(Bytes.of(tr.toByteArray())))
                            .hasPriority(recordWith()
                                    .contractCallResult(resultWith()
                                            .logs(inOrder(logWith()
                                                    .longValue(amount.longValueExact())
                                                    .withTopicsInOrder(List.of(
                                                            eventSignatureOf(TRANSFER_ADDRESS_ADDRESS_UINT_256),
                                                            ByteString.copyFrom(
                                                                    asAddressInTopic(
                                                                            unhex(ownerInfo.getContractAccountID()))),
                                                            ByteString.copyFrom(
                                                                    asAddressInTopic(
                                                                            unhex(
                                                                                    evmAddressFromSecp256k1Key(
                                                                                                    nonExistentAccountKey)
                                                                                            .toString()
                                                                                            .substring(2))))))))))
                            .logged();
                    final var getApproveRecord = getTxnRecord(APPROVE_TX)
                            .exposingTo(tr -> System.out.println(Bytes.of(tr.toByteArray())))
                            .hasPriority(
                                    recordWith()
                                            .contractCallResult(
                                                    resultWith()
                                                            .logs(
                                                                    inOrder(
                                                                            logWith()
                                                                                    .longValue(amount.longValueExact())
                                                                                    .withTopicsInOrder(
                                                                                            List.of(
                                                                                                    eventSignatureOf(
                                                                                                            "Approval(address,address,uint256)"),
                                                                                                    ByteString.copyFrom(
                                                                                                            asAddressInTopic(
                                                                                                                    unhex(
                                                                                                                            ownerInfo
                                                                                                                                    .getContractAccountID()))),
                                                                                                    ByteString.copyFrom(
                                                                                                            asAddressInTopic(
                                                                                                                    unhex(
                                                                                                                            receiverInfo
                                                                                                                                    .getContractAccountID())))))))))
                            .logged();
                    final var getTransferFromRecord = getTxnRecord(TRANSFER_FROM_TX)
                            .exposingTo(tr -> System.out.println(Bytes.of(tr.toByteArray())))
                            .hasPriority(
                                    recordWith()
                                            .contractCallResult(
                                                    resultWith()
                                                            .logs(
                                                                    inOrder(
                                                                            logWith()
                                                                                    .longValue(amount.longValueExact())
                                                                                    .withTopicsInOrder(
                                                                                            List.of(
                                                                                                    eventSignatureOf(
                                                                                                            TRANSFER_ADDRESS_ADDRESS_UINT_256),
                                                                                                    ByteString.copyFrom(
                                                                                                            asAddressInTopic(
                                                                                                                    unhex(
                                                                                                                            ownerInfo
                                                                                                                                    .getContractAccountID()))),
                                                                                                    ByteString.copyFrom(
                                                                                                            asAddressInTopic(
                                                                                                                    unhex(
                                                                                                                            receiverInfo
                                                                                                                                    .getContractAccountID()))))),
                                                                            logWith()
                                                                                    .longValue(0)
                                                                                    .withTopicsInOrder(
                                                                                            List.of(
                                                                                                    eventSignatureOf(
                                                                                                            "Approval(address,address,uint256)"),
                                                                                                    ByteString.copyFrom(
                                                                                                            asAddressInTopic(
                                                                                                                    unhex(
                                                                                                                            ownerInfo
                                                                                                                                    .getContractAccountID()))),
                                                                                                    ByteString.copyFrom(
                                                                                                            asAddressInTopic(
                                                                                                                    unhex(
                                                                                                                            receiverInfo
                                                                                                                                    .getContractAccountID())))))))))
                            .logged();

                    final var getNotEnoughBalanceTransferRecord = getTxnRecord(NOT_ENOUGH_BALANCE_TRANSFER_TX)
                            .exposingTo(tr -> System.out.println(Bytes.of(tr.toByteArray())));
                    final var transferMoreThanApprovedRecord = getTxnRecord(TRANSFER_MORE_THAN_APPROVED_FROM_TX)
                            .exposingTo(tr -> System.out.println(Bytes.of(tr.toByteArray())));

                    allRunFor(
                            spec,
                            transfer,
                            notEnoughBalanceTransfer,
                            approve,
                            transferMoreThanApprovedFrom,
                            transferFrom,
                            transferNonExistent,
                            getCreateRecord,
                            getTransferRecord,
                            getApproveRecord,
                            getTransferFromRecord,
                            getTransferNonExistentRecord,
                            getNotEnoughBalanceTransferRecord,
                            transferMoreThanApprovedRecord);
                }));
    }
}
