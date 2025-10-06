// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.node.base.HookEntityId.EntityIdOneOfType.ACCOUNT_ID;
import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.leftPad32;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.slotKeyOfMappingEntry;
import static com.hedera.node.app.service.contract.impl.schemas.V065ContractSchema.EVM_HOOK_STATES_STATE_ID;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.accountLambdaSStore;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.LAMBDA_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOO_MANY_LAMBDA_STORAGE_UPDATES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.hooks.EvmHookSpec;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.hooks.LambdaEvmHook;
import com.hedera.hapi.node.hooks.LambdaMappingEntry;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.hooks.LambdaSlotKey;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.state.ReadableEvmHookStoreImpl;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.utilops.embedded.MutateStatesStoreOp;
import com.hedera.services.bdd.spec.utilops.embedded.ViewAccountOp;
import com.hedera.services.bdd.spec.utilops.embedded.ViewKVStateOp;
import com.swirlds.base.utility.Pair;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;

@Order(9)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
// Ordered because a final test deletes the hook owner and confirms its LambdaSStore operations fail
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RepeatableLambdaSStoreTests {

    private static final long PURE_HOOK_ID = 123L;
    private static final long LAMBDA_HOOK_ID = 124L;
    private static final long DELETED_HOOK_ID = 125L;
    private static final long MISSING_HOOK_ID = 126L;
    private static final long LAMBDA_HOOK_WITH_ADMIN_ID = 127L;

    private static final Bytes ZERO = leftPad32(Bytes.EMPTY);
    private static final Bytes A = Bytes.wrap("a");
    private static final Bytes B = Bytes.wrap("Bb");
    private static final Bytes C = Bytes.wrap("cCc");
    private static final Bytes D = Bytes.fromHex("dddd");
    private static final Bytes E = Bytes.fromHex("eeeeee");
    private static final Bytes F = Bytes.fromHex("ffffffff");

    private static final LambdaMappingEntry PREIMAGE_ZERO_A_ENTRY =
            LambdaMappingEntry.newBuilder().preimage(ZERO).value(A).build();
    private static final LambdaMappingEntry F_E_ENTRY =
            LambdaMappingEntry.newBuilder().key(F).value(E).build();

    @Account
    static SpecAccount HOOK_OWNER;

    @Account
    static SpecAccount HOOK_ADMIN;

    @Contract(contract = "PayableConstructor")
    static SpecContract HOOK_CONTRACT;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.hooksEnabled", "true"));
        // Manually insert a hook on the owner account for LambdaSStore testing only
        testLifecycle.doAdhoc(
                HOOK_CONTRACT.getInfo(),
                HOOK_OWNER.getBalance(),
                HOOK_ADMIN.getBalance(),
                sourcingContextual(RepeatableLambdaSStoreTests::lambdaHookCreation),
                sourcingContextual(RepeatableLambdaSStoreTests::lambdaHookWithAdminCreation));
    }

    @Order(1)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> mustSpecifyFullHookId() {
        return hapiTest(accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                .omittingEntityId()
                .hasPrecheck(INVALID_HOOK_ID));
    }

    @Order(2)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> cannotManageStorageOfMissingEvmHook() {
        return hapiTest(accountLambdaSStore(HOOK_OWNER.name(), MISSING_HOOK_ID)
                .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                .hasKnownStatus(HOOK_NOT_FOUND));
    }

    @Order(4)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> cannotManageStorageOfDeletedEvmHook() {
        return hapiTest(
                sourcingContextual(RepeatableLambdaSStoreTests::deletedHookCreation),
                accountLambdaSStore(HOOK_OWNER.name(), DELETED_HOOK_ID)
                        .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                        .hasKnownStatus(HOOK_NOT_FOUND));
    }

    @Order(5)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> mustUseMinimalRepresentationsExceptForMappingPreimage() {
        final AtomicLong origCount = new AtomicLong();
        return hapiTest(
                recordCurrentOwnerLambdaSlotUsage(origCount::set),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putSlot(leftPad32(Bytes.EMPTY), Bytes.EMPTY)
                        .hasPrecheck(LAMBDA_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putSlot(Bytes.EMPTY, leftPad32(Bytes.EMPTY))
                        .hasPrecheck(LAMBDA_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putMappingEntryWithKey(leftPad32(Bytes.EMPTY), Bytes.EMPTY, Bytes.EMPTY)
                        .hasPrecheck(LAMBDA_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putMappingEntryWithKey(Bytes.EMPTY, leftPad32(Bytes.EMPTY), Bytes.EMPTY)
                        .hasPrecheck(LAMBDA_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putMappingEntryWithKey(Bytes.EMPTY, Bytes.EMPTY, leftPad32(Bytes.EMPTY))
                        .hasPrecheck(LAMBDA_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putMappingEntryWithPreimage(Bytes.EMPTY, Bytes.EMPTY, leftPad32(Bytes.EMPTY))
                        .hasPrecheck(LAMBDA_STORAGE_UPDATE_BYTES_MUST_USE_MINIMAL_REPRESENTATION),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putMappingEntry(Bytes.EMPTY, PREIMAGE_ZERO_A_ENTRY),
                assertOwnerHasLambdaSlotUsageChange(origCount, 1),
                assertLambdaHasFirstOrderedSlots(
                        LAMBDA_HOOK_ID, List.of(Pair.of(slotKeyOfMappingEntry(ZERO, PREIMAGE_ZERO_A_ENTRY), A))));
    }

    @Order(6)
    @LeakyRepeatableHapiTest(value = NEEDS_STATE_ACCESS, overrides = "hooks.maxLambdaSStoreUpdates")
    Stream<DynamicTest> cannotExceedMaxStorageUpdates() {
        return hapiTest(
                overriding("hooks.maxLambdaSStoreUpdates", "1"),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putSlot(B, C)
                        .putSlot(D, E)
                        .hasKnownStatus(TOO_MANY_LAMBDA_STORAGE_UPDATES));
    }

    @Order(7)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> newEntriesInsertedAtHead() {
        final AtomicLong origCount = new AtomicLong();
        return hapiTest(
                recordCurrentOwnerLambdaSlotUsage(origCount::set),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putSlot(B, C)
                        .putSlot(D, E),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_WITH_ADMIN_ID)
                        .signedBy(DEFAULT_PAYER, HOOK_ADMIN.name())
                        .putMappingEntry(A, F_E_ENTRY)
                        .putSlot(F, E),
                assertOwnerHasLambdaSlotUsageChange(origCount, 4),
                assertLambdaHasFirstOrderedSlots(LAMBDA_HOOK_ID, List.of(Pair.of(D, E), Pair.of(B, C))),
                assertLambdaHasFirstOrderedSlots(
                        LAMBDA_HOOK_WITH_ADMIN_ID,
                        List.of(Pair.of(F, E), Pair.of(slotKeyOfMappingEntry(leftPad32(A), F_E_ENTRY), E))));
    }

    @Order(8)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> updatingSlotsDontChangeCounts() {
        final AtomicLong origCount = new AtomicLong();
        return hapiTest(
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putSlot(A, F)
                        .putSlot(B, F),
                recordCurrentOwnerLambdaSlotUsage(origCount::set),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putSlot(A, E)
                        .putSlot(B, E),
                assertOwnerHasLambdaSlotUsageChange(origCount, 0));
    }

    @Order(9)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> clearingAllSlotsLeavesZeroUsage() {
        final AtomicLong origCount = new AtomicLong();
        return hapiTest(
                recordCurrentOwnerLambdaSlotUsage(origCount::set),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .removeSlot(A)
                        .removeSlot(B)
                        .removeSlot(C)
                        .removeSlot(D)
                        .removeMappingEntryWithPreimage(Bytes.EMPTY, ZERO),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_WITH_ADMIN_ID)
                        .signedBy(DEFAULT_PAYER, HOOK_ADMIN.name())
                        .removeMappingEntry(A, F)
                        .removeSlot(F),
                assertOwnerHasLambdaSlotUsage(origCount, () -> -origCount.get()),
                assertLambdaHasSlotUsage(LAMBDA_HOOK_ID, 0),
                assertLambdaHasSlotUsage(LAMBDA_HOOK_WITH_ADMIN_ID, 0));
    }

    @Order(99)
    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> cannotManageStorageOfHooksOnceCreatorIsDeleted(@Account SpecAccount beneficiary) {
        return hapiTest(
                HOOK_OWNER.deleteWithTransfer(beneficiary),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_ID)
                        .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                        .hasKnownStatus(ACCOUNT_DELETED),
                accountLambdaSStore(HOOK_OWNER.name(), LAMBDA_HOOK_WITH_ADMIN_ID)
                        .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                        .signedBy(DEFAULT_PAYER, HOOK_ADMIN.name())
                        .hasKnownStatus(ACCOUNT_DELETED));
    }

    private static SpecOperation assertLambdaHasFirstOrderedSlots(
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
                final var slotKey = new LambdaSlotKey(HookId, key);
                final var slotValue = store.getSlotValue(slotKey);
                assertNotNull(slotValue, "hook" + hookId + " has no value for " + slotKey);
                assertEquals(slot.value(), slotValue.value(), "hook" + hookId + " has wrong value for " + slotKey);
                key = slotValue.nextKey();
            }
        });
    }

    private static SpecOperation recordCurrentOwnerLambdaSlotUsage(LongConsumer cb) {
        return new ViewAccountOp(HOOK_OWNER.name(), account -> cb.accept(account.numberLambdaStorageSlots()));
    }

    private static SpecOperation assertOwnerHasLambdaSlotUsageChange(AtomicLong origCount, final int delta) {
        return sourcing(() -> new ViewAccountOp(
                HOOK_OWNER.name(),
                account -> assertEquals(
                        origCount.get() + delta,
                        account.numberLambdaStorageSlots(),
                        "Wrong # of lambda storage slots")));
    }

    private static SpecOperation assertLambdaHasSlotUsage(final long hookId, final long numSlots) {
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

    private static SpecOperation assertOwnerHasLambdaSlotUsage(AtomicLong origCount, final LongSupplier delta) {
        return sourcing(() -> new ViewAccountOp(
                HOOK_OWNER.name(),
                account -> assertEquals(
                        origCount.get() + delta.getAsLong(),
                        account.numberLambdaStorageSlots(),
                        "Wrong # of lambda storage slots")));
    }

    private static SpecOperation lambdaHookCreation(@NonNull final HapiSpec spec) {
        return hookCreation(
                spec,
                (contractId, details) -> details.hookId(LAMBDA_HOOK_ID)
                        .lambdaEvmHook(LambdaEvmHook.newBuilder()
                                .spec(EvmHookSpec.newBuilder().contractId(contractId))),
                false);
    }

    private static SpecOperation lambdaHookWithAdminCreation(@NonNull final HapiSpec spec) {
        return hookCreation(
                spec,
                (contractId, details) -> details.hookId(LAMBDA_HOOK_WITH_ADMIN_ID)
                        .adminKey(toPbj(spec.registry().getKey(HOOK_ADMIN.name())))
                        .lambdaEvmHook(LambdaEvmHook.newBuilder()
                                .spec(EvmHookSpec.newBuilder().contractId(contractId))),
                false);
    }

    private static SpecOperation deletedHookCreation(@NonNull final HapiSpec spec) {
        return hookCreation(
                spec,
                (contractId, details) -> details.hookId(DELETED_HOOK_ID)
                        .lambdaEvmHook(LambdaEvmHook.newBuilder()
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
            store.createEvmHook(creation);
            if (deleteAfterwards) {
                store.remove(new HookId(hookEntityId, creation.detailsOrThrow().hookId()));
            }
        });
    }
}
