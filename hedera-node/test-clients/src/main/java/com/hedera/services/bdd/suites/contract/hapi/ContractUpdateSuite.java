// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySourceStaticInitializer.SHARD_AND_REALM;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doWithStartupConfigNow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PROPS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.ZERO_BYTE_MEMO;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.captureChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_MAX_AUTO_ASSOCIATIONS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.utilops.RunnableOp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractUpdateSuite {
    private static final long ONE_DAY = 60L * 60L * 24L;
    public static final String ADMIN_KEY = "adminKey";
    public static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String CONTRACT = "Multipurpose";
    public static final String INITIAL_ADMIN_KEY = "initialAdminKey";
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String STAKED_ACCOUNT = "stakedAccount";

    @HapiTest
    final Stream<DynamicTest> updateMaxAutomaticAssociationsAndRequireKey() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).adminKey(ADMIN_KEY),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(20).signedBy(DEFAULT_PAYER, ADMIN_KEY),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(20).signedBy(DEFAULT_PAYER, ADMIN_KEY),
                doWithStartupConfigNow("entities.maxLifetime", (value, now) -> contractUpdate(CONTRACT)
                        .newMaxAutomaticAssociations(20)
                        .newExpirySecs(now.getEpochSecond() + Long.parseLong(value) - 12345L)
                        .signedBy(DEFAULT_PAYER)
                        .hasKnownStatus(INVALID_SIGNATURE)),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(20)));
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return hapiTest(
                newKeyNamed(ADMIN_KEY),
                cryptoCreate("a"),
                cryptoCreate("b"),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).autoRenewAccountId("a").stakedAccountId("b"),
                submitModified(
                        withSuccessivelyVariedBodyIds(),
                        () -> contractUpdate(CONTRACT).newAutoRenewAccount("b").newStakedAccountId("a")));
    }

    @HapiTest
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
                contractUpdate(CONTRACT).newDeclinedReward(false).newStakedAccountId(SHARD_AND_REALM + "10"),
                getContractInfo(CONTRACT)
                        .has(contractWith()
                                .isDeclinedReward(false)
                                .noStakingNodeId()
                                .stakedAccountId(SHARD_AND_REALM + "10"))
                        .logged(),

                /* --- reset the staking account */
                contractUpdate(CONTRACT).newDeclinedReward(false).newStakedAccountId("0.0.0"),
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
                contractUpdate(CONTRACT).newDeclinedReward(false).newStakedNodeId(-1L),
                getContractInfo(CONTRACT)
                        .has(contractWith()
                                .isDeclinedReward(false)
                                .noStakingNodeId()
                                .noStakedAccountId()));
    }

    // https://github.com/hashgraph/hedera-services/issues/2877
    @HapiTest
    final Stream<DynamicTest> eip1014AddressAlwaysHasPriority() {
        final var contract = "VariousCreate2Calls";
        final var creationTxn = "creationTxn";
        final var callTxn = "callTxn";
        final var callcodeTxn = "callcodeTxn";
        final var staticcallTxn = "staticcallTxn";
        final var delegatecallTxn = "delegatecallTxn";

        final AtomicReference<String> childMirror = new AtomicReference<>();
        final AtomicReference<String> childEip1014 = new AtomicReference<>();

        return hapiTest(
                uploadInitCode(contract),
                contractCreate(contract).via(creationTxn),
                captureChildCreate2MetaFor(2, 0, "setup", creationTxn, childMirror, childEip1014),
                contractCall(contract, "makeNormalCall").via(callTxn),
                sourcing(() -> getTxnRecord(callTxn)
                        .logged()
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "makeNormalCall", contract),
                                                isLiteralResult(
                                                        new Object[] {asHeadlongAddress(childEip1014.get())}))))),
                contractCall(contract, "makeStaticCall").via(staticcallTxn),
                sourcing(() -> getTxnRecord(staticcallTxn)
                        .logged()
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "makeStaticCall", contract),
                                                isLiteralResult(
                                                        new Object[] {asHeadlongAddress(childEip1014.get())}))))),
                contractCall(contract, "makeDelegateCall").via(delegatecallTxn),
                sourcing(() -> getTxnRecord(delegatecallTxn)
                        .logged()
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "makeDelegateCall", contract),
                                                isLiteralResult(
                                                        new Object[] {asHeadlongAddress(childEip1014.get())}))))),
                contractCall(contract, "makeCallCode").via(callcodeTxn),
                sourcing(() -> getTxnRecord(callcodeTxn)
                        .logged()
                        .hasPriority(recordWith()
                                .contractCallResult(resultWith()
                                        .resultThruAbi(
                                                getABIFor(FUNCTION, "makeCallCode", contract),
                                                isLiteralResult(
                                                        new Object[] {asHeadlongAddress(childEip1014.get())}))))));
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
                contractUpdate(CONTRACT).newMemo(secondMemo),
                contractUpdate(CONTRACT).newMemo(ZERO_BYTE_MEMO).hasPrecheck(INVALID_ZERO_BYTE_IN_STRING),
                getContractInfo(CONTRACT).has(contractWith().memo(secondMemo)),
                contractUpdate(CONTRACT).useDeprecatedMemoField().newMemo(thirdMemo),
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
                sourcing(() -> contractUpdate(CONTRACT).newExpirySecs(someValidExpiry.get())),
                sourcing(() -> getContractInfo(CONTRACT).has(contractWith().expiry(someValidExpiry.get()))));
    }

    @HapiTest
    final Stream<DynamicTest> rejectsExpiryTooFarInTheFuture() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                doWithStartupConfigNow("entities.maxLifetime", (value, now) -> contractUpdate(CONTRACT)
                        .newExpirySecs(now.getEpochSecond() + Long.parseLong(value) + 12345L)
                        .hasKnownStatus(INVALID_EXPIRATION_TIME)));
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
                contractUpdate(CONTRACT).newAutoRenew(THREE_MONTHS_IN_SECONDS + ONE_DAY),
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
                contractUpdate(CONTRACT)
                        .newAutoRenewAccount(newAutoRenewAccount)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(CONTRACT)
                        .newAutoRenewAccount(newAutoRenewAccount)
                        .signedBy(DEFAULT_PAYER, ADMIN_KEY, newAutoRenewAccount),
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
                contractUpdate(CONTRACT).newKey(NEW_ADMIN_KEY),
                contractUpdate(CONTRACT).newMemo("some new memo"),
                getContractInfo(CONTRACT)
                        .has(contractWith().adminKey(NEW_ADMIN_KEY).memo("some new memo")));
    }

    // https://github.com/hashgraph/hedera-services/issues/3037
    @HapiTest
    final Stream<DynamicTest> immutableContractKeyFormIsStandard() {
        return hapiTest(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).immutable(),
                getContractInfo(CONTRACT).has(contractWith().immutableContractKey(CONTRACT)));
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
                contractUpdate(CONTRACT).improperlyEmptyingAdminKey().hasPrecheck(INVALID_ADMIN_KEY),
                contractUpdate(CONTRACT).properlyEmptyingAdminKey(),
                contractUpdate(CONTRACT).newKey(NEW_ADMIN_KEY).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT));
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
                contractUpdate(contract)
                        .useDeprecatedAdminKey()
                        .signedBy(GENESIS, contract)
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
                contractUpdate(contract + suffix)
                        .payingWith(payer)
                        .newKey(NEW_ADMIN_KEY)
                        .signedBy(payer, INITIAL_ADMIN_KEY)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(contract + suffix)
                        .payingWith(payer)
                        .newKey(NEW_ADMIN_KEY)
                        .signedBy(payer, NEW_ADMIN_KEY)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(contract + suffix).payingWith(payer).newKey(NEW_ADMIN_KEY),
                contractUpdate(contract + suffix)
                        .payingWith(payer)
                        .newExpirySecs(newExpiry)
                        .newMemo(NEW_MEMO),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith()
                                .solidityAddress(contract + suffix)
                                .memo(NEW_MEMO)
                                .expiry(newExpiry)),
                contractUpdate(contract + suffix).payingWith(payer).newMemo(BETTER_MEMO),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith().memo(BETTER_MEMO).expiry(newExpiry)),
                contractUpdate(contract + suffix).payingWith(payer).newExpirySecs(betterExpiry),
                getContractInfo(contract + suffix)
                        .payingWith(payer)
                        .logged()
                        .has(contractWith().memo(BETTER_MEMO).expiry(betterExpiry)),
                contractUpdate(contract + suffix)
                        .payingWith(payer)
                        .signedBy(payer)
                        .newExpirySecs(newExpiry)
                        .hasKnownStatus(EXPIRATION_REDUCTION_NOT_ALLOWED),
                contractUpdate(contract + suffix)
                        .payingWith(payer)
                        .signedBy(payer)
                        .newMemo(NEW_MEMO)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(contract)
                        .payingWith(payer)
                        .newMemo(BETTER_MEMO)
                        .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                contractDelete(contract).payingWith(payer).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                contractUpdate(contract).payingWith(payer).newExpirySecs(betterExpiry),
                contractDelete(contract + suffix)
                        .payingWith(payer)
                        .signedBy(payer, INITIAL_ADMIN_KEY)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractDelete(contract + suffix)
                        .payingWith(payer)
                        .signedBy(payer)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractDelete(contract + suffix).payingWith(payer).hasKnownStatus(SUCCESS));
    }

    @HapiTest
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
                contractUpdate(simpleStorageContract).bytecode(emptyConstructorContract),
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
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(-2).hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS),
                contractUpdate(CONTRACT)
                        .newMaxAutomaticAssociations(-200)
                        .hasKnownStatus(INVALID_MAX_AUTO_ASSOCIATIONS),
                contractUpdate(CONTRACT)
                        .newMaxAutomaticAssociations(5001)
                        .hasKnownStatus(REQUESTED_NUM_AUTOMATIC_ASSOCIATIONS_EXCEEDS_ASSOCIATION_LIMIT),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(0)),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(-1).hasKnownStatus(SUCCESS),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(-1)),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(0).hasKnownStatus(SUCCESS),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(0)),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(5000).hasKnownStatus(SUCCESS),
                getContractInfo(CONTRACT).has(contractWith().maxAutoAssociations(5000)));
    }

    @HapiTest
    final Stream<DynamicTest> playGame() {
        final var dj = "dj";
        final var players = IntStream.range(1, 30).mapToObj(i -> "Player" + i).toList();
        final var contract = "MusicalChairs";

        final List<HapiSpecOperation> given = new ArrayList<>();
        final List<HapiSpecOperation> when = new ArrayList<>();
        final List<HapiSpecOperation> then = new ArrayList<>();

        ////// Create contract //////
        given.add(cryptoCreate(dj).balance(10 * ONE_HUNDRED_HBARS));
        given.add(getAccountInfo(DEFAULT_CONTRACT_SENDER).savingSnapshot(DEFAULT_CONTRACT_SENDER));
        given.add(uploadInitCode(contract));
        given.add(withOpContext((spec, opLog) -> allRunFor(
                spec,
                contractCreate(
                                contract,
                                asHeadlongAddress(spec.registry()
                                        .getAccountInfo(DEFAULT_CONTRACT_SENDER)
                                        .getContractAccountID()))
                        .payingWith(dj))));

        ////// Add the players //////
        players.stream().map(TxnVerbs::cryptoCreate).forEach(given::add);

        ////// Start the music! //////
        when.add(contractCall(contract, "startMusic").payingWith(DEFAULT_CONTRACT_SENDER));

        ////// 100 "random" seats taken //////
        new Random(0x1337)
                .ints(100, 0, 29)
                .forEach(i -> when.add(contractCall(contract, "sitDown")
                        .payingWith(players.get(i))
                        .refusingEthConversion()
                        .hasAnyStatusAtAll())); // sometimes a player sits
        // too soon, so don't fail
        // on reverts

        ////// Stop the music! //////
        then.add(contractCall(contract, "stopMusic").payingWith(DEFAULT_CONTRACT_SENDER));

        ////// And the winner is..... //////
        then.add(withOpContext((spec, opLog) -> allRunFor(
                spec,
                contractCallLocal(contract, "whoIsOnTheBubble")
                        .has(resultWith()
                                .resultThruAbi(
                                        getABIFor(FUNCTION, "whoIsOnTheBubble", contract),
                                        isLiteralResult(new Object[] {
                                            HapiParserUtil.asHeadlongAddress(
                                                    asAddress(spec.registry().getAccountID("Player13")))
                                        }))))));

        return hapiTest(flattened(
                given.toArray(HapiSpecOperation[]::new),
                when.toArray(HapiSpecOperation[]::new),
                then.toArray(HapiSpecOperation[]::new)));
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
                contractUpdate(CONTRACT).newAutoRenew(1).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                contractUpdate(CONTRACT)
                        .newAutoRenewAccount(AUTO_RENEW_ACCOUNT)
                        .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                contractUpdate(CONTRACT).newDeclinedReward(true).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                contractUpdate(CONTRACT).newExpirySecs(someValidExpiry.get()).hasKnownStatus(SUCCESS),
                contractUpdate(CONTRACT).newKey(ADMIN_KEY).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                contractUpdate(CONTRACT).newMaxAutomaticAssociations(100).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                contractUpdate(CONTRACT).newMemo("The new memo").hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                contractUpdate(CONTRACT).newProxy(CONTRACT).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                contractUpdate(CONTRACT)
                        .newStakedAccountId(STAKED_ACCOUNT)
                        .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                contractUpdate(CONTRACT).newStakedNodeId(1).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT));
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
                contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newAutoRenew(1)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newAutoRenewAccount(AUTO_RENEW_ACCOUNT)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newDeclinedReward(true)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newExpirySecs(someValidExpiry.get())
                        .hasKnownStatus(SUCCESS),
                contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newKey(ADMIN_KEY)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newMaxAutomaticAssociations(100)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newMemo("The new memo")
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newProxy(CONTRACT)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newStakedAccountId(STAKED_ACCOUNT)
                        .hasKnownStatus(INVALID_SIGNATURE),
                contractUpdate(CONTRACT)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER, NEW_ADMIN_KEY)
                        .newStakedNodeId(1)
                        .hasKnownStatus(INVALID_SIGNATURE));
    }
}
