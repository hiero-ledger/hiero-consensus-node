// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.node.base.HookEntityId.EntityIdOneOfType.ACCOUNT_ID;
import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.leftPad32;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.slotKeyOfMappingEntry;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STATES_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountEvmHookStore;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewHook;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewSingleton;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertOwnerHasEvmHookSlotUsageChange;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EVM_HOOK_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOO_MANY_EVM_HOOK_STORAGE_UPDATES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.hooks.EvmHook;
import com.hedera.hapi.node.hooks.EvmHookMappingEntry;
import com.hedera.hapi.node.hooks.EvmHookSpec;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.hooks.EvmHookSlotKey;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema;
import com.hedera.node.app.service.contract.impl.state.ReadableEvmHookStoreImpl;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.spec.utilops.embedded.MutateStatesStoreOp;
import com.hedera.services.bdd.spec.utilops.embedded.ViewAccountOp;
import com.hedera.services.bdd.spec.utilops.embedded.ViewKVStateOp;
import com.swirlds.base.utility.Pair;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

@Order(-2)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
// Ordered because a final test deletes the hook owner and confirms its HookStore operations fail
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RepeatableEvmHookStoreTests {
    private static final long EVM_HOOK_ID = 124L;
    private static final long DELETED_HOOK_ID = 125L;
    private static final long MISSING_HOOK_ID = 126L;
    private static final long EVM_HOOK_WITH_ADMIN_ID = 127L;

    private static final Bytes ZERO = leftPad32(Bytes.EMPTY);
    private static final Bytes A = Bytes.wrap("a");
    private static final Bytes B = Bytes.wrap("Bb");
    private static final Bytes C = Bytes.wrap("cCc");
    private static final Bytes D = Bytes.fromHex("dddd");
    private static final Bytes E = Bytes.fromHex("eeeeee");
    private static final Bytes F = Bytes.fromHex("ffffffff");

    private static final EvmHookMappingEntry PREIMAGE_ZERO_A_ENTRY =
            EvmHookMappingEntry.newBuilder().preimage(ZERO).value(A).build();
    private static final EvmHookMappingEntry F_E_ENTRY =
            EvmHookMappingEntry.newBuilder().key(F).value(E).build();

    @Account
    static SpecAccount HOOK_OWNER;

    @Account
    static SpecAccount HOOK_ADMIN;

    @Contract(contract = "PayableConstructor")
    static SpecContract HOOK_CONTRACT;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        testLifecycle.doAdhoc(
                HOOK_CONTRACT.getInfo(),
                HOOK_OWNER.getBalance(),
                HOOK_ADMIN.getBalance(),
                sourcingContextual(RepeatableEvmHookStoreTests::evmHookCreation),
                sourcingContextual(RepeatableEvmHookStoreTests::evmHookWithAdminCreation));
    }

    @Order(1)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> mustSpecifyFullHookId() {
        return hapiTest(accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                .omittingEntityId()
                .hasPrecheck(INVALID_HOOK_ID));
    }

    @Order(2)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> cannotManageStorageOfMissingEvmHook() {
        return hapiTest(accountEvmHookStore(HOOK_OWNER.name(), MISSING_HOOK_ID)
                .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                .hasKnownStatus(HOOK_NOT_FOUND));
    }

    @Order(4)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> cannotManageStorageOfDeletedEvmHook() {
        return hapiTest(
                sourcingContextual(RepeatableEvmHookStoreTests::deletedHookCreation),
                accountEvmHookStore(HOOK_OWNER.name(), DELETED_HOOK_ID)
                        .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                        .hasKnownStatus(HOOK_NOT_FOUND));
    }

    @Order(5)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> mustUseMinimalRepresentationsExceptForMappingPreimage() {
        final AtomicLong origCount = new AtomicLong();
        return hapiTest(
                UtilVerbs.recordCurrentOwnerEvmHookSlotUsage(HOOK_OWNER.name(), origCount::set),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putSlot(leftPad32(Bytes.EMPTY), Bytes.EMPTY)
                        .hasPrecheck(EVM_HOOK_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putSlot(Bytes.EMPTY, leftPad32(Bytes.EMPTY))
                        .hasPrecheck(EVM_HOOK_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putMappingEntryWithKey(leftPad32(Bytes.EMPTY), Bytes.EMPTY, Bytes.EMPTY)
                        .hasPrecheck(EVM_HOOK_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putMappingEntryWithKey(Bytes.EMPTY, leftPad32(Bytes.EMPTY), Bytes.EMPTY),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putMappingEntryWithKey(Bytes.EMPTY, Bytes.EMPTY, leftPad32(Bytes.EMPTY))
                        .hasPrecheck(EVM_HOOK_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putMappingEntryWithPreimage(Bytes.EMPTY, Bytes.EMPTY, leftPad32(Bytes.EMPTY))
                        .hasPrecheck(EVM_HOOK_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID).putMappingEntry(Bytes.EMPTY, PREIMAGE_ZERO_A_ENTRY),
                assertOwnerHasEvmHookSlotUsageChange(HOOK_OWNER.name(), origCount, 1),
                assertEvmHookHasFirstOrderedSlots(
                        EVM_HOOK_ID, List.of(Pair.of(slotKeyOfMappingEntry(ZERO, PREIMAGE_ZERO_A_ENTRY), A))));
    }

    @Order(6)
    @LeakyRepeatableHapiTest(value = NEEDS_STATE_ACCESS, overrides = "hooks.maxHookStoreUpdates")
    Stream<DynamicTest> cannotExceedMaxStorageUpdates() {
        return hapiTest(
                overriding("hooks.maxHookStoreUpdates", "1"),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putSlot(B, C)
                        .putSlot(D, E)
                        .hasKnownStatus(TOO_MANY_EVM_HOOK_STORAGE_UPDATES));
    }

    @Order(7)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> newEntriesInsertedAtHead() {
        final AtomicLong origCount = new AtomicLong();
        return hapiTest(
                UtilVerbs.recordCurrentOwnerEvmHookSlotUsage(HOOK_OWNER.name(), origCount::set),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putSlot(B, C)
                        .putSlot(D, E),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_WITH_ADMIN_ID)
                        .signedBy(DEFAULT_PAYER, HOOK_ADMIN.name())
                        .putMappingEntry(A, F_E_ENTRY)
                        .putSlot(F, E),
                assertOwnerHasEvmHookSlotUsageChange(HOOK_OWNER.name(), origCount, 4),
                assertEvmHookHasFirstOrderedSlots(EVM_HOOK_ID, List.of(Pair.of(D, E), Pair.of(B, C))),
                assertEvmHookHasFirstOrderedSlots(
                        EVM_HOOK_WITH_ADMIN_ID,
                        List.of(Pair.of(F, E), Pair.of(slotKeyOfMappingEntry(leftPad32(A), F_E_ENTRY), E))));
    }

    @Order(8)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> updatingSlotsDontChangeCounts() {
        final AtomicLong origCount = new AtomicLong();
        return hapiTest(
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putSlot(A, F)
                        .putSlot(B, F),
                UtilVerbs.recordCurrentOwnerEvmHookSlotUsage(HOOK_OWNER.name(), origCount::set),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putSlot(A, E)
                        .putSlot(B, E),
                assertOwnerHasEvmHookSlotUsageChange(HOOK_OWNER.name(), origCount, 0));
    }

    @Order(9)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> clearingAllSlotsLeavesZeroUsage() {
        final AtomicLong origCount = new AtomicLong();
        return hapiTest(
                UtilVerbs.recordCurrentOwnerEvmHookSlotUsage(HOOK_OWNER.name(), origCount::set),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .removeSlot(A)
                        .removeSlot(B)
                        .removeSlot(C)
                        .removeSlot(D)
                        .removeMappingEntryWithPreimage(Bytes.EMPTY, ZERO),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_WITH_ADMIN_ID)
                        .signedBy(DEFAULT_PAYER, HOOK_ADMIN.name())
                        .removeMappingEntry(A, F)
                        .removeSlot(F),
                assertOwnerHasEvmHookSlotUsage(origCount, () -> -origCount.get()),
                assertEvmHookHasSlotUsage(EVM_HOOK_ID, 0),
                assertEvmHookHasSlotUsage(EVM_HOOK_WITH_ADMIN_ID, 0));
    }

    @Order(10)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> eachSStoreScenarioHasCorrectGasCostWithDirectSlotUpdates() {
        final AtomicLong tinybarGasPrice = new AtomicLong();
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT.name())),
                doingContextual(spec -> tinybarGasPrice.set(spec.ratesProvider().currentTinybarGasPrice())),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putSlot(A, Bytes.EMPTY)
                        .via("zeroIntoZero"),
                sourcing(() -> validateChargedUsd("zeroIntoZero", 0.005)),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putSlot(A, F)
                        .via("nonZeroIntoZero"),
                sourcing(() -> validateChargedUsd("nonZeroIntoZero", 0.005)),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putSlot(A, E)
                        .via("nonZeroIntoNonZero"),
                sourcing(() -> validateChargedUsd("nonZeroIntoNonZero", 0.005)),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putSlot(A, E)
                        .via("noopNonZeroIntoNonZero"),
                sourcing(() -> validateChargedUsd("noopNonZeroIntoNonZero", 0.005)),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putSlot(A, Bytes.EMPTY)
                        .via("zeroIntoNonZero"),
                sourcing(() -> validateChargedUsd("zeroIntoNonZero", 0.005)));
    }

    @Order(11)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> eachSStoreScenarioHasCorrectGasCostWithMappingEntrySlotUpdates() {
        final AtomicLong tinybarGasPrice = new AtomicLong();
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT.name())),
                doingContextual(spec -> tinybarGasPrice.set(spec.ratesProvider().currentTinybarGasPrice())),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putMappingEntryWithKey(Bytes.EMPTY, A, Bytes.EMPTY)
                        .via("zeroIntoZero"),
                sourcing(() -> validateChargedUsd("zeroIntoZero", 0.005)),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putMappingEntryWithKey(Bytes.EMPTY, A, F)
                        .via("nonZeroIntoZero"),
                sourcing(() -> validateChargedUsd("nonZeroIntoZero", 0.005)),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putMappingEntryWithKey(Bytes.EMPTY, A, E)
                        .via("nonZeroIntoNonZero"),
                sourcing(() -> validateChargedUsd("nonZeroIntoNonZero", 0.005)),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putMappingEntryWithKey(Bytes.EMPTY, A, E)
                        .via("noopNonZeroIntoNonZero"),
                sourcing(() -> validateChargedUsd("noopNonZeroIntoNonZero", 0.005)),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putMappingEntryWithKey(Bytes.EMPTY, A, Bytes.EMPTY)
                        .via("zeroIntoNonZero"),
                sourcing(() -> validateChargedUsd("zeroIntoNonZero", 0.005)));
    }

    @Order(12)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> duplicateKeyUpdatesCountedCorrectly() {
        return hapiTest(
                cryptoCreate(CIVILIAN_PAYER)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT.name())),
                // Two updates to the same key should count as one net new slot
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .putMappingEntryWithKey(Bytes.EMPTY, A, E)
                        .putMappingEntryWithKey(Bytes.EMPTY, B, F)
                        .putMappingEntryWithKey(Bytes.EMPTY, A, F),
                viewAccount(CIVILIAN_PAYER, account -> assertEquals(2, account.numberEvmHookStorageSlots())),
                // And three removals for same key should count as one net removal
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .removeMappingEntry(Bytes.EMPTY, A)
                        .removeMappingEntry(Bytes.EMPTY, A)
                        .removeMappingEntry(Bytes.EMPTY, A),
                viewAccount(CIVILIAN_PAYER, account -> assertEquals(1, account.numberEvmHookStorageSlots())));
    }

    @Order(13)
    @LeakyRepeatableHapiTest(
            value = NEEDS_STATE_ACCESS,
            overrides = {"hooks.maxEvmHookStorageSlots"})
    Stream<DynamicTest> hookStoreLimitedToMaxStorageSlots() {
        final var currentNumStorageSlots = new AtomicLong();
        return hapiTest(
                viewSingleton(
                        EntityIdService.NAME,
                        ENTITY_COUNTS_STATE_ID,
                        (EntityCounts entityCounts) ->
                                currentNumStorageSlots.set(entityCounts.numEvmHookStorageSlots())),
                sourcing(() -> overriding("hooks.maxEvmHookStorageSlots", "" + (currentNumStorageSlots.get() + 1))),
                cryptoCreate(CIVILIAN_PAYER)
                        .balance(ONE_HUNDRED_HBARS)
                        .withHook(accountAllowanceHook(1L, HOOK_CONTRACT.name())),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putMappingEntryWithKey(Bytes.EMPTY, A, F),
                accountEvmHookStore(CIVILIAN_PAYER, 1L)
                        .payingWith(CIVILIAN_PAYER)
                        .signedBy(CIVILIAN_PAYER)
                        .putMappingEntryWithKey(Bytes.EMPTY, B, F)
                        .hasKnownStatus(MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED),
                viewSingleton(
                        EntityIdService.NAME,
                        ENTITY_COUNTS_STATE_ID,
                        (EntityCounts entityCounts) ->
                                assertEquals(currentNumStorageSlots.get() + 1, entityCounts.numEvmHookStorageSlots())));
    }

    @Order(14)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> hookZeroIntoEmptySlotSStore(
            @Contract(contract = "StorageLinkedListHook", creationGas = 5_000_000) SpecContract contract) {
        final AtomicLong totalHookStorageSlots = new AtomicLong();
        final Supplier<HapiSpecOperation> assertZeroStorageCounts = () -> blockingOrder(
                viewAccount("owner", account -> assertEquals(0, account.numberEvmHookStorageSlots())),
                doingContextual(spec -> viewHook(
                        HookId.newBuilder()
                                .entityId(HookEntityId.newBuilder()
                                        .accountId(toPbj(spec.registry().getAccountID("owner"))))
                                .hookId(42L)
                                .build(),
                        hookState -> assertEquals(0, hookState.numStorageSlots()))));
        return hapiTest(
                contract.getInfo(),
                // Create an account with a hook that puts a zero into an empty slot when invoked with calldata 0x01
                cryptoCreate("owner").withHooks(accountAllowanceHook(42L, contract.name())),
                // Take a snapshot of total hook storage slots before---Map.size() works since this is a FakeState
                doingContextual(spec -> totalHookStorageSlots.set(spec.repeatableEmbeddedHederaOrThrow()
                        .state()
                        .getReadableStates(ContractService.NAME)
                        .get(V065ContractSchema.EVM_HOOK_STORAGE_STATE_ID)
                        .size())),
                sourcing(assertZeroStorageCounts),
                cryptoTransfer(movingHbar(1).between("owner", FUNDING))
                        // The StorageLinkedListHook.allow() method puts a zero into an empty slot on
                        // calldata 0x01, then returns true
                        .withPreHookFor("owner", 42L, 250_000L, ByteString.copyFrom(new byte[] {0x01})),
                sourcing(assertZeroStorageCounts),
                // Confirm the underlying hook storage slots didn't change either
                doingContextual(spec -> {
                    final ReadableKVState<EvmHookSlotKey, SlotValue> hookStorage =
                            spec.repeatableEmbeddedHederaOrThrow()
                                    .state()
                                    .getReadableStates(ContractService.NAME)
                                    .get(V065ContractSchema.EVM_HOOK_STORAGE_STATE_ID);
                    assertEquals(totalHookStorageSlots.get(), hookStorage.size());
                }));
    }

    @Order(99)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> cannotManageStorageOfHooksOnceCreatorIsDeleted(@Account SpecAccount beneficiary) {
        return hapiTest(
                HOOK_OWNER.deleteWithTransfer(beneficiary),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_ID)
                        .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                        .hasKnownStatus(ACCOUNT_DELETED),
                accountEvmHookStore(HOOK_OWNER.name(), EVM_HOOK_WITH_ADMIN_ID)
                        .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                        .signedBy(DEFAULT_PAYER, HOOK_ADMIN.name())
                        .hasKnownStatus(ACCOUNT_DELETED));
    }

    private static SpecOperation assertEvmHookHasFirstOrderedSlots(
            final long hookId, final List<Pair<Bytes, Bytes>> slots) {
        return doingContextual(spec -> {
            final var store =
                    new ReadableEvmHookStoreImpl(spec.embeddedStateOrThrow().getReadableStates(ContractService.NAME));
            final var registry = spec.registry();
            final var hookEntityId =
                    new HookEntityId(new OneOf<>(ACCOUNT_ID, toPbj(registry.getAccountID(HOOK_OWNER.name()))));
            final var HookId = new HookId(hookEntityId, hookId);
            final var hookState = store.getEvmHook(HookId);
            assertNotNull(hookState, "hook" + hookId + " not found");
            assertTrue(
                    slots.size() <= hookState.numStorageSlots(),
                    "hook" + hookId + " has only " + hookState.numStorageSlots()
                            + " slots, but expected a prefix of length " + slots.size());
            var key = hookState.firstContractStorageKey();
            for (final var slot : slots) {
                assertNotNull(key, "hook" + hookId + " has no slot key for " + slot.key());
                assertEquals(slot.key(), key, "hook" + hookId + " has wrong slot key");
                final var slotKey = new EvmHookSlotKey(HookId, key);
                final var slotValue = store.getSlotValue(slotKey);
                assertNotNull(slotValue, "hook" + hookId + " has no value for " + slotKey);
                assertEquals(slot.value(), slotValue.value(), "hook" + hookId + " has wrong value for " + slotKey);
                key = slotValue.nextKey();
            }
        });
    }

    private static SpecOperation assertEvmHookHasSlotUsage(final long hookId, final long numSlots) {
        return sourcingContextual(spec ->
                new ViewKVStateOp<HookId, EvmHookState>(ContractService.NAME, EVM_HOOK_STATES_STATE_ID, state -> {
                    final var hookEntityId = new HookEntityId(
                            new OneOf<>(ACCOUNT_ID, toPbj(spec.registry().getAccountID(HOOK_OWNER.name()))));
                    final var HookId = new HookId(hookEntityId, hookId);
                    final var hookState = state.get(HookId);
                    assertNotNull(hookState, "hook" + hookId + " not found");
                    assertEquals(numSlots, hookState.numStorageSlots(), "hook" + hookId + " has wrong number of slots");
                }));
    }

    private static SpecOperation assertOwnerHasEvmHookSlotUsage(AtomicLong origCount, final LongSupplier delta) {
        return sourcing(() -> new ViewAccountOp(
                HOOK_OWNER.name(),
                account -> assertEquals(
                        origCount.get() + delta.getAsLong(),
                        account.numberEvmHookStorageSlots(),
                        "Wrong # of EVM hook storage slots")));
    }

    private static SpecOperation evmHookCreation(@NonNull final HapiSpec spec) {
        return hookCreation(
                spec,
                (contractId, details) -> details.hookId(EVM_HOOK_ID)
                        .evmHook(EvmHook.newBuilder()
                                .spec(EvmHookSpec.newBuilder().contractId(contractId))),
                false);
    }

    private static SpecOperation evmHookWithAdminCreation(@NonNull final HapiSpec spec) {
        return hookCreation(
                spec,
                (contractId, details) -> details.hookId(EVM_HOOK_WITH_ADMIN_ID)
                        .adminKey(toPbj(spec.registry().getKey(HOOK_ADMIN.name())))
                        .evmHook(EvmHook.newBuilder()
                                .spec(EvmHookSpec.newBuilder().contractId(contractId))),
                false);
    }

    private static SpecOperation deletedHookCreation(@NonNull final HapiSpec spec) {
        return hookCreation(
                spec,
                (contractId, details) -> details.hookId(DELETED_HOOK_ID)
                        .evmHook(EvmHook.newBuilder()
                                .spec(EvmHookSpec.newBuilder().contractId(contractId))),
                true);
    }

    private static SpecOperation hookCreation(
            @NonNull final HapiSpec spec,
            @NonNull final BiConsumer<ContractID, HookCreationDetails.Builder> hookSpec,
            final boolean deleteAfterwards) {
        return new MutateStatesStoreOp(ContractService.NAME, (states, counters) -> {
            final var registry = spec.registry();
            final var hookEntityId =
                    new HookEntityId(new OneOf<>(ACCOUNT_ID, toPbj(registry.getAccountID(HOOK_OWNER.name()))));
            final var contractId = toPbj(registry.getContractId(HOOK_CONTRACT.name()));
            final var builder = HookCreationDetails.newBuilder().extensionPoint(ACCOUNT_ALLOWANCE_HOOK);
            hookSpec.accept(contractId, builder);
            final var creation = HookCreation.newBuilder()
                    .entityId(hookEntityId)
                    .details(builder.build())
                    .nextHookId(null)
                    .build();
            final var store = new WritableEvmHookStore(states, counters);
            store.createEvmHook(creation, Long.MAX_VALUE);
            if (deleteAfterwards) {
                store.remove(new HookId(hookEntityId, creation.detailsOrThrow().hookId()));
            }
        });
    }
}
