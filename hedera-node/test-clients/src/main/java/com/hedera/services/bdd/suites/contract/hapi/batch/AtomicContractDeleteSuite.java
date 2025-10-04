// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
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
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_DELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_UNDELETE_ADMIN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_EXECUTION_EXCEPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of ContractDeleteSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(SMART_CONTRACT)
class AtomicContractDeleteSuite {

    private static final String CONTRACT = "Multipurpose";
    private static final String PAYABLE_CONSTRUCTOR = "PayableConstructor";
    private static final String CONTRACT_DESTROY = "destroy";
    private static final String RECEIVER_CONTRACT_NAME = "receiver";
    private static final String SIMPLE_STORAGE_CONTRACT = "SimpleStorage";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("contracts.throttle.throttleByGas", "false"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
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
                atomicBatch(contractDelete(selfDestructCallable + "1")
                                .hasKnownStatus(ACCOUNT_IS_TREASURY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                tokenAssociate(selfDestructCallable + "2", someToken),
                tokenUpdate(someToken)
                        .treasury(selfDestructCallable + "2")
                        .signedByPayerAnd(multiKey, selfDestructCallable + "2"),
                atomicBatch(contractDelete(selfDestructCallable + "1").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
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
                atomicBatch(contractDelete(otherMiscContract)
                                .hasKnownStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                contractCall(selfDestructableContract, CONTRACT_DESTROY)
                        .hasKnownStatus(CONTRACT_EXECUTION_EXCEPTION)
                        .payingWith(beneficiary));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsWithoutProperSig() {
        return hapiTest(
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).refusingEthConversion(),
                atomicBatch(contractDelete(CONTRACT)
                                .signedBy(GENESIS)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> systemCannotDeleteOrUndeleteContracts() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                atomicBatch(systemContractDelete(CONTRACT)
                                .payingWith(SYSTEM_DELETE_ADMIN)
                                .hasPrecheck(NOT_SUPPORTED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(NOT_SUPPORTED),
                systemContractUndelete(CONTRACT)
                        .payingWith(SYSTEM_UNDELETE_ADMIN)
                        .hasPrecheck(NOT_SUPPORTED),
                getContractInfo(CONTRACT).hasAnswerOnlyPrecheck(OK));
    }

    @HapiTest
    @Tag(MATS)
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
                atomicBatch(contractDelete(CONTRACT)
                                .claimingPermanentRemoval()
                                .hasPrecheck(PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(PERMANENT_REMOVAL_REQUIRES_SYSTEM_INITIATION),
                atomicBatch(contractDelete(CONTRACT).batchKey(BATCH_OPERATOR)).payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT).has(contractWith().isDeleted()));
    }

    @HapiTest
    final Stream<DynamicTest> deleteFailsWithImmutableContract() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).omitAdminKey(),
                atomicBatch(contractDelete(CONTRACT)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
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
                atomicBatch(contractDelete(PAYABLE_CONSTRUCTOR)
                                .transferAccount(RECEIVER_CONTRACT_NAME)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountBalance(RECEIVER_CONTRACT_NAME).hasTinyBars(1L));
    }

    @HapiTest
    @Tag(MATS)
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
                atomicBatch(contractDelete(PAYABLE_CONSTRUCTOR)
                                .transferContract(PAYABLE_CONSTRUCTOR + suffix)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getAccountBalance(PAYABLE_CONSTRUCTOR + suffix).hasTinyBars(1L));
    }

    @HapiTest
    final Stream<DynamicTest> localCallToDeletedContract() {
        return hapiTest(
                // refuse eth conversion because MODIFYING_IMMUTABLE_CONTRACT
                uploadInitCode(SIMPLE_STORAGE_CONTRACT),
                contractCreate(SIMPLE_STORAGE_CONTRACT).refusingEthConversion(),
                contractCallLocal(SIMPLE_STORAGE_CONTRACT, "get")
                        .hasCostAnswerPrecheck(OK)
                        .has(resultWith().contractCallResult(() -> Bytes32.fromHexString("0x0F"))),
                atomicBatch(contractDelete(SIMPLE_STORAGE_CONTRACT).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                contractCallLocal(SIMPLE_STORAGE_CONTRACT, "get")
                        .hasCostAnswerPrecheck(OK)
                        .has(resultWith().contractCallResult(() -> Bytes.EMPTY)));
    }
}
