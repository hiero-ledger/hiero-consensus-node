// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi.batch;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.FULL_PREFIXES;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PROPS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.TrieSigMapGenerator;
import com.hedera.services.bdd.spec.utilops.RunnableOp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of ContractUpdateSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(SMART_CONTRACT)
class AtomicContractUpdateSuite {

    private static final long ONE_DAY = 60L * 60L * 24L;
    public static final String ADMIN_KEY = "adminKey";
    public static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String CONTRACT = "Multipurpose";
    public static final String INITIAL_ADMIN_KEY = "initialAdminKey";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String STAKED_ACCOUNT = "stakedAccount";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("contracts.throttle.throttleByGas", "false"));
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> updateStakingFieldsWorks() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                // refuse eth conversion because ethereum transaction is missing staking fields to map
                // (isDeclinedReward)
                contractCreate(CONTRACT).declinedReward(true).stakedNodeId(0).refusingEthConversion(),
                getContractInfo(CONTRACT)
                        .has(contractWith()
                                .isDeclinedReward(true)
                                .noStakedAccountId()
                                .stakedNodeId(0)),
                atomicBatch(contractUpdate(CONTRACT)
                                .newDeclinedReward(false)
                                .newStakedAccountId("10")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT)
                        .has(contractWith()
                                .isDeclinedReward(false)
                                .noStakingNodeId()
                                .stakedAccountId("10"))
                        .logged(),

                /* --- reset the staking account */
                atomicBatch(contractUpdate(CONTRACT)
                                .newDeclinedReward(false)
                                .newStakedAccountId("0.0.0")
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT)
                        .has(contractWith()
                                .isDeclinedReward(false)
                                .noStakingNodeId()
                                .noStakedAccountId())
                        .logged(),
                // refuse eth conversion because ethereum transaction is missing staking fields to map
                // (isDeclinedReward)
                contractCreate(CONTRACT).declinedReward(true).stakedNodeId(0).refusingEthConversion(),
                getContractInfo(CONTRACT)
                        .has(contractWith()
                                .isDeclinedReward(true)
                                .noStakedAccountId()
                                .stakedNodeId(0))
                        .logged(),

                /* --- reset the staking account */
                atomicBatch(contractUpdate(CONTRACT)
                                .newDeclinedReward(false)
                                .newStakedNodeId(-1L)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT)
                        .has(contractWith()
                                .isDeclinedReward(false)
                                .noStakingNodeId()
                                .noStakedAccountId()));
    }

    @HapiTest
    final Stream<DynamicTest> updateWithBothMemoSettersWorks() {
        final var firstMemo = "First";
        final var secondMemo = "Second";
        final var thirdMemo = "Third";

        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion()
                        .adminKey(ADMIN_KEY)
                        .entityMemo(firstMemo),
                atomicBatch(contractUpdate(CONTRACT).newMemo(secondMemo).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractUpdate(CONTRACT)
                                .newMemo(ZERO_BYTE_MEMO)
                                .hasKnownStatus(INVALID_ZERO_BYTE_IN_STRING)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getContractInfo(CONTRACT).has(contractWith().memo(secondMemo)),
                atomicBatch(contractUpdate(CONTRACT)
                                .useDeprecatedMemoField()
                                .newMemo(thirdMemo)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT).has(contractWith().memo(thirdMemo)));
    }

    @HapiTest
    final Stream<DynamicTest> updatingExpiryWorks() {
        final var someValidExpiry = new AtomicLong();
        return hapiTest(
                new RunnableOp(
                        () -> someValidExpiry.set(Instant.now().getEpochSecond() + THREE_MONTHS_IN_SECONDS + 123L)),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                sourcing(() -> atomicBatch(contractUpdate(CONTRACT)
                                .newExpirySecs(someValidExpiry.get())
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)),
                sourcing(() -> getContractInfo(CONTRACT).has(contractWith().expiry(someValidExpiry.get()))));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsExpiryTooFarInTheFuture() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                doWithStartupConfigNow("entities.maxLifetime", (value, now) -> atomicBatch(contractUpdate(CONTRACT)
                                .newExpirySecs(now.getEpochSecond() + Long.parseLong(value) + 12345L)
                                .hasKnownStatus(INVALID_EXPIRATION_TIME)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    @HapiTest
    final Stream<DynamicTest> updateAutoRenewWorks() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT)
                        // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon
                        // tokenAssociate,
                        // since we have CONTRACT_ID key
                        .refusingEthConversion()
                        .adminKey(ADMIN_KEY)
                        .autoRenewSecs(THREE_MONTHS_IN_SECONDS),
                atomicBatch(contractUpdate(CONTRACT)
                                .newAutoRenew(THREE_MONTHS_IN_SECONDS + ONE_DAY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT).has(contractWith().autoRenew(THREE_MONTHS_IN_SECONDS + ONE_DAY)));
    }

    @HapiTest
    final Stream<DynamicTest> updateAutoRenewAccountWorks() {
        final var newAutoRenewAccount = "newAutoRenewAccount";
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(AUTO_RENEW_ACCOUNT),
                cryptoCreate(newAutoRenewAccount),
                uploadInitCode(CONTRACT),
                // refuse eth conversion because ethereum transaction is missing admin key and autoRenewAccount
                contractCreate(CONTRACT)
                        .adminKey(ADMIN_KEY)
                        .autoRenewAccountId(AUTO_RENEW_ACCOUNT)
                        .refusingEthConversion(),
                getContractInfo(CONTRACT)
                        .has(ContractInfoAsserts.contractWith().autoRenewAccountId(AUTO_RENEW_ACCOUNT)),
                atomicBatch(contractUpdate(CONTRACT)
                                .newAutoRenewAccount(newAutoRenewAccount)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newAutoRenewAccount(newAutoRenewAccount)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY, newAutoRenewAccount)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT)
                        .has(ContractInfoAsserts.contractWith().autoRenewAccountId(newAutoRenewAccount)));
    }

    @HapiTest
    final Stream<DynamicTest> updateAdminKeyWorks() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(NEW_ADMIN_KEY),
                uploadInitCode(CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(CONTRACT).refusingEthConversion().adminKey(ADMIN_KEY),
                atomicBatch(contractUpdate(CONTRACT).newKey(NEW_ADMIN_KEY).batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractUpdate(CONTRACT).newMemo("some new memo").batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT)
                        .has(contractWith().adminKey(NEW_ADMIN_KEY).memo("some new memo")));
    }

    @HapiTest
    final Stream<DynamicTest> canMakeContractImmutableWithEmptyKeyList() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(NEW_ADMIN_KEY),
                uploadInitCode(CONTRACT),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(CONTRACT).refusingEthConversion().adminKey(ADMIN_KEY),
                atomicBatch(contractUpdate(CONTRACT)
                                .improperlyEmptyingAdminKey()
                                .hasPrecheck(INVALID_ADMIN_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_ADMIN_KEY),
                atomicBatch(contractUpdate(CONTRACT).properlyEmptyingAdminKey().batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractUpdate(CONTRACT)
                                .newKey(NEW_ADMIN_KEY)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> givenAdminKeyMustBeValid() {
        final var contract = "BalanceLookup";
        return hapiTest(
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                uploadInitCode(contract),
                contractCreate(contract).refusingEthConversion(),
                getContractInfo(contract),
                atomicBatch(contractUpdate(contract)
                                .useDeprecatedAdminKey()
                                .signedBy(GENESIS, contract)
                                .hasPrecheck(INVALID_ADMIN_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasPrecheck(INVALID_ADMIN_KEY));
    }

    @HapiTest
    final Stream<DynamicTest> fridayThe13thSpec() {
        final var contract = "SimpleStorage";
        final var suffix = "Clone";
        final var newExpiry = Instant.now().getEpochSecond() + DEFAULT_PROPS.defaultExpirationSecs() + 200;
        final var betterExpiry = Instant.now().getEpochSecond() + DEFAULT_PROPS.defaultExpirationSecs() + 300;
        final var INITIAL_MEMO = "This is a memo string with only Ascii characters";
        final var NEW_MEMO = "Turning and turning in the widening gyre, the falcon cannot hear the falconer...";
        final var BETTER_MEMO = "This was Mr. Bleaney's room...";
        final var initialKeyShape = KeyShape.SIMPLE;
        final var newKeyShape = listOf(3);
        final var payer = "payer";

        return hapiTest(
                newKeyNamed(INITIAL_ADMIN_KEY).shape(initialKeyShape),
                newKeyNamed(NEW_ADMIN_KEY).shape(newKeyShape),
                cryptoCreate(payer).balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(contract),
                contractCreate(contract).payingWith(payer).omitAdminKey(),
                // refuse eth conversion because ethereum transaction is missing admin key and memo is same as
                // parent
                contractCustomCreate(contract, suffix)
                        .payingWith(payer)
                        .adminKey(INITIAL_ADMIN_KEY)
                        .entityMemo(INITIAL_MEMO)
                        .refusingEthConversion(),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith().memo(INITIAL_MEMO).adminKey(INITIAL_ADMIN_KEY)),
                atomicBatch(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newKey(NEW_ADMIN_KEY)
                                .signedBy(payer, INITIAL_ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newKey(NEW_ADMIN_KEY)
                                .signedBy(payer, NEW_ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newKey(NEW_ADMIN_KEY)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newExpirySecs(newExpiry)
                                .newMemo(NEW_MEMO)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith()
                                .solidityAddress(contract + suffix)
                                .memo(NEW_MEMO)
                                .expiry(newExpiry)),
                atomicBatch(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newMemo(BETTER_MEMO)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith().memo(BETTER_MEMO).expiry(newExpiry)),
                atomicBatch(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newExpirySecs(betterExpiry)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith().memo(BETTER_MEMO).expiry(betterExpiry)),
                atomicBatch(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer)
                                .newExpirySecs(newExpiry)
                                .hasKnownStatus(EXPIRATION_REDUCTION_NOT_ALLOWED)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer)
                                .newMemo(NEW_MEMO)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(contract)
                                .payingWith(payer)
                                .newMemo(BETTER_MEMO)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                contractDelete(contract).payingWith(payer).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                atomicBatch(contractUpdate(contract)
                                .payingWith(payer)
                                .newExpirySecs(betterExpiry)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                contractDelete(contract + suffix)
                        .payingWith(payer)
                        .signedBy(payer, INITIAL_ADMIN_KEY)
                        .sigMapPrefixes(TrieSigMapGenerator.withNature(FULL_PREFIXES))
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractDelete(contract + suffix)
                        .payingWith(payer)
                        .signedBy(payer)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractDelete(contract + suffix).payingWith(payer).hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> updateDoesNotChangeBytecode() {
        // HSCS-DCPR-001
        final var simpleStorageContract = "SimpleStorage";
        final var emptyConstructorContract = "EmptyConstructor";
        return hapiTest(
                uploadInitCode(simpleStorageContract, emptyConstructorContract),
                // Refusing ethereum create conversion, because we get INVALID_SIGNATURE upon tokenAssociate,
                // since we have CONTRACT_ID key
                contractCreate(simpleStorageContract).refusingEthConversion(),
                getContractBytecode(simpleStorageContract).saveResultTo("initialBytecode"),
                atomicBatch(contractUpdate(simpleStorageContract)
                                .bytecode(emptyConstructorContract)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                withOpContext((spec, log) -> {
                    final var op = getContractBytecode(simpleStorageContract)
                            .hasBytecode(spec.registry().getBytes("initialBytecode"));
                    allRunFor(spec, op);
                }));
    }

    @LeakyHapiTest(overrides = {"ledger.maxAutoAssociations"})
    final Stream<DynamicTest> tryContractUpdateWithMaxAutoAssociations() {
        return hapiTest(
                overriding("ledger.maxAutoAssociations", "5000"),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(ADMIN_KEY),
                atomicBatch(contractUpdate(CONTRACT)
                                .newMaxAutomaticAssociations(-2)
                                .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newMaxAutomaticAssociations(-200)
                                .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newMaxAutomaticAssociations(5001)
                                .hasKnownStatus(REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(0)),
                atomicBatch(contractUpdate(CONTRACT)
                                .newMaxAutomaticAssociations(-1)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(-1)),
                atomicBatch(contractUpdate(CONTRACT)
                                .newMaxAutomaticAssociations(0)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(0)),
                atomicBatch(contractUpdate(CONTRACT)
                                .newMaxAutomaticAssociations(5000)
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(5000)));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateImmutableContractExceptExpiry() {
        final var someValidExpiry = new AtomicLong(Instant.now().getEpochSecond() + THREE_MONTHS_IN_SECONDS + 1234L);
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate(AUTO_RENEW_ACCOUNT),
                cryptoCreate(STAKED_ACCOUNT),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).immutable(),
                atomicBatch(contractUpdate(CONTRACT)
                                .newAutoRenew(1)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newAutoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newDeclinedReward(true)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newExpirySecs(someValidExpiry.get())
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractUpdate(CONTRACT)
                                .newKey(ADMIN_KEY)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newMaxAutomaticAssociations(100)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newMemo("The new memo")
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newProxy(CONTRACT)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newStakedAccountId(STAKED_ACCOUNT)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .newStakedNodeId(1)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    final Stream<DynamicTest> cannotUpdateContractExceptExpiryWithWrongKey() {
        final var someValidExpiry = new AtomicLong(Instant.now().getEpochSecond() + THREE_MONTHS_IN_SECONDS + 1234L);
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(NEW_ADMIN_KEY),
                cryptoCreate(AUTO_RENEW_ACCOUNT),
                cryptoCreate(STAKED_ACCOUNT),
                cryptoCreate(CIVILIAN_PAYER).balance(10 * ONE_HUNDRED_HBARS),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(ADMIN_KEY),
                atomicBatch(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newAutoRenew(1)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newAutoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newDeclinedReward(true)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newExpirySecs(someValidExpiry.get())
                                .hasKnownStatus(SUCCESS)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                atomicBatch(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newKey(ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newMaxAutomaticAssociations(100)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newMemo("The new memo")
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newProxy(CONTRACT)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newStakedAccountId(STAKED_ACCOUNT)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                atomicBatch(contractUpdate(CONTRACT)
                                .payingWith(CIVILIAN_PAYER)
                                .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                                .newStakedNodeId(1)
                                .hasKnownStatus(INVALID_SIGNATURE)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }
}
