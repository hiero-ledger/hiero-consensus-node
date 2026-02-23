// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createDefaultContract;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemContractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemContractUndelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.specOps;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_DELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_UNDELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.TOKEN_TREASURY;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.LOCAL_CALL_MODIFICATION_EXCEPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractDeleteSuite {
    private static final String CONTRACT = "Multipurpose";
    private static final String PAYABLE_CONSTRUCTOR = "PayableConstructor";
    private static final String CONTRACT_DESTROY = "destroy";
    private static final String RECEIVER_CONTRACT_NAME = "receiver";
    private static final String SIMPLE_STORAGE_CONTRACT = "SimpleStorage";

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                newKeyNamed("adminKey"),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                cryptoCreate("transferAccount"),
                contractCreate("a").bytecode(CONTRACT).adminKey("adminKey"),
                contractCreate("b").bytecode(CONTRACT).adminKey("adminKey"),
                submitModified(withSuccessivelyVariedBodyIds(), () -> contractDelete("a")
                        .transferAccount("transferAccount")),
                submitModified(withSuccessivelyVariedBodyIds(), () -> contractDelete("b")
                        .transferContract(CONTRACT)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUseMoreThanChildContractLimit() {
        final var fungible = "fungible";
        final var contract = "ManyChildren";
        final var precompileViolation = "precompileViolation";
        final var internalCreateViolation = "internalCreateViolation";
        final AtomicReference<String> treasuryMirrorAddr = new AtomicReference<>();
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        return hapiTest(
                cryptoCreate(TOKEN_TREASURY)
                        .exposingCreatedIdTo(id -> treasuryMirrorAddr.set(asHexedSolidityAddress(id))),
                tokenCreate(fungible).treasury(TOKEN_TREASURY),
                tokenCreate(fungible)
                        .tokenType(FUNGIBLE_COMMON)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(1234567)
                        .exposingCreatedIdTo(idLit ->
                                tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                uploadInitCode(contract),
                contractCreate(contract),
                doSeveralWithStartupConfig("consensus.handle.maxFollowingRecords", value -> {
                    final var illegalNumChildren = Integer.parseInt(value) + 1;
                    return specOps(
                            contractCall(
                                            contract,
                                            "checkBalanceRepeatedly",
                                            asHeadlongAddress(tokenMirrorAddr.get()),
                                            asHeadlongAddress(treasuryMirrorAddr.get()),
                                            BigInteger.valueOf(illegalNumChildren))
                                    .via(precompileViolation)
                                    .gas(215_000L)
                                    .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED),
                            contractCall(contract, "createThingsRepeatedly", BigInteger.valueOf(illegalNumChildren))
                                    .via(internalCreateViolation)
                                    .gas(15_000_000L)
                                    .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED));
                }),
                getTxnRecord(precompileViolation)
                        .andAllChildRecords()
                        .hasChildRecords(IntStream.range(0, 50)
                                .mapToObj(i -> recordWith().status(REVERTED_SUCCESS))
                                .toArray(TransactionRecordAsserts[]::new)),
                getTxnRecord(internalCreateViolation)
                        .andAllChildRecords()
                        // Reverted internal CONTRACT_CREATION messages are not externalized
                        .hasChildRecords());
    }

    @HapiTest
    final Stream<DynamicTest> cannotSendValueToTokenAccount() {
        final var multiKey = "multiKey";
        final var nonFungibleToken = "NFT";
        final var contract = "ManyChildren";
        final var internalViolation = "internal";
        final var externalViolation = "external";
        final AtomicReference<String> tokenMirrorAddr = new AtomicReference<>();
        return hapiTest(
                newKeyNamed(multiKey),
                cryptoCreate(TOKEN_TREASURY).balance(ONE_HUNDRED_HBARS),
                tokenCreate(nonFungibleToken)
                        .supplyType(TokenSupplyType.INFINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE)
                        .treasury(TOKEN_TREASURY)
                        .initialSupply(0)
                        .supplyKey(multiKey)
                        .exposingCreatedIdTo(idLit ->
                                tokenMirrorAddr.set(asHexedSolidityAddress(HapiPropertySource.asToken(idLit)))),
                uploadInitCode(contract),
                contractCreate(contract),
                sourcing(() -> contractCall(contract, "sendSomeValueTo", asHeadlongAddress(tokenMirrorAddr.get()))
                        .sending(ONE_HBAR)
                        .payingWith(TOKEN_TREASURY)
                        .via(internalViolation)
                        .hasKnownStatus(CONTRACT_REVERT_EXECUTED)),
                sourcing((() -> contractCall(tokenMirrorAddr.get())
                        .sending(1L)
                        .payingWith(TOKEN_TREASURY)
                        .refusingEthConversion()
                        .via(externalViolation)
                        .hasKnownStatus(LOCAL_CALL_MODIFICATION_EXCEPTION))),
                getTxnRecord(internalViolation).hasPriority(recordWith().feeGreaterThan(0L)),
                getTxnRecord(externalViolation).hasPriority(recordWith().feeGreaterThan(0L)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteOrSelfDestructTokenTreasury() {
        final var someToken = "someToken";
        final var selfDestructCallable = "SelfDestructCallable";
        final var multiKey = "multi";
        final var escapeRoute = "civilian";
        final var beneficiary = "beneficiary";
        return hapiTest(
                cryptoCreate(beneficiary).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(multiKey),
                cryptoCreate(escapeRoute),
                uploadInitCode(selfDestructCallable),
                contractCustomCreate(selfDestructCallable, "1")
                        .adminKey(multiKey)
                        .balance(123)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                contractCustomCreate(selfDestructCallable, "2")
                        .adminKey(multiKey)
                        .balance(321)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                tokenCreate(someToken).adminKey(multiKey).treasury(selfDestructCallable + "1"),
                contractDelete(selfDestructCallable + "1").hasKnownStatus(ACCOUNT_IS_TREASURY),
                tokenAssociate(selfDestructCallable + "2", someToken),
                tokenUpdate(someToken)
                        .treasury(selfDestructCallable + "2")
                        .signedByPayerAnd(multiKey, selfDestructCallable + "2"),
                contractDelete(selfDestructCallable + "1"),
                contractCall(selfDestructCallable + "2", CONTRACT_DESTROY)
                        .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION)
                        .payingWith(beneficiary),
                tokenAssociate(escapeRoute, someToken),
                tokenUpdate(someToken).treasury(escapeRoute).signedByPayerAnd(multiKey, escapeRoute),
                contractCall(selfDestructCallable + "2", CONTRACT_DESTROY).payingWith(beneficiary));
    }

    @HapiTest
    final Stream<DynamicTest> cannotDeleteOrSelfDestructContractWithNonZeroBalance() {
        final var someToken = "someToken";
        final var multiKey = "multi";
        final var selfDestructableContract = "SelfDestructCallable";
        final var otherMiscContract = "PayReceivable";
        final var beneficiary = "beneficiary";

        return hapiTest(
                cryptoCreate(beneficiary).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(multiKey),
                uploadInitCode(selfDestructableContract),
                contractCreate(selfDestructableContract)
                        .adminKey(multiKey)
                        .balance(123)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion(),
                uploadInitCode(otherMiscContract),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(otherMiscContract).refusingEthConversion(),
                tokenCreate(someToken)
                        .initialSupply(0L)
                        .adminKey(multiKey)
                        .supplyKey(multiKey)
                        .treasury(selfDestructableContract)
                        .supplyType(TokenSupplyType.INFINITE)
                        .tokenType(NON_FUNGIBLE_UNIQUE),
                mintToken(someToken, List.of(ByteString.copyFromUtf8("somemetadata"))),
                tokenAssociate(otherMiscContract, someToken),
                cryptoTransfer(
                        TokenMovement.movingUnique(someToken, 1).between(selfDestructableContract, otherMiscContract)),
                contractDelete(otherMiscContract).hasKnownStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES),
                contractCall(selfDestructableContract, CONTRACT_DESTROY)
                        .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION)
                        .payingWith(beneficiary));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> rejectsWithoutProperSig() {
        return hapiTest(
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).refusingEthConversion(),
                contractDelete(CONTRACT).signedBy(GENESIS).hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> systemCannotDeleteOrUndeleteContracts() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                systemContractDelete(CONTRACT).payingWith(SYSTEM_DELETE_ADMIN).hasPrecheck(NOT_SUPPORTED),
                systemContractUndelete(CONTRACT)
                        .payingWith(SYSTEM_UNDELETE_ADMIN)
                        .hasPrecheck(NOT_SUPPORTED),
                getContractInfo(CONTRACT).hasAnswerOnlyPrecheck(OK));
    }

    @HapiTest
    final Stream<DynamicTest> deleteWorksWithMutableContract() {
        final var tbdFile = "FTBD";
        final var tbdContract = "CTBD";
        return hapiTest(
                fileCreate(tbdFile),
                fileDelete(tbdFile),
                // refuse eth conversion because we can't set invalid bytecode to callData in ethereum
                // transaction + trying to delete immutable contract
                createDefaultContract(tbdContract)
                        .bytecode(tbdFile)
                        .hasKnownStatus(FILE_DELETED)
                        .refusingEthConversion(),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).refusingEthConversion(),
                contractDelete(CONTRACT)
                        .claimingPermanentRemoval()
                        .hasPrecheck(PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION),
                contractDelete(CONTRACT),
                getContractInfo(CONTRACT).has(contractWith().isDeleted()));
    }

    @HapiTest
    final Stream<DynamicTest> deleteFailsWithImmutableContract() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).omitAdminKey(),
                contractDelete(CONTRACT).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT));
    }

    @HapiTest
    final Stream<DynamicTest> deleteTransfersToAccount() {
        return hapiTest(
                cryptoCreate(RECEIVER_CONTRACT_NAME).balance(0L),
                uploadInitCode(PAYABLE_CONSTRUCTOR),
                contractCreate(PAYABLE_CONSTRUCTOR)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion()
                        .balance(1L),
                contractDelete(PAYABLE_CONSTRUCTOR).transferAccount(RECEIVER_CONTRACT_NAME),
                getAccountBalance(RECEIVER_CONTRACT_NAME).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> deleteTransfersToContract() {
        final var suffix = "Receiver";

        return hapiTest(
                uploadInitCode(PAYABLE_CONSTRUCTOR),
                contractCreate(PAYABLE_CONSTRUCTOR)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion()
                        .balance(0L),
                contractCustomCreate(PAYABLE_CONSTRUCTOR, suffix).balance(1L),
                contractDelete(PAYABLE_CONSTRUCTOR).transferContract(PAYABLE_CONSTRUCTOR + suffix),
                getAccountBalance(PAYABLE_CONSTRUCTOR + suffix).hasTinyBars(1L));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> localCallToDeletedContract() {
        return hapiTest(
                // refuse eth conversion because MODIFYING_IMMUTABLE_CONTRACT
                uploadInitCode(SIMPLE_STORAGE_CONTRACT),
                contractCreate(SIMPLE_STORAGE_CONTRACT).refusingEthConversion(),
                contractCallLocal(SIMPLE_STORAGE_CONTRACT, "get")
                        .hasCostAnswerPrecheck(OK)
                        .has(resultWith().contractCallResult(() -> Bytes32.fromHexString("0x0F"))),
                contractDelete(SIMPLE_STORAGE_CONTRACT),
                contractCallLocal(SIMPLE_STORAGE_CONTRACT, "get")
                        .hasCostAnswerPrecheck(OK)
                        .has(resultWith().contractCallResult(() -> Bytes.EMPTY)));
    }
}
