// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import static com.hedera.hapi.node.base.HookEntityId.EntityIdOneOfType.UNSET;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_LAMBDA_STORAGE_UPDATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EXCLUSIVE_HOOK_ALREADY_IN_USE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_CREATION_BYTES_MUST_USE_MINIMAL_REPRESENTATION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_CREATION_BYTES_TOO_LONG;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_EXTENSION_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.HOOK_ID_REPEATED_IN_CREATION_DETAILS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_CREATION_SPEC;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_HOOK_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REJECTED_BY_ACCOUNT_ALLOWANCE_HOOK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.hooks.HookExtensionPoint.MINT_CONTROL_HOOK;
import static com.hedera.node.app.hapi.utils.contracts.HookUtils.minimalRepresentationOf;
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
import com.hedera.hapi.node.hooks.HookExtensionPoint;
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
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class HookDispatchUtils {
    private static final long MAX_UPDATE_BYTES_LEN = 32L;
    private static final DispatchMetadata GROUP_METADATA = new DispatchMetadata(Map.of(EXPLICIT_WRITE_TRACING, true));

    /**
     * The hooks that can be extended at most once per entity.
     */
    private static final Set<HookExtensionPoint> EXCLUSIVE_HOOKS = Set.of(MINT_CONTROL_HOOK);

    public static final long HTS_HOOKS_CONTRACT_NUM = 365L;
    public static final String HTS_HOOKS_EVM_ADDRESS = "0x" + Long.toHexString(HTS_HOOKS_CONTRACT_NUM);

    /**
     * Asserts that the given hook id is plausible.
     * @param hookId the hook id to check
     * @throws PreCheckException if the hook id is not plausible
     */
    public static void assertPlausibleHookId(@NonNull final HookId hookId) throws PreCheckException {
        requireNonNull(hookId);
        validateTruePreCheck(hookId.hasEntityId(), INVALID_HOOK_ID);
        final var ownerType = hookId.entityIdOrThrow().entityId().kind();
        validateTruePreCheck(ownerType != UNSET, INVALID_HOOK_ID);
    }

    /**
     * Dispatches the hook deletions to the given context.
     * @param context the handle context
     * @param hooksToDelete the list of hook IDs to delete
     * @param headBefore the head of the hook list before deletions
     * @param hookEntityId the owner of the hooks (the created contract)
     * @return the head of the hook list after deletions
     * @throws HandleException if the dispatch fails
     */
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
    public static int dispatchCreation(@NonNull final HandleContext context, @NonNull final HookCreation creation) {
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

    /**
     * Validates the given hook creation details list.
     *
     * @param hookCreationDetailsList the list of hook creation details
     * @param allowedExtensionPoints the allowed extension points
     * @throws PreCheckException if the hook creation details are invalid
     */
    public static void validateAllHookCreationDetails(
            @NonNull final List<HookCreationDetails> hookCreationDetailsList,
            @NonNull final Set<HookExtensionPoint> allowedExtensionPoints)
            throws PreCheckException {
        validateAllHookCreationDetails(hookCreationDetailsList, allowedExtensionPoints, null);
    }

    /**
     * Validates the given hook creation details list.
     *
     * @param hookCreationDetailsList the list of hook creation details
     * @param allowedExtensionPoints the allowed extension points
     * @param exclusivePointsInUse the exclusive points in use, if any
     * @throws PreCheckException if the hook creation details are invalid
     */
    public static void validateAllHookCreationDetails(
            @NonNull final List<HookCreationDetails> hookCreationDetailsList,
            @NonNull final Set<HookExtensionPoint> allowedExtensionPoints,
            @Nullable final Set<HookExtensionPoint> exclusivePointsInUse)
            throws PreCheckException {
        requireNonNull(hookCreationDetailsList);
        requireNonNull(allowedExtensionPoints);
        final Set<Long> hookIds = new HashSet<>();
        final EnumSet<HookExtensionPoint> newExclusivePoints = EnumSet.noneOf(HookExtensionPoint.class);
        for (final var details : hookCreationDetailsList) {
            validateHookCreationDetails(details, allowedExtensionPoints);
            if (!hookIds.add(details.hookId())) {
                throw new PreCheckException(HOOK_ID_REPEATED_IN_CREATION_DETAILS);
            }
            if (EXCLUSIVE_HOOKS.contains(details.extensionPoint())) {
                if (exclusivePointsInUse != null) {
                    validateTruePreCheck(
                            !exclusivePointsInUse.contains(details.extensionPoint()), EXCLUSIVE_HOOK_ALREADY_IN_USE);
                }
                if (!newExclusivePoints.add(details.extensionPoint())) {
                    throw new PreCheckException(EXCLUSIVE_HOOK_ALREADY_IN_USE);
                }
            }
        }
    }

    /**
     * Validates the given hook creation details.
     *
     * @param hookCreationDetails the hook creation details to validate
     * @param allowedExtensionPoints the allowed extension points
     * @throws PreCheckException if the hook creation details are invalid
     */
    public static void validateHookCreationDetails(
            @NonNull final HookCreationDetails hookCreationDetails,
            @NonNull final Set<HookExtensionPoint> allowedExtensionPoints)
            throws PreCheckException {
        requireNonNull(hookCreationDetails);
        requireNonNull(allowedExtensionPoints);
        validateTruePreCheck(hookCreationDetails.extensionPoint() != null, HOOK_EXTENSION_EMPTY);
        validateTruePreCheck(hookCreationDetails.hasLambdaEvmHook(), INVALID_HOOK_CREATION_SPEC);

        final var lambda = hookCreationDetails.lambdaEvmHookOrThrow();
        validateTruePreCheck(lambda.hasSpec() && lambda.specOrThrow().hasContractId(), INVALID_HOOK_CREATION_SPEC);

        for (final var storage : lambda.storageUpdates()) {
            validateTruePreCheck(storage.hasStorageSlot() || storage.hasMappingEntries(), EMPTY_LAMBDA_STORAGE_UPDATE);

            if (storage.hasStorageSlot()) {
                final var s = storage.storageSlotOrThrow();
                // The key for a storage slot can be empty. If present, it should have minimal encoding and maximum
                // 32 bytes
                validateWord(s.key());
                validateWord(s.value());
            } else if (storage.hasMappingEntries()) {
                final var mapping = storage.mappingEntriesOrThrow();
                for (final var e : mapping.entries()) {
                    validateTruePreCheck(e.hasKey() || e.hasPreimage(), EMPTY_LAMBDA_STORAGE_UPDATE);
                    if (e.hasKey()) {
                        validateWord(e.keyOrThrow());
                    }
                    validateWord(e.value());
                }
            }
        }
    }

    /**
     * Validates that the given bytes are a valid "word" (i.e. a 32-byte value) for use in a lambda storage update.
     * Specifically, it checks that the length is at most 32 bytes, and that it is in its minimal representation
     * (i.e. no leading zeros).
     * @param bytes the bytes to validate
     * @throws PreCheckException if the bytes are not a valid word
     */
    private static void validateWord(@NonNull final Bytes bytes) throws PreCheckException {
        validateTruePreCheck(bytes.length() <= MAX_UPDATE_BYTES_LEN, HOOK_CREATION_BYTES_TOO_LONG);
        final var minimalBytes = minimalRepresentationOf(bytes);
        validateTruePreCheck(bytes.equals(minimalBytes), HOOK_CREATION_BYTES_MUST_USE_MINIMAL_REPRESENTATION);
    }
}
