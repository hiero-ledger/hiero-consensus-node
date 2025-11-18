// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.workflows.DispatchOptions.hookDispatch;
import static com.hedera.node.app.spi.workflows.DispatchOptions.hookDispatchForExecution;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.EMPTY_METADATA;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.EXPLICIT_WRITE_TRACING;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.signedTxWith;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.hooks.HookDispatchTransactionBody;
import com.hedera.hapi.node.hooks.HookExecution;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.UncheckedParseException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class HookDispatchUtils {
    private static final DispatchMetadata GROUP_METADATA = new DispatchMetadata(Map.of(EXPLICIT_WRITE_TRACING, true));
    public static final long HTS_HOOKS_CONTRACT_NUM = 365L;
    public static final String HTS_HOOKS_EVM_ADDRESS = "0x" + Long.toHexString(HTS_HOOKS_CONTRACT_NUM);

    public static @Nullable Long dispatchHookDeletions(
            @NonNull final HandleContext context,
            @NonNull final List<Long> hooksToDelete,
            @Nullable final Long headBefore,
            @NonNull final HookEntityId hookEntityId) {
        requireNonNull(context);
        requireNonNull(hooksToDelete);
        requireNonNull(hookEntityId);
        var currentHead = headBefore;
        for (final var hookId : hooksToDelete) {
            final var hookDispatch = HookDispatchTransactionBody.newBuilder()
                    .hookIdToDelete(new HookId(hookEntityId, hookId))
                    .build();
            final var streamBuilder = context.dispatch(hookDispatch(
                    context.payer(),
                    TransactionBody.newBuilder().hookDispatch(hookDispatch).build(),
                    HookDispatchStreamBuilder.class));
            validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());
            if (Objects.equals(hookId, currentHead)) {
                currentHead = streamBuilder.getNextHookId();
            }
        }
        return currentHead;
    }

    /**
     * Dispatches the hook creations in reverse order, so it is not necessary to return the updated head; it is
     * necessarily the first hook ID in the list. Instead, it returns the total number of storage slots updated.
     * *
     * @param context the handle context
     * @param creations the hook creation details
     * @param currentHead the head of the hook list
     * @param hookEntityId the owner of the hooks (the created contract)
     * @return the total number of storage slots updated
     */
    public static int dispatchHookCreations(
            @NonNull final HandleContext context,
            @NonNull final List<HookCreationDetails> creations,
            @Nullable final Long currentHead,
            @NonNull final HookEntityId hookEntityId) {
        requireNonNull(context);
        requireNonNull(creations);
        requireNonNull(hookEntityId);
        var totalStorageSlotsUpdated = 0;
        // Build new block A → B → C → currentHead
        var nextId = currentHead;
        for (int i = creations.size() - 1; i >= 0; i--) {
            final var d = creations.get(i);
            final var creation =
                    HookCreation.newBuilder().entityId(hookEntityId).details(d);
            if (nextId != null) {
                creation.nextHookId(nextId);
            }
            totalStorageSlotsUpdated += dispatchCreation(context, creation.build());
            nextId = d.hookId();
        }
        return totalStorageSlotsUpdated;
    }

    /**
     * Dispatches the hook creation to the given context.
     *
     * @param context the handle context
     * @param creation the hook creation to dispatch
     */
    static int dispatchCreation(@NonNull final HandleContext context, @NonNull final HookCreation creation) {
        final var hookDispatch =
                HookDispatchTransactionBody.newBuilder().creation(creation).build();
        final var streamBuilder = context.dispatch(hookDispatch(
                context.payer(),
                TransactionBody.newBuilder().hookDispatch(hookDispatch).build(),
                HookDispatchStreamBuilder.class));
        validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());
        return streamBuilder.getDeltaStorageSlotsUpdated();
    }

    /**
     * Validates the hook creation details list, if there are any duplicate hook IDs.
     *
     * @param details the list of hook creation details
     * @throws PreCheckException if there are duplicate hook IDs
     */
    public static void validateHookDuplicates(@NonNull final List<HookCreationDetails> details)
            throws PreCheckException {
        if (!details.isEmpty()) {
            final var hookIds =
                    details.stream().map(HookCreationDetails::hookId).collect(Collectors.toSet());
            if (hookIds.size() != details.size()) {
                throw new PreCheckException(HOOK_ID_REPEATED_IN_CREATION_DETAILS);
            }
        }
    }

    /**
     * Validates the hook creation details and deletions list, if there are any duplicate hook IDs.
     *
     * @param details the list of hook creation details
     * @param hookIdsToDelete the list of hook IDs to delete
     * @throws PreCheckException if there are duplicate hook IDs
     */
    public static void validateHookDuplicates(
            @NonNull final List<HookCreationDetails> details, @NonNull final List<Long> hookIdsToDelete)
            throws PreCheckException {
        validateHookDuplicates(details);
        if (!hookIdsToDelete.isEmpty()) {
            validateTruePreCheck(
                    hookIdsToDelete.stream().distinct().count() == hookIdsToDelete.size(),
                    HOOK_ID_REPEATED_IN_CREATION_DETAILS);
        }
    }

    /**
     * Dispatches the hook execution to the given context.
     *
     * @param context the handle context
     * @param execution the hook execution to dispatch
     * @param function the function to decode the result
     * @param entityIdFactory the entity id factory
     * @param isolated whether this is an isolated hook execution
     */
    public static void dispatchExecution(
            @NonNull final HandleContext context,
            @NonNull final HookExecution execution,
            @NonNull final Function function,
            @NonNull final EntityIdFactory entityIdFactory,
            final boolean isolated) {
        requireNonNull(context);
        requireNonNull(execution);
        requireNonNull(function);
        requireNonNull(entityIdFactory);
        final var hookDispatch =
                HookDispatchTransactionBody.newBuilder().execution(execution).build();
        final var hookContractId = entityIdFactory.newContractId(HTS_HOOKS_CONTRACT_NUM);
        final StreamBuilder.SignedTxCustomizer executionCustomizer = signedTx -> {
            try {
                final var dispatchedBody = TransactionBody.PROTOBUF.parseStrict(
                        signedTx.bodyBytes().toReadableSequentialData());
                final var hookCall = dispatchedBody
                        .hookDispatchOrThrow()
                        .executionOrThrow()
                        .callOrThrow()
                        .evmHookCallOrThrow();
                return signedTxWith(dispatchedBody
                        .copyBuilder()
                        .contractCall(new ContractCallTransactionBody(
                                hookContractId, hookCall.gasLimit(), 0L, hookCall.data()))
                        .build());
            } catch (ParseException e) {
                // Should be impossible
                throw new UncheckedParseException(e);
            }
        };
        final var streamBuilder = context.dispatch(hookDispatchForExecution(
                context.payer(),
                TransactionBody.newBuilder().hookDispatch(hookDispatch).build(),
                HookDispatchStreamBuilder.class,
                executionCustomizer,
                isolated ? EMPTY_METADATA : GROUP_METADATA));
        validateTrue(streamBuilder.status() == SUCCESS, REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK);
        final var result = streamBuilder.getEvmCallResult();
        try {
            final var decoded = function.getOutputs().decode(result.toByteArray());
            validateTrue(decoded.get(0), REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK);
        } catch (final Exception ignore) {
            throw new HandleException(REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK);
        }
    }
}
