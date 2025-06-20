// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_STAKING_ID;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Validations needed for staking related fields in token operations.
 */
@Singleton
public class StakingValidator {
    /**
     * Default constructor for injection.
     */
    @Inject
    public StakingValidator() {
        // Dagger2
    }

    /**
     * Validates staked id if present for update transactions. It will error if stakedId is set to
     * the sentinel values for creation.
     *
     * @param hasDeclineRewardChange if the transaction body has decline reward field to be updated
     * @param stakedIdKind           staked id kind (account or node)
     * @param stakedAccountIdInOp    staked account id
     * @param stakedNodeIdInOp       staked node id
     * @param accountStore           readable account store
     * @param networkInfo            network info
     */
    public static void validateStakedIdForCreation(
            final boolean hasDeclineRewardChange,
            @NonNull final String stakedIdKind,
            @Nullable final AccountID stakedAccountIdInOp,
            @Nullable final Long stakedNodeIdInOp,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final NetworkInfo networkInfo) {
        final var hasStakingId = stakedAccountIdInOp != null || stakedNodeIdInOp != null;
        if (!hasStakingId) {
            return;
        }

        // sentinel values on -1 for stakedNodeId and 0.0.0 for stakedAccountId are used to reset
        // staking on an account
        // On creation it is not valid to have sentinel staking id
        validateTrue(
                isValidStakingIdForCreation(stakedIdKind, stakedAccountIdInOp, stakedNodeIdInOp), INVALID_STAKING_ID);
        validateStakedId(stakedIdKind, stakedAccountIdInOp, stakedNodeIdInOp, accountStore, networkInfo);
    }
    /**
     * Validates staked id if present for update transactions. It is possible for stakedId to be set to
     * the sentinel values for update.
     *
     * @param hasDeclineRewardChange if the transaction body has decline reward field to be updated
     * @param stakedIdKind           staked id kind (account or node)
     * @param stakedAccountIdInOp    staked account id
     * @param stakedNodeIdInOp       staked node id
     * @param accountStore           readable account store
     * @param networkInfo            network info
     */
    public static void validateStakedIdForUpdate(
            final boolean hasDeclineRewardChange,
            @NonNull final String stakedIdKind,
            @Nullable final AccountID stakedAccountIdInOp,
            @Nullable final Long stakedNodeIdInOp,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final NetworkInfo networkInfo) {
        final var hasStakingId = stakedAccountIdInOp != null || stakedNodeIdInOp != null;
        if (!hasStakingId) {
            return;
        }

        // sentinel values on -1 for stakedNodeId and 0.0.0 for stakedAccountId are used to reset
        // staking on an account
        // On creation it is not valid to have sentinel staking id
        if (isValidStakingSentinel(stakedIdKind, stakedAccountIdInOp, stakedNodeIdInOp)) {
            return;
        }
        validateStakedId(stakedIdKind, stakedAccountIdInOp, stakedNodeIdInOp, accountStore, networkInfo);
    }

    /**
     * Validates staked id if present.
     *
     * @param stakedIdKind           staked id kind (account or node)
     * @param stakedAccountIdInOp    staked account id
     * @param stakedNodeIdInOp       staked node id
     * @param accountStore           readable account store
     */
    private static void validateStakedId(
            @NonNull final String stakedIdKind,
            @Nullable final AccountID stakedAccountIdInOp,
            @Nullable final Long stakedNodeIdInOp,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final NetworkInfo networkInfo) {
        // If the stakedId is not sentinel values, then validate the accountId is present in account store
        // or nodeId is valid
        if (stakedIdKind.equals("STAKED_ACCOUNT_ID")) {
            validateTrue(accountStore.getAccountById(requireNonNull(stakedAccountIdInOp)) != null, INVALID_STAKING_ID);
        } else if (stakedIdKind.equals("STAKED_NODE_ID")) {
            requireNonNull(stakedNodeIdInOp);
            validateTrue(stakedNodeIdInOp >= -1L, INVALID_STAKING_ID);
            validateTrue(networkInfo.nodeInfo(stakedNodeIdInOp) != null, INVALID_STAKING_ID);
        }
    }

    /**
     * Validates if the staked id is a sentinel value. Sentinel values are used to reset staking
     * on an account. The sentinel values are -1 for stakedNodeId and 0.0.0 for stakedAccountId.
     * @param stakedIdKind staked id kind
     * @param stakedAccountId staked account id
     * @param stakedNodeId staked node id
     * @return true if staked id is a sentinel value
     */
    private static boolean isValidStakingSentinel(
            @NonNull String stakedIdKind, @Nullable AccountID stakedAccountId, @Nullable Long stakedNodeId) {
        if (stakedIdKind.equals("STAKED_ACCOUNT_ID")) {
            // current checking only account num since shard and realm are 0.0
            return requireNonNull(stakedAccountId).accountNumOrThrow() == 0;
        } else if (stakedIdKind.equals("STAKED_NODE_ID")) {
            return requireNonNull(stakedNodeId) == -1;
        } else {
            return false;
        }
    }

    private static boolean isValidStakingIdForCreation(
            final String stakedIdKind, final AccountID stakedAccountId, final Long stakedNodeId) {
        if (stakedIdKind.equals("STAKED_ACCOUNT_ID")) {
            // current checking only account num since shard and realm are 0.0
            return requireNonNull(stakedAccountId).accountNumOrThrow() > 0;
        } else if (stakedIdKind.equals("STAKED_NODE_ID")) {
            return requireNonNull(stakedNodeId) >= 0;
        } else {
            return false;
        }
    }
}
