// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.workflows.DispatchOptions.hookDispatch;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HookEntityId;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.hooks.HookCreation;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.hooks.HookDispatchTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.stream.Collectors;

public class HookDispatchUtils {
    public static long dispatchHookDeletions(
            final @NonNull HandleContext context,
            final List<Long> hooksToDelete,
            final long headBefore,
            final AccountID ownerId) {
        var currentHead = headBefore;
        for (final var hookId : hooksToDelete) {
            final var hookDispatch = HookDispatchTransactionBody.newBuilder()
                    .hookIdToDelete(new HookId(
                            HookEntityId.newBuilder().accountId(ownerId).build(), hookId))
                    .build();
            final var streamBuilder = context.dispatch(hookDispatch(
                    context.payer(),
                    TransactionBody.newBuilder().hookDispatch(hookDispatch).build(),
                    HookDispatchStreamBuilder.class));
            validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());
            if (hookId == currentHead) {
                currentHead = streamBuilder.getNextHookId();
            }
        }
        return currentHead;
    }

    /**
     * Dispatches the hook creations in reverse order, so that the "next" pointers can be set correctly.
     *
     * @param context the handle context
     * @param creations the hook creation details
     * @param currentHead the head of the hook list
     * @param owner the owner of the hooks (the created contract)
     */
    public static void dispatchHookCreations(
            final HandleContext context,
            final List<HookCreationDetails> creations,
            final Long currentHead,
            final AccountID owner) {
        final var ownerId = HookEntityId.newBuilder().accountId(owner).build();
        // Build new block A → B → C → currentHead
        Long nextId = currentHead == 0 ? null : currentHead;
        for (int i = creations.size() - 1; i >= 0; i--) {
            final var d = creations.get(i);
            final var creation = HookCreation.newBuilder().entityId(ownerId).details(d);
            if (nextId != null) {
                creation.nextHookId(nextId);
            }
            dispatchCreation(context, creation.build());
            nextId = d.hookId();
        }
    }

    /**
     * Dispatches the hook creation to the given context.
     *
     * @param context the handle context
     * @param creation the hook creation to dispatch
     */
    static void dispatchCreation(final @NonNull HandleContext context, final HookCreation creation) {
        final var hookDispatch =
                HookDispatchTransactionBody.newBuilder().creation(creation).build();
        final var streamBuilder = context.dispatch(hookDispatch(
                context.payer(),
                TransactionBody.newBuilder().hookDispatch(hookDispatch).build(),
                HookDispatchStreamBuilder.class));
        validateTrue(streamBuilder.status() == SUCCESS, streamBuilder.status());
    }
    /**
     * Validates the hook creation details list, if there are any duplicate hook IDs.
     * @param details the list of hook creation details
     * @throws PreCheckException if there are duplicate hook IDs
     */
    public static void validateHookDuplicates(final List<HookCreationDetails> details) throws PreCheckException {
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
     * @param details the list of hook creation details
     * @param hookIdsToDelete the list of hook IDs to delete
     * @throws PreCheckException if there are duplicate hook IDs
     */
    public static void validateHookDuplicates(final List<HookCreationDetails> details, List<Long> hookIdsToDelete)
            throws PreCheckException {
        validateHookDuplicates(details);
        if (!hookIdsToDelete.isEmpty()) {
            validateTruePreCheck(
                    hookIdsToDelete.stream().distinct().count() == hookIdsToDelete.size(),
                    HOOK_ID_REPEATED_IN_CREATION_DETAILS);
        }
    }
}
