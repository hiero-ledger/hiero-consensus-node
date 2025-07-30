// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.hapi.node.base.HookEntityId.EntityIdOneOfType.ACCOUNT_ID;
import static com.hedera.hapi.node.hooks.HookExtensionPoint.ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.contract.HapiLambdaSStore.accountLambdaSStore;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.HOOK_IS_NOT_A_LAMBDA;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.CreatedHookId;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.hooks.EvmHookSpec;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.hooks.LambdaEvmHook;
import com.hedera.hapi.node.hooks.PureEvmHook;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.state.WritableEvmHookStore;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
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
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(9)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableLambdaSStoreTests {
    private static final long PURE_HOOK_ID = 123L;
    private static final long LAMBDA_HOOK_ID = 124L;
    private static final long DELETED_HOOK_ID = 125L;
    private static final long MISSING_HOOK_ID = 126L;

    @Account
    static SpecAccount HOOK_OWNER;

    @Contract(contract = "PayableConstructor")
    static SpecContract HOOK_CONTRACT;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("hooks.enabled", "true"));
        // Manually insert a hook on the owner account for LambdaSStore testing only
        testLifecycle.doAdhoc(
                HOOK_OWNER.getBalance(),
                HOOK_CONTRACT.getInfo(),
                sourcingContextual(RepeatableLambdaSStoreTests::lambdaHookCreation),
                sourcingContextual(RepeatableLambdaSStoreTests::deletedHookCreation));
    }

    @RepeatableHapiTest(NEEDS_STATE_ACCESS)
    Stream<DynamicTest> cannotManageStorageOfPureEvmHook() {
        return hapiTest(
                sourcingContextual(RepeatableLambdaSStoreTests::pureHookCreation),
                accountLambdaSStore(HOOK_OWNER.name(), PURE_HOOK_ID)
                        .putSlot(Bytes.EMPTY, Bytes.EMPTY)
                        .hasKnownStatus(HOOK_IS_NOT_A_LAMBDA));
    }

    private static SpecOperation pureHookCreation(@NonNull final HapiSpec spec) {
        return hookCreation(
                spec,
                (contractId, details) -> details.hookId(PURE_HOOK_ID)
                        .pureEvmHook(PureEvmHook.newBuilder()
                                .spec(EvmHookSpec.newBuilder().contractId(contractId))),
                false);
    }

    private static SpecOperation lambdaHookCreation(@NonNull final HapiSpec spec) {
        return hookCreation(
                spec,
                (contractId, details) -> details.hookId(LAMBDA_HOOK_ID)
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
                store.markDeleted(new CreatedHookId(
                        hookEntityId, creation.detailsOrThrow().hookId()));
            }
        });
    }
}
