// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.STORAGE_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContractString;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewContract;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CONTRACT_STORAGE_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Storage accounting for contract executions nested in other contract executions through child dispatches.
 */
@Order(21)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableNestedContractStorageTests {
    private static final String ONE_TPS_CRYPTO_CREATE_THROTTLES = "testSystemFiles/one-tps-crypto-create.json";
    private static final String SCHEDULE_SERVICE = "0x000000000000000000000000000000000000016b";
    private static final Function AUTHORIZE_SCHEDULE = new Function("authorizeSchedule(address)");
    private static final TupleType SET_AND_PASS_ARGS = TupleType.parse("(uint32,address)");
    private static final TupleType FORWARD_ARGS = TupleType.parse("(address,bytes)");
    private static final Function SET_CALL_SET =
            new Function("setCallSet(uint256,uint256,address,bytes[],uint256,uint256,bool)");
    private static final Function SET = new Function("set(uint256,uint256)");
    private static final Function EXECUTE_CALL_ON_PAYER_SIGNATURE =
            new Function("executeCallOnPayerSignature(address,address,uint256,uint256,uint64,bytes)");
    // Writes its own storage around a call, so its storage is written by both outer and nested executions
    private static final String WRITER = "StorageAroundCall";
    private static final Write NO_WRITE = new Write(0, 0);

    @Contract(contract = "HRC755Contract", creationGas = 1_000_000, isImmutable = true)
    static SpecContract SIGNING_CONTRACT;

    @Contract(contract = "SetAndPassHook", creationGas = 1_000_000L)
    static SpecContract SET_AND_PASS_HOOK;

    // As a hook, forwards the call encoded in its hook data
    @Contract(contract = "CodeDelegationContract", creationGas = 5_000_000L)
    static SpecContract FORWARDING_HOOK;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(SIGNING_CONTRACT.getInfo(), SET_AND_PASS_HOOK.getInfo(), FORWARDING_HOOK.getInfo());
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> nestedScheduledContractCallUpdatesStorageCounts() {
        final var slotsBefore = new AtomicLong();
        final var factory = "NoncesExternalization";
        return hapiTest(
                uploadInitCode(factory),
                contractCreate(factory).gas(1_000_000L),
                recordSlotCount(slotsBefore),
                scheduleAndAuthorize(
                        contractCall(factory, "deployParentContract").gas(500_000L)),
                // The scheduled call adds exactly one storage slot
                slotCountChangedBy(slotsBefore, 1),
                // 4 slots from the constructor plus the pushed element
                storageListMatches(factory, 5));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> nestedScheduledContractCallRemovalUpdatesStorageCounts() {
        final var slotsBefore = new AtomicLong();
        final var target = "SimpleUpdate";
        return hapiTest(
                uploadInitCode(target),
                contractCreate(target).gas(500_000L),
                contractCall(target, "set", BigInteger.ONE, BigInteger.TWO).gas(200_000L),
                storageListMatches(target, 2),
                recordSlotCount(slotsBefore),
                scheduleAndAuthorize(contractCall(target, "set", BigInteger.valueOf(5), BigInteger.ZERO)
                        .gas(200_000L)),
                // The scheduled call clears one of the two slots
                slotCountChangedBy(slotsBefore, -1),
                storageListMatches(target, 1));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> nestedScheduledContractCreateUpdatesStorageCounts() {
        final var slotsBefore = new AtomicLong();
        final var createdId = new AtomicReference<String>();
        final var factory = "NoncesExternalization";
        return hapiTest(
                uploadInitCode(factory),
                recordSlotCount(slotsBefore),
                scheduleAndAuthorize(contractCreate(factory).omitAdminKey().gas(1_000_000L)),
                getTxnRecord("scheduleCreation")
                        .scheduled()
                        .exposingTo(record -> createdId.set(
                                asContractString(record.getReceipt().getContractID()))),
                // The scheduled creation's constructor adds exactly 4 storage slots
                slotCountChangedBy(slotsBefore, 4),
                sourcingContextual(spec -> storageListMatches(createdId.get(), 4)));
    }

    @LeakyRepeatableHapiTest(
            value = NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW,
            overrides = {"contracts.enforceCreationThrottle"},
            throttles = ONE_TPS_CRYPTO_CREATE_THROTTLES)
    final Stream<DynamicTest> nestedContractCreationWithinCreationThrottleSucceeds() {
        final var factory = "NoncesExternalization";
        return hapiTest(
                uploadInitCode(factory),
                contractCreate(factory).gas(1_000_000L),
                overriding("contracts.enforceCreationThrottle", "true"),
                // The throttle admits one creation per second and the scheduled call creates one contract
                scheduleAndAuthorize(
                        contractCall(factory, "deployParentContract").gas(500_000L)));
    }

    @LeakyRepeatableHapiTest(
            value = NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW,
            overrides = {"contracts.enforceCreationThrottle"},
            throttles = ONE_TPS_CRYPTO_CREATE_THROTTLES)
    final Stream<DynamicTest> topLevelContractCreationWithinCreationThrottleSucceeds() {
        final var factory = "NoncesExternalization";
        return hapiTest(
                uploadInitCode(factory),
                contractCreate(factory).gas(1_000_000L),
                overriding("contracts.enforceCreationThrottle", "true"),
                // The constructor creates four contracts, more than the throttle admits per second
                contractCustomCreate(factory, "Copy").gas(1_000_000L).hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                // A call that creates one contract is within the throttle
                contractCall(factory, "deployParentContract").gas(500_000L));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> outerAndNestedWritesToOverlappingSlotsUpdateStorageCounts() {
        final var slotsBefore = new AtomicLong();
        return hapiTest(
                writerCreated(),
                recordSlotCount(slotsBefore),
                // The outer call inserts key 1, the scheduled call updates key 1 and inserts key 2, and then the
                // outer call inserts key 3
                writeAroundAuthorizing(
                        "overlap", writerCall("setTwo", 1, 2, 2, 3), new Write(1, 1), new Write(3, 4), false, SUCCESS),
                scheduledStatusIs("overlap", SUCCESS),
                slotCountChangedBy(slotsBefore, 3),
                storageValuesAre(WRITER, 2, 3, 4));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> nestedRemovalOfOuterInsertionLeavesNoSlot() {
        final var slotsBefore = new AtomicLong();
        return hapiTest(
                writerCreated(),
                recordSlotCount(slotsBefore),
                writeAroundAuthorizing("removal", writerCall("set", 1, 0), new Write(1, 1), NO_WRITE, false, SUCCESS),
                scheduledStatusIs("removal", SUCCESS),
                slotCountChangedBy(slotsBefore, 0),
                storageValuesAre(WRITER));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> outerRemovalOfNestedInsertionLeavesNoSlot() {
        final var slotsBefore = new AtomicLong();
        return hapiTest(
                writerCreated(),
                recordSlotCount(slotsBefore),
                writeAroundAuthorizing("insertion", writerCall("set", 1, 5), NO_WRITE, new Write(1, 0), false, SUCCESS),
                scheduledStatusIs("insertion", SUCCESS),
                slotCountChangedBy(slotsBefore, 0),
                storageValuesAre(WRITER));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> nestedRollbackLeavesOnlyOuterWrites() {
        final var slotsBefore = new AtomicLong();
        return hapiTest(
                writerCreated(),
                recordSlotCount(slotsBefore),
                writeAroundAuthorizing(
                        "reverting",
                        writerCall("setAndRevert", 2, 2),
                        new Write(1, 1),
                        new Write(3, 3),
                        false,
                        SUCCESS),
                scheduledStatusIs("reverting", CONTRACT_REVERT_EXECUTED),
                slotCountChangedBy(slotsBefore, 2),
                storageValuesAre(WRITER, 1, 3));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> outerRollbackDiscardsNestedWrites() {
        final var slotsBefore = new AtomicLong();
        return hapiTest(
                writerCreated(),
                recordSlotCount(slotsBefore),
                writeAroundAuthorizing(
                        "rolledBack",
                        writerCall("set", 2, 2),
                        new Write(1, 1),
                        new Write(3, 3),
                        true,
                        CONTRACT_REVERT_EXECUTED),
                getScheduleInfo("rolledBack").isNotExecuted(),
                slotCountChangedBy(slotsBefore, 0),
                storageValuesAre(WRITER));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS},
            overrides = {"contracts.maxKvPairs.aggregate"})
    final Stream<DynamicTest> nestedInsertionBeyondAggregateLimitIsRejected() {
        final var slotsBefore = new AtomicLong();
        return hapiTest(
                writerCreated(),
                capAggregateSlotsAtCurrentPlus(0),
                recordSlotCount(slotsBefore),
                // Only the scheduled call fails, and the outer call continues without its insertion
                writeAroundAuthorizing("overCap", writerCall("set", 1, 1), NO_WRITE, NO_WRITE, false, SUCCESS),
                scheduledStatusIs("overCap", MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED),
                slotCountChangedBy(slotsBefore, 0),
                storageValuesAre(WRITER));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS},
            overrides = {"contracts.maxKvPairs.aggregate"})
    final Stream<DynamicTest> nestedInsertionUpToAggregateLimitSucceeds() {
        final var slotsBefore = new AtomicLong();
        return hapiTest(
                writerCreated(),
                capAggregateSlotsAtCurrentPlus(1),
                recordSlotCount(slotsBefore),
                writeAroundAuthorizing("atCap", writerCall("set", 1, 1), NO_WRITE, NO_WRITE, false, SUCCESS),
                scheduledStatusIs("atCap", SUCCESS),
                slotCountChangedBy(slotsBefore, 1),
                storageValuesAre(WRITER, 1));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS},
            overrides = {"contracts.maxKvPairs.aggregate"})
    final Stream<DynamicTest> outerAndNestedInsertionsBeyondAggregateLimitFailOuterCall() {
        final var slotsBefore = new AtomicLong();
        return hapiTest(
                writerCreated(),
                capAggregateSlotsAtCurrentPlus(1),
                recordSlotCount(slotsBefore),
                // Each insertion fits alone, but the outer call's commit sees both
                writeAroundAuthorizing(
                        "bothOverCap",
                        writerCall("set", 2, 2),
                        new Write(1, 1),
                        NO_WRITE,
                        false,
                        MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED),
                getScheduleInfo("bothOverCap").isNotExecuted(),
                slotCountChangedBy(slotsBefore, 0),
                storageValuesAre(WRITER));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS},
            overrides = {"contracts.maxKvPairs.aggregate"})
    final Stream<DynamicTest> topLevelInsertionBeyondAggregateLimitIsRejected() {
        final var slotsBefore = new AtomicLong();
        return hapiTest(
                writerCreated(),
                capAggregateSlotsAtCurrentPlus(0),
                recordSlotCount(slotsBefore),
                writerCall("set", 1, 1).hasKnownStatus(MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED),
                slotCountChangedBy(slotsBefore, 0),
                storageValuesAre(WRITER));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS},
            overrides = {"contracts.maxKvPairs.individual"})
    final Stream<DynamicTest> nestedInsertionIsCheckedAgainstContractLimit() {
        return hapiTest(
                writerCreated(),
                writerCall("set", 1, 1),
                overriding("contracts.maxKvPairs.individual", "1"),
                // Only the scheduled call fails, and the outer call continues without its insertion
                writeAroundAuthorizing("overLimit", writerCall("set", 2, 2), NO_WRITE, NO_WRITE, false, SUCCESS),
                scheduledStatusIs("overLimit", MAX_CONTRACT_STORAGE_EXCEEDED),
                storageValuesAre(WRITER, 1),
                overriding("contracts.maxKvPairs.individual", "2"),
                writeAroundAuthorizing("atLimit", writerCall("set", 2, 3), NO_WRITE, NO_WRITE, false, SUCCESS),
                scheduledStatusIs("atLimit", SUCCESS),
                storageValuesAre(WRITER, 1, 3));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> hookExecutionsInOneTransferEachPersistTheirStorage() {
        final var slotsBefore = new AtomicLong();
        final var target = "Multipurpose";
        return hapiTest(
                uploadInitCode(target),
                // Each copy starts with one slot holding its lucky number
                contractCustomCreate(target, "A").gas(500_000L),
                contractCustomCreate(target, "B").gas(500_000L),
                cryptoCreate("ownerA").withHooks(accountAllowanceHook(1L, SET_AND_PASS_HOOK.name())),
                cryptoCreate("ownerB").withHooks(accountAllowanceHook(1L, SET_AND_PASS_HOOK.name())),
                recordSlotCount(slotsBefore),
                // Two independent hook executions in one transfer, each clearing the slot of a different contract
                sourcingContextual(spec -> cryptoTransfer(
                                movingHbar(1).between("ownerA", FUNDING),
                                movingHbar(1).between("ownerB", FUNDING))
                        .withPreHookFor(
                                "ownerA",
                                1L,
                                64_000L,
                                SET_AND_PASS_ARGS.encode(Tuple.of(
                                        0L, mirrorAddrWith(spec.registry().getContractId(target + "A")))))
                        .withPreHookFor(
                                "ownerB",
                                1L,
                                64_000L,
                                SET_AND_PASS_ARGS.encode(Tuple.of(
                                        0L, mirrorAddrWith(spec.registry().getContractId(target + "B")))))),
                slotCountChangedBy(slotsBefore, -2),
                storageValuesAre(target + "A"),
                storageValuesAre(target + "B"));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> executionNestedInBatchInnerContractCallUpdatesStorageCounts() {
        final var slotsBefore = new AtomicLong();
        final var scheduleId = new AtomicReference<ScheduleID>();
        final var batchOperator = "batchOperator";
        return hapiTest(
                writerCreated(),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("batchedPayer").balance(ONE_HUNDRED_HBARS).keyShape(KeyShape.CONTRACT.signedWith(WRITER)),
                scheduleCreate("batched", writerCall("set", 2, 2))
                        .designatingPayer("batchedPayer")
                        .exposingCreatedIdTo(scheduleId::set),
                recordSlotCount(slotsBefore),
                // A transfer, then a contract call whose scheduled call writes the same contract's storage
                sourcing(() -> atomicBatch(
                                cryptoTransfer(tinyBarsFromTo(batchOperator, FUNDING, 1))
                                        .payingWith(batchOperator)
                                        .batchKey(batchOperator),
                                writerCall(
                                                "setCallSet",
                                                authorizingArgs(
                                                        List.of(scheduleId.get()),
                                                        new Write(1, 1),
                                                        new Write(3, 3),
                                                        false))
                                        .gas(2_000_000L)
                                        .batchKey(batchOperator))
                        .signedByPayerAnd(batchOperator)),
                getScheduleInfo("batched").isExecuted(),
                slotCountChangedBy(slotsBefore, 3),
                storageValuesAre(WRITER, 1, 2, 3));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> executionNestedTwoLevelsDeepUpdatesStorageCounts() {
        final var slotsBefore = new AtomicLong();
        final var innerId = new AtomicReference<ScheduleID>();
        final var middleId = new AtomicReference<ScheduleID>();
        return hapiTest(
                writerCreated(),
                recordSlotCount(slotsBefore),
                scheduledForWriter("inner", writerCall("set", 3, 3), innerId),
                // The middle scheduled call writes keys 2 and 4 around authorizing the inner one
                sourcing(() -> scheduledForWriter(
                        "middle",
                        writerCall(
                                        "setCallSet",
                                        authorizingArgs(
                                                List.of(innerId.get()), new Write(2, 2), new Write(4, 4), false))
                                .gas(2_000_000L),
                        middleId)),
                writerAuthorizing("outer", List.of(middleId), new Write(1, 1), new Write(5, 5), false, SUCCESS),
                scheduledStatusIs("middle", SUCCESS),
                scheduledStatusIs("inner", SUCCESS),
                slotCountChangedBy(slotsBefore, 5),
                storageValuesAre(WRITER, 1, 2, 3, 4, 5));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> executionNestedThroughScheduleCreationUpdatesStorageCounts() {
        final var slotsBefore = new AtomicLong();
        return hapiTest(
                writerCreated(),
                cryptoCreate("callPayer").balance(ONE_HUNDRED_HBARS).keyShape(KeyShape.CONTRACT.signedWith(WRITER)),
                recordSlotCount(slotsBefore),
                // The writer schedules a call to itself that its own key lets execute immediately, and writes keys 1
                // and 3 around it
                sourcingContextual(spec -> writerCall("setCallSet", new Object[] {
                            BigInteger.ONE,
                            BigInteger.ONE,
                            asHeadlongAddress(SCHEDULE_SERVICE),
                            new byte[][] {
                                EXECUTE_CALL_ON_PAYER_SIGNATURE
                                        .encodeCallWithArgs(
                                                mirrorAddrWith(spec.registry().getContractId(WRITER)),
                                                mirrorAddrWith(spec.registry().getAccountID("callPayer")),
                                                BigInteger.valueOf(
                                                        spec.consensusTime().getEpochSecond() + 3_600),
                                                BigInteger.valueOf(200_000L),
                                                BigInteger.ZERO,
                                                SET.encodeCallWithArgs(BigInteger.TWO, BigInteger.TWO)
                                                        .array())
                                        .array()
                            },
                            BigInteger.valueOf(3),
                            BigInteger.valueOf(3),
                            false
                        })
                        .gas(2_000_000L)
                        .hasKnownStatus(SUCCESS)),
                slotCountChangedBy(slotsBefore, 3),
                storageValuesAre(WRITER, 1, 2, 3));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> siblingNestedExecutionsUpdateStorageCounts() {
        final var slotsBefore = new AtomicLong();
        final var firstId = new AtomicReference<ScheduleID>();
        final var secondId = new AtomicReference<ScheduleID>();
        return hapiTest(
                writerCreated(),
                recordSlotCount(slotsBefore),
                scheduledForWriter("first", writerCall("set", 2, 2), firstId),
                // The second scheduled call updates the first one's key and inserts another
                scheduledForWriter("second", writerCall("setTwo", 2, 3, 3, 3), secondId),
                writerAuthorizing(
                        "outer", List.of(firstId, secondId), new Write(1, 1), new Write(4, 4), false, SUCCESS),
                scheduledStatusIs("first", SUCCESS),
                scheduledStatusIs("second", SUCCESS),
                slotCountChangedBy(slotsBefore, 4),
                storageValuesAre(WRITER, 1, 3, 3, 4));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS},
            overrides = {"contracts.maxKvPairs.individual"})
    final Stream<DynamicTest> siblingNestedInsertionBeyondContractLimitFailsOnlyThatScheduledCall() {
        final var slotsBefore = new AtomicLong();
        final var firstId = new AtomicReference<ScheduleID>();
        final var secondId = new AtomicReference<ScheduleID>();
        return hapiTest(
                writerCreated(),
                writerCall("set", 1, 1),
                recordSlotCount(slotsBefore),
                scheduledForWriter("first", writerCall("set", 2, 2), firstId),
                scheduledForWriter("second", writerCall("set", 3, 3), secondId),
                overriding("contracts.maxKvPairs.individual", "2"),
                // Only one more slot fits, so the second scheduled insertion exceeds the limit
                writerAuthorizing("outer", List.of(firstId, secondId), NO_WRITE, NO_WRITE, false, SUCCESS),
                scheduledStatusIs("first", SUCCESS),
                scheduledStatusIs("second", MAX_CONTRACT_STORAGE_EXCEEDED),
                getScheduleInfo("first").isExecuted(),
                getScheduleInfo("second").isExecuted(),
                slotCountChangedBy(slotsBefore, 1),
                storageValuesAre(WRITER, 1, 2));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS},
            overrides = {"contracts.maxKvPairs.individual"})
    final Stream<DynamicTest> siblingNestedRemovalMakesRoomForInsertionWithinContractLimit() {
        final var slotsBefore = new AtomicLong();
        final var firstId = new AtomicReference<ScheduleID>();
        final var secondId = new AtomicReference<ScheduleID>();
        return hapiTest(
                writerCreated(),
                writerCall("set", 1, 1),
                writerCall("set", 2, 2),
                recordSlotCount(slotsBefore),
                scheduledForWriter("first", writerCall("set", 1, 0), firstId),
                scheduledForWriter("second", writerCall("set", 3, 3), secondId),
                overriding("contracts.maxKvPairs.individual", "2"),
                // The first scheduled call frees the slot the second one uses
                writerAuthorizing("outer", List.of(firstId, secondId), NO_WRITE, NO_WRITE, false, SUCCESS),
                scheduledStatusIs("first", SUCCESS),
                scheduledStatusIs("second", SUCCESS),
                slotCountChangedBy(slotsBefore, 0),
                storageValuesAre(WRITER, 2, 3));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS},
            overrides = {"contracts.maxKvPairs.aggregate"})
    final Stream<DynamicTest> siblingNestedRemovalMakesRoomForInsertionWithinAggregateLimit() {
        final var slotsBefore = new AtomicLong();
        final var firstId = new AtomicReference<ScheduleID>();
        final var secondId = new AtomicReference<ScheduleID>();
        return hapiTest(
                writerCreated(),
                writerCall("set", 1, 1),
                scheduledForWriter("first", writerCall("set", 1, 0), firstId),
                scheduledForWriter("second", writerCall("set", 3, 3), secondId),
                capAggregateSlotsAtCurrentPlus(0),
                recordSlotCount(slotsBefore),
                writerAuthorizing("outer", List.of(firstId, secondId), NO_WRITE, NO_WRITE, false, SUCCESS),
                scheduledStatusIs("first", SUCCESS),
                scheduledStatusIs("second", SUCCESS),
                slotCountChangedBy(slotsBefore, 0),
                storageValuesAre(WRITER, 3));
    }

    @LeakyRepeatableHapiTest(
            value = NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW,
            overrides = {"contracts.enforceCreationThrottle"},
            throttles = ONE_TPS_CRYPTO_CREATE_THROTTLES)
    final Stream<DynamicTest> nestedCreationsBeyondCreationThrottleFailOnlyTheScheduledCreation() {
        final var factory = "NoncesExternalization";
        return hapiTest(
                uploadInitCode(factory),
                writerCreated(),
                overriding("contracts.enforceCreationThrottle", "true"),
                // The scheduled creation's constructor creates four more contracts, more than the throttle admits
                writeAroundAuthorizing(
                        "creating",
                        contractCreate(factory).omitAdminKey().gas(1_000_000L),
                        NO_WRITE,
                        NO_WRITE,
                        false,
                        SUCCESS),
                scheduledStatusIs("creating", CONTRACT_REVERT_EXECUTED),
                getTxnRecord("creatingCreation")
                        .scheduled()
                        .hasPriority(recordWith()
                                .contractCreateResult(resultWith().error(revertReason(CONSENSUS_GAS_EXHAUSTED)))),
                getScheduleInfo("creating").isExecuted());
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> executionNestedInHookExecutionUpdatesStorageCounts() {
        final var slotsBefore = new AtomicLong();
        final var scheduleId = new AtomicReference<ScheduleID>();
        return hapiTest(
                writerCreated(),
                cryptoCreate("hookOwner").withHooks(accountAllowanceHook(1L, FORWARDING_HOOK.name())),
                scheduledForWriter("hooked", writerCall("set", 2, 2), scheduleId),
                recordSlotCount(slotsBefore),
                // The hook calls the writer, which writes keys 1 and 3 around authorizing the schedule
                sourcingContextual(spec -> cryptoTransfer(movingHbar(1).between("hookOwner", FUNDING))
                        .withPreHookFor(
                                "hookOwner",
                                1L,
                                1_000_000L,
                                FORWARD_ARGS.encode(Tuple.of(
                                        mirrorAddrWith(spec.registry().getContractId(WRITER)),
                                        SET_CALL_SET
                                                .encodeCallWithArgs(authorizingArgs(
                                                        List.of(scheduleId.get()),
                                                        new Write(1, 1),
                                                        new Write(3, 3),
                                                        false))
                                                .array())))),
                getScheduleInfo("hooked").isExecuted(),
                scheduledStatusIs("hooked", SUCCESS),
                slotCountChangedBy(slotsBefore, 3),
                storageValuesAre(WRITER, 1, 2, 3));
    }

    @RepeatableHapiTest({NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> executionNestedInEthereumTransactionUpdatesStorageCounts() {
        final var slotsBefore = new AtomicLong();
        final var scheduleId = new AtomicReference<ScheduleID>();
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                cryptoCreate(RELAYER).balance(ONE_MILLION_HBARS),
                writerCreated(),
                scheduledForWriter("ethereum", writerCall("set", 2, 2), scheduleId),
                recordSlotCount(slotsBefore),
                sourcing(() -> ethereumCall(
                                WRITER,
                                "setCallSet",
                                authorizingArgs(List.of(scheduleId.get()), new Write(1, 1), new Write(3, 3), false))
                        .type(EthTxData.EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxFeePerGas(50L)
                        .maxPriorityGas(2L)
                        .gasLimit(2_000_000L)
                        .hasKnownStatus(SUCCESS)),
                getScheduleInfo("ethereum").isExecuted(),
                scheduledStatusIs("ethereum", SUCCESS),
                slotCountChangedBy(slotsBefore, 3),
                storageValuesAre(WRITER, 1, 2, 3));
    }

    /**
     * Schedules {@code op} with a payer keyed to {@link #SIGNING_CONTRACT}, which then authorizes it.
     */
    private static <T extends HapiTxnOp<T>> SpecOperation scheduleAndAuthorize(@NonNull final HapiTxnOp<T> op) {
        final var scheduleIdRef = new AtomicReference<ScheduleID>();
        return blockingOrder(
                cryptoCreate("schedulePayer")
                        .balance(ONE_HUNDRED_HBARS)
                        .keyShape(KeyShape.CONTRACT.signedWith(SIGNING_CONTRACT.name())),
                scheduleCreate("schedule", op)
                        .designatingPayer("schedulePayer")
                        .exposingCreatedIdTo(scheduleIdRef::set)
                        .via("scheduleCreation"),
                sourcingContextual(spec -> SIGNING_CONTRACT
                        .call(
                                "authorizeScheduleCall",
                                mirrorAddrWith(spec, scheduleIdRef.get().getScheduleNum()))
                        .gas(2_000_000L)),
                getScheduleInfo("schedule").isExecuted());
    }

    /**
     * Schedules {@code scheduledOp} with a payer keyed to {@link #WRITER}, and calls {@link #WRITER} to write
     * {@code before}, authorize the schedule, write {@code after}, and optionally revert.
     */
    private static SpecOperation writeAroundAuthorizing(
            @NonNull final String schedule,
            @NonNull final HapiTxnOp<?> scheduledOp,
            @NonNull final Write before,
            @NonNull final Write after,
            final boolean revertAfter,
            @NonNull final ResponseCodeEnum expectedStatus) {
        final var scheduleId = new AtomicReference<ScheduleID>();
        return blockingOrder(
                scheduledForWriter(schedule, scheduledOp, scheduleId),
                writerAuthorizing(schedule + "Outer", List.of(scheduleId), before, after, revertAfter, expectedStatus));
    }

    /**
     * Schedules {@code scheduledOp} with a payer keyed to {@link #WRITER}, so that {@link #WRITER} can authorize it.
     */
    private static SpecOperation scheduledForWriter(
            @NonNull final String schedule,
            @NonNull final HapiTxnOp<?> scheduledOp,
            @NonNull final AtomicReference<ScheduleID> scheduleId) {
        return blockingOrder(
                cryptoCreate(schedule + "Payer")
                        .balance(ONE_HUNDRED_HBARS)
                        .keyShape(KeyShape.CONTRACT.signedWith(WRITER)),
                scheduleCreate(schedule, scheduledOp)
                        .designatingPayer(schedule + "Payer")
                        .exposingCreatedIdTo(scheduleId::set)
                        .via(schedule + "Creation"));
    }

    /**
     * Calls {@link #WRITER} to write {@code before}, authorize the given schedules, write {@code after}, and
     * optionally revert.
     */
    private static SpecOperation writerAuthorizing(
            @NonNull final String txnName,
            @NonNull final List<AtomicReference<ScheduleID>> scheduleIds,
            @NonNull final Write before,
            @NonNull final Write after,
            final boolean revertAfter,
            @NonNull final ResponseCodeEnum expectedStatus) {
        return sourcing(() -> writerCall(
                        "setCallSet",
                        authorizingArgs(
                                scheduleIds.stream().map(AtomicReference::get).toList(), before, after, revertAfter))
                .gas(2_000_000L)
                .via(txnName)
                .hasKnownStatus(expectedStatus));
    }

    private static Object[] authorizingArgs(
            @NonNull final List<ScheduleID> scheduleIds,
            @NonNull final Write before,
            @NonNull final Write after,
            final boolean revertAfter) {
        return new Object[] {
            BigInteger.valueOf(before.key()),
            BigInteger.valueOf(before.value()),
            asHeadlongAddress(SCHEDULE_SERVICE),
            scheduleIds.stream()
                    .map(scheduleId -> AUTHORIZE_SCHEDULE
                            .encodeCallWithArgs(mirrorAddrWith(scheduleId))
                            .array())
                    .toArray(byte[][]::new),
            BigInteger.valueOf(after.key()),
            BigInteger.valueOf(after.value()),
            revertAfter
        };
    }

    private static String revertReason(@NonNull final ResponseCodeEnum status) {
        return "0x" + HexFormat.of().formatHex(status.name().getBytes(StandardCharsets.UTF_8));
    }

    private static SpecOperation writerCreated() {
        return blockingOrder(uploadInitCode(WRITER), contractCreate(WRITER).gas(500_000L));
    }

    private static HapiContractCall writerCall(@NonNull final String function, final long... args) {
        return writerCall(
                function, Arrays.stream(args).mapToObj(BigInteger::valueOf).toArray());
    }

    private static HapiContractCall writerCall(@NonNull final String function, @NonNull final Object[] args) {
        return contractCall(WRITER, function, args).gas(200_000L);
    }

    private static SpecOperation scheduledStatusIs(
            @NonNull final String schedule, @NonNull final ResponseCodeEnum status) {
        return getTxnRecord(schedule + "Creation")
                .scheduled()
                .hasPriority(recordWith().status(status));
    }

    private static SpecOperation capAggregateSlotsAtCurrentPlus(final long extra) {
        final var slotsUsed = new AtomicLong();
        return blockingOrder(
                viewSingleton(
                        EntityIdService.NAME,
                        ENTITY_COUNTS_STATE_ID,
                        (EntityCounts counts) ->
                                slotsUsed.set(counts.numContractStorageSlots() + counts.numEvmHookStorageSlots())),
                sourcing(() -> overriding("contracts.maxKvPairs.aggregate", String.valueOf(slotsUsed.get() + extra))));
    }

    private static SpecOperation recordSlotCount(@NonNull final AtomicLong slots) {
        return viewSingleton(
                EntityIdService.NAME,
                ENTITY_COUNTS_STATE_ID,
                (EntityCounts counts) -> slots.set(counts.numContractStorageSlots()));
    }

    private static SpecOperation slotCountChangedBy(@NonNull final AtomicLong slotsBefore, final long change) {
        return viewSingleton(
                EntityIdService.NAME,
                ENTITY_COUNTS_STATE_ID,
                (EntityCounts counts) -> assertEquals(slotsBefore.get() + change, counts.numContractStorageSlots()));
    }

    /**
     * Walks the contract's storage list from its first key and checks that the links are consistent and that
     * both the list length and the contract's kv pairs count equal {@code expectedSlots}.
     */
    private static SpecOperation storageListMatches(@NonNull final String contract, final int expectedSlots) {
        return storageMatches(contract, expectedSlots, null);
    }

    /**
     * Like {@link #storageListMatches(String, int)}, and also checks the values in the contract's storage.
     */
    private static SpecOperation storageValuesAre(@NonNull final String contract, final long... expectedValues) {
        return storageMatches(contract, expectedValues.length, expectedValues);
    }

    private static SpecOperation storageMatches(
            @NonNull final String contract, final int expectedSlots, @Nullable final long[] expectedValues) {
        return sourcingContextual(spec -> viewContract(contract, (Account account) -> {
            final ReadableKVState<SlotKey, SlotValue> storage = spec.embeddedStateOrThrow()
                    .getReadableStates(ContractService.NAME)
                    .get(STORAGE_STATE_ID);
            final var accountId = account.accountIdOrThrow();
            final var contractId = ContractID.newBuilder()
                    .shardNum(accountId.shardNum())
                    .realmNum(accountId.realmNum())
                    .contractNum(accountId.accountNumOrThrow())
                    .build();
            final Set<Bytes> visited = new HashSet<>();
            final List<BigInteger> values = new ArrayList<>();
            var prevKey = Bytes.EMPTY;
            var key = account.firstContractStorageKey();
            while (key.length() > 0) {
                assertTrue(visited.add(key), "Storage list repeats slot " + key);
                final var value = storage.get(new SlotKey(contractId, key));
                assertNotNull(value, "Storage list has no value for slot " + key);
                assertEquals(prevKey, value.previousKey(), "Wrong previous key for slot " + key);
                values.add(new BigInteger(1, value.value().toByteArray()));
                prevKey = key;
                key = value.nextKey();
            }
            assertEquals(expectedSlots, visited.size(), "Wrong storage list length");
            assertEquals(expectedSlots, account.contractKvPairsNumber(), "Wrong kv pairs count");
            if (expectedValues != null) {
                assertEquals(
                        Arrays.stream(expectedValues)
                                .sorted()
                                .mapToObj(BigInteger::valueOf)
                                .toList(),
                        values.stream().sorted().toList(),
                        "Wrong storage values");
            }
        }));
    }

    private record Write(long key, long value) {}
}
