// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551.contracts;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludeNoFailuresFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sidecarIdValidator;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PROPS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.ADMIN_KEY;
import static com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.NEW_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.util.HapiAtomicBatch;
import com.hederahashgraph.api.proto.java.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

@HapiTestLifecycle
public class AtomicBatchContractSignatureValidationTest {
    private static final String DEFAULT_BATCH_OPERATOR = "defaultBatchOperator";
    private static final String RECEIVER_SIG_REQUIRED = "receiverSigRequired";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String STAKED_ACCOUNT = "stakedAccount";

    // contracts
    private static final String INTERNAL_CALLER_CONTRACT = "InternalCaller";
    private static final String CALL_WITH_VALUE_TO_FUNCTION = "callWithValueTo";
    private static final String TRANSFERRING_CONTRACT = "Transferring";
    private static final String TRANSFER_TO_ADDRESS = "transferToAddress";
    private static final String EMPTY_CONSTRUCTOR_CONTRACT = "EmptyConstructor";
    private static final String CONTRACT = "Multipurpose";
    private static final String SELF_DESTRUCT_CALLABLE_CONTRACT = "SelfDestructCallable";
    private static final String DESTROY_EXPLICIT_BENEFICIARY = "destroyExplicitBeneficiary";

    private static final Long GAS_LIMIT_FOR_CALL = 26000L;

    private static final AtomicReference<AccountID> receiverId = new AtomicReference<>();

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
        testLifecycle.doAdhoc(cryptoCreate(DEFAULT_BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
        testLifecycle.doAdhoc(
                cryptoCreate(RECEIVER_SIG_REQUIRED).receiverSigRequired(true).exposingCreatedIdTo(receiverId::set));
        testLifecycle.doAdhoc(cryptoCreate(AUTO_RENEW_ACCOUNT));
    }

    @HapiTest
    @DisplayName("Validate internal call with value to account requiring receiver signature")
    public final Stream<DynamicTest> internalCallWithValueToAccountWithReceiverSigRequired() {
        return hapiTest(
                uploadInitCode(INTERNAL_CALLER_CONTRACT),
                contractCreate(INTERNAL_CALLER_CONTRACT).balance(ONE_HBAR),
                balanceSnapshot("initialBalance", INTERNAL_CALLER_CONTRACT),
                atomicBatchDefaultOperator(contractCall(
                                        INTERNAL_CALLER_CONTRACT,
                                        CALL_WITH_VALUE_TO_FUNCTION,
                                        mirrorAddrWith(receiverId.get()))
                                .via("callWithValueTxn")
                                .gas(GAS_LIMIT_FOR_CALL * 4))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("callWithValueTxn").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                getAccountBalance(INTERNAL_CALLER_CONTRACT).hasTinyBars(changeFromSnapshot("initialBalance", 0)));
    }

    @HapiTest
    @DisplayName("Validate transfer to account requiring receiver signature")
    public final Stream<DynamicTest> transferToAccountWithReceiverSigRequired() {
        return hapiTest(
                recordStreamMustIncludeNoFailuresFrom(sidecarIdValidator()),
                getAccountInfo(RECEIVER_SIG_REQUIRED).savingSnapshot("accInfo"),
                uploadInitCode(TRANSFERRING_CONTRACT),
                contractCreate(TRANSFERRING_CONTRACT).balance(ONE_HUNDRED_HBARS),
                // First we will try to call the contract without a signature from the receiver
                atomicBatchDefaultOperator(contractCall(
                                        TRANSFERRING_CONTRACT,
                                        TRANSFER_TO_ADDRESS,
                                        mirrorAddrWith(receiverId.get()),
                                        BigInteger.valueOf(ONE_HUNDRED_HBARS / 2))
                                .via("invalidSignatureTxn"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                // The inner transaction should fail with INVALID_SIGNATURE
                getTxnRecord("invalidSignatureTxn").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                // Now we will try the same call with a valid signature from the receiver
                atomicBatchDefaultOperator(contractCall(
                                TRANSFERRING_CONTRACT,
                                TRANSFER_TO_ADDRESS,
                                mirrorAddrWith(receiverId.get()),
                                BigInteger.valueOf(ONE_HUNDRED_HBARS / 2))
                        .payingWith(RECEIVER_SIG_REQUIRED)));
    }

    @HapiTest
    @DisplayName("Validate contract creation fails if missing required signatures")
    public final Stream<DynamicTest> createFailsIfMissingSigs() {
        final var shape = listOf(SIMPLE, threshOf(2, 3), threshOf(1, 3));
        final var validSig = shape.signedWith(sigs(ON, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));
        final var invalidSig = shape.signedWith(sigs(OFF, sigs(ON, ON, OFF), sigs(OFF, OFF, ON)));

        return hapiTest(
                uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                atomicBatchDefaultOperator(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                .adminKeyShape(shape)
                                .sigControl(forKey(EMPTY_CONSTRUCTOR_CONTRACT, invalidSig))
                                .via("invalidSigTxn"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("invalidSigTxn").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                        .adminKeyShape(shape)
                        .sigControl(forKey(EMPTY_CONSTRUCTOR_CONTRACT, validSig))
                        .hasKnownStatus(SUCCESS)));
    }

    @HapiTest
    @DisplayName("Validate contract creation with auto-renew account requiring signatures")
    public final Stream<DynamicTest> contractWithAutoRenewNeedSignatures() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                cryptoCreate(AUTO_RENEW_ACCOUNT).balance(ONE_HUNDRED_HBARS),
                atomicBatchDefaultOperator(contractCreate(CONTRACT)
                                .adminKey(ADMIN_KEY)
                                .autoRenewAccountId(AUTO_RENEW_ACCOUNT)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                                .via("createTxn"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("createTxn").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractCreate(CONTRACT)
                        .adminKey(ADMIN_KEY)
                        .autoRenewAccountId(AUTO_RENEW_ACCOUNT)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY, AUTO_RENEW_ACCOUNT)),
                getContractInfo(CONTRACT).has(ContractInfoAsserts.contractWith().maxAutoAssociations(0)));
    }

    @HapiTest
    @DisplayName("Validate updating auto-renew account with proper signatures")
    public final Stream<DynamicTest> updateAutoRenewAccountWorks() {
        final var newAccount = "newAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(newAccount),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(ADMIN_KEY).autoRenewAccountId(AUTO_RENEW_ACCOUNT),
                getContractInfo(CONTRACT)
                        .has(ContractInfoAsserts.contractWith().autoRenewAccountId(AUTO_RENEW_ACCOUNT)),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                .newAutoRenewAccount(newAccount)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                                .via("updateTxn"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                        .newAutoRenewAccount(newAccount)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY, newAccount)),
                getContractInfo(CONTRACT).has(ContractInfoAsserts.contractWith().autoRenewAccountId(newAccount)));
    }

    @HapiTest
    @DisplayName("Validate updating max automatic associations requires proper key")
    public final Stream<DynamicTest> updateMaxAutomaticAssociationsAndRequireKey() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(ADMIN_KEY),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(20).signedBy(DEFAULT_PAYER, ADMIN_KEY),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(20).signedBy(DEFAULT_PAYER, ADMIN_KEY),
                doWithStartupConfigNow(
                        "entities.maxLifetime", (value, now) -> atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                        .newMaxAutomaticAssociations(20)
                                        .newExpirySecs(now.getEpochSecond() + Long.parseLong(value) - 12345L)
                                        .signedBy(DEFAULT_PAYER)
                                        .via("updateTxnInvalidSig"))
                                .hasKnownStatus(INNER_TRANSACTION_FAILED)),
                getTxnRecord("updateTxnInvalidSig").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(20)));
    }

    @HapiTest
    @DisplayName("Validate contract update fails for all fields except expiry when using the wrong key")
    public final Stream<DynamicTest> cannotUpdateContractExceptExpiryWithWrongKey() {
        final var someValidExpiry = new AtomicLong(Instant.now().getEpochSecond() + THREE_MONTHS_IN_SECONDS + 1234L);
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(NEW_ADMIN_KEY),
                cryptoCreate(AUTO_RENEW_ACCOUNT),
                cryptoCreate(STAKED_ACCOUNT),
                cryptoCreate(CIVILIAN_PAYER).balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(ADMIN_KEY),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newAutoRenew(1)
                                .via("updateTxn_1"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_1").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newAutoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .via("updateTxn_2"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_2").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newDeclinedReward(true)
                                .via("updateTxn_3"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_3").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newExpirySecs(someValidExpiry.get())),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newKey(ADMIN_KEY)
                                .via("updateTxn_4"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_4").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newMaxAutomaticAssociations(100)
                                .via("updateTxn_5"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_5").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newMemo("The new memo")
                                .via("updateTxn_6"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_6").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newProxy(CONTRACT)
                                .via("updateTxn_7"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_7").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newStakedAccountId(STAKED_ACCOUNT)
                                .via("updateTxn_8"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_8").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newStakedNodeId(1)
                                .via("updateTxn_9"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_9").hasPriority(recordWith().status(INVALID_SIGNATURE)));
    }

    @HapiTest
    @DisplayName("Validate contract deletion fails without proper signature")
    public final Stream<DynamicTest> deleteWithoutProperSig() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                atomicBatchDefaultOperator(
                                contractDelete(CONTRACT).signedBy(GENESIS).via("deleteTxn"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("deleteTxn").hasPriority(recordWith().status(INVALID_SIGNATURE)));
    }

    @HapiTest
    @DisplayName("Validate self-destruct fails when beneficiary requires receiver signature without proper signature")
    public final Stream<DynamicTest> selfDestructFailsWhenBeneficiaryHasReceiverSigRequiredAndHasNotSignedTheTxn() {
        return hapiTest(
                uploadInitCode(SELF_DESTRUCT_CALLABLE_CONTRACT),
                contractCreate(SELF_DESTRUCT_CALLABLE_CONTRACT).balance(ONE_HBAR),
                atomicBatchDefaultOperator(contractCall(
                                        SELF_DESTRUCT_CALLABLE_CONTRACT,
                                        DESTROY_EXPLICIT_BENEFICIARY,
                                        mirrorAddrWith(receiverId.get()))
                                .via("selfDestructTxn"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("selfDestructTxn").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                getContractInfo(SELF_DESTRUCT_CALLABLE_CONTRACT)
                        .has(contractWith().balance(ONE_HBAR)));
    }

    @HapiTest
    @DisplayName("Validate contract update and delete")
    // This test is inspired by the "Friday the 13th" contract update and delete scenario
    // see com.hedera.services.bdd.suites.contract.hapi.ContractUpdateSuite.fridayThe13thSpec
    public final Stream<DynamicTest> fridayThe13thSpec() {
        final var contract = "SimpleStorage";
        final var suffix = "Clone";
        final var newExpiry = Instant.now().getEpochSecond() + DEFAULT_PROPS.defaultExpirationSecs() + 200;
        final var betterExpiry = Instant.now().getEpochSecond() + DEFAULT_PROPS.defaultExpirationSecs() + 300;
        final var initialMemo = "This is a memo string with only Ascii characters";
        final var newMemo = "Turning and turning in the widening gyre, the falcon cannot hear the falconer...";
        final var betterMemo = "This was Mr. Bleaney's room...";
        final var initialKeyShape = KeyShape.SIMPLE;
        final var newKeyShape = listOf(3);
        final var payer = "payer";

        return hapiTest(
                newKeyNamed("INITIAL_ADMIN_KEY").shape(initialKeyShape),
                newKeyNamed("NEW_ADMIN_KEY").shape(newKeyShape),
                cryptoCreate(payer).balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(contract),
                contractCreate(contract).payingWith(payer).omitAdminKey(),
                atomicBatchDefaultOperator(contractCustomCreate(contract, suffix)
                        .payingWith(payer)
                        .adminKey("INITIAL_ADMIN_KEY")
                        .entityMemo(initialMemo)),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith().memo(initialMemo).adminKey("INITIAL_ADMIN_KEY")),
                atomicBatchDefaultOperator(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newKey("NEW_ADMIN_KEY")
                                .signedBy(payer, "INITIAL_ADMIN_KEY")
                                .via("updateTxn_1"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_1").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newKey("NEW_ADMIN_KEY")
                                .signedBy(payer, "NEW_ADMIN_KEY")
                                .via("contractUpdateKeyTxn"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("contractUpdateKeyTxn").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(
                        contractUpdate(contract + suffix).payingWith(payer).newKey("NEW_ADMIN_KEY")),
                atomicBatchDefaultOperator(contractUpdate(contract + suffix)
                        .payingWith(payer)
                        .newExpirySecs(newExpiry)
                        .newMemo(newMemo)),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith()
                                .solidityAddress(contract + suffix)
                                .memo(newMemo)
                                .expiry(newExpiry)),
                atomicBatchDefaultOperator(
                        contractUpdate(contract + suffix).payingWith(payer).newMemo(betterMemo)),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith().memo(betterMemo).expiry(newExpiry)),
                atomicBatchDefaultOperator(
                        contractUpdate(contract + suffix).payingWith(payer).newExpirySecs(betterExpiry)),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith().memo(betterMemo).expiry(betterExpiry)),
                atomicBatchDefaultOperator(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer)
                                .newExpirySecs(newExpiry)
                                .via("updateTxn_3"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_3").hasPriority(recordWith().status(EXPIRATION_REDUCTION_NOT_ALLOWED)),
                atomicBatchDefaultOperator(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer)
                                .newMemo(newMemo)
                                .via("updateTxn_4"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_4").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractUpdate(contract)
                                .payingWith(payer)
                                .newMemo(betterMemo)
                                .via("updateTxn_5"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("updateTxn_5").hasPriority(recordWith().status(MODIFYING_IMMUTABLE_CONTRACT)),
                atomicBatchDefaultOperator(
                                contractDelete(contract).payingWith(payer).via("deleteTxn_1"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("deleteTxn_1").hasPriority(recordWith().status(MODIFYING_IMMUTABLE_CONTRACT)),
                atomicBatchDefaultOperator(
                        contractUpdate(contract).payingWith(payer).newExpirySecs(betterExpiry)),
                atomicBatchDefaultOperator(contractDelete(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer, "INITIAL_ADMIN_KEY")
                                .via("deleteTxn_2"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("deleteTxn_2").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractDelete(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer)
                                .via("deleteTxn_3"))
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("deleteTxn_3").hasPriority(recordWith().status(INVALID_SIGNATURE)),
                atomicBatchDefaultOperator(contractDelete(contract + suffix).payingWith(payer)));
    }

    private HapiAtomicBatch atomicBatchDefaultOperator(final HapiTxnOp<?>... ops) {
        return atomicBatch(Arrays.stream(ops)
                        .map(op -> op.batchKey(DEFAULT_BATCH_OPERATOR))
                        .toArray(HapiTxnOp[]::new))
                .payingWith(DEFAULT_BATCH_OPERATOR);
    }
}
